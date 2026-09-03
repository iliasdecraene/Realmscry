package tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The in-game overlay: one undecorated, per-pixel-translucent, always-on-top,
 * CLICK-THROUGH window positioned over the RotMG client, painting up to three
 * boxes (latest timeline event, latest guild event, last boss damage) at
 * user-chosen normalized positions AND sizes configured in the Overlay tab.
 *
 * Geometry: each box stores x, y and w as fractions of the game window; the
 * box is painted in a fixed 330-unit-wide design space and scaled to
 * w * windowWidth, so the web preview and the real overlay always agree,
 * and resizing keeps the aspect ratio by construction.
 *
 * Click-through + no-activate via user32 extended styles through the JNA
 * bundled in the Tomato jar. The game window ("RotMGExalt") is located by
 * title every 2 s; the overlay hides when the game is gone.
 * -Dtracker.overlaydebug anchors to the primary screen for testing.
 */
final class OverlayManager {

    // Design space: every box is painted 330 units wide, then scaled.
    static final int UNIT_W = 330;
    static final int TL_H = 62, GD_H = 74, BOSS_H = 118;
    private static final double DEF_W = 330.0 / 1600, MIN_W = 0.08, MAX_W = 0.6;
    private static final Path CONFIG = Paths.get("overlay.properties");
    private static final String WINDOW_TITLE = "Realmscry Overlay";

