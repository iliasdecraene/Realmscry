package tracker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Makes the jar self-contained and keeps it current:
 *
 * 1. The ObjectID/TileID tables and sprite atlases the library reads from
 *    ./assets/ are embedded in the jar (under trackerassets/) and extracted
 *    next to it on first run — the app always starts, even with no game
 *    client installed.
 * 2. Then, Tomato-style self-update: if the RotMG Exalt client's
 *    resources.assets is newer than what we last extracted, run Tomato's own
 *    UnityExtractor against it and regenerate everything. This is exactly how
 *    Tomato survives game patches — it never ships assets, it regenerates
 *    them from the player's always-current install.
 */
public class AssetBootstrap {

    private static final String[] FILES = {
            "ObjectID.list",
            "TileID.list",
            "flatbuffer/spritesheetf",
            "sprites/characters.png",
            "sprites/characters_masks.png",
            "sprites/groundTiles.png",
            "sprites/mapObjects.png",
    };

    /** Marker recording which resources.assets we last extracted from. */
    private static final Path MARKER = Paths.get("assets", ".assets-version");
    private static final Path BACKUP_DIR = Paths.get("assets", ".bak-refresh");

    public static void ensure() {
        extractEmbedded();
        refreshFromGameClient();
    }

    private static void extractEmbedded() {
        File dir = new File("assets");
        for (String name : FILES) {
            File target = new File(dir, name);
            if (target.exists() && target.length() > 0) continue;
            try (InputStream in = AssetBootstrap.class.getClassLoader()
                    .getResourceAsStream("trackerassets/" + name)) {
                if (in == null) {
                    System.err.println("[Assets] embedded " + name + " missing from jar");
                    continue;
                }
                target.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                System.out.println("[Assets] extracted " + target.getPath());
            } catch (IOException e) {
                System.err.println("[Assets] failed to extract " + name + ": " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Self-update from the installed game client
    // ------------------------------------------------------------------

    private static void refreshFromGameClient() {
        try {
            File res = findResourcesAssets();
            if (res == null) {
                System.out.println("[Assets] Game client not found - using bundled assets");
                return;
            }
            String sig = res.lastModified() + ":" + res.length();
            String prev = Files.exists(MARKER) ? Files.readString(MARKER).trim() : "";
            // Re-extract when the client updated, or when features need XML
            // data an older extraction didn't produce (players.xml powers the
            // death-card "maxed" badge).
            boolean xmlMissing = !Files.isRegularFile(Paths.get("assets", "xml", "players.xml"));
            if (sig.equals(prev) && !xmlMissing) return; // assets already current
            System.out.println("[Assets] Game update detected - extracting fresh assets from "
                    + res.getAbsolutePath());
            javax.swing.JFrame progress = showProgressWindow();
            backupAssets();
            try {
                runTomatoExtraction(res);
                Files.writeString(MARKER, sig);
                deleteRecursive(BACKUP_DIR);
                System.out.println("[Assets] Asset refresh complete");
            } catch (Throwable t) {
                System.err.println("[Assets] Extraction failed, restoring previous assets: " + t);
                t.printStackTrace();
                restoreAssets();
            } finally {
                if (progress != null) {
                    javax.swing.JFrame p = progress;
                    javax.swing.SwingUtilities.invokeLater(p::dispose);
                }
            }
        } catch (Throwable t) {
            // Never let the refresh keep the tracker from starting.
            System.err.println("[Assets] Asset refresh skipped: " + t);
        }
    }

    /** RotMG Exalt's Unity bundle; -Dtracker.resassets overrides discovery. */
    private static File findResourcesAssets() {
        String override = System.getProperty("tracker.resassets");
        if (override != null) {
            File f = new File(override);
            return f.isFile() ? f : null;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            File f = new File(localAppData,
                    "RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets");
            if (f.isFile()) return f;
        }
        File mac = new File(System.getProperty("user.home"),
                ".local/share/RealmOfTheMadGod/Production/RotMGExalt.app/Contents/Resources/Data/resources.assets");
        return mac.isFile() ? mac : null;
    }

    /**
     * Drives Tomato's extractor exactly like its own GUI flow does:
     * UnityExtractor parses resources.assets into spritesheetf + sprite
     * atlases + game XMLs, then extractAssetsFromXML flattens the XMLs into
     * ObjectID.list / TileID.list (Util.print truncates, so no stale merge).
     * The extractor reports progress into AssetExtractor's static JOptionPane;
     * seed one via reflection or setDisplay NPEs (the pane is never shown).
     */
    private static void runTomatoExtraction(File res) throws Exception {
        Field pane = assets.AssetExtractor.class.getDeclaredField("pane");
        pane.setAccessible(true);
        pane.set(null, new javax.swing.JOptionPane());

        new assets.resextractor.UnityExtractor().extract(res, new File[]{
                new File("assets/flatbuffer/"),
                new File("assets/sprites/"),
                new File("assets/xml/"),
        });

        Method m = assets.AssetExtractor.class.getDeclaredMethod("extractAssetsFromXML");
        m.setAccessible(true);
        m.invoke(null);
    }

    private static void backupAssets() {
        for (String name : FILES) {
            try {
                Path src = Paths.get("assets", name);
                if (!Files.exists(src)) continue;
                Path dst = BACKUP_DIR.resolve(name);
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[Assets] backup failed for " + name + ": " + e);
            }
        }
    }

    private static void restoreAssets() {
        for (String name : FILES) {
            try {
                Path src = BACKUP_DIR.resolve(name);
                if (!Files.exists(src)) continue;
                Files.copy(src, Paths.get("assets", name), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[Assets] restore failed for " + name + ": " + e);
            }
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    /** Tiny "please wait" window so a double-click launch doesn't look hung. */
    private static javax.swing.JFrame showProgressWindow() {
        if (java.awt.GraphicsEnvironment.isHeadless()
                || System.getProperty("tracker.nogui") != null) return null;
        try {
            javax.swing.JFrame f = new javax.swing.JFrame("Realm Tracker");
            javax.swing.JLabel l = new javax.swing.JLabel(
                    "Game update detected - extracting fresh assets, this takes a moment...");
            l.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 24, 18, 24));
            f.add(l);
            f.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
            f.pack();
            f.setLocationByPlatform(true);
            javax.swing.SwingUtilities.invokeLater(() -> f.setVisible(true));
            return f;
        } catch (Throwable t) {
            return null;
        }
    }
}
