package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An editing surface for a block graph: either the main program ({@code owner == null})
 * or the body of a custom block definition. Uses a null layout; every {@link Block}
 * is a child component. Connections are painted underneath the blocks.
 */
public final class FlowCanvas extends JPanel {

    /** A connection: output port {@code port} of {@code from} goes to {@code to}. */
    record Link(Block from, int port, Block to) {}

    final Library lib;
    final BlockDef owner;                 // null for the main program

    private final List<Block> blocks = new ArrayList<>();
    private final Set<Block> selection = new LinkedHashSet<>();
    private int nextId = 1;
    private Block active;
    private boolean locked;

    // rubber band for dragging a new connection
    private Block connFrom;
    private int connPort;
    private Point connPoint;

    // rubber band for rectangle selection
    private Point bandStart;
    private Rectangle band;

    FlowCanvas(Library lib, BlockDef owner) {
        super(null);
        this.lib = lib;
        this.owner = owner;
        setBackground(owner == null ? new Color(248, 249, 252) : new Color(244, 251, 250));
        setTransferHandler(new BlockDropHandler());
        setFocusable(true);
        setPreferredSize(new Dimension(1000, 800));

        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (maybePopup(e)) return;
                if (!e.isShiftDown() && !e.isControlDown() && !e.isMetaDown()) clearSelection();
                if (SwingUtilities.isLeftMouseButton(e)) bandStart = e.getPoint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (bandStart == null) return;
                band = new Rectangle(bandStart);
                band.add(e.getPoint());
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (band != null) {
                    for (Block b : blocks) if (band.intersects(b.getBounds())) addToSelection(b);
                }
                band = null;
                bandStart = null;
                repaint();
                maybePopup(e);
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);

        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelection);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete", this::deleteSelection);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "selectAll", this::selectAll);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK), "group", this::groupSelection);
    }

    private void bindKey(KeyStroke ks, String name, Runnable r) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    boolean isBody() {
        return owner != null;
    }

    /** Which block types may be dropped on this canvas. */
    boolean allows(BlockType t) {
        return switch (t) {
            case START, END -> !isBody();
            case EXIT -> isBody();
            case ENTER -> false;            // created automatically, exactly one per body
            default -> true;
        };
    }

    // ================================================================ model

    List<Block> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    Block addBlock(BlockType type, int x, int y) {
        return addBlock(nextId, type, type.defaultCode, null, x, y);
    }

    Block addBlock(BlockType type, String code, BlockDef def, int x, int y) {
        return addBlock(nextId, type, code, def, x, y);
    }

    Block addInstance(BlockDef def, int x, int y) {
        String args = String.join(", ", def.params);   // sensible default: pass same-named variables
        return addBlock(nextId, BlockType.CUSTOM, args, def, x, y);
    }

    Block addBlock(int id, BlockType type, String code, BlockDef def, int x, int y) {
        Block b = new Block(this, id, type, code, def);
        b.setLocation(x, y);
        add(b);
        setComponentZOrder(b, 0);
        blocks.add(b);
        nextId = Math.max(nextId, id + 1);
        blockMoved();
        if (type == BlockType.EXIT) changed();
        return b;
    }

    void removeBlock(Block b) {
        if (b.type == BlockType.ENTER) {
            Toolkit.getDefaultToolkit().beep();     // every definition needs its Enter block
            return;
        }
        for (Block other : blocks) {
            for (int i = 0; i < other.next.length; i++) {
                if (other.next[i] == b) other.next[i] = null;
            }
        }
        blocks.remove(b);
        selection.remove(b);
        remove(b);
        if (active == b) active = null;
        repaint();
        if (b.type == BlockType.EXIT) changed();
    }

    void connect(Block from, int port, Block to) {
        from.next[port] = to;
        repaint();
    }

    void clear() {
        blocks.clear();
        selection.clear();
        removeAll();
        active = null;
        nextId = 1;
        blockMoved();
    }

    boolean hasStart() {
        return blocks.stream().anyMatch(b -> b.type == BlockType.START);
    }

    List<Link> links() {
        List<Link> out = new ArrayList<>();
        for (Block b : blocks) {
            for (int i = 0; i < b.next.length; i++) {
                if (b.next[i] != null) out.add(new Link(b, i, b.next[i]));
            }
        }
        return out;
    }

    /** Something that affects instances of this body's definition changed (exits added/moved/renamed). */
    void changed() {
        if (isBody()) lib.sync();
    }

    // ================================================================ selection

    Set<Block> selection() {
        return Collections.unmodifiableSet(selection);
    }

    void select(Block b) {
        clearSelection();
        if (b != null) addToSelection(b);
    }

    void addToSelection(Block b) {
        selection.add(b);
        b.setSelected(true);
    }

    void toggleSelect(Block b) {
        if (selection.remove(b)) b.setSelected(false);
        else addToSelection(b);
    }

    void clearSelection() {
        for (Block b : selection) b.setSelected(false);
        selection.clear();
    }

    void selectAll() {
        for (Block b : blocks) addToSelection(b);
    }

    void moveSelectionBy(int dx, int dy) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (Block b : selection) {
            minX = Math.min(minX, b.getX());
            minY = Math.min(minY, b.getY());
        }
        dx = Math.max(dx, -minX);
        dy = Math.max(dy, -minY);
        for (Block b : selection) b.setLocation(b.getX() + dx, b.getY() + dy);
        blockMoved();
    }

    void deleteSelection() {
        if (locked) return;
        for (Block b : List.copyOf(selection)) removeBlock(b);
    }

    void groupSelection() {
        if (locked || selection.isEmpty()) return;
        String name = (String) JOptionPane.showInputDialog(this,
                "Name of the new custom block (" + selection.size() + " blocks will be moved into it):",
                "Group into custom block", JOptionPane.PLAIN_MESSAGE, null, null, lib.uniqueName("Group"));
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty() || lib.nameTaken(name, null)) {
            JOptionPane.showMessageDialog(this, "Choose a unique, non-empty name.", "Group", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            Grouper.group(this, selection, name);
        } catch (FlowException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Cannot group", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ================================================================ run marks

    void setActive(Block b) {
        if (active != null) active.setActive(false);
        active = b;
        if (b != null) {
            b.setActive(true);
            scrollRectToVisible(b.getBounds());
        }
    }

    void setError(Block b) {
        if (b != null) {
            b.setError(true);
            scrollRectToVisible(b.getBounds());
        }
    }

    void clearMarks() {
        setActive(null);
        for (Block b : blocks) b.setError(false);
    }

    void setLocked(boolean v) {
        locked = v;
    }

    boolean isLocked() {
        return locked;
    }

    // ================================================================ called by Block

    void blockMoved() {
        int w = 1000, h = 800;
        for (Block b : blocks) {
            w = Math.max(w, b.getX() + b.getWidth() + 200);
            h = Math.max(h, b.getY() + b.getHeight() + 200);
        }
        setPreferredSize(new Dimension(w, h));
        revalidate();
        repaint();
    }

    void beginConnect(Block from, int port, Point p) {
        connFrom = from;
        connPort = port;
        connPoint = p;
        repaint();
    }

    void updateConnect(Point p) {
        connPoint = p;
        repaint();
    }

    void endConnect(Point p) {
        Component c = getComponentAt(p);
        if (c instanceof Block target && target != connFrom && target.type.hasInput) {
            connect(connFrom, connPort, target);
        }
        connFrom = null;
        connPoint = null;
        repaint();
    }

    void editBlock(Block b) {
        if (locked) return;
        if (b.def != null && !b.editable()) {
            lib.openEditor(b.def);
            return;
        }
        if (!b.editable()) return;
        String prompt = b.def != null
                ? "Arguments for " + b.def.signature() + "  (comma separated):"
                : b.type.hint;
        String code = b.getCode();
        while (true) {
            Object r = JOptionPane.showInputDialog(this, prompt, "Edit " + b.title(),
                    JOptionPane.PLAIN_MESSAGE, null, null, code);
            if (r == null) return;
            code = r.toString().trim();
            String err = Interpreter.check(b, code);
            if (err == null) {
                b.setCode(code);
                b.setError(false);
                blockMoved();
                if (b.type == BlockType.EXIT) changed();
                return;
            }
            JOptionPane.showMessageDialog(this, err, "Invalid code", JOptionPane.ERROR_MESSAGE);
        }
    }

    void showBlockMenu(Block b, int x, int y) {
        if (locked) return;
        JPopupMenu menu = new JPopupMenu();
        int n = selection.size();
        if (n > 1) {
            JMenuItem grp = new JMenuItem("Group " + n + " blocks into custom block...   Ctrl+G");
            grp.addActionListener(e -> groupSelection());
            menu.add(grp);
            JMenuItem del = new JMenuItem("Delete " + n + " blocks");
            del.addActionListener(e -> deleteSelection());
            menu.add(del);
        } else {
            if (b.editable()) {
                JMenuItem edit = new JMenuItem(b.def != null ? "Edit arguments..." : "Edit...");
                edit.addActionListener(e -> editBlock(b));
                menu.add(edit);
            }
            if (b.def != null) {
                JMenuItem open = new JMenuItem("Open definition of " + b.def.name + "...");
                open.addActionListener(e -> lib.openEditor(b.def));
                menu.add(open);
            }
            if (b.type != BlockType.START && b.type != BlockType.END
                    && b.type != BlockType.ENTER && b.type != BlockType.EXIT) {
                JMenuItem grp = new JMenuItem("Make custom block from this...");
                grp.addActionListener(e -> groupSelection());
                menu.add(grp);
            }
            if (b.next.length > 0) {
                JMenuItem disc = new JMenuItem("Remove outgoing connections");
                disc.addActionListener(e -> {
                    Arrays.fill(b.next, null);
                    repaint();
                });
                menu.add(disc);
            }
            if (b.type != BlockType.ENTER) {
                JMenuItem del = new JMenuItem("Delete block");
                del.addActionListener(e -> removeBlock(b));
                menu.add(del);
            }
        }
        menu.show(b, x, y);
    }

    private boolean maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger() || locked) return false;
        Link l = linkAt(e.getPoint());
        if (l == null) return false;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem del = new JMenuItem("Delete connection");
        del.addActionListener(ev -> {
            l.from().next[l.port()] = null;
            repaint();
        });
        menu.add(del);
        menu.show(this, e.getX(), e.getY());
        return true;
    }

    private Link linkAt(Point p) {
        BasicStroke hit = new BasicStroke(10f);
        for (Link l : links()) {
            Shape s = linkShape(l.from().outPort(l.port()), l.to().inPort());
            if (hit.createStrokedShape(s).contains(p)) return l;
        }
        return null;
    }

    // ================================================================ painting

    /** Bezier from an output port (going down) to an input port (coming from above). */
    CubicCurve2D linkShape(Point a, Point b) {
        if (b.y > a.y + 30) {
            double dy = Math.max(40, (b.y - a.y) / 2.0);
            return new CubicCurve2D.Double(a.x, a.y, a.x, a.y + dy, b.x, b.y - dy, b.x, b.y);
        }
        // back edge (loop): swing out sideways along a free vertical lane.
        // The curve's outermost x is 1/8*(a.x+b.x) + 3/4*cx, so solve cx for the wanted lane.
        double lane = freeLane(a, b);
        double cx = (lane - 0.125 * (a.x + b.x)) / 0.75;
        return new CubicCurve2D.Double(a.x, a.y, cx, a.y + 110, cx, b.y - 110, b.x, b.y);
    }

    private double freeLane(Point a, Point b) {
        int top = Math.min(a.y, b.y) - 60, bottom = Math.max(a.y, b.y) + 60;
        int left = Math.min(a.x, b.x), right = Math.max(a.x, b.x);
        boolean preferLeft = a.x <= b.x;
        for (int k = 110; k <= 650; k += 40) {
            int[] lanes = preferLeft ? new int[]{left - k, right + k} : new int[]{right + k, left - k};
            for (int x : lanes) if (x >= 12 && laneIsFree(x, top, bottom)) return x;
        }
        return preferLeft ? Math.max(12, left - 130) : right + 130;
    }

    private boolean laneIsFree(int x, int top, int bottom) {
        Rectangle lane = new Rectangle(x - 8, top, 16, bottom - top);
        for (Block b : blocks) if (lane.intersects(b.getBounds())) return false;
        return true;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // dot grid
        g.setColor(isBody() ? new Color(200, 225, 222) : new Color(214, 218, 228));
        Rectangle clip = g.getClipBounds();
        int step = 20;
        for (int x = clip.x / step * step; x < clip.x + clip.width; x += step) {
            for (int y = clip.y / step * step; y < clip.y + clip.height; y += step) {
                g.fillRect(x, y, 2, 2);
            }
        }

        // connections
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Link l : links()) {
            Color c = l.from().type == BlockType.IF
                    ? (l.port() == 0 ? new Color(50, 150, 60) : new Color(200, 60, 50))
                    : new Color(70, 75, 90);
            g.setColor(c);
            CubicCurve2D curve = linkShape(l.from().outPort(l.port()), l.to().inPort());
            g.draw(curve);
            drawArrow(g, curve);
        }

        // connection being dragged
        if (connFrom != null && connPoint != null) {
            g.setColor(new Color(30, 90, 210));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{6f, 5f}, 0f));
            g.draw(linkShape(connFrom.outPort(connPort), connPoint));
        }

        // selection rectangle
        if (band != null) {
            g.setColor(new Color(30, 90, 210, 30));
            g.fill(band);
            g.setColor(new Color(30, 90, 210, 160));
            g.setStroke(new BasicStroke(1f));
            g.draw(band);
        }
        g.dispose();
    }

    private static void drawArrow(Graphics2D g, CubicCurve2D c) {
        double angle = Math.atan2(c.getY2() - c.getCtrlY2(), c.getX2() - c.getCtrlX2());
        double tipX = c.getX2(), tipY = c.getY2() - Block.PORT_R;
        double len = 11, spread = Math.toRadians(25);
        Path2D head = new Path2D.Double();
        head.moveTo(tipX, tipY);
        head.lineTo(tipX - len * Math.cos(angle - spread), tipY - len * Math.sin(angle - spread));
        head.lineTo(tipX - len * Math.cos(angle + spread), tipY - len * Math.sin(angle + spread));
        head.closePath();
        g.fill(head);
    }

    // ================================================================ drag and drop from palette

    /** Transfer strings: "TYPE:SET" for a primitive, "DEF:3" for a custom block definition. */
    private final class BlockDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return !locked && s.isDrop() && s.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport s) {
            if (!canImport(s)) return false;
            try {
                String data = (String) s.getTransferable().getTransferData(DataFlavor.stringFlavor);
                Point p = s.getDropLocation().getDropPoint();
                Block b = createFromTransfer(data, p);
                if (b == null) return false;
                select(b);
                return true;
            } catch (Exception e) {
                return false;   // not one of ours
            }
        }
    }

    /** Creates a block from a palette transfer string centered at p; null if not allowed here. */
    Block createFromTransfer(String data, Point p) {
        int x = Math.max(0, p.x - Block.DEFAULT_TOTAL_W / 2), y = Math.max(0, p.y - Block.TOTAL_H / 2);
        if (data.startsWith("DEF:")) {
            BlockDef d = lib.byId(Integer.parseInt(data.substring(4)));
            return d == null ? null : addInstance(d, x, y);
        }
        if (data.startsWith("TYPE:")) {
            BlockType t = BlockType.valueOf(data.substring(5));
            if (!allows(t)) return null;
            if (t == BlockType.START && hasStart()) {
                JOptionPane.showMessageDialog(this, "Only one Start block is allowed.");
                return null;
            }
            return addBlock(t, x, y);
        }
        return null;
    }
}
