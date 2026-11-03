package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Window for editing one custom block definition: name, parameters, color, and its body
 * (a full canvas with its own palette). Changes apply immediately to every object of it.
 */
final class DefinitionEditor extends JFrame {
    private final Library lib;
    private final BlockDef def;
    private final JTextField nameField = new JTextField(14);
    private final JTextField paramsField = new JTextField(18);
    private final JButton colorBtn = new JButton("Color");

    DefinitionEditor(Library lib, BlockDef def) {
        this.lib = lib;
        this.def = def;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setSize(1050, 720);
        AppIcon.apply(this);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        top.add(new JLabel("Name:"));
        top.add(nameField);
        top.add(new JLabel("Parameters:"));
        paramsField.setToolTipText("Comma separated, e.g.  a, b   - inside the block use this.a, this.b");
        top.add(paramsField);
        JButton apply = new JButton("Apply");
        top.add(apply);
        top.add(colorBtn);
        JButton add = new JButton("Place object in main program");
        add.setToolTipText("Adds an instance of this block to the main program canvas");
        top.add(add);

        apply.addActionListener(e -> apply());
        nameField.addActionListener(e -> apply());
        paramsField.addActionListener(e -> apply());
        colorBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Color of " + def.name, def.color);
            if (c != null) {
                def.color = c;
                lib.changed();
                refresh();
            }
        });
        add.addActionListener(e -> {
            if (lib.main.isLocked()) return;
            Rectangle v = lib.main.getVisibleRect();
            lib.main.select(lib.main.addInstance(def, v.x + 60, v.y + 60));
        });

        JScrollPane bodyScroll = new JScrollPane(def.body);
        bodyScroll.getVerticalScrollBar().setUnitIncrement(16);
        JScrollPane paletteScroll = new JScrollPane(new PalettePanel(lib, def.body));
        paletteScroll.setBorder(BorderFactory.createEmptyBorder());
        paletteScroll.setPreferredSize(new Dimension(220, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, paletteScroll, bodyScroll);
        split.setDividerLocation(220);

        JLabel help = new JLabel("<html>The body runs from <b>Enter</b> until an <b>Exit</b>. "
                + "Each Exit name becomes an output port of the block. "
                + "Parameters and <code>this.x</code> fields belong to each object separately; "
                + "plain names are program variables.</html>");
        help.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(help, BorderLayout.SOUTH);
        refresh();
    }

    BlockDef def() {
        return def;
    }

    void refresh() {
        setTitle("Custom block: " + def.signature() + " - EVIA Paradigma");
        nameField.setText(def.name);
        paramsField.setText(String.join(", ", def.params));
        colorBtn.setBackground(def.color);
    }

    private void apply() {
        String name = nameField.getText().trim();
        if (name.isEmpty() || lib.nameTaken(name, def)) {
            JOptionPane.showMessageDialog(this, "Choose a unique, non-empty name.", "Name", JOptionPane.ERROR_MESSAGE);
            return;
        }
        List<String> params;
        try {
            params = BlockDef.parseParams(paramsField.getText());
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Parameters", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean paramsChanged = !params.equals(def.params);
        def.name = name;
        def.params = List.copyOf(params);
        lib.changed();
        refresh();
        if (paramsChanged && lib.isUsed(def)) {
            JOptionPane.showMessageDialog(this, "Parameters changed. Objects of " + def.name
                    + " with the wrong number of arguments are marked when you press Run.");
        }
    }
}
