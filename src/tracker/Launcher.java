package tracker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URI;

/**
 * Minimal control window: shows sniffer status and the current map, with a
 * single button that opens the web UI in the default browser. Closing the
 * window exits the tracker.
 */
public class Launcher {

    private static final Color BG = new Color(0x1a1a19);
    private static final Color FG = new Color(0xffffff);
    private static final Color MUTED = new Color(0x8a897f);
    private static final Color ACCENT = new Color(0x3987e5);

    private JLabel mapLabel;
    private final String url;

    public Launcher(String url) {
        this.url = url;
        SwingUtilities.invokeLater(this::build);
    }

    private void build() {
        JFrame frame = new JFrame("Realmscry v" + Updater.VERSION);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setIconImages(loadIcons());

        JPanel root = new JPanel();
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(18, 24, 18, 24));
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Realmscry");
        title.setForeground(FG);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel status = new JLabel("Sniffing TCP port 2050 — join or re-enter a map to sync");
        status.setForeground(MUTED);
        status.setFont(status.getFont().deriveFont(11.5f));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);

        mapLabel = new JLabel(" ");
        mapLabel.setForeground(MUTED);
        mapLabel.setFont(mapLabel.getFont().deriveFont(11.5f));
        mapLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton open = new JButton("Open Tracker UI");
        open.setAlignmentX(Component.LEFT_ALIGNMENT);
        open.setBackground(ACCENT);
        open.setForeground(Color.WHITE);
        open.setFocusPainted(false);
        open.setBorder(new EmptyBorder(9, 18, 9, 18));
        open.setFont(open.getFont().deriveFont(Font.BOLD, 13f));
        open.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        open.addActionListener(e -> openBrowser(url));

        root.add(title);
        root.add(Box.createVerticalStrut(4));
        root.add(status);
        root.add(mapLabel);
        root.add(Box.createVerticalStrut(14));
        root.add(open);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    public void setMap(String map) {
        SwingUtilities.invokeLater(() -> {
            if (mapLabel != null) {
                mapLabel.setText(map == null || map.isEmpty() ? " " : "Map: " + map);
            }
        });
    }

    /** Window/taskbar icons from the embedded scrying-orb art (web/icon-*.png). */
    private static java.util.List<Image> loadIcons() {
        java.util.List<Image> icons = new java.util.ArrayList<>();
        for (int s : new int[]{16, 32, 64}) {
            try (java.io.InputStream in = Launcher.class.getClassLoader()
                    .getResourceAsStream("web/icon-" + s + ".png")) {
                if (in != null) icons.add(javax.imageio.ImageIO.read(in));
            } catch (Exception ignored) {
            }
        }
        return icons;
    }

    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            System.err.println("[Launcher] could not open browser: " + e);
        }
    }
}
