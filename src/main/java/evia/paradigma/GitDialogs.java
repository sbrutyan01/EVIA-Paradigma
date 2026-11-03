package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;

/** Dialogs for the Git menu: identity, commit message, GitHub remote, history. */
final class GitDialogs {
    private GitDialogs() {}

    /** Makes sure commits have an author; asks once and stores it in this repository. Returns false if cancelled. */
    static boolean ensureIdentity(Component parent, Path repo) throws IOException {
        if (GitService.hasIdentity(repo)) return true;
        JTextField name = new JTextField(GitService.config(repo, "user.name"), 24);
        JTextField email = new JTextField(GitService.config(repo, "user.email"), 24);
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Git needs a name and an e-mail for your commits."));
        p.add(new JLabel("Name:"));
        p.add(name);
        p.add(new JLabel("E-mail (use the one of your GitHub account):"));
        p.add(email);
        while (true) {
            int r = JOptionPane.showConfirmDialog(parent, p, "Git identity", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return false;
            if (!name.getText().isBlank() && email.getText().contains("@")) break;
            JOptionPane.showMessageDialog(parent, "Please enter a name and an e-mail address.");
        }
        GitService.setLocalConfig(repo, "user.name", name.getText().trim());
        GitService.setLocalConfig(repo, "user.email", email.getText().trim());
        return true;
    }

    /** Asks for a commit message (Vim keys when enabled); null if cancelled. */
    static String commitMessage(Component parent, String changes, String suggestion) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(owner, "Commit", Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(dlg);
        String[] result = {null};

        JTextArea msg = new JTextArea(suggestion, 6, 60);
        msg.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        msg.setLineWrap(true);
        msg.setWrapStyleWord(true);
        JTextArea files = new JTextArea(changes, 8, 60);
        files.setEditable(false);
        files.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JLabel mode = new JLabel(" ");
        mode.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        mode.setForeground(new Color(30, 90, 170));

        Runnable accept = () -> {
            String m = msg.getText().strip();
            if (m.isEmpty()) {
                mode.setText("Write a message first.");
                return;
            }
            result[0] = m;
            dlg.dispose();
        };
        if (Settings.vimKeys()) {
            VimKeys.install(msg, new VimKeys.Host() {
                @Override public boolean ex(String cmd) {
                    switch (cmd) {
                        case "w", "wq", "x" -> accept.run();
                        case "q", "q!" -> dlg.dispose();
                        default -> { return false; }
                    }
                    return true;
                }
                @Override public void status(String text) { mode.setText(text.isEmpty() ? " " : text); }
            }, true);
        }

        JButton vim = new JButton("Edit in Vim...");
        vim.addActionListener(e -> {
            String s = ExternalEditor.edit(dlg, msg.getText(), "COMMIT_EDITMSG");
            if (s != null) msg.setText(s.strip());
        });
        JButton ok = new JButton("Commit");
        ok.addActionListener(e -> accept.run());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dlg.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(vim);
        buttons.add(Box.createHorizontalStrut(16));
        buttons.add(ok);
        buttons.add(cancel);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        JPanel top = new JPanel(new BorderLayout());
        top.add(new JLabel("Changes (all of them are committed):"), BorderLayout.NORTH);
        top.add(new JScrollPane(files), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JLabel("Message" + (Settings.vimKeys() ? "  (Vim keys: Esc, then :w to commit, :q to cancel)" : "") + ":"),
                BorderLayout.NORTH);
        bottom.add(new JScrollPane(msg), BorderLayout.CENTER);
        bottom.add(mode, BorderLayout.SOUTH);
        center.add(top, BorderLayout.NORTH);
        center.add(bottom, BorderLayout.CENTER);
        dlg.getContentPane().add(center, BorderLayout.CENTER);
        dlg.getContentPane().add(buttons, BorderLayout.SOUTH);
        dlg.getRootPane().registerKeyboardAction(e -> accept.run(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dlg.pack();
        dlg.setLocationRelativeTo(parent);
        msg.selectAll();
        msg.requestFocusInWindow();
        dlg.setVisible(true);
        return result[0];
    }

    /** Result of the Connect to GitHub dialog. */
    record Remote(String url, boolean pushNow) {}

    static Remote connect(Component parent, String current, String suggestedName) {
        JTextField url = new JTextField(current, 36);
        JCheckBox push = new JCheckBox("Commit and push now", true);
        JButton create = new JButton("Create repository on GitHub...");
        create.setToolTipText("Opens github.com in your browser with the name filled in");
        create.addActionListener(e -> {
            HelpWindow.browse(parent, "https://github.com/new?name=" + java.net.URLEncoder.encode(
                    Project.safeName(suggestedName), java.nio.charset.StandardCharsets.UTF_8));
            if (url.getText().isBlank()) url.setText("your-name/" + Project.safeName(suggestedName));
        });
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(3, 3, 3, 3);
        g.fill = GridBagConstraints.HORIZONTAL;
        p.add(new JLabel("<html>GitHub repository: <b>owner/name</b>, or a clone URL (https or ssh).<br>"
                + "Git uses the login it already has on this computer (Git Credential Manager, SSH key or gh).</html>"), g);
        p.add(url, g);
        g.fill = GridBagConstraints.NONE;
        p.add(create, g);
        p.add(new JLabel("<html><small>Create the repository <b>empty</b> (no README or license), then press OK.</small></html>"), g);
        p.add(push, g);
        int r = JOptionPane.showConfirmDialog(parent, p, "Connect to GitHub", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION || url.getText().isBlank() || url.getText().startsWith("your-name/")) return null;
        return new Remote(GitService.normalizeRemote(url.getText()), push.isSelected());
    }

    static void showText(Component parent, String title, String text) {
        JTextArea a = new JTextArea(text, 24, 90);
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        a.setCaretPosition(0);
        JOptionPane.showMessageDialog(parent, new JScrollPane(a), title, JOptionPane.PLAIN_MESSAGE);
    }
}