    /** Minimal user32 surface via core JNA (no jna-platform needed). */
    interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.DEFAULT_OPTIONS);
        Pointer FindWindowW(WString cls, WString title);
        boolean GetWindowRect(Pointer hwnd, int[] rect);
        boolean IsIconic(Pointer hwnd);
        int GetWindowLongW(Pointer hwnd, int index);
        int SetWindowLongW(Pointer hwnd, int index, int value);
    }

    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TRANSPARENT = 0x20, WS_EX_TOOLWINDOW = 0x80,
            WS_EX_LAYERED = 0x80000, WS_EX_NOACTIVATE = 0x8000000;

    static class Box {
        boolean on;
        double x = 0.35, y = 0.05, w = DEF_W;
    }

    private final Box timeline = new Box(), guildBox = new Box(), boss = new Box();
    private final WebServer web;
    private final GuildClient guild;
    private volatile JsonObject latestGuildEvent;
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "overlay");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<Integer, BufferedImage> icons = new ConcurrentHashMap<>();
    private final boolean debugAnchor = System.getProperty("tracker.overlaydebug") != null;

    private volatile JFrame frame;
    private volatile boolean clickThroughApplied;
    private volatile Rectangle lastBounds;

    OverlayManager(WebServer web, GuildClient guild) {
        this.web = web;
        this.guild = guild;
        load();
        exec.scheduleAtFixedRate(this::tick, 2, 2, TimeUnit.SECONDS);
        exec.scheduleAtFixedRate(this::pollGuild, 5, 30, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // Config (web UI <-> here)
    // ------------------------------------------------------------------

    synchronized JsonObject configJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add("timeline", boxJson(timeline));
        o.add("guild", boxJson(guildBox));
        o.add("boss", boxJson(boss));
        o.addProperty("gameFound", findGame() != null);
        return o;
    }

    private static JsonObject boxJson(Box b) {
        JsonObject o = new JsonObject();
        o.addProperty("on", b.on);
        o.addProperty("x", b.x);
        o.addProperty("y", b.y);
        o.addProperty("w", b.w);
        return o;
    }

    synchronized void applyConfig(JsonObject cfg) {
        readBox(cfg, "timeline", timeline);
        readBox(cfg, "guild", guildBox);
        readBox(cfg, "boss", boss);
        save();
        refresh();
    }

    private static void readBox(JsonObject cfg, String key, Box b) {
        if (!cfg.has(key) || !cfg.get(key).isJsonObject()) return;
        JsonObject o = cfg.getAsJsonObject(key);
        if (o.has("on")) b.on = o.get("on").getAsBoolean();
        if (o.has("x")) b.x = clamp(o.get("x").getAsDouble(), 0, 0.97);
        if (o.has("y")) b.y = clamp(o.get("y").getAsDouble(), 0, 0.97);
        if (o.has("w")) b.w = clamp(o.get("w").getAsDouble(), MIN_W, MAX_W);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void load() {
        try {
            if (!Files.exists(CONFIG)) return;
            Properties p = new Properties();
            p.load(Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8));
            loadBox(p, "timeline", timeline);
            loadBox(p, "guild", guildBox);
            loadBox(p, "boss", boss);
        } catch (Exception e) {
            System.err.println("[Overlay] config load failed: " + e);
        }
    }

    private static void loadBox(Properties p, String k, Box b) {
        b.on = Boolean.parseBoolean(p.getProperty(k + ".on", "false"));
        try {
            b.x = clamp(Double.parseDouble(p.getProperty(k + ".x", String.valueOf(b.x))), 0, 0.97);
            b.y = clamp(Double.parseDouble(p.getProperty(k + ".y", String.valueOf(b.y))), 0, 0.97);
            b.w = clamp(Double.parseDouble(p.getProperty(k + ".w", String.valueOf(b.w))), MIN_W, MAX_W);
        } catch (NumberFormatException ignored) {
        }
    }

    private void save() {
        try {
            Properties p = new Properties();
            saveBox(p, "timeline", timeline);
            saveBox(p, "guild", guildBox);
            saveBox(p, "boss", boss);
            try (var w = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
                p.store(w, "Realmscry overlay layout");
            }
        } catch (Exception e) {
            System.err.println("[Overlay] config save failed: " + e);
        }
    }

    private static void saveBox(Properties p, String k, Box b) {
        p.setProperty(k + ".on", String.valueOf(b.on));
        p.setProperty(k + ".x", String.valueOf(b.x));
        p.setProperty(k + ".y", String.valueOf(b.y));
        p.setProperty(k + ".w", String.valueOf(b.w));
    }

    // ------------------------------------------------------------------
    // Window management
    // ------------------------------------------------------------------

    private boolean anyOn() {
        return timeline.on || guildBox.on || boss.on;
    }

    // The live client's window title is "RotMGExalt" (no space); keep the
    // spaced variant as a fallback in case a build changes it.
    private static final String[] GAME_TITLES = {"RotMGExalt", "RotMG Exalt"};

    private Pointer findGame() {
        try {
            for (String title : GAME_TITLES) {
                Pointer h = U32.I.FindWindowW(null, new WString(title));
                if (h != null) return U32.I.IsIconic(h) ? null : h;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Logical (Swing) bounds to cover: game window, or screen in debug mode. */
    private Rectangle targetBounds() {
        Pointer game = findGame();
        if (game != null) {
            int[] r = new int[4];
            if (U32.I.GetWindowRect(game, r)) {
                double s = dpiScale();
                Rectangle b = new Rectangle((int) (r[0] / s), (int) (r[1] / s),
                        (int) ((r[2] - r[0]) / s), (int) ((r[3] - r[1]) / s));
                if (b.width > 200 && b.height > 200) return b;
            }
        }
        if (debugAnchor) {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        }
        return null;
    }

    private double dpiScale() {
        try {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            return gc.getDefaultTransform().getScaleX();
        } catch (Exception e) {
            return 1.0;
        }
    }

    private void tick() {
        try {
            Rectangle target = anyOn() ? targetBounds() : null;
            SwingUtilities.invokeLater(() -> {
                if (target == null) {
                    if (frame != null) frame.setVisible(false);
                    return;
                }
                ensureFrame();
                if (!target.equals(lastBounds)) {
                    frame.setBounds(target);
                    lastBounds = target;
                }
                if (!frame.isVisible()) frame.setVisible(true);
                applyClickThrough();
                frame.repaint();
            });
        } catch (Throwable t) {
            // the overlay must never take the tracker down
        }
    }

    /** Data changed (new drop/death/boss/guild event) — repaint soon. */
    void refresh() {
        JFrame f = frame;
        if (f != null && f.isVisible()) SwingUtilities.invokeLater(f::repaint);
    }

    private void ensureFrame() {
        if (frame != null) return;
        JFrame f = new JFrame(WINDOW_TITLE);
        f.setUndecorated(true);
        f.setAlwaysOnTop(true);
        f.setAutoRequestFocus(false);
        f.setFocusableWindowState(false);
        f.setType(java.awt.Window.Type.UTILITY);
        f.setBackground(new Color(0, 0, 0, 0));
        f.setContentPane(new JPanel() {
            {
                setOpaque(false);
            }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintOverlay((Graphics2D) g, getWidth(), getHeight());
            }
        });
        f.setVisible(true);
        frame = f;
    }

    private void applyClickThrough() {
        if (clickThroughApplied) return;
        try {
            Pointer h = U32.I.FindWindowW(null, new WString(WINDOW_TITLE));
            if (h == null) return;
            int ex = U32.I.GetWindowLongW(h, GWL_EXSTYLE);
            U32.I.SetWindowLongW(h, GWL_EXSTYLE,
                    ex | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE);
            clickThroughApplied = true;
        } catch (Throwable t) {
            System.err.println("[Overlay] click-through failed: " + t);
            clickThroughApplied = true; // don't retry-spam; overlay still shows
        }
    }

    // ------------------------------------------------------------------
    // Painting — styled to match the web app's timeline rows
    // ------------------------------------------------------------------

    private static final Color SURFACE = new Color(26, 26, 25, 205);
    private static final Color SURFACE2 = new Color(35, 35, 34, 220);
    private static final Color BORDER = new Color(78, 78, 74, 235);
    private static final Color TEXT = new Color(255, 255, 255, 245);
    private static final Color SECONDARY = new Color(205, 204, 192, 235);
    private static final Color MUTED = new Color(170, 169, 158, 220);
    private static final Color GOLD = new Color(232, 195, 90);
    private static final Color RED = new Color(230, 103, 103);
    private static final Color CYAN = new Color(123, 226, 255);
    private static final Color DEATH_BG = new Color(48, 24, 24, 210);
    private static final Color DEATH_BORDER = new Color(109, 43, 43, 240);
    private static final Color BAG_WHITE = new Color(245, 244, 239);
    private static final Color BAG_ORANGE = new Color(217, 89, 38);
    private static final Color BAG_RED = new Color(230, 103, 103);
    private static final Font CHIP = new Font("Segoe UI", Font.BOLD, 10);
    private static final Font MAIN = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font SUB = new Font("Segoe UI", Font.PLAIN, 11);

    private void paintOverlay(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (timeline.on) paintScaled(g, timeline, w, h, TL_H,
                gg -> paintTimelineContent(gg, web.latestTimelineEntry()));
        if (guildBox.on) paintScaled(g, guildBox, w, h, GD_H, this::paintGuildContent);
        if (boss.on) paintScaled(g, boss, w, h, BOSS_H, this::paintBossContent);
    }

    private interface Painter {
        void paint(Graphics2D g);
    }

    private void paintScaled(Graphics2D g, Box b, int w, int h, int unitH, Painter p) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            double scale = (b.w * w) / UNIT_W;
            g2.translate(b.x * w, b.y * h);
            g2.scale(scale, scale);
            p.paint(g2);
        } finally {
            g2.dispose();
        }
    }

    private void bg(Graphics2D g, int hUnits, Color fill, Color border) {
        g.setColor(fill);
        g.fillRoundRect(0, 0, UNIT_W, hUnits, 10, 10);
        g.setColor(border);
        g.drawRoundRect(0, 0, UNIT_W, hUnits, 10, 10);
    }

    /** Items worth showing: hide "minor" filler when a real item is present. */
    private static List<JsonObject> shownItems(JsonObject e) {
        List<JsonObject> all = new ArrayList<>(), major = new ArrayList<>();
        if (e != null && e.has("items")) {
            for (var el : e.getAsJsonArray("items")) {
                JsonObject it = el.getAsJsonObject();
                all.add(it);
                if (!it.has("minor")) major.add(it);
            }
        }
        return major.isEmpty() ? all : major;
    }

    private void paintTimelineContent(Graphics2D g, JsonObject e) {
        boolean death = e != null && e.has("type") && "death".equals(str(e, "type", ""));
        if (death) {
            paintDeathRow(g, e, 10);
            return;
        }
        boolean shiny = e != null && e.has("shiny");
        bg(g, TL_H, SURFACE, shiny ? CYAN : BORDER);
        if (e == null) {
            g.setFont(SUB);
            g.setColor(MUTED);
            g.drawString("no drops yet", 12, 36);
            return;
        }
        // tier chip like the web pill
        String tier = str(e, "tier", "").toUpperCase();
        Color swatch = switch (str(e, "tier", "")) {
            case "orange" -> BAG_ORANGE;
            case "red" -> BAG_RED;
            case "shiny" -> CYAN;
            default -> BAG_WHITE;
        };
        g.setFont(CHIP);
        int chipW = g.getFontMetrics().stringWidth(tier) + 26;
        g.setColor(SURFACE2);
        g.fillRoundRect(10, 10, chipW, 18, 9, 9);
        g.setColor(swatch);
        g.fillRoundRect(17, 15, 8, 8, 3, 3);
        g.setColor(SECONDARY);
        g.drawString(tier, 29, 23);
        // items: sprites only — no names (the sprite IS the news)
        int cx = 10;
        List<JsonObject> items = shownItems(e);
        for (int i = 0; i < items.size() && i < 9; i++) {
            cx = drawIcon(g, items.get(i).has("id") ? items.get(i).get("id").getAsInt() : 0, cx, 32, 24) + 2;
        }
        // meta right of the chip
        g.setFont(SUB);
        g.setColor(MUTED);
        g.drawString(trim(g, str(e, "map", "") + " · " + ago(e), UNIT_W - chipW - 30), chipW + 20, 23);
    }

    /** Death: visuals only — skull, big skin sprite, class chip, badges. */
    private void paintDeathRow(Graphics2D g, JsonObject e, int x0) {
        bg(g, TL_H, DEATH_BG, DEATH_BORDER);
        g.setFont(new Font("Segoe UI", Font.BOLD, 22));
        g.setColor(RED);
        g.drawString("☠", x0, 40);
        int cx = drawIcon(g, e.has("icon") ? e.get("icon").getAsInt() : 0, x0 + 26, 14, 34) + 4;
        String cls = str(e, "className", "");
        if (!cls.isEmpty()) {
            g.setFont(CHIP);
            int cw = g.getFontMetrics().stringWidth(cls) + 14;
            g.setColor(SURFACE2);
            g.fillRoundRect(cx, 22, cw, 18, 9, 9);
            g.setColor(SECONDARY);
            g.drawString(cls, cx + 7, 35);
        }
        int maxed = e.has("maxed") ? e.get("maxed").getAsInt() : -1;
        if (maxed >= 0) {
            g.setFont(CHIP);
            String badge = maxed + "/8";
            int bw = g.getFontMetrics().stringWidth(badge) + 14;
            g.setColor(SURFACE2);
            g.fillRoundRect(UNIT_W - bw - 10, 10, bw, 16, 8, 8);
            g.setColor(maxed >= 8 ? GOLD : SECONDARY);
            g.drawString(badge, UNIT_W - bw - 3, 22);
        }
        g.setFont(SUB);
        g.setColor(MUTED);
        String meta = str(e, "map", "") + " · " + ago(e);
        g.drawString(trim(g, meta, 150), UNIT_W - 12 - Math.min(150, g.getFontMetrics().stringWidth(meta)), TL_H - 10);
    }

    /** Guild box: big member avatar hard left, event info to the right. */
    private void paintGuildContent(Graphics2D g) {
        JsonObject ev = latestGuildEvent;
        bg(g, GD_H, SURFACE, BORDER);
        if (ev == null) {
            g.setFont(CHIP);
            g.setColor(MUTED);
            g.drawString("GUILD", 10, 18);
            g.setFont(SUB);
            g.drawString(guild != null && guild.inGuild() ? "nothing yet" : "not in a guild", 12, 44);
            return;
        }
        JsonObject data = ev.has("data") ? ev.getAsJsonObject("data") : new JsonObject();
        boolean death = "death".equals(str(ev, "type", str(data, "type", "")));
        boolean shiny = data.has("shiny");
        if (death) bg(g, GD_H, DEATH_BG, DEATH_BORDER);
        else if (shiny) bg(g, GD_H, SURFACE, CYAN);
        // avatar panel
        int icon = ev.has("icon") ? ev.get("icon").getAsInt() : 0;
        g.setColor(SURFACE2);
        g.fillRoundRect(8, 8, GD_H - 16, GD_H - 16, 8, 8);
        drawIcon(g, icon, 12, 12, GD_H - 24);
        int x0 = GD_H + 2;
        String who = str(ev, "dname", "").isEmpty() ? str(ev, "ign", "Unknown") : str(ev, "dname", "");
        g.setFont(MAIN);
        g.setColor(death ? RED : GOLD);
        g.drawString(trim(g, who, UNIT_W - x0 - 60), x0, 24);
        g.setFont(SUB);
        g.setColor(MUTED);
        String agoS = ago(ev);
        g.drawString(agoS, UNIT_W - 12 - g.getFontMetrics().stringWidth(agoS), 24);
        if (death) {
            g.setFont(new Font("Segoe UI", Font.BOLD, 16));
            g.setColor(RED);
            g.drawString("☠", x0, 48);
            int cx = drawIcon(g, data.has("icon") ? data.get("icon").getAsInt() : 0, x0 + 20, 30, 24) + 2;
            String cls = str(data, "className", "");
            if (!cls.isEmpty()) {
                g.setFont(CHIP);
                int cw = g.getFontMetrics().stringWidth(cls) + 14;
                g.setColor(SURFACE2);
                g.fillRoundRect(cx, 33, cw, 18, 9, 9);
                g.setColor(SECONDARY);
                g.drawString(cls, cx + 7, 46);
            }
        } else {
            // sprites only, no item names
            int cx = x0;
            List<JsonObject> items = shownItems(data);
            for (int i = 0; i < items.size() && i < 7; i++) {
                cx = drawIcon(g, items.get(i).has("id") ? items.get(i).get("id").getAsInt() : 0, cx, 28, 24) + 2;
            }
        }
        g.setFont(SUB);
        g.setColor(MUTED);
        g.drawString(trim(g, str(data, "tier", "").toUpperCase() + (str(data, "map", "").isEmpty() ? "" : " · " + str(data, "map", "")), UNIT_W - x0 - 12), x0, GD_H - 12);
    }

    private void paintBossContent(Graphics2D g) {
        bg(g, BOSS_H, SURFACE, BORDER);
        JsonObject b = web.lastBossJson();
        g.setFont(CHIP);
        g.setColor(MUTED);
        g.drawString("LAST BOSS", 10, 18);
        if (b == null) {
            g.setFont(SUB);
            g.drawString("no boss kills yet", 12, 44);
            return;
        }
        int cx = drawIcon(g, b.has("icon") ? b.get("icon").getAsInt() : 0, 10, 24, 24);
        g.setFont(MAIN);
        g.setColor(TEXT);
        g.drawString(trim(g, str(b, "name", "?"), UNIT_W - cx - 16), cx + 6, 41);
        JsonArray top = b.has("top") ? b.getAsJsonArray("top") : new JsonArray();
        int line = 0, yy = 62;
        for (int i = 0; i < top.size() && line < 4; i++) {
            JsonObject t = top.get(i).getAsJsonObject();
            boolean me = t.has("me") && t.get("me").getAsBoolean();
            if (line == 3 && !me) continue; // keep the last line for our row
            int rank = t.has("rank") ? t.get("rank").getAsInt() : i + 1;
            g.setFont(SUB);
            g.setColor(MUTED);
            g.drawString("#" + rank, 12, yy);
            g.setColor(me ? GOLD : SECONDARY);
            g.drawString(trim(g, str(t, "name", "?"), 190), 40, yy);
            String dmg = fmt(t.has("dmg") ? t.get("dmg").getAsLong() : 0);
            g.drawString(dmg, UNIT_W - 12 - g.getFontMetrics().stringWidth(dmg), yy);
            yy += 15;
            line++;
        }
    }

    private int drawIcon(Graphics2D g, int id, int cx, int cy, int size) {
        if (id <= 0) return cx;
        BufferedImage img = icons.computeIfAbsent(id, k -> {
            try {
                byte[] png = web.iconPng(k);
                return png.length == 0 ? null
                        : javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
            } catch (Exception e) {
                return null;
            }
        });
        if (img == null) return cx;
        g.drawImage(img, cx, cy, size, size, null);
        return cx + size + 3;
    }

    private String trim(Graphics2D g, String s, int maxW) {
        var fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    private static String str(JsonObject o, String k, String dflt) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : dflt;
        } catch (Exception e) {
            return dflt;
        }
    }

    private static String fmt(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1e6);
        if (n >= 1_000) return String.format("%.1fk", n / 1e3);
        return String.valueOf(n);
    }

    private static String ago(JsonObject e) {
        try {
            long s = Math.max(0, System.currentTimeMillis() - e.get("ts").getAsLong()) / 1000;
            if (s < 60) return "just now";
            if (s < 3600) return (s / 60) + "m ago";
            return (s / 3600) + "h ago";
        } catch (Exception ex) {
            return "";
        }
    }

    private void pollGuild() {
        try {
            if (!guildBox.on || guild == null || !guild.inGuild()) return;
            JFrame f = frame;
            if (f == null || !f.isVisible()) return;
            JsonObject r = guild.timeline("all", 0);
            if (r.has("ok") && r.get("ok").getAsBoolean()) {
                JsonArray evs = r.getAsJsonArray("events");
                latestGuildEvent = evs != null && evs.size() > 0
                        ? evs.get(0).getAsJsonObject() : null;
                refresh();
            }
        } catch (Throwable ignored) {
        }
    }
}
