package evia.paradigma;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;

/**
 * Window for one class: its name and color, its fields (with initial values) and one tab per
 * method. The method named "init" is the constructor: it runs once when an object is created,
 * right after the fields get their initial values.
 */
final class ClassEditor extends JFrame {
    private final Library lib;
    private final BlockDef def;
    private final JTextField nameField = new JTextField(14);
    private final JButton colorBtn = new JButton("Color");
    private final JLabel moduleLabel = new JLabel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final FieldsModel fieldsModel = new FieldsModel();

    ClassEditor(Library lib, BlockDef def) {
        this.lib = lib;
        this.def = def;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setSize(1120, 760);
        AppIcon.apply(this);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        top.add(new JLabel("Class:"));
        top.add(nameField);
        JButton rename = new JButton("Rename");
        rename.addActionListener(e -> rename());
        nameField.addActionListener(e -> rename());
        top.add(rename);
        top.add(colorBtn);
        colorBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Color of " + def.name, def.color);
            if (c != null) {
                def.color = c;
                lib.changed();
                refresh();
            }
        });
        JButton addMethod = new JButton("+ Method...");
        addMethod.addActionListener(e -> addMethod(false));
        JButton addCtor = new JButton("+ Constructor");
        addCtor.setToolTipText("A method named init that runs once when an object is created");
        addCtor.addActionListener(e -> addMethod(true));
        JButton place = new JButton("Use in main program");
        place.setToolTipText("Adds a block that calls this class to the main program");
        place.addActionListener(e -> {
            if (lib.main.isLocked() || def.defaultMethod() == null) return;
            Rectangle v = lib.main.getVisibleRect();
            lib.main.select(lib.main.addInstance(def, v.x + 60, v.y + 60));
        });
        JButton source = new JButton("View code");
        source.addActionListener(e -> lib.viewSource(def));
        top.add(addMethod);
        top.add(addCtor);
        top.add(place);
        top.add(source);
        top.add(moduleLabel);

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
        JLabel help = new JLabel("<html>Fields belong to each object and keep their values between calls; read and write them as "
                + "<code>this.name</code>. Each method runs from <b>Enter</b> to an <b>Exit</b>; every Exit name is an output "
                + "port. The constructor <code>init</code> runs once when an object is created.</html>");
        help.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        getContentPane().add(help, BorderLayout.SOUTH);
        rebuildTabs();
        refresh();
    }

    BlockDef def() {
        return def;
    }

    void refresh() {
        setTitle("Class " + def.name + " - EVIA Paradigma");
        nameField.setText(def.name);
        colorBtn.setBackground(def.color);
        colorBtn.setForeground(new Color(30, 30, 40));
        moduleLabel.setText(def.module == null ? "  stored in the program"
                : "  stored in module " + Path.of(def.module).getFileName());
        for (int i = 1; i < tabs.getTabCount() && i - 1 < def.methods.size(); i++) {
            tabs.setTitleAt(i, tabTitle(def.methods.get(i - 1)));
        }
        fieldsModel.fireTableDataChanged();
    }

    /** Shows the tab of one method (used when an error is inside it). */
    void showMethod(Method m) {
        int i = def.methods.indexOf(m);
        if (i >= 0) tabs.setSelectedIndex(i + 1);
    }

    private static String tabTitle(Method m) {
        return m.isConstructor() ? "init() - constructor" : m.signature();
    }

    private void rebuildTabs() {
        int sel = tabs.getSelectedIndex();
        tabs.removeAll();
        tabs.addTab("Fields", fieldsPanel());
        for (Method m : def.methods) tabs.addTab(tabTitle(m), methodPanel(m));
        if (sel >= 0 && sel < tabs.getTabCount()) tabs.setSelectedIndex(sel);
    }

    // ================================================================ fields

    private final class FieldsModel extends AbstractTableModel {
        @Override public int getRowCount() { return def.fields.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int c) { return c == 0 ? "Field" : "Initial value (expression)"; }
        @Override public boolean isCellEditable(int r, int c) { return true; }
        @Override public Object getValueAt(int r, int c) {
            BlockDef.Field f = def.fields.get(r);
            return c == 0 ? f.name() : f.init();
        }
        @Override public void setValueAt(Object v, int r, int c) {
            BlockDef.Field f = def.fields.get(r);
            String s = String.valueOf(v).trim();
            if (c == 0) {
                if (!BlockDef.isIdentifier(s)) {
                    JOptionPane.showMessageDialog(ClassEditor.this, "A field name must be a simple name, e.g. count");
                    return;
                }
                def.fields.set(r, new BlockDef.Field(s, f.init()));
            } else {
                try {
                    ExprParser.parse(s.isEmpty() ? "0" : s);
                } catch (FlowException e) {
                    JOptionPane.showMessageDialog(ClassEditor.this, e.getMessage(), "Initial value", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                def.fields.set(r, new BlockDef.Field(f.name(), s));
            }
            lib.changed();
        }
    }

    private JComponent fieldsPanel() {
        JTable table = new JTable(fieldsModel);
        table.setRowHeight(24);
        table.setFont(Fonts.mono(Font.PLAIN, 13));
        JButton add = new JButton("+ Field");
        add.addActionListener(e -> {
            String base = "field";
            String n = base;
            for (int i = 2; def.hasField(n); i++) n = base + i;
            def.fields.add(new BlockDef.Field(n, "0"));
            fieldsModel.fireTableDataChanged();
            lib.changed();
            int row = def.fields.size() - 1;
            table.editCellAt(row, 0);
        });
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r < 0) return;
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            def.fields.remove(r);
            fieldsModel.fireTableDataChanged();
            lib.changed();
        });
        JButton up = new JButton("Up");
        up.addActionListener(e -> move(table, -1));
        JButton down = new JButton("Down");
        down.addActionListener(e -> move(table, 1));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(add);
        buttons.add(remove);
        buttons.add(up);
        buttons.add(down);
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel("<html><div style='padding:8px'>Fields get their initial values, in this order, when an object is "
                + "created; the constructor runs after that. Initial values can use program variables and earlier fields "
                + "(<code>this.name</code>). Fields you only use as <code>this.x</code> without declaring them start at 0."
                + "</div></html>"), BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private void move(JTable table, int delta) {
        int r = table.getSelectedRow(), to = r + delta;
        if (r < 0 || to < 0 || to >= def.fields.size()) return;
        def.fields.add(to, def.fields.remove(r));
        fieldsModel.fireTableDataChanged();
        table.setRowSelectionInterval(to, to);
        lib.changed();
    }

    // ================================================================ methods

    private JComponent methodPanel(Method m) {
        JTextField name = new JTextField(m.name, 12);
        JTextField params = new JTextField(String.join(", ", m.params), 18);
        params.setToolTipText("Comma separated, e.g.  a, b   - inside the method use this.a, this.b");
        JButton apply = new JButton("Apply");
        Runnable doApply = () -> applyMethod(m, name.getText().trim(), params.getText());
        apply.addActionListener(e -> doApply.run());
        name.addActionListener(e -> doApply.run());
        params.addActionListener(e -> doApply.run());
        JButton delete = new JButton("Delete method");
        delete.addActionListener(e -> deleteMethod(m));
        JButton src = new JButton("View code");
        src.addActionListener(e -> lib.viewSource(m));

        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        head.add(new JLabel(m.isConstructor() ? "Constructor:" : "Method:"));
        head.add(name);
        if (!m.isConstructor()) {
            head.add(new JLabel("Parameters:"));
            head.add(params);
        }
        head.add(apply);
        head.add(delete);
        head.add(src);
        name.setEditable(!m.isConstructor());

        JScrollPane bodyScroll = new JScrollPane(m.body);
        bodyScroll.getVerticalScrollBar().setUnitIncrement(16);
        JScrollPane paletteScroll = new JScrollPane(new PalettePanel(lib, m.body));
        paletteScroll.setBorder(BorderFactory.createEmptyBorder());
        paletteScroll.setPreferredSize(new Dimension(220, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, paletteScroll, bodyScroll);
        split.setDividerLocation(220);

        JPanel p = new JPanel(new BorderLayout());
        p.add(head, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private void applyMethod(Method m, String newName, String paramText) {
        if (!BlockDef.isIdentifier(newName)) {
            JOptionPane.showMessageDialog(this, "A method name must be a simple name, e.g. add", "Method", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Method other = def.method(newName);
        if (other != null && other != m) {
            JOptionPane.showMessageDialog(this, def.name + " already has a method " + newName, "Method", JOptionPane.ERROR_MESSAGE);
            return;
        }
        List<String> params;
        try {
            params = BlockDef.parseParams(paramText);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Parameters", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!newName.equals(m.name)) {
            // blocks that call it by name follow the rename
            for (FlowCanvas c : lib.canvases()) {
                for (Block b : c.getBlocks()) {
                    if (b.def == def && m.name.equals(b.methodName())) b.setCall(newName, b.objectName());
                }
            }
        }
        m.name = newName;
        m.params = List.copyOf(params);
        lib.changed();
        refresh();
    }

    private void addMethod(boolean constructor) {
        String name;
        if (constructor) {
            if (def.constructor() != null) {
                showMethod(def.constructor());
                return;
            }
            name = BlockDef.CONSTRUCTOR;
        } else {
            name = (String) JOptionPane.showInputDialog(this, "Name of the new method:", "New method",
                    JOptionPane.PLAIN_MESSAGE, null, null, "method" + (def.methods.size() + 1));
            if (name == null) return;
            name = name.trim();
            if (!BlockDef.isIdentifier(name) || def.method(name) != null || name.equals(BlockDef.CONSTRUCTOR)) {
                JOptionPane.showMessageDialog(this, "Choose a new, simple name (not init), e.g. reset", "New method",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        Method m = lib.newMethod(def, name, List.of());
        lib.changed();
        rebuildTabs();
        showMethod(m);
        refresh();
    }

    private void deleteMethod(Method m) {
        if (!m.isConstructor() && def.callable().size() == 1) {
            JOptionPane.showMessageDialog(this, "A class needs at least one method that blocks can call.");
            return;
        }
        if (lib.isUsed(m)) {
            JOptionPane.showMessageDialog(this, m.qualified() + " is still called somewhere. Change or delete those blocks first.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete method " + m.qualified() + "?", "Delete method",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        def.methods.remove(m);
        lib.changed();
        rebuildTabs();
        refresh();
    }

    private void rename() {
        String name = nameField.getText().trim();
        if (name.isEmpty() || lib.nameTaken(name, def)) {
            JOptionPane.showMessageDialog(this, "Choose a unique, non-empty name.", "Name", JOptionPane.ERROR_MESSAGE);
            return;
        }
        def.name = name;
        lib.changed();
        refresh();
    }
}
