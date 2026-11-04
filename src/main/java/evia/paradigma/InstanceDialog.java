package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Edits a block that calls a class: which method, on which object, with which arguments.
 * Blocks with the same object name share one object (and its fields); an empty name gives
 * the block an object of its own.
 */
final class InstanceDialog {
    private InstanceDialog() {}

    static void edit(Component parent, Block b) {
        BlockDef d = b.def;
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(owner, "Call " + d.name, Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(dlg);

        JComboBox<String> method = new JComboBox<>();
        for (Method m : d.callable()) method.addItem(m.signature());
        Method current = b.method();
        if (current != null) method.setSelectedItem(current.signature());

        Set<String> names = new LinkedHashSet<>();
        names.add("");
        for (Block other : b.canvas().getBlocks()) {
            if (other.def == d && !other.objectName().isEmpty()) names.add(other.objectName());
        }
        JComboBox<String> object = new JComboBox<>(names.toArray(new String[0]));
        object.setEditable(true);
        object.setSelectedItem(b.objectName());
        object.setToolTipText("Blocks with the same object name share one object. Empty = this block has its own object.");

        JTextField args = new JTextField(b.getCode(), 36);
        args.setFont(Fonts.mono(Font.PLAIN, 14));
        JLabel mode = new JLabel(" ");
        mode.setFont(Fonts.mono(Font.BOLD, 12));
        mode.setForeground(Theme.accentText());
        JLabel error = new JLabel(" ");
        error.setForeground(Theme.error());

        Runnable accept = () -> {
            Method m = d.callable().get(Math.max(0, method.getSelectedIndex()));
            String obj = String.valueOf(object.getSelectedItem() == null ? "" : object.getSelectedItem()).trim();
            if (!obj.isEmpty() && !BlockDef.isIdentifier(obj)) {
                error.setText("Object name must be a simple name, e.g. counter");
                return;
            }
            String oldMethod = b.methodName(), oldObject = b.objectName();
            b.setCall(m.name, obj);
            String err = Interpreter.check(b, args.getText().trim());
            if (err != null) {
                b.setCall(oldMethod, oldObject);
                error.setText(err);
                return;
            }
            b.setCode(args.getText().trim());
            b.setError(false);
            b.canvas().repaint();
            dlg.dispose();
        };

        if (Settings.vimKeys()) {
            VimKeys.install(args, new VimKeys.Host() {
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
        args.addActionListener(e -> accept.run());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0; g.gridy = 0; form.add(new JLabel("Method:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; form.add(method, g);
        g.gridx = 0; g.gridy = 1; g.fill = GridBagConstraints.NONE; form.add(new JLabel("Object:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; form.add(object, g);
        g.gridx = 0; g.gridy = 2; g.fill = GridBagConstraints.NONE; form.add(new JLabel("Arguments:"), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; form.add(args, g);
        g.gridx = 1; g.gridy = 3; form.add(mode, g);
        g.gridy = 4; form.add(error, g);
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));

        JButton vim = new JButton("Edit in Vim...");
        vim.addActionListener(e -> {
            String s = ExternalEditor.edit(dlg, args.getText(), "arguments.txt");
            if (s != null) args.setText(s.replace('\n', ' ').strip());
        });
        JButton open = new JButton("Open class " + d.name + "...");
        open.addActionListener(e -> {
            dlg.dispose();
            b.canvas().lib.openEditor(d);
        });
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> accept.run());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dlg.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(open);
        buttons.add(vim);
        buttons.add(Box.createHorizontalStrut(16));
        buttons.add(ok);
        buttons.add(cancel);

        dlg.getContentPane().add(new JLabel("<html><div style='padding:8px 12px 0 12px'>Call a method of a <b>"
                + d.name + "</b> object. Blocks that use the same object name share the object and its fields.</div></html>"),
                BorderLayout.NORTH);
        dlg.getContentPane().add(form, BorderLayout.CENTER);
        dlg.getContentPane().add(buttons, BorderLayout.SOUTH);
        dlg.getRootPane().setDefaultButton(ok);
        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dlg.pack();
        dlg.setLocationRelativeTo(parent);
        args.requestFocusInWindow();
        dlg.setVisible(true);
    }
}
