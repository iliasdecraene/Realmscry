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
public class WebServer implements GameState.Publisher, PartyClient.Listener {

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

    // Party: relay frames the jar received, replayed to fresh pages via the
    // snapshot. Deduped (reconnects replay relay history), capped.
    private static final int PARTY_CAP = 200;
    private final ArrayDeque<JsonObject> partyEvents = new ArrayDeque<>();
    private final java.util.LinkedHashSet<String> partySeen = new java.util.LinkedHashSet<>();
    private JsonArray partyMembers = new JsonArray();
    private volatile PartyClient party;

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
        server.createContext("/party/join", this::servePartyJoin);
        server.createContext("/party/leave", this::servePartyLeave);
        server.createContext("/guild/", this::serveGuild);
        server.createContext("/account/", this::serveAccount);
        server.createContext("/timeline/like", this::serveTimelineLike);
        server.createContext("/settings/", this::serveSettings);
        server.createContext("/overlay/", this::serveOverlay);
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
        java.util.Map<String, Object> d = s == null ? new java.util.LinkedHashMap<>() : s.debug();
        PartyClient p = party;
        d.put("partyJoined", p != null && p.joined());
        d.put("partyConnected", p != null && p.connected());
        d.put("partyCode", p == null ? "" : p.code());
        synchronized (partyEvents) {
            d.put("partyMembers", partyMembers.size());
            d.put("partyEvents", partyEvents.size());
        }
        byte[] body = GSON.toJson(d).getBytes(StandardCharsets.UTF_8);
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
        snap.add("party", partyStateJson());
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
        if (overlay != null) overlay.refresh();
        PartyClient p = party;
        if (p != null && p.joined()) {
            JsonObject share = o.deepCopy();
            share.addProperty("t", "loot");
            p.send(share);
        }
        GuildClient g = guild;
        if (g != null) g.postEvent("loot", o.deepCopy(), ts);
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
        if (overlay != null) overlay.refresh();
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
        PartyClient p = party;
        if (p != null && p.joined()) {
            JsonObject share = e.deepCopy();
            share.addProperty("t", "boss");
            share.addProperty("map", mapName);
            p.send(share);
        }
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

    // ------------------------------------------------------------------
    // Party
    // ------------------------------------------------------------------

    public void setParty(PartyClient party) {
        this.party = party;
    }

    /** In-game names of the other party members (for leaderboard matching). */
    public java.util.Set<String> partyMemberIgns() {
        PartyClient p = party;
        if (p == null || !p.joined()) return java.util.Collections.emptySet();
        java.util.Set<String> names = new java.util.HashSet<>();
        synchronized (partyEvents) {
            for (var el : partyMembers) {
                if (!el.isJsonObject()) continue;
                JsonObject m = el.getAsJsonObject();
                if (m.has("id") && p.installId().equals(m.get("id").getAsString())) continue;
                String ign = m.has("ign") ? m.get("ign").getAsString() : "";
                if (!ign.isEmpty()) names.add(ign);
                else if (m.has("name")) names.add(m.get("name").getAsString());
            }
        }
        return names;
    }

    private JsonObject partyStateJson() {
        JsonObject o = new JsonObject();
        PartyClient p = party;
        o.addProperty("joined", p != null && p.joined());
        o.addProperty("connected", p != null && p.connected());
        o.addProperty("code", p == null ? "" : p.code());
        o.addProperty("name", p == null ? "" : p.name());
        o.addProperty("id", p == null ? "" : p.installId());
        synchronized (partyEvents) {
            o.add("members", partyMembers.deepCopy());
            JsonArray evs = new JsonArray();
            for (JsonObject e : partyEvents) evs.add(e);
            o.add("events", evs);
        }
        return o;
    }

