package tracker;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Loot-drop audio cues, reusing the wavs already bundled in the Tomato jar
 * (sound/whitebag.wav etc.). Mirrors tomato.realmshark.Sound's loading and
 * gain handling, but deliberately does NOT touch that class — its statics
 * pull in Tomato GUI classes, the same trap as CharacterClass.
 *
 * A real ./sound/<name>.wav on disk overrides the bundled one (custom cues).
 * -Dtracker.volume=0..100 sets loudness; 0 mutes, default 85.
 */
final class Sounds {

    private static final boolean MUTED;
    private static final float GAIN_DB;

    static {
        int v = Integer.getInteger("tracker.volume", 85);
        v = Math.max(0, Math.min(100, v));
        MUTED = v == 0;
        GAIN_DB = MUTED ? 0f : (float) (20.0 * Math.log10(v / 100.0));
    }

    private static final Clip WHITE = load("sound/whitebag.wav");
    private static final Clip ORANGE = load("sound/orangebag.wav");
    private static final Clip RED = load("sound/redbag.wav");

    private Sounds() {
    }

    /** Called once at startup so clip loading doesn't happen on the capture thread. */
    static void init() {
        // static initializers did the work
    }

    /** Cue for a published loot drop; shiny pseudo-tier gets the white-bag hype. */
    static void playBag(String tier) {
        if (MUTED) return;
        Clip c;
        switch (tier) {
            case "orange": c = ORANGE; break;
            case "red": c = RED; break;
            default: c = WHITE; break; // white, shiny, anything future
        }
        play(c);
    }

    private static void play(Clip c) {
        if (c == null) return;
        try {
            try {
                FloatControl gain = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
                gain.setValue(GAIN_DB);
            } catch (Exception ignored) { // no gain control: play at line volume
            }
            c.setFramePosition(0);
            c.start();
        } catch (Throwable t) {
            // audio must never break tracking
        }
    }

    private static Clip load(String path) {
        try {
            File onDisk = new File(path);
            InputStream in = onDisk.isFile()
                    ? Files.newInputStream(onDisk.toPath())
                    : Sounds.class.getClassLoader().getResourceAsStream(path);
            if (in == null) return null;
            AudioInputStream stream = AudioSystem.getAudioInputStream(new BufferedInputStream(in));
            AudioFormat base = stream.getFormat();
            // Same normalization Tomato uses for these wavs.
            AudioFormat decoded = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 1, 2, 44100f, false);
            Clip clip = AudioSystem.getClip();
            if (AudioSystem.isConversionSupported(decoded, base)) {
                clip.open(AudioSystem.getAudioInputStream(decoded, stream));
            } else {
                clip.open(stream);
            }
            return clip;
        } catch (Throwable t) {
            System.err.println("[Sound] could not load " + path + ": " + t);
            return null;
        }
    }
}
