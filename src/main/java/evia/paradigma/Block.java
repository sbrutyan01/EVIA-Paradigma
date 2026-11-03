package evia.paradigma;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Objects;

/**
 * A draggable block on a canvas: a rounded body, one input port on top,
 * and output ports at the bottom. A CUSTOM block is an instance (object) of a
 * {@link BlockDef}; its output ports are the definition's Exit names.
 */
public final class Block extends JComponent {
    static final int MIN_W = 170, MAX_W = 360, H = 54, PORT_R = 6, PAD = 8;
    static final int TOTAL_H = H + 2 * PAD;
    static final int DEFAULT_TOTAL_W = MIN_W + 2 * PAD;

    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);
    private static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final Font PORT_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 10);

    final int id;
    final BlockType type;
    final BlockDef def;                        // non-null only for CUSTOM
    java.util.Set<Block> loopBody = java.util.Set.of();   // loops only: set by Interpreter.prepare
    Block[] next;                              // next[i] = block connected to output port i
    private List<String> ports;                // port names (CUSTOM only)
    private String code;
    private Interpreter.Compiled compiled;     // parse cache

    private int w = MIN_W;                     // body width (grows with the code text)
    private boolean selected, active, error;
    private final FlowCanvas canvas;

    Block(FlowCanvas canvas, int id, BlockType type, String code, BlockDef def) {
        this.canvas = canvas;
        this.id = id;
        this.type = type;
        this.def = def;
        if (type == BlockType.CUSTOM) {
            Objects.requireNonNull(def, "custom block without definition");
            ports = def.exitNames();
            next = new Block[ports.size()];
        } else {
            ports = List.of();
            next = new Block[type.outputs];
        }
        this.code = code == null ? type.defaultCode : code;
        setOpaque(false);
        refresh();
        MouseHandler m = new MouseHandler();
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    // ================================================================ state

    FlowCanvas canvas() {
        return canvas;
    }

    String getCode() {
        return code;
    }

    void setCode(String c) {
        code = c;
        refresh();
    }

    /** Recompute everything derived from code / definition: ports, size, tooltip, parse cache. */
    void refresh() {
        compiled = null;
        if (def != null) syncPorts();
        resize();
        updateTooltip();
        repaint();
    }

    Interpreter.Compiled compiled() {
        if (compiled == null) compiled = Interpreter.compile(this, code);
        return compiled;
    }

    /** Keep connections when the definition's exits change: match old and new ports by name. */
    private void syncPorts() {
        List<String> names = def.exitNames();
        if (names.equals(ports)) return;
        Block[] nx = new Block[names.size()];
        for (int i = 0; i < names.size(); i++) {
            int j = ports.indexOf(names.get(i));
            if (j >= 0) nx[i] = next[j];
        }
        next = nx;
        ports = names;
    }

    int portIndex(String name) {
        return ports.indexOf(name);
    }

    String portName(int i) {
        return def != null ? ports.get(i) : null;
    }

    String title() {
        return def != null ? def.name : type.title.toUpperCase();
    }

    Color color() {
        return def != null ? def.color : type.color;
    }

    /** Whether double-click edits the code (a custom block without parameters has nothing to edit). */
    boolean editable() {
        return type.hasCode && !(def != null && def.params.isEmpty());
    }

    private String displayCode() {
        if (def != null) return def.params.isEmpty() ? "" : "(" + code + ")";
        return type.hasCode ? code.replace('\n', ' ') : "";
    }

    private void resize() {
        FontMetrics cm = getFontMetrics(CODE_FONT), tm = getFontMetrics(TITLE_FONT);
        int need = Math.max(cm.stringWidth(displayCode()), tm.stringWidth(title())) + 26;
        if (next.length > 2) need = Math.max(need, next.length * 64);
        w = Math.max(MIN_W, Math.min(MAX_W, need));
        setSize(w + 2 * PAD, TOTAL_H);
    }

    void setSelected(boolean v) { selected = v; repaint(); }
    void setActive(boolean v)   { active = v;   repaint(); }
    void setError(boolean v)    { error = v;    repaint(); }
    boolean isSelectedBlock()   { return selected; }

    private void updateTooltip() {
        String esc = code.replace("&", "&amp;").replace("<", "&lt;");
        if (def != null) {
            setToolTipText("<html><b>" + def.signature() + "</b> &nbsp;(custom block)<br>exits: "
                    + String.join(", ", ports) + "<br>"
                    + (editable() ? "Double-click to edit arguments" : "Double-click to open the definition")
                    + "</html>");
        } else if (type.hasCode) {
            setToolTipText("<html><b>" + type.title + "</b>: <code>" + esc + "</code><br>Double-click to edit</html>");
        } else {
            setToolTipText(type.hint);
        }
    }

    // ================================================================ ports

    Point inPortLocal() {
        return new Point(PAD + w / 2, PAD);
    }

    Point outPortLocal(int i) {
        int n = next.length;
        int x = n == 1 ? PAD + w / 2 : PAD + w * (i + 1) / (n + 1);
        return new Point(x, PAD + H);
    }

    Point inPort() {
        Point p = inPortLocal();
        p.translate(getX(), getY());
        return p;
    }

    Point outPort(int i) {
        Point p = outPortLocal(i);
        p.translate(getX(), getY());
        return p;
    }

    private int outPortAt(Point local) {
        for (int i = 0; i < next.length; i++) {
            if (local.distance(outPortLocal(i)) <= PORT_R + 4) return i;
        }
        return -1;
    }

    private String portLabel(int i) {
        if (type == BlockType.IF) return i == 0 ? "then" : "else";
        if (type.isLoop()) return i == 0 ? "body" : "done";
        if (def != null && next.length > 1) return ports.get(i);
        return null;
    }

    private Color portColor(int i) {
        if (type == BlockType.IF) return i == 0 ? new Color(60, 170, 70) : new Color(210, 60, 50);
        if (type.isLoop()) return i == 0 ? new Color(60, 170, 70) : new Color(90, 90, 100);
        return Color.DARK_GRAY;
    }

    // ================================================================ painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Color base = color();
        RoundRectangle2D body = new RoundRectangle2D.Float(PAD, PAD, w, H, 14, 14);

        // shadow
        g.setColor(new Color(0, 0, 0, 35));
        g.fill(new RoundRectangle2D.Float(PAD + 3, PAD + 3, w, H, 14, 14));

        // custom blocks look like a small stack of cards: "many blocks in one"
        if (def != null) {
            RoundRectangle2D back = new RoundRectangle2D.Float(PAD + 5, PAD - 5, w, H, 14, 14);
            g.setColor(blend(base, Color.WHITE, 0.25f));
            g.fill(back);
            g.setStroke(new BasicStroke(1.2f));
            g.setColor(blend(base, Color.BLACK, 0.45f));
            g.draw(back);
        }

        g.setPaint(new GradientPaint(0, PAD, blend(base, Color.WHITE, 0.45f), 0, PAD + H, base));
        g.fill(body);

        Color border;
        float width;
        if (error)         { border = new Color(220, 30, 30);  width = 3f; }
        else if (active)   { border = new Color(255, 150, 0);  width = 3.5f; }
        else if (selected) { border = new Color(30, 90, 210);  width = 2.5f; }
        else               { border = blend(base, Color.BLACK, 0.45f); width = 1.2f; }
        g.setStroke(new BasicStroke(width));
        g.setColor(border);
        g.draw(body);

        // title
        g.setFont(def != null ? TITLE_FONT.deriveFont(12.5f) : TITLE_FONT);
        g.setColor(new Color(0, 0, 0, def != null ? 200 : 150));
        g.drawString(title(), PAD + 9, PAD + 15);

        // code
        String text = displayCode();
        if (!text.isEmpty()) {
            g.setFont(CODE_FONT);
            g.setColor(new Color(20, 20, 30));
            g.drawString(ellipsize(text, g.getFontMetrics(), w - 18), PAD + 9, PAD + 38);
        }

        // ports
        if (type.hasInput) drawPort(g, inPortLocal(), Color.WHITE);
        g.setFont(PORT_FONT);
        for (int i = 0; i < next.length; i++) {
            Point p = outPortLocal(i);
            Color c = portColor(i);
            drawPort(g, p, c);
            String label = portLabel(i);
            if (label != null) {
                g.setColor(type == BlockType.IF || type.isLoop() ? c.darker() : new Color(40, 40, 50));
                g.drawString(label, p.x + PORT_R + 3, p.y - 3);
            }
        }
        g.dispose();
    }

    private static void drawPort(Graphics2D g, Point p, Color fill) {
        Ellipse2D e = new Ellipse2D.Float(p.x - PORT_R, p.y - PORT_R, PORT_R * 2, PORT_R * 2);
        g.setColor(fill);
        g.fill(e);
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(40, 40, 40));
        g.draw(e);
    }

    private static String ellipsize(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        while (!s.isEmpty() && fm.stringWidth(s + "...") > max) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    static Color blend(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    // ================================================================ mouse

    private final class MouseHandler extends MouseAdapter {
        private Point dragOffset;
        private boolean connecting, moved;

        private Point toCanvas(MouseEvent e) {
            return SwingUtilities.convertPoint(Block.this, e.getPoint(), canvas);
        }

        @Override
        public void mousePressed(MouseEvent e) {
            canvas.requestFocusInWindow();
            if (e.isPopupTrigger()) {
                popup(e);
                return;
            }
            if (!SwingUtilities.isLeftMouseButton(e)) return;

            int port = outPortAt(e.getPoint());
            if (port >= 0 && !canvas.isLocked()) {
                connecting = true;
                canvas.beginConnect(Block.this, port, toCanvas(e));
                return;
            }
            if (e.isShiftDown() || e.isControlDown() || e.isMetaDown()) {
                canvas.toggleSelect(Block.this);
            } else if (!selected) {
                canvas.select(Block.this);
            }
            dragOffset = e.getPoint();
            moved = false;
            getParent().setComponentZOrder(Block.this, 0);
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (connecting) {
                canvas.updateConnect(toCanvas(e));
            } else if (dragOffset != null && selected) {
                canvas.moveSelectionBy(e.getX() - dragOffset.x, e.getY() - dragOffset.y);
                moved = true;
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (connecting) {
                connecting = false;
                canvas.endConnect(toCanvas(e));
            }
            if (moved) canvas.changed();   // exit order may have changed
            dragOffset = null;
            moved = false;
            if (e.isPopupTrigger()) popup(e);
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) canvas.editBlock(Block.this);
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            setCursor(Cursor.getPredefinedCursor(outPortAt(e.getPoint()) >= 0
                    ? Cursor.CROSSHAIR_CURSOR : Cursor.MOVE_CURSOR));
        }

        private void popup(MouseEvent e) {
            if (!selected) canvas.select(Block.this);
            canvas.showBlockMenu(Block.this, e.getX(), e.getY());
        }
    }
}
