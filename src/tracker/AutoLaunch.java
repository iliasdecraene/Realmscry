package tracker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * "Launch Realmscry when the game launches" (Windows only).
 *
 * Implemented as a tiny VBScript in the user's Startup folder: it wakes
 * every ~15 s, and when it sees "RotMG Exalt.exe" without a Realmscry
 * already running it starts the jar, then re-arms only after the game
 * exits (so closing the tracker mid-session doesn't relaunch it).
 * A wscript loop idles at ~5 MB — no JVM sits in the background.
 *
 * Disable = delete the script: the watcher checks its own file each cycle
 * and exits when it's gone, so no process hunting is needed.
 */
final class AutoLaunch {

    private static final String SCRIPT_NAME = "RealmscryWatch.vbs";

    private AutoLaunch() {
    }

    static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                && System.getenv("APPDATA") != null;
    }

    private static Path scriptPath() {
        return Paths.get(System.getenv("APPDATA"),
                "Microsoft", "Windows", "Start Menu", "Programs", "Startup", SCRIPT_NAME);
    }

    static boolean enabled() {
        try {
            return supported() && Files.isRegularFile(scriptPath());
        } catch (Exception e) {
            return false;
        }
    }

    /** Writes the watcher into Startup and starts it right away. */
    static synchronized void enable() throws Exception {
        if (!supported()) throw new IllegalStateException("Windows only");
        Path jar = ownJar();
        if (jar == null) throw new IllegalStateException("not running from a jar");
        Path javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe");
        String vbs = buildScript(javaw.toString(), jar.toString(),
                jar.getParent().toString());
        Files.createDirectories(scriptPath().getParent());
        Files.writeString(scriptPath(), vbs, StandardCharsets.UTF_8);
        // Take effect now, not at next login. Multiple instances are safe:
        // both see the tracker running and idle, and both exit on disable.
        new ProcessBuilder("wscript.exe", scriptPath().toString()).start();
        System.out.println("[AutoLaunch] watcher installed: " + scriptPath());
    }

    static synchronized void disable() throws Exception {
        Files.deleteIfExists(scriptPath()); // running watcher exits by itself
        System.out.println("[AutoLaunch] watcher removed");
    }

    private static Path ownJar() {
        try {
            Path p = Paths.get(AutoLaunch.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) && p.toString().endsWith(".jar") ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String q(String s) {
        return "\"\"" + s + "\"\"";
    }

    private static String buildScript(String javaw, String jar, String dir) {
        return String.join("\r\n",
            "' Realmscry auto-launch watcher - managed by Realmscry (Settings).",
            "' Deleting this file stops the watcher within one cycle.",
            "Set fso = CreateObject(\"Scripting.FileSystemObject\")",
            "Set wmi = GetObject(\"winmgmts:\\\\.\\root\\cimv2\")",
            "Set sh = CreateObject(\"WScript.Shell\")",
            "Do",
            "  If Not fso.FileExists(WScript.ScriptFullName) Then WScript.Quit",
            "  If wmi.ExecQuery(\"SELECT * FROM Win32_Process WHERE Name='RotMG Exalt.exe'\").Count > 0 Then",
            "    If wmi.ExecQuery(\"SELECT * FROM Win32_Process WHERE Name='javaw.exe' AND CommandLine LIKE '%Realmscry%.jar%'\").Count = 0 Then",
            "      sh.CurrentDirectory = \"" + dir + "\"",
            "      sh.Run \"" + q(javaw) + " -jar " + q(jar) + "\", 1, False",
            "    End If",
            "    Do While wmi.ExecQuery(\"SELECT * FROM Win32_Process WHERE Name='RotMG Exalt.exe'\").Count > 0",
            "      If Not fso.FileExists(WScript.ScriptFullName) Then WScript.Quit",
            "      WScript.Sleep 20000",
            "    Loop",
            "  End If",
            "  WScript.Sleep 15000",
            "Loop",
            "");
    }
}
