package evia.paradigma;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entry point. */
public final class App {
    public static void main(String[] args) {
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