    @Override
    public void partyFrame(JsonObject frame) {
        String t = frame.has("t") ? frame.get("t").getAsString() : "";
        synchronized (partyEvents) {
            switch (t) {
                case "members" -> partyMembers =
                        frame.getAsJsonArray("members") != null
                                ? frame.getAsJsonArray("members") : new JsonArray();
                case "history" -> {
                    JsonArray evs = frame.getAsJsonArray("events");
                    if (evs != null) for (var el : evs) {
                        if (el.isJsonObject()) addPartyEvent(el.getAsJsonObject());
                    }
                }
                case "loot", "boss" -> {
                    if (!addPartyEvent(frame)) return; // duplicate: don't rebroadcast
                }
                default -> {
                    return; // unknown frame: ignore
                }
            }
        }
        broadcast("party", frame.toString());
    }

    /** Dedupe + append; returns false for an already-seen event. */
    private boolean addPartyEvent(JsonObject e) {
        try {
            String key = e.get("t").getAsString() + "|"
                    + e.get("fromId").getAsString() + "|" + e.get("ts").getAsLong();
            if (!partySeen.add(key)) return false;
            while (partySeen.size() > PARTY_CAP * 2) {
                partySeen.remove(partySeen.iterator().next());
            }
        } catch (Exception ex) {
            return false; // malformed relay event
        }
        partyEvents.addLast(e);
        while (partyEvents.size() > PARTY_CAP) partyEvents.removeFirst();
        return true;
    }

    @Override
    public void partyStatus() {
        JsonObject o = partyStateJson();
        o.remove("events"); // status pushes stay small; snapshot has the log
        broadcast("partystatus", o.toString());
    }

