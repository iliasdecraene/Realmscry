package tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * Self-update from GitHub releases, so friends only ever download the jar
 * once. On every launch the tracker asks the repo for its latest release;
 * if the tag is newer than {@link #VERSION} it downloads the release's
 * RealmTracker.jar and swaps itself out.
 *
 * Windows locks a running jar, so the swap is a relaunch dance:
 *   1. this process downloads to RealmTracker.update.jar, verifies it,
 *      starts it with `--finish-update <ourPid> <ourJarPath>` and exits;
 *   2. the new process waits for us to die, copies its own jar over
 *      RealmTracker.jar, and then simply continues its normal startup —
 *      the user is now running the new version;
 *   3. the leftover .update.jar (locked while step 2's JVM lives) is
 *      deleted on the next normal launch.
 *
 * Every failure — offline, GitHub down, bad download — just logs and lets
 * the current version start. `-Dtracker.noupdate` skips the check,
 * `-Dtracker.repo=user/repo` overrides the repo, and `-Dtracker.updatebase`
 * points the API elsewhere (used by tests).
 */
final class Updater {

    /** Bump on every release — must match the git tag (tag "v" + VERSION). */
    static final String VERSION = "1.6.3";

    /** "user/repo" on GitHub. Empty disables self-update entirely. */
    private static final String DEFAULT_REPO = "iliasdecraene/Realmscry";

    private static final String UPDATE_NAME = "Realmscry.update.jar";
    private static final String ASSET_NAME = "Realmscry.jar";

    private Updater() {
    }

    /** Called first thing in main(); may relaunch the process and not return. */
    static void run(String[] args) {
        try {
            if (args.length >= 3 && "--finish-update".equals(args[0])) {
                finishUpdate(Long.parseLong(args[1]), Paths.get(args[2]));
                return; // keep starting up — we ARE the new version
            }
            checkAndUpdate();
        } catch (Throwable t) {
            System.err.println("[Update] skipped: " + t);
        }
    }

    // --------------------------------------------------------------
    // Step 2: we are the freshly downloaded jar
    // --------------------------------------------------------------

    private static void finishUpdate(long oldPid, Path target) {
        try {
            ProcessHandle.of(oldPid).ifPresent(h -> {
                try {
                    h.onExit().get(15, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            });
            Path self = selfJar();
            if (self == null || self.equals(target)) return;
            // The old JVM releases its lock a beat after the process ends.
            for (int i = 0; i < 20; i++) {
                try {
                    Files.copy(self, target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[Update] installed " + VERSION + " -> " + target);
                    return;
                } catch (Exception e) {
                    Thread.sleep(500);
                }
            }
            System.err.println("[Update] could not replace " + target
                    + " — running the new version anyway");
        } catch (Throwable t) {
            System.err.println("[Update] finish failed: " + t);
        }
    }

    // --------------------------------------------------------------
    // Step 1: normal launch — check GitHub, maybe hand over
    // --------------------------------------------------------------

    private static void checkAndUpdate() throws Exception {
        Path self = selfJar();
        if (self == null) return; // dev run from classes, not a jar

        // Leftover from a previous update, deletable now that its JVM is gone.
        if (!UPDATE_NAME.equals(self.getFileName().toString())) {
            try {
                Files.deleteIfExists(self.resolveSibling(UPDATE_NAME));
            } catch (Exception ignored) {
            }
        }

        String repo = System.getProperty("tracker.repo", DEFAULT_REPO);
        if (repo.isEmpty() || System.getProperty("tracker.noupdate") != null) return;
        String apiBase = System.getProperty("tracker.updatebase", "https://api.github.com");

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(4))
                .build();
        HttpResponse<String> rsp = http.send(HttpRequest.newBuilder()
                        .uri(new URI(apiBase + "/repos/" + repo + "/releases/latest"))
                        .header("Accept", "application/vnd.github+json")
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (rsp.statusCode() != 200) return; // no releases yet, rate-limited, …

        JsonObject rel = JsonParser.parseString(rsp.body()).getAsJsonObject();
        String tag = rel.get("tag_name").getAsString();
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!isNewer(latest, VERSION)) return;

        String url = null;
        JsonArray assets = rel.getAsJsonArray("assets");
        for (int i = 0; assets != null && i < assets.size(); i++) {
            JsonObject a = assets.get(i).getAsJsonObject();
            if (ASSET_NAME.equals(a.get("name").getAsString())) {
                url = a.get("browser_download_url").getAsString();
                break;
            }
        }
        if (url == null) return; // release without our asset

        System.out.println("[Update] " + VERSION + " -> " + latest + ", downloading…");
        JDialog note = showNote("Updating Realmscry to v" + latest + "…");

        Path update = self.resolveSibling(UPDATE_NAME);
        try {
            HttpResponse<Path> dl = http.send(HttpRequest.newBuilder()
                            .uri(new URI(url))
                            .timeout(Duration.ofMinutes(3))
                            .build(),
                    HttpResponse.BodyHandlers.ofFile(update));
            if (dl.statusCode() != 200 || !looksLikeTracker(update)) {
                throw new IllegalStateException("bad download (HTTP " + dl.statusCode() + ")");
            }
            relaunch(update, self);
            System.exit(0); // hands the port + jar lock to the new process
        } catch (Exception e) {
            try {
                Files.deleteIfExists(update);
            } catch (Exception ignored) {
            }
            if (note != null) SwingUtilities.invokeLater(note::dispose);
            System.err.println("[Update] download failed, starting current version: " + e);
        }
    }

    /** The downloaded file must at least be a jar containing our main class. */
    private static boolean looksLikeTracker(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry("tracker/Main.class") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void relaunch(Path jar, Path self) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin());
        // Keep the user's -D flags (port, volume, nogui, repo override, …).
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-D")) cmd.add(a);
        }
        cmd.add("-jar");
        cmd.add(jar.toString());
        cmd.add("--finish-update");
        cmd.add(String.valueOf(ProcessHandle.current().pid()));
        cmd.add(self.toString());
        new ProcessBuilder(cmd)
                .directory(self.getParent().toFile()) // keep assets/ + history in place
                .inheritIO()
                .start();
    }

    /** javaw.exe when it exists (no console flash on Windows), else java. */
    private static String javaBin() {
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        Path javaw = bin.resolve("javaw.exe");
        if (Files.isRegularFile(javaw)) return javaw.toString();
        Path exe = bin.resolve("java.exe");
        return Files.isRegularFile(exe) ? exe.toString() : bin.resolve("java").toString();
    }

    /** Path of the jar we are running from, or null when run from classes. */
    private static Path selfJar() {
        try {
            Path p = Paths.get(Updater.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) && p.toString().endsWith(".jar") ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Dotted-numeric compare: "1.10.0" beats "1.9.3"; junk compares equal. */
    static boolean isNewer(String candidate, String current) {
        try {
            String[] a = candidate.split("\\.");
            String[] b = current.split("\\.");
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length ? Integer.parseInt(a[i].trim()) : 0;
                int y = i < b.length ? Integer.parseInt(b[i].trim()) : 0;
                if (x != y) return x > y;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Tiny always-on-top note while the new jar downloads; null if headless. */
    private static JDialog showNote(String text) {
        if (GraphicsEnvironment.isHeadless()
                || System.getProperty("tracker.nogui") != null) return null;
        try {
            JDialog d = new JDialog((java.awt.Frame) null, "Realmscry");
            d.setUndecorated(true);
            JLabel l = new JLabel(text);
            l.setForeground(Color.WHITE);
            l.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x3987e5)),
                    BorderFactory.createEmptyBorder(14, 22, 14, 22)));
            l.setOpaque(true);
            l.setBackground(new Color(0x1a1a19));
            d.add(l);
            d.pack();
            d.setLocationRelativeTo(null);
            d.setAlwaysOnTop(true);
            d.setVisible(true);
            return d;
        } catch (Exception e) {
            return null;
        }
    }
}
