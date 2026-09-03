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
import java.awt.AlphaComposite;
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
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The in-game overlay: one undecorated, per-pixel-translucent, always-on-top,
 * CLICK-THROUGH window positioned over the RotMG client, painting up to three
 * boxes (latest timeline event, latest guild event, last boss damage) at
 * user-chosen normalized positions configured in the web UI's Overlay tab.
 *
 * Click-through + no-activate is done via user32 extended window styles
 * through the JNA already bundled in the Tomato jar. The game window is
 * located by title every 2 s; the overlay hides when the game is gone.
 * Works with windowed / borderless game modes (exclusive fullscreen covers
 * any OS overlay). -Dtracker.overlaydebug anchors to the primary screen so
 * the overlay can be seen without the game.
 */
final class OverlayManager {

    // ---- box geometry (logical px, fixed v1) ----
    private static final int BOX_W = 330, TL_H = 62, GD_H = 62, BOSS_H = 118;
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
        double x = 0.35, y = 0.05; // normalized top-left within the game area
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
    // Config (web UI -> here)
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
        if (o.has("x")) b.x = clamp(o.get("x").getAsDouble());
        if (o.has("y")) b.y = clamp(o.get("y").getAsDouble());
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(0.97, v));
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
            b.x = clamp(Double.parseDouble(p.getProperty(k + ".x", String.valueOf(b.x))));
            b.y = clamp(Double.parseDouble(p.getProperty(k + ".y", String.valueOf(b.y))));
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
    // Painting
    // ------------------------------------------------------------------

    private static final Color BG = new Color(14, 14, 14, 150);
    private static final Color BORDER = new Color(255, 255, 255, 80);
    private static final Color TEXT = new Color(255, 255, 255, 235);
    private static final Color MUTED = new Color(200, 200, 200, 170);
    private static final Color GOLD = new Color(232, 195, 90);
    private static final Color RED = new Color(230, 103, 103);
    private static final Color CYAN = new Color(123, 226, 255);
    private static final Font LABEL = new Font("Segoe UI", Font.BOLD, 10);
    private static final Font MAIN = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font SUB = new Font("Segoe UI", Font.PLAIN, 11);

    private void paintOverlay(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (timeline.on) paintTimelineBox(g, px(timeline.x, w), px(timeline.y, h),
                "TIMELINE", web.latestTimelineEntry(), null);
        if (guildBox.on) paintGuildBox(g, px(guildBox.x, w), px(guildBox.y, h));
        if (boss.on) paintBossBox(g, px(boss.x, w), px(boss.y, h));
    }

    private static int px(double norm, int total) {
        return (int) (norm * total);
    }

    private void boxBg(Graphics2D g, int x, int y, int bw, int bh, Color border) {
        g.setColor(BG);
        g.fillRoundRect(x, y, bw, bh, 10, 10);
        g.setColor(border == null ? BORDER : border);
        g.drawRoundRect(x, y, bw, bh, 10, 10);
    }

    private void label(Graphics2D g, int x, int y, String s) {
        g.setFont(LABEL);
        g.setColor(MUTED);
        g.drawString(s, x + 10, y + 14);
    }

    private void paintTimelineBox(Graphics2D g, int x, int y, String title,
                                  JsonObject e, String author) {
        boolean death = e != null && e.has("type")
                && "death".equals(e.get("type").getAsString());
        boolean shiny = e != null && e.has("shiny");
        Color border = death ? RED : shiny ? CYAN : null;
        boxBg(g, x, y, BOX_W, TL_H, border);
        label(g, x, y, title);
        if (e == null) {
            g.setFont(SUB);
            g.setColor(MUTED);
            g.drawString("nothing yet", x + 10, y + 40);
            return;
        }
        int cx = x + 10, cy = y + 24;
        String main, sub;
        if (death) {
            g.setFont(MAIN);
            g.setColor(RED);
            g.drawString("☠", cx, cy + 14);
            cx += 18;
            int icon = e.has("icon") ? e.get("icon").getAsInt() : 0;
            cx = drawIcon(g, icon, cx, cy);
            main = str(e, "name", "Unknown") + " died to " + str(e, "killedBy", "?");
            int maxed = e.has("maxed") ? e.get("maxed").getAsInt() : -1;
            sub = (maxed >= 0 ? maxed + "/8 · " : "") + str(e, "map", "") + " · " + ago(e);
        } else {
            JsonArray items = e.has("items") ? e.getAsJsonArray("items") : new JsonArray();
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                JsonObject it = items.get(i).getAsJsonObject();
                if (i < 3) cx = drawIcon(g, it.get("id").getAsInt(), cx, cy);
                if (names.length() > 0) names.append(", ");
                names.append(str(it, "name", "?"));
            }
            main = names.length() == 0 ? "drop" : names.toString();
            sub = str(e, "tier", "").toUpperCase() + " · " + str(e, "map", "") + " · " + ago(e);
        }
        if (author != null) sub = "by " + author + " · " + sub;
        g.setFont(MAIN);
        g.setColor(death ? RED : shiny ? CYAN : TEXT);
        g.drawString(trim(g, main, BOX_W - (cx - x) - 14), cx + 4, cy + 14);
        g.setFont(SUB);
        g.setColor(MUTED);
        g.drawString(trim(g, sub, BOX_W - 20), x + 10, y + TL_H - 9);
    }

    private void paintGuildBox(Graphics2D g, int x, int y) {
        JsonObject ev = latestGuildEvent;
        if (ev == null) {
            boxBg(g, x, y, BOX_W, GD_H, null);
            label(g, x, y, "GUILD" + (guild != null && guild.inGuild() ? "" : " (no guild)"));
            g.setFont(SUB);
            g.setColor(MUTED);
            g.drawString("nothing yet", x + 10, y + 40);
            return;
        }
        JsonObject data = ev.has("data") ? ev.getAsJsonObject("data") : new JsonObject();
        String who = str(ev, "dname", "").isEmpty() ? str(ev, "ign", "Unknown") : str(ev, "dname", "");
        paintTimelineBox(g, x, y, "GUILD", data, who);
    }

    private void paintBossBox(Graphics2D g, int x, int y) {
        boxBg(g, x, y, BOX_W, BOSS_H, null);
        JsonObject b = web.lastBossJson();
        label(g, x, y, "LAST BOSS");
        if (b == null) {
            g.setFont(SUB);
            g.setColor(MUTED);
            g.drawString("no boss kills yet", x + 10, y + 40);
            return;
        }
        g.setFont(MAIN);
        g.setColor(TEXT);
        g.drawString(trim(g, str(b, "name", "?"), BOX_W - 20), x + 10, y + 30);
        JsonArray top = b.has("top") ? b.getAsJsonArray("top") : new JsonArray();
        int line = 0;
        int yy = y + 48;
        for (int i = 0; i < top.size() && line < 4; i++) {
            JsonObject t = top.get(i).getAsJsonObject();
            boolean me = t.has("me") && t.get("me").getAsBoolean();
            if (line == 3 && !me) continue; // reserve the 4th line for our row
            int rank = t.has("rank") ? t.get("rank").getAsInt() : i + 1;
            if (line < 3 || me) {
                g.setFont(SUB);
                g.setColor(me ? GOLD : TEXT);
                String row = "#" + rank + "  " + str(t, "name", "?");
                g.drawString(trim(g, row, 210), x + 12, yy);
                String dmg = fmt(t.has("dmg") ? t.get("dmg").getAsLong() : 0);
                g.drawString(dmg, x + BOX_W - 12 - g.getFontMetrics().stringWidth(dmg), yy);
                yy += 16;
                line++;
            }
        }
    }

    private int drawIcon(Graphics2D g, int id, int cx, int cy) {
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
        java.awt.Composite old = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(img, cx, cy, 20, 20, null);
        g.setComposite(old);
        return cx + 23;
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
