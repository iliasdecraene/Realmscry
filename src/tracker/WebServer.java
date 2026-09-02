package tracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Serves the UI (web/index.html) on localhost and pushes loot / boss-kill
 * events to it over Server-Sent Events. Bound to 127.0.0.1 only.
 */
public class WebServer implements GameState.Publisher {

    public static final int PORT = Integer.getInteger("tracker.port", 8420);
    public static final String URL = "http://localhost:" + PORT;
    private static final Gson GSON = new Gson();

    private final Path webDir;
    private final CopyOnWriteArrayList<OutputStream> clients = new CopyOnWriteArrayList<>();

    // Retained state so a freshly opened page gets history immediately.
    // Loot is also persisted to LOOT_HISTORY (one JSON object per line,
    // append-only) and reloaded on startup, capped at LOOT_CAP entries.
    private static final Path LOOT_HISTORY = Paths.get("loot-history.jsonl");
    private static final int LOOT_CAP = 2000;
    private final ArrayDeque<JsonObject> lootLog = new ArrayDeque<>();
    // Personal boss history: compact {boss, myDmg, myRank, total} entries,
    // persisted like loot, but the UI only ever shows the newest BOSS_SHOWN.
    private static final Path BOSS_HISTORY = Paths.get("boss-history.jsonl");
    private static final int BOSS_CAP = 100, BOSS_SHOWN = 5;
    private final ArrayDeque<JsonObject> bossLog = new ArrayDeque<>();
    private JsonObject lastBoss = null;
    private String mapName = "";

    public WebServer() throws IOException {
        String dir = System.getProperty("tracker.web", "web");
        webDir = Paths.get(dir);
        loadLootHistory();
        loadBossHistory();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", this::servePage);
        server.createContext("/events", this::serveEvents);
        server.createContext("/debug", this::serveDebug);
        server.createContext("/icon/", this::serveIcon);
        server.createContext("/favicon.png", this::serveFavicon);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        // SSE heartbeat so dead connections get culled.
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        ses.scheduleAtFixedRate(() -> raw(": ping\n\n"), 15, 15, TimeUnit.SECONDS);

        System.out.println("[Web] UI at http://localhost:" + PORT);
    }