    private void servePartyJoin(HttpExchange ex) throws IOException {
        JsonObject rsp = new JsonObject();
        try {
            if (!"POST".equals(ex.getRequestMethod())) throw new IllegalArgumentException("POST only");
            PartyClient p = party;
            if (p == null) throw new IllegalStateException("party client not ready");
            JsonObject body = com.google.gson.JsonParser
                    .parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String code = body.has("code") ? body.get("code").getAsString() : "";
            String name = body.has("name") ? body.get("name").getAsString() : "";
            String joinedCode = p.join(code, name);
            rsp.addProperty("ok", true);
            rsp.addProperty("code", joinedCode);
        } catch (Exception e) {
            rsp.addProperty("ok", false);
            rsp.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        byte[] body = rsp.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void servePartyLeave(HttpExchange ex) throws IOException {
        PartyClient p = party;
        if (p != null) p.leave();
        synchronized (partyEvents) {
            partyEvents.clear();
            partySeen.clear();
            partyMembers = new JsonArray();
        }
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ------------------------------------------------------------------
    // Guild (account + guild live behind GuildClient; page goes through us
    // so the auth token never leaves the jar)
    // ------------------------------------------------------------------

    private volatile GuildClient guild;

    public void setGuild(GuildClient guild) {
        this.guild = guild;
    }

    private void serveGuild(HttpExchange ex) throws IOException {
        GuildClient g = guild;
        JsonObject rsp;
        if (g == null) {
            rsp = new JsonObject();
            rsp.addProperty("ok", false);
            rsp.addProperty("error", "guild client not ready");
        } else {
            String path = ex.getRequestURI().getPath();
            JsonObject body = new JsonObject();
            try {
                byte[] raw = ex.getRequestBody().readAllBytes();
                if (raw.length > 0) {
                    body = com.google.gson.JsonParser
                            .parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
                }
            } catch (Exception ignored) {
            }
            String q = ex.getRequestURI().getQuery();
            java.util.Map<String, String> qs = new java.util.HashMap<>();
            if (q != null) {
                for (String kv : q.split("&")) {
                    int i = kv.indexOf('=');
                    if (i > 0) qs.put(kv.substring(0, i), kv.substring(i + 1));
                }
            }
            rsp = switch (path) {
                case "/guild/status" -> g.state();
                case "/guild/create" -> g.create(body.has("name") ? body.get("name").getAsString() : "");
                case "/guild/join" -> g.join(body.has("code") ? body.get("code").getAsString() : "");
                case "/guild/leave" -> g.leave();
                case "/guild/timeline" -> g.timeline(qs.getOrDefault("filter", "all"),
                        Long.parseLong(qs.getOrDefault("before", "0")));
                case "/guild/like" -> g.like(
                        body.has("eventId") ? body.get("eventId").getAsLong() : 0,
                        body.has("on") && body.get("on").getAsBoolean());
                default -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("ok", false);
                    o.addProperty("error", "unknown route");
                    yield o;
                }
            };
        }
        byte[] out = rsp.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    private void serveAccount(HttpExchange ex) throws IOException {
        GuildClient g = guild;
        JsonObject rsp = new JsonObject();
        String path = ex.getRequestURI().getPath();
        if (g == null) {
            rsp.addProperty("ok", false);
            rsp.addProperty("error", "not ready");
        } else if ("/account/status".equals(path)) {
            rsp.addProperty("ok", true);
            rsp.addProperty("name", g.displayName());
            rsp.addProperty("ign", g.detectedIgn());
            rsp.addProperty("inGuild", g.inGuild());
        } else if ("/account/name".equals(path)) {
            try {
                JsonObject body = com.google.gson.JsonParser
                        .parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                rsp = g.setDisplayName(body.has("name") ? body.get("name").getAsString() : "");
            } catch (Exception e) {
                rsp.addProperty("ok", false);
                rsp.addProperty("error", "bad request");
            }
        } else {
            rsp.addProperty("ok", false);
            rsp.addProperty("error", "unknown route");
        }
        byte[] out = rsp.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    // ------------------------------------------------------------------
    // Overlay
    // ------------------------------------------------------------------

    private volatile OverlayManager overlay;

    public void setOverlay(OverlayManager o) {
        this.overlay = o;
    }

    /** Newest own timeline entry (loot or death) for the overlay. */
    public JsonObject latestTimelineEntry() {
        synchronized (lootLog) {
            return lootLog.peekFirst();
        }
    }

    /** Last published boss kill for the overlay. */
    public JsonObject lastBossJson() {
        return lastBoss;
    }

    /** Cached sprite PNG bytes for the overlay renderer (empty = unknown id). */
    public byte[] iconPng(int id) {
        return id > 0 ? iconCache.computeIfAbsent(id, this::renderIcon) : NO_ICON;
    }

    private void serveOverlay(HttpExchange ex) throws IOException {
        OverlayManager o = overlay;
        JsonObject rsp;
        String path = ex.getRequestURI().getPath();
        if (o == null) {
            rsp = new JsonObject();
            rsp.addProperty("ok", false);
            rsp.addProperty("error", "overlay not available");
        } else if ("/overlay/status".equals(path)) {
            rsp = o.configJson();
        } else if ("/overlay/config".equals(path)) {
            try {
                JsonObject body = com.google.gson.JsonParser
                        .parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                o.applyConfig(body);
                rsp = o.configJson();
            } catch (Exception e) {
                rsp = new JsonObject();
                rsp.addProperty("ok", false);
                rsp.addProperty("error", "bad request");
            }
        } else {
            rsp = new JsonObject();
            rsp.addProperty("ok", false);
            rsp.addProperty("error", "unknown route");
        }
        byte[] out = rsp.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    private void serveSettings(HttpExchange ex) throws IOException {
        JsonObject rsp = new JsonObject();
        String path = ex.getRequestURI().getPath();
        try {
            if ("/settings/status".equals(path)) {
                rsp.addProperty("ok", true);
                rsp.addProperty("autoLaunchSupported", AutoLaunch.supported());
                rsp.addProperty("autoLaunch", AutoLaunch.enabled());
            } else if ("/settings/autolaunch".equals(path)) {
                JsonObject body = com.google.gson.JsonParser
                        .parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                boolean on = body.has("on") && body.get("on").getAsBoolean();
                if (on) AutoLaunch.enable();
                else AutoLaunch.disable();
                rsp.addProperty("ok", true);
                rsp.addProperty("autoLaunch", AutoLaunch.enabled());
            } else {
                rsp.addProperty("ok", false);
                rsp.addProperty("error", "unknown route");
            }
        } catch (Exception e) {
            rsp.addProperty("ok", false);
            rsp.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        byte[] out = rsp.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    /**
     * Heart on a local timeline entry (own loot/death, addressed by ts).
     * Persisted in the entry itself ("liked":true, full history rewrite —
     * cheap at our cap) and mirrored to the guild timeline when member.
     */
    private void serveTimelineLike(HttpExchange ex) throws IOException {
        JsonObject rsp = new JsonObject();
        try {
            JsonObject body = com.google.gson.JsonParser
                    .parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            long ts = body.get("ts").getAsLong();
            boolean on = body.has("on") && body.get("on").getAsBoolean();
            JsonObject entry = null;
            synchronized (lootLog) {
                for (JsonObject o : lootLog) {
                    if (o.has("ts") && o.get("ts").getAsLong() == ts) {
                        if (on) o.addProperty("liked", true);
                        else o.remove("liked");
                        entry = o;
                        break;
                    }
                }
                if (entry != null) rewriteLootHistory();
            }
            boolean found = entry != null;
            if (found) {
                GuildClient g = guild;
                if (g != null) {
                    JsonObject share = entry.deepCopy();
                    share.remove("liked");
                    String type = entry.has("type") ? entry.get("type").getAsString() : "loot";
                    g.likeByTs(ts, on, share, type);
                }
            }
            rsp.addProperty("ok", found);
            if (!found) rsp.addProperty("error", "entry not found");
            rsp.addProperty("liked", on);
        } catch (Exception e) {
            rsp.addProperty("ok", false);
            rsp.addProperty("error", "bad request");
        }
        byte[] out = rsp.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    /** Rewrite the whole timeline history (oldest first) — likes changed. */
    private void rewriteLootHistory() {
        try {
            StringBuilder sb = new StringBuilder();
            var it = lootLog.descendingIterator();
            while (it.hasNext()) sb.append(it.next().toString()).append(System.lineSeparator());
            Files.writeString(LOOT_HISTORY, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Web] Could not rewrite timeline history: " + e);
        }
    }

    @Override
    public void died(GameState.Death d) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "death");
        o.addProperty("ts", d.ts);
        o.addProperty("map", mapName);
        o.addProperty("name", d.name);
        o.addProperty("icon", d.icon);
        o.addProperty("classType", d.classType);
        o.addProperty("killedBy", d.killedBy);
        o.addProperty("fame", d.fame);
        o.addProperty("maxed", d.maxed);
        JsonArray equip = new JsonArray();
        for (int id : d.equip) {
            JsonObject it = new JsonObject();
            it.addProperty("id", id);
            if (id > 0) it.addProperty("name", Names.item(id));
            equip.add(it);
        }
        o.add("equip", equip);
        JsonArray carried = new JsonArray();
        for (int id : d.backpack) {
            JsonObject it = new JsonObject();
            it.addProperty("id", id);
            it.addProperty("name", Names.item(id));
            carried.add(it);
        }
        o.add("backpack", carried);
        synchronized (lootLog) {
            lootLog.addFirst(o);
            while (lootLog.size() > LOOT_CAP) lootLog.removeLast();
        }
        appendHistory(LOOT_HISTORY, o);
        broadcast("death", o.toString());
        if (overlay != null) overlay.refresh();
        PartyClient p = party;
        if (p != null && p.joined()) {
            JsonObject share = o.deepCopy();
            share.addProperty("t", "death");
            p.send(share);
        }
        GuildClient g = guild;
        if (g != null) g.postEvent("death", o.deepCopy(), d.ts);
        System.out.println("[Death] " + d.name + " killed by " + d.killedBy
                + " (fame " + d.fame + ", " + d.maxed + "/8)");
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
