package tracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Connection to the party relay (Cloudflare Worker, see party-worker/).
 * The JAR owns the WebSocket — sharing works whether or not the browser
 * page is open; the page only renders what the jar forwards over SSE.
 *
 * Membership survives restarts via party.properties next to the jar
 * (install id, display name, party code, joined flag). Reconnects use
 * capped exponential backoff and a JSON ping every 30 s keeps the
 * connection (and the relay room) alive through NATs and hibernation.
 *
 * -Dtracker.partyurl overrides the relay base URL (tests use a local one).
 */
final class PartyClient implements WebSocket.Listener {

    interface Listener {
        /** A frame from the relay: history / members / loot / boss. */
        void partyFrame(JsonObject frame);

        /** Join/leave/connect state changed (reflect in UI + snapshot). */
        void partyStatus();
    }

    private static final Path CONFIG = Paths.get("party.properties");

    private final String base = System.getProperty("tracker.partyurl",
            "https://realmscry.ilias-decraene.workers.dev");
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "party-client");
                t.setDaemon(true);
                return t;
            });
    private final Listener listener;
    private final String installId;

    private volatile String code = "", name = "";
    private volatile boolean joined, connected;
    private volatile WebSocket ws;
    private final StringBuilder partial = new StringBuilder();
    private volatile int backoffSec = 2;

    // Own profile (avatar sprite + in-game name) shared with the party so
    // rosters can show faces and leaderboards can be color-matched.
    private volatile java.util.function.IntSupplier iconSrc = () -> 0;
    private volatile java.util.function.Supplier<String> ignSrc = () -> "";
    private volatile int sentIcon = -1;
    private volatile String sentIgn = null;

    PartyClient(Listener listener) {
        this.listener = listener;
        Properties p = new Properties();
        try {
            if (Files.exists(CONFIG)) {
                p.load(Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        String id = p.getProperty("id", "");
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        installId = id;
        name = p.getProperty("name", "");
        code = p.getProperty("code", "");
        joined = Boolean.parseBoolean(p.getProperty("joined", "false"));
        exec.scheduleAtFixedRate(this::pingIfConnected, 30, 30, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // Public API (called from WebServer's /party/* routes and Main)
    // ------------------------------------------------------------------

    String code() { return joined ? code : ""; }
    String name() { return name; }
    String installId() { return installId; }
    boolean joined() { return joined; }
    boolean connected() { return connected; }

    void setProfileSource(java.util.function.IntSupplier icon,
                          java.util.function.Supplier<String> ign) {
        iconSrc = icon;
        ignSrc = ign;
    }

    /** Rejoin the saved party on startup, silently. */
    void autoRejoin() {
        if (joined && !code.isEmpty()) exec.execute(this::connect);
    }

    /**
     * Join a party (blank/null code = create a new one at the relay first).
     * Returns the party code. Throws with a readable message on failure.
     */
    synchronized String join(String wantedCode, String displayName) throws Exception {
        String c = wantedCode == null ? "" : wantedCode.trim().toUpperCase();
        if (c.isEmpty()) {
            HttpResponse<String> rsp = http.send(HttpRequest.newBuilder()
                            .uri(new URI(base + "/party"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (rsp.statusCode() != 200) throw new IllegalStateException("relay unreachable (HTTP " + rsp.statusCode() + ")");
            c = JsonParser.parseString(rsp.body()).getAsJsonObject().get("code").getAsString();
        }
        if (!c.matches("[A-Z0-9]{4,12}")) throw new IllegalArgumentException("invalid party code");
        displayName = displayName == null ? "" : displayName.trim();
        if (displayName.isEmpty()) throw new IllegalArgumentException("display name required");

        disconnect();
        code = c;
        name = displayName;
        joined = true;
        save();
        connect();
        return c;
    }

    synchronized void leave() {
        joined = false;
        save();
        disconnect();
        listener.partyStatus();
    }

    /** Publish one of our own events ({t:"loot"|"boss", ...}) to the party. */
    void send(JsonObject event) {
        WebSocket w = ws;
        if (w == null || !connected) return;
        try {
            w.sendText(event.toString(), true);
        } catch (Throwable t) {
            // dropped events are acceptable; reconnect logic will recover
        }
    }

    // ------------------------------------------------------------------
    // Connection handling
    // ------------------------------------------------------------------

    private void connect() {
        if (!joined) return;
        try {
            String wsBase = base.replaceFirst("^http", "ws"); // http(s) -> ws(s)
            URI uri = new URI(wsBase + "/party/" + code + "/ws?name="
                    + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + "&id=" + URLEncoder.encode(installId, StandardCharsets.UTF_8));
            http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, this)
                    .whenComplete((sock, err) -> {
                        if (err != null) {
                            System.err.println("[Party] connect failed: " + err);
                            scheduleReconnect();
                        }
                    });
        } catch (Exception e) {
            System.err.println("[Party] connect failed: " + e);
            scheduleReconnect();
        }
    }

    private void disconnect() {
        WebSocket w = ws;
        ws = null;
        connected = false;
        if (w != null) {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Throwable ignored) {
            }
        }
    }

    private void scheduleReconnect() {
        if (!joined) return;
        int delay = backoffSec;
        backoffSec = Math.min(backoffSec * 2, 60);
        exec.schedule(() -> {
            if (joined && !connected) connect();
        }, delay, TimeUnit.SECONDS);
    }

    private void pingIfConnected() {
        WebSocket w = ws;
        if (w != null && connected) {
            maybeSendProfile();
            try {
                w.sendText("{\"t\":\"ping\"}", true);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Push our avatar/IGN when it becomes known or changes (skin swap, map join). */
    private void maybeSendProfile() {
        try {
            int icon = iconSrc.getAsInt();
            String ign = ignSrc.get();
            if (ign == null) ign = "";
            if (icon == sentIcon && ign.equals(sentIgn)) return;
            JsonObject o = new JsonObject();
            o.addProperty("t", "profile");
            o.addProperty("icon", icon);
            o.addProperty("ign", ign);
            send(o);
            sentIcon = icon;
            sentIgn = ign;
        } catch (Throwable ignored) {
        }
    }

    private void save() {
        try {
            Properties p = new Properties();
            p.setProperty("id", installId);
            p.setProperty("name", name);
            p.setProperty("code", code);
            p.setProperty("joined", String.valueOf(joined));
            try (var wtr = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
                p.store(wtr, "Realmscry party membership");
            }
        } catch (Exception e) {
            System.err.println("[Party] could not save config: " + e);
        }
    }

    // ------------------------------------------------------------------
    // WebSocket.Listener
    // ------------------------------------------------------------------

    @Override
    public void onOpen(WebSocket webSocket) {
        ws = webSocket;
        connected = true;
        backoffSec = 2;
        sentIcon = -1; // fresh room connection: (re)send our profile
        sentIgn = null;
        System.out.println("[Party] connected to " + code);
        listener.partyStatus();
        webSocket.request(1);
        maybeSendProfile();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String full = partial.toString();
            partial.setLength(0);
            try {
                JsonObject frame = JsonParser.parseString(full).getAsJsonObject();
                String t = frame.has("t") ? frame.get("t").getAsString() : "";
                if (!"pong".equals(t)) listener.partyFrame(frame);
            } catch (Throwable ignored) { // relay data, never fatal
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (webSocket == ws) {
            connected = false;
            listener.partyStatus();
            scheduleReconnect();
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (webSocket == ws) {
            connected = false;
            System.err.println("[Party] socket error: " + error);
            listener.partyStatus();
            scheduleReconnect();
        }
    }
}