    private void servePage(HttpExchange ex) throws IOException {
        byte[] body = null;
        // Dev override: a real file on disk wins so index.html can be live-edited.
        try {
            Path f = webDir.resolve("index.html");
            if (Files.isReadable(f)) body = Files.readAllBytes(f);
        } catch (Exception ignored) {
        }
        if (body == null) { // packaged mode: page embedded in the jar
            try (java.io.InputStream in = WebServer.class.getClassLoader()
                    .getResourceAsStream("web/index.html")) {
                if (in != null) body = in.readAllBytes();
            }
        }
        if (body == null) {
            body = "index.html not found (neither on disk nor in jar)".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(500, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void serveFavicon(HttpExchange ex) throws IOException {
        byte[] body = null;
        try {
            Path f = webDir.resolve("icon-64.png"); // disk wins, like index.html
            if (Files.isReadable(f)) body = Files.readAllBytes(f);
        } catch (Exception ignored) {
        }
        if (body == null) {
            try (java.io.InputStream in = WebServer.class.getClassLoader()
                    .getResourceAsStream("web/icon-64.png")) {
                if (in != null) body = in.readAllBytes();
            }
        }
        if (body == null) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private volatile GameState state; // for /debug

    public void setState(GameState state) {
        this.state = state;
    }

    private void serveDebug(HttpExchange ex) throws IOException {
        GameState s = state;
        byte[] body = (s == null ? "{}" : GSON.toJson(s.debug())).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // Sprite PNGs by object id, rendered once and cached. Guarded by iconLock
    // because ImageBuffer's internal caches are not thread-safe.
    private final java.util.concurrent.ConcurrentHashMap<Integer, byte[]> iconCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Object iconLock = new Object();
    private static final byte[] NO_ICON = new byte[0];

    private void serveIcon(HttpExchange ex) throws IOException {
        int id = -1;
        try {
            id = Integer.parseInt(ex.getRequestURI().getPath().substring("/icon/".length()));
        } catch (Exception ignored) {
        }
        byte[] png = id > 0 ? iconCache.computeIfAbsent(id, this::renderIcon) : NO_ICON;
        if (png.length == 0) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
        ex.sendResponseHeaders(200, png.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(png); }
    }

    private byte[] renderIcon(int id) {
        synchronized (iconLock) {
            try {
                java.awt.image.BufferedImage img = assets.ImageBuffer.getImage(id);
                // Unknown ids come back as the library's shared empty sprite.
                if (img == null || img == assets.ImageBuffer.getEmptyImg() || img.getWidth() <= 1) return NO_ICON;
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(img, "png", bos);
                return bos.toByteArray();
            } catch (Throwable t) {
                return NO_ICON;
            }
        }
    }

    private void serveEvents(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        clients.add(os);
        // Send current snapshot to the newly connected page.
        JsonObject snap = new JsonObject();
        snap.addProperty("map", mapName);
        JsonArray loot = new JsonArray();
        synchronized (lootLog) {
            for (JsonObject o : lootLog) loot.add(o);
        }
        snap.add("loot", loot);
        if (lastBoss != null) snap.add("boss", lastBoss);
        snap.add("bossLog", bossLogJson());
        sendTo(os, "snapshot", snap.toString());
    }

    private void sendTo(OutputStream os, String event, String data) {
        try {
            os.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            clients.remove(os);
        }
    }

    private void broadcast(String event, String data) {
        for (OutputStream os : clients) sendTo(os, event, data);
    }

    private void raw(String s) {
        for (OutputStream os : clients) {
            try {
                os.write(s.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                clients.remove(os);
            }
        }
    }

    // ------------------------------------------------------------------
    // GameState.Publisher
    // ------------------------------------------------------------------

    @Override
    public void lootDropped(String tier, boolean boosted, int bagType, List<int[]> items, long ts) {
        JsonObject o = new JsonObject();
        o.addProperty("tier", tier);
        o.addProperty("boosted", boosted);
        o.addProperty("bagType", bagType);
        o.addProperty("ts", ts);
        o.addProperty("map", mapName);
        boolean anyShiny = false;
        JsonArray arr = new JsonArray();
        for (int[] it : items) {
            JsonObject item = new JsonObject();
            item.addProperty("id", it[0]);
            item.addProperty("name", Names.item(it[0]));
            boolean shiny = it.length > 1 && it[1] == 1;
            if (shiny) item.addProperty("shiny", true);
            anyShiny |= shiny;
            int slots = it.length > 2 ? it[2] : 0;
            if (slots > 0) item.addProperty("slots", slots);
            arr.add(item);
        }
        if (anyShiny) o.addProperty("shiny", true);
        o.add("items", arr);
        synchronized (lootLog) {
            lootLog.addFirst(o);
            while (lootLog.size() > LOOT_CAP) lootLog.removeLast();
        }
        appendHistory(LOOT_HISTORY, o);
        broadcast("loot", o.toString());
        System.out.println("[Loot] " + tier + (boosted ? " (boosted)" : "")
                + (anyShiny ? " SHINY" : "") + " bag: " + GSON.toJson(items));
    }

    /** Reloads the persisted loot history (oldest first on disk → newest first in memory). */
    private void loadLootHistory() {
        try {
            if (!Files.exists(LOOT_HISTORY)) return;
            for (String line : Files.readAllLines(LOOT_HISTORY, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    lootLog.addFirst(GSON.fromJson(line, JsonObject.class));
                } catch (Exception ignored) { // skip corrupt lines, keep the rest
                }
            }
            while (lootLog.size() > LOOT_CAP) lootLog.removeLast();
            System.out.println("[Web] Loaded " + lootLog.size() + " loot entries from " + LOOT_HISTORY);
        } catch (Exception e) {
            System.err.println("[Web] Could not load loot history: " + e);
        }
    }

    private void appendHistory(Path file, JsonObject o) {
        try {
            Files.writeString(file, o.toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[Web] Could not append " + file + ": " + e);
        }
    }

    @Override
    public void bossKilled(String bossName, int bossType, long totalDmg, long fightMs, List<Object[]> top, long ts) {
        JsonObject o = new JsonObject();
        o.addProperty("name", bossName);
        o.addProperty("icon", bossType);
        o.addProperty("total", totalDmg);
        o.addProperty("fightMs", fightMs);
        o.addProperty("ts", ts);
        o.addProperty("map", mapName);
        JsonArray arr = new JsonArray();
        for (Object[] t : top) {
            JsonObject p = new JsonObject();
            p.addProperty("name", (String) t[0]);
            p.addProperty("dmg", (Long) t[1]);
            p.addProperty("me", (Boolean) t[2]);
            p.addProperty("icon", (Integer) t[3]);
            p.addProperty("rank", (Integer) t[4]);
            JsonArray loadout = new JsonArray();
            for (int id : (int[]) t[5]) {
                JsonObject item = new JsonObject();
                item.addProperty("id", id);
                if (id > 0) item.addProperty("name", Names.item(id));
                loadout.add(item);
            }
            p.add("loadout", loadout);
            arr.add(p);
        }
        o.add("top", arr);
        lastBoss = o;
        broadcast("boss", o.toString());
        recordBossKill(bossName, bossType, totalDmg, top, ts);
        System.out.println("[Boss] " + bossName + " killed, total " + totalDmg);
    }

    /**
     * Appends the user's own line for this kill to the personal boss history.
     * A phase despawn can publish the same fight before the confirmed kill
     * does, so a same-boss entry within 90s is replaced, not duplicated.
     */
    private void recordBossKill(String bossName, int bossType, long totalDmg,
                                List<Object[]> top, long ts) {
        long myDmg = 0;
        int myRank = 0;
        for (Object[] t : top) {
            if ((Boolean) t[2]) { // my row is always included when I dealt damage
                myDmg = (Long) t[1];
                myRank = (Integer) t[4];
                break;
            }
        }
        JsonObject e = new JsonObject();
        e.addProperty("name", bossName);
        e.addProperty("icon", bossType);
        e.addProperty("total", totalDmg);
        e.addProperty("myDmg", myDmg);
        if (myRank > 0) e.addProperty("myRank", myRank);
        e.addProperty("ts", ts);
        boolean replaced = false;
        synchronized (bossLog) {
            JsonObject newest = bossLog.peekFirst();
            if (newest != null && newest.get("icon").getAsInt() == bossType
                    && ts - newest.get("ts").getAsLong() < 90_000) {
                bossLog.removeFirst();
                replaced = true;
            }
            bossLog.addFirst(e);
            while (bossLog.size() > BOSS_CAP) bossLog.removeLast();
        }
        if (replaced) rewriteBossHistory();
        else appendHistory(BOSS_HISTORY, e);
        broadcast("bosslog", bossLogJson().toString());
    }

    private JsonArray bossLogJson() {
        JsonArray arr = new JsonArray();
        synchronized (bossLog) {
            int n = 0;
            for (JsonObject e : bossLog) {
                if (n++ >= BOSS_SHOWN) break;
                arr.add(e);
            }
        }
        return arr;
    }

    private void loadBossHistory() {
        try {
            if (!Files.exists(BOSS_HISTORY)) return;
            for (String line : Files.readAllLines(BOSS_HISTORY, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    bossLog.addFirst(GSON.fromJson(line, JsonObject.class));
                } catch (Exception ignored) {
                }
            }
            while (bossLog.size() > BOSS_CAP) bossLog.removeLast();
            System.out.println("[Web] Loaded " + bossLog.size() + " boss entries from " + BOSS_HISTORY);
        } catch (Exception e) {
            System.err.println("[Web] Could not load boss history: " + e);
        }
    }

    /** Full rewrite (oldest first) — only used for the rare phase-dedupe. */
    private void rewriteBossHistory() {
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (bossLog) {
                var it = bossLog.descendingIterator();
                while (it.hasNext()) sb.append(it.next().toString()).append(System.lineSeparator());
            }
            Files.writeString(BOSS_HISTORY, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Web] Could not rewrite boss history: " + e);
        }
    }

    @Override
    public void mapChanged(String name) {
        this.mapName = name;
        JsonObject o = new JsonObject();
        o.addProperty("map", name);
        broadcast("map", o.toString());
    }

    /** Item-name lookup with a safe fallback. */
    static class Names {
        static String item(int id) {
            try {
                String n = assets.IdToAsset.getDisplayName(id);
                if (n != null && !n.isEmpty()) return n;
            } catch (Exception ignored) {
            }
            try {
                return assets.IdToAsset.objectName(id);
            } catch (Exception ignored) {
            }
            return "Item #" + id;
        }
    }
}
