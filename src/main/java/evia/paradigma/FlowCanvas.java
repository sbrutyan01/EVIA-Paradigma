package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
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
    final Method owner;                   // null for the main program

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

    /** Scale of the diagram on screen: 1 = 100 %. Block positions are stored in diagram units. */
    private double zoom = 1;
    static final double MIN_ZOOM = 0.25, MAX_ZOOM = 3.0;
    private final List<Runnable> zoomListeners = new ArrayList<>();

    FlowCanvas(Library lib, Method owner) {
        super(null);
        this.lib = lib;
        this.owner = owner;
        setTransferHandler(new BlockDropHandler());
        setFocusable(true);
        setPreferredSize(new Dimension(1000, 800));

        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (panPress(e)) return;
                if (maybePopup(e)) return;
                if (!e.isShiftDown() && !e.isControlDown() && !e.isMetaDown()) clearSelection();
                if (SwingUtilities.isLeftMouseButton(e)) bandStart = e.getPoint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (panDrag(e)) return;
                if (bandStart == null) return;
                band = new Rectangle(bandStart);
                band.add(e.getPoint());
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (panRelease(e)) return;
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
        addMouseWheelListener(this::wheel);

        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelection);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete", this::deleteSelection);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "selectAll", this::selectAll);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK), "group", this::groupSelection);
    }

    // ================================================================ panning with the middle mouse button

    private Point panFrom;      // mouse position on screen when the middle button went down
    private Point panView;      // the viewport's top-left corner then

    /** Middle button down (on the canvas or a block): starts moving the canvas. Returns true if the event was used. */
    boolean panPress(MouseEvent e) {
        if (!SwingUtilities.isMiddleMouseButton(e)) return false;
        JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        if (vp == null) return true;
        panFrom = e.getLocationOnScreen();
        panView = vp.getViewPosition();
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return true;
    }

    /** Dragging with the middle button: the canvas follows the mouse. */
    boolean panDrag(MouseEvent e) {
        if (panFrom == null) return SwingUtilities.isMiddleMouseButton(e);
        JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        if (vp == null) return true;
        Point now = e.getLocationOnScreen();
        Dimension view = vp.getExtentSize();
        int x = Math.max(0, Math.min(getWidth() - view.width, panView.x - (now.x - panFrom.x)));
        int y = Math.max(0, Math.min(getHeight() - view.height, panView.y - (now.y - panFrom.y)));
        vp.setViewPosition(new Point(x, y));
        return true;
    }

    boolean panRelease(MouseEvent e) {
        if (panFrom == null) return false;
        panFrom = null;
        panView = null;
        setCursor(Cursor.getDefaultCursor());
        return true;
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
        return addInstance(def, def.defaultMethod(), x, y);
    }

    Block addInstance(BlockDef def, Method m, int x, int y) {
        String args = m == null ? "" : String.join(", ", m.params);   // sensible default: pass same-named variables
        Block b = addBlock(nextId, BlockType.CUSTOM, args, def, x, y);
        if (m != null) b.setCall(m.name, "");
        return b;
    }

    Block addBlock(int id, BlockType type, String code, BlockDef def, int x, int y) {
        Block b = new Block(this, id, type, code, def);
        b.place(x, y);
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

    /** Moves the selected blocks by (dx, dy) diagram units, never past the top or left edge. */
    void moveSelectionBy(int dx, int dy) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (Block b : selection) {
            minX = Math.min(minX, b.modelX());
            minY = Math.min(minY, b.modelY());
        }
        dx = Math.max(dx, -minX);
        dy = Math.max(dy, -minY);
        for (Block b : selection) b.place(b.modelX() + dx, b.modelY() + dy);
        blockMoved();
    }

    void deleteSelection() {
        if (locked) return;
        for (Block b : List.copyOf(selection)) removeBlock(b);
    }

    void groupSelection() {
        if (locked || selection.isEmpty()) return;
        String name = (String) JOptionPane.showInputDialog(this,
                "Name of the new class (" + selection.size() + " blocks will be moved into it):",
                "Group into class", JOptionPane.PLAIN_MESSAGE, null, null, lib.uniqueName("Group"));
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty() || lib.nameTaken(name, null)) {
            JOptionPane.showMessageDialog(this, Text.t("Choose a unique, non-empty name."), "Group", JOptionPane.ERROR_MESSAGE);
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
            Rectangle r = b.modelBounds();
            w = Math.max(w, r.x + r.width + 200);
            h = Math.max(h, r.y + r.height + 200);
        }
        setPreferredSize(new Dimension((int) Math.ceil(w * zoom), (int) Math.ceil(h * zoom)));
        revalidate();
        repaint();
    }

    // ================================================================ zoom

    double zoom() {
        return zoom;
    }

    void onZoom(Runnable r) {
        zoomListeners.add(r);
    }

    /**
     * Sets the zoom, keeping the diagram point under {@code anchor} (canvas pixels, e.g. the mouse)
     * where it is on screen; with no anchor, the middle of the visible area stays put.
     */
    void setZoom(double z, Point anchor) {
        z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        if (Math.abs(z - 1) < 0.02) z = 1;   // snap to 100 %
        if (z == zoom) return;
        JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        Rectangle view = getVisibleRect();
        if (anchor == null) anchor = new Point(view.x + view.width / 2, view.y + view.height / 2);
        Point onScreen = new Point(anchor.x - view.x, anchor.y - view.y);   // anchor within the viewport
        double ax = anchor.x / zoom, ay = anchor.y / zoom;                  // anchor in diagram units
        zoom = z;
        for (Block b : blocks) b.applyZoom();
        blockMoved();
        if (vp != null) {
            vp.setViewSize(getPreferredSize());
            Dimension size = getPreferredSize(), ext = vp.getExtentSize();
            int vx = (int) Math.round(ax * zoom) - onScreen.x, vy = (int) Math.round(ay * zoom) - onScreen.y;
            vx = Math.max(0, Math.min(vx, Math.max(0, size.width - ext.width)));
            vy = Math.max(0, Math.min(vy, Math.max(0, size.height - ext.height)));
            vp.setViewPosition(new Point(vx, vy));
        }
        for (Runnable r : List.copyOf(zoomListeners)) r.run();
    }

    void zoomIn() { setZoom(zoom * 1.25, null); }
    void zoomOut() { setZoom(zoom / 1.25, null); }
    void zoomReset() { setZoom(1, null); }

    /** Zoom so that all blocks fit the visible area (at most 100 %). */
    void zoomToFit() {
        if (blocks.isEmpty()) {
            zoomReset();
            return;
        }
        Rectangle all = null;
        for (Block b : blocks) all = all == null ? b.modelBounds() : all.union(b.modelBounds());
        JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        Dimension ext = vp != null ? vp.getExtentSize() : getVisibleRect().getSize();
        double z = Math.min(1.0, Math.min((ext.width - 40.0) / (all.x + all.width), (ext.height - 40.0) / (all.y + all.height)));
        setZoom(z, null);
        if (vp != null) vp.setViewPosition(new Point(0, 0));
    }

    /**
     * Mouse wheel: zooms toward the mouse (Shift+wheel scrolls sideways, Ctrl+wheel up and down).
     * With "Mouse wheel zooms" off in Settings it scrolls, and Ctrl+wheel zooms.
     */
    private void wheel(MouseWheelEvent e) {
        boolean ctrl = e.isControlDown() || e.isMetaDown();
        boolean zoomGesture = Settings.wheelZooms() ? !ctrl && !e.isShiftDown() : ctrl;
        if (zoomGesture) {
            setZoom(zoom * Math.pow(1.1, -e.getPreciseWheelRotation()), e.getPoint());
            return;
        }
        JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        if (sp == null) return;
        // let the scroll pane scroll: without Ctrl, and Shift keeps meaning "sideways"
        MouseWheelEvent plain = new MouseWheelEvent(sp, e.getID(), e.getWhen(),
                e.isShiftDown() ? InputEvent.SHIFT_DOWN_MASK : 0,
                SwingUtilities.convertPoint(this, e.getPoint(), sp).x, SwingUtilities.convertPoint(this, e.getPoint(), sp).y,
                e.getXOnScreen(), e.getYOnScreen(), e.getClickCount(), false, e.getScrollType(), e.getScrollAmount(),
                e.getWheelRotation(), e.getPreciseWheelRotation());
        sp.dispatchEvent(plain);
    }

    /** A canvas pixel position in diagram units. */
    Point toModel(Point p) {
        return new Point((int) Math.round(p.x / zoom), (int) Math.round(p.y / zoom));
    }

    void beginConnect(Block from, int port, Point p) {
        connFrom = from;
        connPort = port;
        connPoint = toModel(p);
        repaint();
    }

    void updateConnect(Point p) {
        connPoint = toModel(p);
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
        if (b.def != null) {
            InstanceDialog.edit(this, b);
            blockMoved();
            return;
        }
        if (!b.editable()) return;
        String prompt = b.def != null
                ? "Arguments for " + b.def.signature() + "  (comma separated):"
                : b.type.hint;
        String code = CodeEditDialog.show(this, "Edit " + b.title(), prompt, b.getCode(),
                c -> Interpreter.check(b, c));
        if (code == null) return;
        b.setCode(code);
        b.setError(false);
        blockMoved();
        if (b.type == BlockType.EXIT) changed();
    }

    void showBlockMenu(Block b, int x, int y) {
        if (locked) return;
        JPopupMenu menu = new JPopupMenu();
        int n = selection.size();
        if (n > 1) {
            JMenuItem grp = new JMenuItem("Group " + n + " blocks into class...   Ctrl+G");
            grp.addActionListener(e -> groupSelection());
            menu.add(grp);
            JMenuItem del = new JMenuItem("Delete " + n + " blocks");
            del.addActionListener(e -> deleteSelection());
            menu.add(del);
        } else {
            if (b.editable()) {
                JMenuItem edit = new JMenuItem(b.def != null ? "Method, object and arguments..." : "Edit...");
                edit.addActionListener(e -> editBlock(b));
                menu.add(edit);
            }
            JMenuItem src = new JMenuItem(Text.t("View source code"));
            src.addActionListener(e -> lib.viewSource(b));
            menu.add(src);
            if (b.def != null) {
                JMenuItem open = new JMenuItem("Open class " + b.def.name + "...");
                open.addActionListener(e -> lib.openEditor(b.def));
                menu.add(open);
            }
            if (b.type != BlockType.START && b.type != BlockType.END
                    && b.type != BlockType.ENTER && b.type != BlockType.EXIT) {
                JMenuItem grp = new JMenuItem(Text.t("Make class from this..."));
                grp.addActionListener(e -> groupSelection());
                menu.add(grp);
            }
            if (b.next.length > 0) {
                JMenuItem disc = new JMenuItem(Text.t("Remove outgoing connections"));
                disc.addActionListener(e -> {
                    Arrays.fill(b.next, null);
                    repaint();
                });
                menu.add(disc);
            }
            if (b.type != BlockType.ENTER) {
                JMenuItem del = new JMenuItem(Text.t("Delete block"));
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
        JMenuItem del = new JMenuItem(Text.t("Delete connection"));
        del.addActionListener(ev -> {
            l.from().next[l.port()] = null;
            repaint();
        });
        menu.add(del);
        menu.show(this, e.getX(), e.getY());
        return true;
    }

    private Link linkAt(Point pixel) {
        Point p = toModel(pixel);
        BasicStroke hit = new BasicStroke((float) (10 / zoom));
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
        for (Block b : blocks) if (lane.intersects(b.modelBounds())) return false;
        return true;
    }

    @Override
    public Color getBackground() {
        return Theme.canvas(isBody());
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // dot grid, every 20 diagram units (thinned out when zoomed far out)
        g.setColor(Theme.grid(isBody()));
        Rectangle clip = g.getClipBounds();
        double step = 20 * zoom;
        while (step < 10) step *= 2;
        int dot = zoom >= 0.75 ? 2 : 1;
        for (int i = (int) (clip.x / step); i * step < clip.x + clip.width; i++) {
            for (int j = (int) (clip.y / step); j * step < clip.y + clip.height; j++) {
                g.fillRect((int) Math.round(i * step), (int) Math.round(j * step), dot, dot);
            }
        }

        // selection rectangle (canvas pixels)
        if (band != null) {
            g.setColor(Theme.selectFill());
            g.fill(band);
            g.setColor(Theme.selectLine());
            g.setStroke(new BasicStroke(1f));
            g.draw(band);
        }

        // connections, in diagram units
        g.scale(zoom, zoom);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Link l : links()) {
            Color c = l.from().type == BlockType.IF
                    ? (l.port() == 0 ? Theme.wireTrue() : Theme.wireFalse())
                    : l.from().type.isLoop() && l.port() == 0 ? Theme.wireTrue()
                    : Theme.wire();
            g.setColor(c);
            CubicCurve2D curve = linkShape(l.from().outPort(l.port()), l.to().inPort());
            g.draw(curve);
            drawArrow(g, curve);
        }

        // connection being dragged
        if (connFrom != null && connPoint != null) {
            g.setColor(Theme.select());
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{6f, 5f}, 0f));
            g.draw(linkShape(connFrom.outPort(connPort), connPoint));
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
    Block createFromTransfer(String data, Point pixel) {
        Point p = toModel(pixel);
        int x = Math.max(0, p.x - Block.DEFAULT_TOTAL_W / 2), y = Math.max(0, p.y - Block.TOTAL_H / 2);
        if (data.startsWith("DEF:")) {
            String[] parts = data.substring(4).split("/", 2);
            BlockDef d = lib.byId(Integer.parseInt(parts[0]));
            if (d == null) return null;
            Method m = parts.length > 1 ? d.method(parts[1]) : d.defaultMethod();
            return addInstance(d, m != null ? m : d.defaultMethod(), x, y);
        }
        if (data.startsWith("TYPE:")) {
            BlockType t = BlockType.valueOf(data.substring(5));
            if (!allows(t)) return null;
            if (t == BlockType.START && hasStart()) {
                JOptionPane.showMessageDialog(this, Text.t("Only one Start block is allowed."));
                return null;
            }
            return addBlock(t, x, y);
        }
        return null;
    }
}
