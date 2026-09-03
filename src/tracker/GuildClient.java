package tracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Account + guild client against the relay's /api. The account is created
 * silently on first need: the backend issues a random bearer token that IS
 * the credential (stored in account.properties, never shown); the detected
 * IGN / skin / game-account id are just profile data attached to it — so
 * nobody can take an account over by spoofing packet contents.
 *
 * The page never talks to the backend directly: WebServer proxies the
 * /guild/* routes through this class, so the token stays in the jar.
 */
final class GuildClient {

    private static final Path CONFIG = Paths.get("account.properties");

    private final String base = System.getProperty("tracker.partyurl",
            "https://realmscry.ilias-decraene.workers.dev");
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "guild-client");
                t.setDaemon(true);
                return t;
            });

    private volatile String accountId = "", token = "";
    private volatile boolean inGuild;
    private volatile String guildName = "";
    private volatile String displayName = ""; // user-chosen, from the Account page

    // Profile sources (wired from GameState) + last pushed values.
    private volatile java.util.function.IntSupplier iconSrc = () -> 0;
    private volatile java.util.function.Supplier<String> ignSrc = () -> "";
    private volatile java.util.function.Supplier<String> gameAccSrc = () -> "";
    private volatile String sentProfile = null;

    GuildClient() {
        Properties p = new Properties();
        try {
            if (Files.exists(CONFIG)) {
                p.load(Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        accountId = p.getProperty("accountId", "");
        token = p.getProperty("token", "");
        inGuild = Boolean.parseBoolean(p.getProperty("inGuild", "false"));
        guildName = p.getProperty("guildName", "");
        displayName = p.getProperty("displayName", "");
        // Keep the backend profile in sync with what the sniffer learns.
        exec.scheduleAtFixedRate(this::syncProfile, 60, 120, TimeUnit.SECONDS);
    }

    void setProfileSource(java.util.function.IntSupplier icon,
                          java.util.function.Supplier<String> ign,
                          java.util.function.Supplier<String> gameAccount) {
        iconSrc = icon;
        ignSrc = ign;
        gameAccSrc = gameAccount;
    }

    boolean inGuild() { return inGuild; }
    String guildName() { return guildName; }
    String displayName() { return displayName; }
    String detectedIgn() { return ignSrc.get(); }

    /** Set from the Account page; pushed to the backend right away. */
    JsonObject setDisplayName(String name) {
        name = name == null ? "" : name.trim();
        if (name.length() > 24) name = name.substring(0, 24);
        displayName = name;
        save();
        sentProfile = null; // force a resend
        JsonObject b = new JsonObject();
        b.addProperty("ign", ignSrc.get());
        b.addProperty("icon", iconSrc.getAsInt());
        b.addProperty("gameAccount", gameAccSrc.get());
        b.addProperty("name", name);
        JsonObject r = call("/api/profile", "POST", b, true);
        if (r.has("ok") && r.get("ok").getAsBoolean()) {
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("name", displayName);
            return out;
        }
        return r;
    }

    private void save() {
        try {
            Properties p = new Properties();
            p.setProperty("accountId", accountId);
            p.setProperty("token", token);
            p.setProperty("inGuild", String.valueOf(inGuild));
            p.setProperty("guildName", guildName);
            p.setProperty("displayName", displayName);
            try (var w = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
                p.store(w, "Realmscry account (the token IS the credential - do not share)");
            }
        } catch (Exception e) {
            System.err.println("[Guild] could not save account: " + e);
        }
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private JsonObject call(String path, String method, JsonObject body, boolean auth) {
        try {
            if (auth && token.isEmpty() && !register()) {
                return err("could not create an account (relay unreachable?)");
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(new URI(base + path))
                    .timeout(Duration.ofSeconds(8));
            if (auth) rb.header("Authorization", "Bearer " + token);
            if ("POST".equals(method)) {
                rb.header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(
                        body == null ? "{}" : body.toString()));
            }
            HttpResponse<String> rsp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject o = JsonParser.parseString(rsp.body()).getAsJsonObject();
            if (rsp.statusCode() == 401 && auth) {
                // token revoked/unknown (e.g. wiped backend): re-register once
                token = "";
                if (register()) return call(path, method, body, false, true);
            }
            return o;
        } catch (Exception e) {
            return err("relay unreachable: " + e.getMessage());
        }
    }

    /** Retry variant used after a mid-call re-register. */
    private JsonObject call(String path, String method, JsonObject body,
                            boolean unusedAuthFlag, boolean withToken) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(new URI(base + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token);
            if ("POST".equals(method)) {
                rb.header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(
                        body == null ? "{}" : body.toString()));
            }
            HttpResponse<String> rsp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            return JsonParser.parseString(rsp.body()).getAsJsonObject();
        } catch (Exception e) {
            return err("relay unreachable: " + e.getMessage());
        }
    }

    private static JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }

    private synchronized boolean register() {
        if (!token.isEmpty()) return true;
        JsonObject body = new JsonObject();
        body.addProperty("ign", ignSrc.get());
        body.addProperty("icon", iconSrc.getAsInt());
        body.addProperty("gameAccount", gameAccSrc.get());
        JsonObject r = call("/api/register", "POST", body, false);
        if (r.has("ok") && r.get("ok").getAsBoolean()) {
            accountId = r.get("accountId").getAsString();
            token = r.get("token").getAsString();
            save();
            System.out.println("[Guild] account registered (" + accountId + ")");
            return true;
        }
        return false;
    }

    private void syncProfile() {
        try {
            if (token.isEmpty()) return; // don't create accounts just to sync
            String ign = ignSrc.get();
            int icon = iconSrc.getAsInt();
            String ga = gameAccSrc.get();
            if (ign.isEmpty() && icon == 0 && displayName.isEmpty()) return;
            String sig = ign + "|" + icon + "|" + ga + "|" + displayName;
            if (sig.equals(sentProfile)) return;
            JsonObject b = new JsonObject();
            b.addProperty("ign", ign);
            b.addProperty("icon", icon);
            b.addProperty("gameAccount", ga);
            b.addProperty("name", displayName);
            JsonObject r = call("/api/profile", "POST", b, true);
            if (r.has("ok") && r.get("ok").getAsBoolean()) sentProfile = sig;
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Operations (called from WebServer's /guild/* routes)
    // ------------------------------------------------------------------

    JsonObject create(String name) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name == null ? "" : name);
        JsonObject r = call("/api/guild/create", "POST", b, true);
        noteMembership(r);
        return r;
    }

    JsonObject join(String code) {
        JsonObject b = new JsonObject();
        b.addProperty("code", code == null ? "" : code);
        JsonObject r = call("/api/guild/join", "POST", b, true);
        noteMembership(r);
        return r;
    }

    JsonObject leave() {
        JsonObject r = call("/api/guild/leave", "POST", null, true);
        if (r.has("ok") && r.get("ok").getAsBoolean()) {
            inGuild = false;
            guildName = "";
            save();
        }
        return r;
    }

    JsonObject state() {
        if (token.isEmpty()) return err("no account yet — create or join a guild first");
        JsonObject r = call("/api/guild/state", "GET", null, true);
        if (r.has("ok") && r.get("ok").getAsBoolean() && r.has("inGuild")) {
            boolean now = r.get("inGuild").getAsBoolean();
            String name = now && r.has("name") ? r.get("name").getAsString() : "";
            if (now != inGuild || !name.equals(guildName)) {
                inGuild = now;
                guildName = name;
                save();
            }
        }
        return r;
    }

    JsonObject timeline(String filter, long before) {
        if (token.isEmpty()) return err("no account yet");
        String q = "/api/guild/timeline?filter=" + (filter == null ? "all" : filter);
        if (before > 0) q += "&before=" + before;
        return call(q, "GET", null, true);
    }

    JsonObject like(long eventId, boolean on) {
        JsonObject b = new JsonObject();
        b.addProperty("eventId", eventId);
        b.addProperty("on", on);
        return call("/api/guild/like", "POST", b, true);
    }

    private void noteMembership(JsonObject r) {
        if (r.has("ok") && r.get("ok").getAsBoolean() && r.has("name")) {
            inGuild = true;
            guildName = r.get("name").getAsString();
            save();
            syncProfile();
        }
    }

    /** Fire-and-forget: mirror a local timeline heart onto our guild event. */
    void likeByTs(long ts, boolean on) {
        if (!inGuild || token.isEmpty()) return;
        JsonObject b = new JsonObject();
        b.addProperty("ts", ts);
        b.addProperty("on", on);
        exec.execute(() -> call("/api/guild/likeByTs", "POST", b, true));
    }

    /** Fire-and-forget: publish one of our events to the guild timeline. */
    void postEvent(String type, JsonObject data, long ts) {
        if (!inGuild || token.isEmpty()) return;
        JsonObject b = new JsonObject();
        b.addProperty("type", type);
        b.addProperty("ts", ts);
        b.add("data", data);
        exec.execute(() -> {
            JsonObject r = call("/api/guild/event", "POST", b, true);
            if (!(r.has("ok") && r.get("ok").getAsBoolean())) {
                System.err.println("[Guild] event not delivered: "
                        + (r.has("error") ? r.get("error").getAsString() : "?"));
            }
        });
    }
}
