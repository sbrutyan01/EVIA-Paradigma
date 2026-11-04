package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Mouse-wheel zoom on the real main window: anchoring, limits, scrolling with modifiers, saving, dragging, dropping. */
public class ZoomTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok    " : "FAIL  ") + what); if (!ok) fails++; }
    static void ui(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static Object field(Object o, String f) throws Exception { java.lang.reflect.Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o); }
    static void shot(Window w, String name) throws Exception {
        ui(() -> {}); Thread.sleep(500);
        ui(() -> { try {
            java.awt.image.MultiResolutionImage mr = new Robot().createMultiResolutionScreenCapture(w.getBounds());
            List<Image> v = mr.getResolutionVariants(); Image big = v.get(v.size() - 1);
            BufferedImage b = new BufferedImage(big.getWidth(null), big.getHeight(null), BufferedImage.TYPE_INT_RGB);
            b.getGraphics().drawImage(big, 0, 0, null);
            ImageIO.write(b, "png", new File(System.getProperty("out", "build/zoom"), name + ".png"));
        } catch (Exception e) { throw new RuntimeException(e); } });
    }
    static void wheel(FlowCanvas c, Point p, double rotation, int mods) throws Exception {
        ui(() -> c.dispatchEvent(new MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), mods, p.x, p.y, 0, 0, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, (int) Math.signum(rotation), rotation)));
    }
    public static void main(String[] a) throws Exception {
        MainFrame[] fr = new MainFrame[1];
        ui(() -> { fr[0] = new MainFrame(); fr[0].setBounds(0, 0, 1180, 880); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        Library lib = (Library) field(f, "lib");
        FlowCanvas c = lib.main;
        Thread.sleep(800);
        Path before = Files.createTempFile("z", ".evia"), after = Files.createTempFile("z", ".evia");
        ProgramIO.save(lib, before);

        // a block's diagram position under the mouse stays put while zooming with the wheel
        Block b0 = c.getBlocks().get(3);
        Point[] anchor = new Point[1];
        ui(() -> anchor[0] = new Point(b0.getX() + 20, b0.getY() + 10));
        Point screenBefore = new Point(anchor[0]); ui(() -> SwingUtilities.convertPointToScreen(screenBefore, c));
        for (int i = 0; i < 5; i++) {   // wheel up = zoom in; the mouse stays at the same screen point
            Point here = new Point(screenBefore);
            ui(() -> SwingUtilities.convertPointFromScreen(here, c));
            wheel(c, here, -1, 0);
        }
        Thread.sleep(300);
        double z = c.zoom();
        check(Math.abs(z - Math.pow(1.1, 5)) < 0.01, "5 wheel notches up zoom to " + Math.round(z * 100) + "%");
        Point[] now = new Point[1];
        ui(() -> { now[0] = new Point((int) Math.round((b0.modelX() + 20 / 1.0) * z), (int) Math.round((b0.modelY() + 10) * z)); SwingUtilities.convertPointToScreen(now[0], c); });
        check(now[0].distance(screenBefore) <= 3, "the point under the mouse stays put (moved " + now[0].distance(screenBefore) + " px)");
        ui(() -> check(b0.getWidth() == (int) Math.ceil(b0.modelBounds().width * z), "block component is scaled"));
        shot(f, "zoom-in");

        for (int i = 0; i < 12; i++) wheel(c, new Point(400, 300), 1, 0);   // zoom out
        Thread.sleep(300);
        check(c.zoom() < 0.6, "wheel down zooms out to " + Math.round(c.zoom() * 100) + "%");
        shot(f, "zoom-out");
        for (int i = 0; i < 60; i++) wheel(c, new Point(100, 100), 1, 0);
        check(Math.abs(c.zoom() - FlowCanvas.MIN_ZOOM) < 1e-9, "zoom stops at " + Math.round(FlowCanvas.MIN_ZOOM * 100) + "%");
        for (int i = 0; i < 80; i++) wheel(c, new Point(100, 100), -1, 0);
        check(Math.abs(c.zoom() - FlowCanvas.MAX_ZOOM) < 1e-9, "zoom stops at " + Math.round(FlowCanvas.MAX_ZOOM * 100) + "%");

        // Shift+wheel and Ctrl+wheel scroll instead of zooming
        double keep = c.zoom();
        JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, c);
        Point[] pos = new Point[1];
        ui(() -> pos[0] = vp.getViewPosition());
        wheel(c, new Point(300, 300), 3, InputEvent.CTRL_DOWN_MASK);
        Thread.sleep(200);
        Point[] pos2 = new Point[1];
        ui(() -> pos2[0] = vp.getViewPosition());
        check(c.zoom() == keep && pos2[0].y > pos[0].y, "Ctrl+wheel scrolls down, zoom unchanged (" + pos[0].y + " -> " + pos2[0].y + ")");
        wheel(c, new Point(300, 300), 3, InputEvent.SHIFT_DOWN_MASK);
        Thread.sleep(200);
        Point[] pos3 = new Point[1];
        ui(() -> pos3[0] = vp.getViewPosition());
        check(c.zoom() == keep && pos3[0].x != pos2[0].x, "Shift+wheel scrolls sideways (" + pos2[0].x + " -> " + pos3[0].x + ")");

        // the middle mouse button moves the canvas, on empty canvas and on a block (the mouse moves with the content)
        for (int round = 0; round < 2; round++) {
            Component src = round == 0 ? c : (Component) lib.main.getBlocks().get(0);
            Point[] start = new Point[1], end = new Point[1];
            ui(() -> start[0] = vp.getViewPosition());
            final int dx = start[0].x >= 30 ? 30 : -30, dy = start[0].y >= 20 ? 20 : -20;   // mouse moves right/down: the view moves left/up
            Point[] origin = new Point[1];
            ui(() -> origin[0] = src.getLocationOnScreen());
            int bx = 5, by = 5;
            ui(() -> src.dispatchEvent(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON2_DOWN_MASK,
                    bx, by, origin[0].x + bx, origin[0].y + by, 1, false, MouseEvent.BUTTON2)));
            ui(() -> src.dispatchEvent(new MouseEvent(src, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), InputEvent.BUTTON2_DOWN_MASK,
                    bx + dx, by + dy, origin[0].x + bx + dx, origin[0].y + by + dy, 0, false, MouseEvent.NOBUTTON)));
            ui(() -> end[0] = vp.getViewPosition());
            ui(() -> src.dispatchEvent(new MouseEvent(src, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                    bx + dx, by + dy, origin[0].x + bx + dx, origin[0].y + by + dy, 1, false, MouseEvent.BUTTON2)));
            check(end[0].x == start[0].x - dx && end[0].y == start[0].y - dy,
                    "middle-button drag on " + (round == 0 ? "the canvas" : "a block") + " moves the view (" + start[0].x + "," + start[0].y + " -> " + end[0].x + "," + end[0].y + ")");
        }

        // saved positions don't depend on the zoom
        ProgramIO.save(lib, after);
        check(Files.readString(before).equals(Files.readString(after)), "saved file is identical at " + Math.round(c.zoom() * 100) + "%");

        // dragging at 200 % moves by half as many diagram units as pixels
        ui(() -> c.setZoom(2.0, null));
        int mx = b0.modelX();
        ui(() -> { c.select(b0); b0.scrollRectToVisible(new Rectangle(0, 0, b0.getWidth(), b0.getHeight())); });
        Thread.sleep(300);
        Robot r = new Robot();
        Point start = new Point(); ui(() -> { start.setLocation(b0.getWidth() / 2, b0.getHeight() / 2 - 10); SwingUtilities.convertPointToScreen(start, b0); });
        r.mouseMove(start.x, start.y); Thread.sleep(150);
        r.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(100);
        for (int i = 1; i <= 10; i++) { r.mouseMove(start.x + i * 10, start.y); Thread.sleep(30); }
        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(300);
        check(Math.abs(b0.modelX() - (mx + 50)) <= 2, "100 px drag at 200% moves 50 units (" + mx + " -> " + b0.modelX() + ")");

        // a palette drop lands under the mouse in diagram units
        Block[] dropped = new Block[1];
        ui(() -> dropped[0] = c.createFromTransfer("TYPE:PRINT", new Point(600, 400)));
        check(Math.abs(dropped[0].modelX() + Block.DEFAULT_TOTAL_W / 2 - 300) <= 1 && Math.abs(dropped[0].modelY() + Block.TOTAL_H / 2 - 200) <= 1,
                "drop at (600,400) px at 200% is centred at (300,200) units");

        // View menu and status bar
        ui(c::zoomReset);
        check(c.zoom() == 1, "Actual size");
        ui(c::zoomToFit);
        check(c.zoom() <= 1, "Fit diagram: " + Math.round(c.zoom() * 100) + "%");
        shot(f, "zoom-fit");
        ui(() -> c.setZoom(0.75, null));
        shot(f, "zoom-75");
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
