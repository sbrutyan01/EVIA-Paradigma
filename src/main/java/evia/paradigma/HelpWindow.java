package evia.paradigma;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The user guide (bundled HTML with a topic list) and the other Help menu actions. */
final class HelpWindow extends JFrame {
    static final String REPO = "https://github.com/sbrutyan01/EVIA-Paradigma";

    private static final String[][] TOPICS = {
            {"Getting started", "start"},
            {"Blocks", "blocks"},
            {"Expressions", "expressions"},
            {"Classes and objects", "classes"},
            {"Modules", "modules"},
            {"Projects", "projects"},
            {"Git and GitHub", "git"},
            {"C and C++ code", "cpp"},
            {"Keyboard shortcuts", "keys"},
            {"Vim keys", "vim"},
    };

    private static HelpWindow instance;
    private final JEditorPane page = new JEditorPane();

    private HelpWindow() {
        super("EVIA Paradigma Guide");
        AppIcon.apply(this);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setSize(980, 720);
        page.setEditable(false);
        page.setContentType("text/html");
        page.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        try (InputStream in = HelpWindow.class.getResourceAsStream("help/guide.html")) {
            page.setText(in == null ? "<html>Guide not found.</html>" : new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            page.setText("<html>Guide not available: " + e.getMessage() + "</html>");
        }
        page.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null) {
                if (e.getDescription().startsWith("#")) page.scrollToReference(e.getDescription().substring(1));
                else browse(this, e.getDescription());
            }
        });
        DefaultListModel<String> model = new DefaultListModel<>();
        for (String[] t : TOPICS) model.addElement(t[0]);
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(26);
        list.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        list.addListSelectionListener(e -> {
            int i = list.getSelectedIndex();
            if (!e.getValueIsAdjusting() && i >= 0) page.scrollToReference(TOPICS[i][1]);
        });
        JScrollPane left = new JScrollPane(list);
        left.setPreferredSize(new Dimension(200, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, new JScrollPane(page));
        split.setDividerLocation(200);
        getContentPane().add(split);
    }

    static void showTopic(Component parent, String anchor) {
        if (instance == null) {
            instance = new HelpWindow();
            instance.setLocationRelativeTo(parent);
        }
        instance.setVisible(true);
        instance.toFront();
        SwingUtilities.invokeLater(() -> instance.page.scrollToReference(anchor));
    }

    static void browse(Component parent, String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | RuntimeException e) {
            // fall through and show the address
        }
        JTextField f = new JTextField(url);
        f.setEditable(false);
        JOptionPane.showMessageDialog(parent, new Object[]{"Open this address in your browser:", f}, "Link",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** Asks GitHub for the latest release and compares it with this version. */
    static void checkForUpdates(Component parent) {
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                HttpURLConnection c = (HttpURLConnection) URI.create(
                        "https://api.github.com/repos/sbrutyan01/EVIA-Paradigma/releases/latest").toURL().openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "EVIA-Paradigma/" + App.VERSION);
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream()) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                    return m.find() ? m.group(1) : null;
                }
            }

            @Override protected void done() {
                try {
                    String tag = get();
                    if (tag == null) throw new IOException("no release found");
                    String latest = tag.replaceFirst("^[vV]", "");
                    if (compare(latest, App.VERSION) > 0) {
                        int r = JOptionPane.showConfirmDialog(parent, "EVIA Paradigma " + latest + " is available (you have "
                                + App.VERSION + ").\nOpen the download page?", "Update available", JOptionPane.YES_NO_OPTION);
                        if (r == JOptionPane.YES_OPTION) browse(parent, REPO + "/releases/latest");
                    } else {
                        JOptionPane.showMessageDialog(parent, "You have the latest version (" + App.VERSION + ").",
                                "Check for updates", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(parent, "Could not reach GitHub: " + c.getMessage()
                            + "\n\nReleases: " + REPO + "/releases", "Check for updates", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    /** Compares dotted versions numerically ("0.10.0" > "0.9.1"). */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
