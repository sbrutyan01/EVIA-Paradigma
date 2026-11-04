package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.Function;

/**
 * Editor for one block's code: a one-line field with Vim keys (starts in INSERT mode),
 * plus a button to edit the code in the real Vim.
 *
 * <p>Vim users: Esc goes to NORMAL mode, Esc again (or :q) cancels, Enter or :w accepts.
 */
final class CodeEditDialog {
    private CodeEditDialog() {}

    /** Returns the accepted text, or null if cancelled. {@code validator} returns an error message or null. */
    static String show(Component parent, String title, String hint, String initial, Function<String, String> validator) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);

        JTextField field = new JTextField(initial, 44);
        field.setFont(Fonts.mono(Font.PLAIN, 14));
        JLabel hintLabel = new JLabel(hint);
        JLabel error = new JLabel(" ");
        error.setForeground(Theme.error());
        JLabel mode = new JLabel(" ");
        mode.setFont(Fonts.mono(Font.BOLD, 12));
        mode.setForeground(Theme.accentText());

        String[] result = {null};
        Runnable accept = () -> {
            String text = field.getText().trim();
            String err = validator.apply(text);
            if (err != null) {
                error.setText(err);
                return;
            }
            result[0] = text;
            d.dispose();
        };

        if (Settings.vimKeys()) {
            VimKeys.install(field, new VimKeys.Host() {
                @Override public boolean ex(String cmd) {
                    switch (cmd) {
                        case "w", "wq", "x", "w!" -> accept.run();
                        case "q", "q!" -> d.dispose();
                        default -> { return false; }
                    }
                    return true;
                }
                @Override public void status(String text) {
                    mode.setText(text.isEmpty() ? " " : text);
                }
            }, true);
        } else {
            mode.setText("Vim keys are off (Settings)");
        }
        field.addActionListener(e -> accept.run());

        JButton vim = new JButton("Edit in Vim...");
        vim.setToolTipText("Open this code in Vim; save and quit (:wq) to come back");
        vim.addActionListener(e -> {
            String s = ExternalEditor.edit(d, field.getText(), "block-code.txt");
            if (s != null) {
                field.setText(s.replace('\n', ' ').strip());
                field.requestFocusInWindow();
            }
        });
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> accept.run());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> d.dispose());

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        center.add(hintLabel, BorderLayout.NORTH);
        center.add(field, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(mode, BorderLayout.NORTH);
        south.add(error, BorderLayout.SOUTH);
        center.add(south, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(vim);
        buttons.add(Box.createHorizontalStrut(20));
        buttons.add(ok);
        buttons.add(cancel);

        d.getContentPane().add(center, BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.pack();
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
        return result[0];
    }
}
