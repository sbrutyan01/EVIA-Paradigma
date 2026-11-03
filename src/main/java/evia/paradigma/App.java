package evia.paradigma;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entry point. */
public final class App {
    /** Released version, shown by --version and in the About dialog. */
    public static final String VERSION = "0.1";

    public static void main(String[] args) {
        if (args.length > 0) {                       // command-line mode: --run, --gen, --help
            System.setProperty("java.awt.headless", "true");
            System.exit(Cli.run(args));
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // default look and feel is fine
            }
            MainFrame f = new MainFrame();
            f.setVisible(true);
        });
    }
}
