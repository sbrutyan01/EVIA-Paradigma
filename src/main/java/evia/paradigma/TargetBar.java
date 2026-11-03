package evia.paradigma;

import javax.swing.*;
import java.awt.*;

/**
 * Three drop-down lists, Language / Flow / Values, bound to {@link Settings#target()}.
 * Every bar in the application shows the same choice; changing one changes them all.
 */
final class TargetBar extends JPanel {
    private final JComboBox<Target.Lang> lang = new JComboBox<>(Target.Lang.values());
    private final JComboBox<Target.Flow> flow = new JComboBox<>(Target.Flow.values());
    private final JComboBox<Target.Values> values = new JComboBox<>(Target.Values.values());
    private boolean updating;

    TargetBar() {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        lang.setToolTipText("Language of the generated code");
        lang.setMaximumRowCount(Target.Lang.values().length);
        lang.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                  boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Target.Lang l) setText(l.display + "  (" + l.note.substring(l.note.length() - 4) + ")");
                return this;
            }
        });
        flow.setToolTipText("<html><b>Structured</b>: if/else, for, while, do-while and switch (labels only where the wiring needs them)"
                + "<br><b>Goto</b>: every block is a label and every wire a jump - closest to the block diagram</html>");
        values.setToolTipText("<html><b>Safe</b>: numbers and text with the interpreter's checks (undefined variables, division by zero ...)"
                + "<br><b>Fast</b>: plain <code>double</code> numbers, text only in Print, no runtime checks</html>");
        add(new JLabel("Language:"));
        add(lang);
        add(new JLabel(" Flow:"));
        add(flow);
        add(new JLabel(" Values:"));
        add(values);
        for (JComboBox<?> c : new JComboBox<?>[]{lang, flow, values}) c.setFocusable(false);
        refresh();
        lang.addActionListener(e -> changed());
        flow.addActionListener(e -> changed());
        values.addActionListener(e -> changed());
        Settings.onChange(() -> SwingUtilities.invokeLater(this::refresh));
    }

    private void changed() {
        if (updating) return;
        Settings.setTarget(new Target((Target.Lang) lang.getSelectedItem(), (Target.Flow) flow.getSelectedItem(),
                (Target.Values) values.getSelectedItem()));
    }

    private void refresh() {
        Target t = Settings.target();
        updating = true;
        try {
            lang.setSelectedItem(t.lang());
            flow.setSelectedItem(t.flow());
            values.setSelectedItem(t.values());
        } finally {
            updating = false;
        }
    }

    @Override public Dimension getMaximumSize() {
        return getPreferredSize();
    }
}
