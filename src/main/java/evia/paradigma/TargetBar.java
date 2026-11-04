package evia.paradigma;

import javax.swing.*;
import java.awt.*;

/**
 * Drop-down lists Language / Flow / Values (bound to {@link Settings#target()}) and, for the
 * microcontroller languages, Board (bound to {@link Settings#board()}). Every bar in the application
 * shows the same choice; changing one changes them all.
 */
final class TargetBar extends JPanel {
    private final JComboBox<Target.Lang> lang = new JComboBox<>(Target.Lang.values());
    private final JComboBox<Target.Flow> flow = new JComboBox<>(Target.Flow.values());
    private final JComboBox<Target.Values> values = new JComboBox<>(Target.Values.values());
    private final JComboBox<Board> board = new JComboBox<>();
    private final JLabel boardLabel = new JLabel(" Board:");
    private boolean updating;
    private static final String valuesTip = "<html><b>Safe</b>: numbers and text with the interpreter's checks (undefined variables, division by zero ...)"
            + "<br><b>Fast</b>: plain <code>double</code> numbers, text only in Print, no runtime checks</html>";

    TargetBar() {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        lang.setToolTipText("Language of the generated code");
        lang.setMaximumRowCount(Target.Lang.values().length);
        lang.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                  boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Target.Lang l) setText(l.display + "  (" + l.tag() + ")");
                return this;
            }
        });
        flow.setToolTipText("<html><b>Structured</b>: if/else, for, while, do-while and switch (labels only where the wiring needs them)"
                + "<br><b>Goto</b>: every block is a label and every wire a jump - closest to the block diagram</html>");
        values.setToolTipText(valuesTip);
        add(new JLabel("Language:"));
        add(lang);
        add(new JLabel(" Flow:"));
        add(flow);
        add(new JLabel(" Values:"));
        add(values);
        board.setToolTipText("<html>The board that Build and Upload use. The generated code is the same for every board:"
                + "<br>pins are written as LED_BUILTIN, A0 ... and each board's headers say where they are.</html>");
        board.setMaximumRowCount(Board.values().length);
        add(boardLabel);
        add(board);
        for (JComboBox<?> c : new JComboBox<?>[]{lang, flow, values, board}) c.setFocusable(false);
        refresh();
        lang.addActionListener(e -> changed());
        flow.addActionListener(e -> changed());
        values.addActionListener(e -> changed());
        board.addActionListener(e -> {
            if (!updating && board.getSelectedItem() instanceof Board b) Settings.setBoard(b);
        });
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
            values.setEnabled(!t.embedded());
            values.setToolTipText(t.embedded() ? "Microcontrollers use fast values: numbers only, text only in Print" : valuesTip);
            board.removeAllItems();
            for (Board b : Board.values()) if (b.supports(t.lang())) board.addItem(b);
            board.setSelectedItem(Board.forLang(Settings.board(), t.lang()));
            board.setVisible(t.embedded());
            boardLabel.setVisible(t.embedded());
            revalidate();
        } finally {
            updating = false;
        }
    }

    @Override public Dimension getMaximumSize() {
        return getPreferredSize();
    }
}
