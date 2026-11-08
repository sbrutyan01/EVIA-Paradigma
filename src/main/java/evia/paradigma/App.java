package evia.paradigma;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.io.File;

/**
 * Entry point.
 * <ul>
 *   <li>{@code --run}, {@code --gen}, ... : command-line mode (see {@link Cli})</li>
 *   <li>a {@code .evia} file : open it in the editor (Windows/Linux file associations pass it this way)</li>
 *   <li>nothing : start the editor with the example program</li>
 * </ul>
 */
public final class App {
    /** Released version, shown by --version and in the About dialog. */
    public static final String VERSION = "0.6.9";

    public static void main(String[] args) {
        if (args.length > 0 && args[0].startsWith("-")) {
            System.setProperty("java.awt.headless", "true");
            System.exit(Cli.run(args));
        }
        File toOpen = args.length > 0 ? new File(args[0]) : null;
        SwingUtilities.invokeLater(() -> {
            Theme.init();
            MainFrame f = new MainFrame();
            f.setVisible(true);
            if (toOpen != null) f.openFile(toOpen);
            // macOS delivers double-clicked documents through an event instead of arguments
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
                Desktop.getDesktop().setOpenFileHandler(e -> {
                    if (!e.getFiles().isEmpty()) SwingUtilities.invokeLater(() -> f.openFile(e.getFiles().get(0)));
                });
            }
        });
    }
}
