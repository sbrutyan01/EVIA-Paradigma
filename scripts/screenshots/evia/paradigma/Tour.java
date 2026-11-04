package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/** Drives the real UI through the README tutorial and saves a screenshot for each step. */
public class Tour {
    static final String OUT = System.getProperty("out", "docs/screenshots");
    static Robot robot;

    static void ui(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static void settle(int ms) throws Exception { ui(() -> {}); Thread.sleep(ms); ui(() -> {}); }

    static void capture(Rectangle r, String name) throws Exception {
        settle(600);
        java.awt.image.MultiResolutionImage mr = robot.createMultiResolutionScreenCapture(r);
        List<Image> vs = mr.getResolutionVariants();
        Image big = vs.get(vs.size() - 1);
        BufferedImage b = new BufferedImage(big.getWidth(null), big.getHeight(null), BufferedImage.TYPE_INT_RGB);
        b.getGraphics().drawImage(big, 0, 0, null);
        ImageIO.write(b, "png", new File(OUT, name + ".png"));
        System.out.println("shot " + name + " " + b.getWidth() + "x" + b.getHeight());
    }
    static void capture(Window w, String name) throws Exception { capture(w.getBounds(), name); }

    static Object call(Object o, String m, Object... args) throws Exception {
        for (java.lang.reflect.Method x : o.getClass().getDeclaredMethods()) {
            if (x.getName().equals(m) && x.getParameterCount() == args.length) { x.setAccessible(true); return x.invoke(o, args); }
        }
        throw new NoSuchMethodException(m);
    }
    static void later(Object o, String m, Object... args) {
        SwingUtilities.invokeLater(() -> { try { call(o, m, args); } catch (Exception e) { e.printStackTrace(); } });
    }
    static Object field(Object o, String f) throws Exception {
        java.lang.reflect.Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o);
    }
    static <T> T waitFor(Supplier<T> s, String what) throws Exception {
        for (int i = 0; i < 200; i++) {
            Object[] r = new Object[1];
            ui(() -> r[0] = s.get());
            if (r[0] != null && !Boolean.FALSE.equals(r[0])) { @SuppressWarnings("unchecked") T t = (T) r[0]; return t; }
            Thread.sleep(100);
        }
        throw new IllegalStateException("timeout waiting for " + what);
    }
    static JDialog dialog(String titlePart) throws Exception {
        return waitFor(() -> {
            for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isShowing() && (titlePart == null || d.getTitle().contains(titlePart))) return d;
            return null;
        }, "dialog " + titlePart);
    }
    static <C extends Component> java.util.List<C> find(Container c, Class<C> type) {
        java.util.List<C> out = new java.util.ArrayList<>();
        for (Component x : c.getComponents()) {
            if (type.isInstance(x)) out.add(type.cast(x));
            if (x instanceof Container k) out.addAll(find(k, type));
        }
        return out;
    }
    static void click(Container c, String text) throws Exception {
        ui(() -> {
            for (AbstractButton b : find(c, AbstractButton.class)) if (text.equals(b.getText())) { b.doClick(); return; }
            throw new IllegalStateException("no button " + text);
        });
    }
    static void menuShot(JFrame f, int index, String name) throws Exception {
        ui(() -> f.getJMenuBar().getMenu(index).doClick(0));
        settle(500);
        JPopupMenu p = f.getJMenuBar().getMenu(index).getPopupMenu();
        Point pl = p.getLocationOnScreen();
        Rectangle fr = f.getBounds();
        int right = Math.max(pl.x + p.getWidth() + 30, fr.x + 520);
        int bottom = pl.y + p.getHeight() + 20;
        capture(new Rectangle(fr.x, fr.y, right - fr.x, bottom - fr.y), name);
        ui(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
    }

    public static void main(String[] a) throws Exception {
        new File(OUT).mkdirs();
        robot = new Robot();
        Settings.setVimKeys(true);
        MainFrame[] fr = new MainFrame[1];
        ui(() -> { fr[0] = new MainFrame(); fr[0].setBounds(0, 0, 1180, 880); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        Library lib = (Library) field(f, "lib");
        FlowCanvas main = lib.main;
        ((JSlider) field(f, "speed")).setValue(0);

        // ---- 1. new project dialog
        later(f, "newProject");
        JDialog np = dialog("New Project");
        ui(() -> {
            List<JTextField> tf = find(np, JTextField.class);
            tf.get(0).setText("CounterDemo");
            tf.get(1).setText(Project.defaultParent().toString());
        });
        capture(np, "01-new-project");
        click(np, "OK");
        waitFor(() -> GitService.isRepo(Project.defaultParent().resolve("CounterDemo")) ? Boolean.TRUE : null, "git repo");
        settle(3000);
        capture(f, "02-project-created");

        // ---- 2. main program: Start -> Set -> For -> Print -> End (loop back)
        BlockDef[] cls = new BlockDef[1];
        ui(() -> {
            main.clear();
            Block st = main.addBlock(BlockType.START, 330, 20);
            Block set = main.addBlock(BlockType.SET, "n = 3", null, 330, 115);
            Block loop = main.addBlock(BlockType.FOR, "i = 1; i <= n; i = i + 1", null, 330, 210);
            Block pr = main.addBlock(BlockType.PRINT, "\"step \" + i", null, 120, 315);
            Block end = main.addBlock(BlockType.END, 540, 315);
            main.connect(st, 0, set); main.connect(set, 0, loop); main.connect(loop, 0, pr);
            main.connect(pr, 0, loop); main.connect(loop, 1, end);
        });
        later(f, "run");
        settle(1500);
        capture(f, "03-first-run");

        // ---- 3. class Counter
        ui(() -> {
            BlockDef d = lib.createEmpty("Counter", null);
            cls[0] = d;
            d.fields.add(new BlockDef.Field("total", "0"));
            d.fields.add(new BlockDef.Field("calls", "0"));
            Method init = lib.newMethod(d, "init", List.of());
            Method add = lib.newMethod(d, "add", List.of("x"));
            Method show = lib.newMethod(d, "show", List.of());
            wire(init, BlockType.PRINT, "\"new counter\"");
            Block[] eb = enterExit(add);
            Block s1 = add.body.addBlock(BlockType.SET, "this.total = this.total + this.x", null, 60, 125);
            Block s2 = add.body.addBlock(BlockType.SET, "this.calls = this.calls + 1", null, 60, 220);
            eb[1].place(60, 330);
            add.body.connect(eb[0], 0, s1); add.body.connect(s1, 0, s2); add.body.connect(s2, 0, eb[1]);
            wire(show, BlockType.PRINT, "\"total = \" + this.total + \" after \" + this.calls + \" calls\"");
            lib.changed();
        });
        BlockDef d = cls[0];
        ClassEditor ce = (ClassEditor) call(f, "openEditor", d);
        ui(() -> ce.setBounds(40, 40, 1080, 640));
        capture(ce, "04-class-fields");
        ui(() -> ce.showMethod(d.method("add")));
        capture(ce, "05-class-method");
        ui(() -> ce.showMethod(d.method("init")));
        capture(ce, "06-class-constructor");
        ui(ce::dispose);

        // ---- 4. use it: c.add(i) in the loop, c.show() after
        Block[] calls = new Block[2];
        ui(() -> {
            Block pr = null, loop = null, end = null;
            for (Block b : main.getBlocks()) {
                if (b.type == BlockType.PRINT) pr = b;
                if (b.type == BlockType.FOR) loop = b;
                if (b.type == BlockType.END) end = b;
            }
            Block add = main.addInstance(d, d.method("add"), 120, 420);
            add.setCall("add", "c"); add.setCode("i");
            Block show = main.addInstance(d, d.method("show"), 540, 315);
            show.setCall("show", "c");
            end.place(540, 420);
            main.connect(pr, 0, add); main.connect(add, 0, loop);
            main.connect(loop, 1, show); main.connect(show, 0, end);
            calls[0] = add; calls[1] = show;
            lib.changed();
        });
        SwingUtilities.invokeLater(() -> InstanceDialog.edit(main, calls[0]));
        JDialog inst = dialog("Call Counter");
        capture(inst, "07-call-dialog");
        click(inst, "Cancel");
        later(f, "run");
        settle(1500);
        capture(f, "08-run-with-class");

        // ---- 4b. zoom with the mouse wheel, toward the pointer
        Point toward = new Point();
        ui(() -> toward.setLocation(calls[0].getX() + calls[0].getWidth() / 2, calls[0].getY()));
        for (int i = 0; i < 5; i++) {
            Point here = new Point(toward);
            ui(() -> main.dispatchEvent(new java.awt.event.MouseWheelEvent(main, java.awt.event.MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, here.x, here.y, 0, 0, 0, false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1, -1.0)));
            ui(() -> {   // the pointer stays where it is on screen: follow the block
                toward.setLocation(calls[0].getX() + calls[0].getWidth() / 2, calls[0].getY());
            });
        }
        settle(500);
        capture(f, "25-zoom-in");
        ui(() -> main.setZoom(0.6, null));
        settle(500);
        capture(f, "26-zoom-out");
        ui(main::zoomReset);
        settle(300);

        // ---- 5. right-click -> View C++ source
        ui(() -> main.showBlockMenu(calls[0], 150, 40));
        settle(500);
        JPopupMenu pm = waitFor(() -> {
            for (Window w : Window.getWindows()) for (JPopupMenu p : find(w instanceof RootPaneContainer rp ? rp.getRootPane() : (Container) w, JPopupMenu.class)) if (p.isShowing()) return p;
            return null;
        }, "popup");
        Point pl = pm.getLocationOnScreen();
        Point cl = calls[0].getLocationOnScreen();
        capture(new Rectangle(cl.x - 30, cl.y - 30, Math.max(pm.getWidth() + pl.x - cl.x + 60, 420), pl.y + pm.getHeight() + 20 - cl.y + 30), "09-block-menu");
        ui(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
        ui(() -> lib.viewSource(calls[0]));
        CodeViewer cv = (CodeViewer) field(f, "codeViewer");
        ui(() -> cv.setBounds(60, 30, 1060, 760));
        capture(cv, "10-view-source");
        // compile & run
        click(cv.getContentPane(), "Compile & Run");
        JTextArea out = (JTextArea) field(cv, "output");
        waitFor(() -> out.getText().contains("exited with code") || out.getText().contains("failed") ? Boolean.TRUE : null, "compile");
        settle(800);
        capture(cv, "11-compile-run");

        // ---- 5b. choose another language in the drop-down list: the code is generated again
        JComboBox<?> langBox = find(cv.getContentPane(), JComboBox.class).get(0);
        ui(langBox::showPopup);
        settle(500);
        Point lb = langBox.getLocationOnScreen();
        capture(new Rectangle(cv.getX(), cv.getY(), Math.min(cv.getWidth(), lb.x - cv.getX() + 560), lb.y - cv.getY() + 360), "21-language-dropdown");
        ui(langBox::hidePopup);
        ui(() -> Settings.setTarget(Settings.target().withLang(Target.Lang.C11)));
        waitFor(() -> cv.getTitle().contains("C11") ? Boolean.TRUE : null, "C11 code");
        settle(500);
        click(cv.getContentPane(), "Compile & Run");
        waitFor(() -> out.getText().contains("exited with code") || out.getText().contains("failed") ? Boolean.TRUE : null, "compile C");
        settle(800);
        capture(cv, "22-c11-compile-run");
        ui(() -> Settings.setTarget(new Target(Target.Lang.CPP17, Target.Flow.GOTO, Target.Values.FAST)));
        waitFor(() -> cv.getTitle().contains("C++17") ? Boolean.TRUE : null, "C++17 code");
        settle(500);
        click(cv.getContentPane(), "Compile & Run");
        waitFor(() -> out.getText().contains("exited with code") || out.getText().contains("failed") ? Boolean.TRUE : null, "compile C++17");
        settle(800);
        capture(cv, "23-cpp17-goto-fast");
        ui(() -> Settings.setTarget(Target.DEFAULT));
        settle(500);
        ui(() -> cv.setVisible(false));

        // ---- 6. save, menus
        ui(() -> { try { call(f, "save"); } catch (Exception e) { throw new RuntimeException(e); } });
        menuShot(f, 0, "12-menu-file");
        menuShot(f, 1, "13-menu-edit");
        menuShot(f, 3, "14-menu-run");
        // Run > Code Language and Mode (the same choices as the drop-down lists)
        JMenu runMenu = f.getJMenuBar().getMenu(3);
        JMenu sub = null;
        for (Component c : runMenu.getMenuComponents()) if (c instanceof JMenu m && m.getText().startsWith("Code")) sub = m;
        JMenu codeMenu = sub;
        ui(() -> MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{
                f.getJMenuBar(), runMenu, runMenu.getPopupMenu(), codeMenu, codeMenu.getPopupMenu()}));
        settle(700);
        JPopupMenu sp = codeMenu.getPopupMenu();
        Point sl = sp.getLocationOnScreen();
        capture(new Rectangle(f.getX(), f.getY(), sl.x + sp.getWidth() + 20 - f.getX(), sl.y + sp.getHeight() + 20 - f.getY()), "24-menu-code-language");
        ui(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
        menuShot(f, 4, "15-menu-git");
        menuShot(f, 5, "16-menu-help");

        // ---- 7. git commit and connect
        later(f, "gitCommit");
        JDialog cd = dialog("Commit");
        ui(() -> {
            JTextArea msg = find(cd, JTextArea.class).stream().filter(JTextArea::isEditable).findFirst().orElseThrow();
            msg.setText("Add Counter class and loop");
        });
        capture(cd, "17-commit");
        click(cd, "Commit");
        settle(2500);
        later(f, "gitConnect");
        JDialog gh = dialog("Connect to GitHub");
        ui(() -> find(gh, JTextField.class).get(0).setText("sbrutyan01/CounterDemo"));
        capture(gh, "18-connect-github");
        click(gh, "Cancel");
        settle(300);
        later(f, "gitShow", "History");
        JDialog hist = dialog("Git History");
        capture(hist, "19-history");
        click(hist, "OK");

        // ---- 8. help
        ui(() -> HelpWindow.showTopic(f, "classes"));
        HelpWindow hw = waitFor(() -> { for (Window w : Window.getWindows()) if (w instanceof HelpWindow h && h.isShowing()) return h; return null; }, "help");
        ui(() -> hw.setBounds(40, 40, 1000, 700));
        settle(800);
        ui(() -> hw.getRootPane().repaint());
        capture(hw, "20-help-guide");
        ui(hw::dispose);
        capture(f, "00-main-window");

        // ---- 9. boards: simulated run, Arduino sketch, bare-metal AVR build, settings, language list
        later(f, "openExample", "boards/blink.evia");
        settle(1200);
        for (Window w : Window.getWindows()) {
            if (w instanceof JDialog dd && dd.isShowing()) click(dd, "No");   // "Save changes?" - the project is saved already
        }
        waitFor(() -> f.getTitle().contains("blink") ? Boolean.TRUE : null, "blink example");
        settle(600);
        later(f, "run");
        JTextArea console = (JTextArea) field(f, "console");
        waitFor(() -> console.getText().contains(">> finished") ? Boolean.TRUE : null, "simulated run");
        capture(f, "27-board-simulated");

        Block write = null;
        for (Block b : lib.main.getBlocks()) if (b.type == BlockType.DIGITAL_WRITE && b.getCode().endsWith("HIGH")) write = b;
        Block w0 = write;
        ui(() -> Settings.setBoard(Board.ESP32_S3));
        ui(() -> Settings.setTarget(new Target(Target.Lang.ARDUINO, Target.Flow.STRUCTURED, Target.Values.FAST)));
        later(f, "viewSource", w0);
        CodeViewer bv = waitFor(() -> { for (Window w : Window.getWindows()) if (w instanceof CodeViewer v && v.isShowing()) return v; return null; }, "sketch");
        ui(() -> bv.setBounds(60, 40, 1180, 860));
        waitFor(() -> bv.getTitle().contains("Arduino") ? Boolean.TRUE : null, "Arduino code");
        settle(800);
        capture(bv, "28-arduino-sketch");

        ui(() -> Settings.setBoard(Board.UNO));
        ui(() -> Settings.setTarget(new Target(Target.Lang.AVR, Target.Flow.STRUCTURED, Target.Values.FAST)));
        waitFor(() -> bv.getTitle().contains("AVR") ? Boolean.TRUE : null, "AVR code");
        settle(800);
        JTextArea bout = (JTextArea) field(bv, "output");
        click(bv.getContentPane(), "Build");
        waitFor(() -> bout.getText().contains(">> built") || bout.getText().contains("failed") || bout.getText().contains("avr-gcc and avr-libc") ? Boolean.TRUE : null, "AVR build");
        settle(800);
        capture(bv, "29-avr-build");
        ui(() -> bv.setVisible(false));

        SwingUtilities.invokeLater(() -> Settings.showDialog(f));
        JDialog sd = dialog("Settings");
        ui(() -> {
            for (JComboBox<?> c : find(sd, JComboBox.class)) if (c.getItemCount() == 2) c.setSelectedIndex(1);   // GCC
        });
        settle(1500);
        capture(sd, "30-settings-gcc-boards");
        click(sd, "Cancel");
        settle(300);

        ui(() -> Settings.setTarget(new Target(Target.Lang.ARDUINO, Target.Flow.STRUCTURED, Target.Values.FAST)));
        settle(500);
        JComboBox<?> langList = find(find(f.getContentPane(), TargetBar.class).get(0), JComboBox.class).get(0);
        ui(() -> langList.showPopup());
        settle(700);
        Rectangle fb = f.getBounds();
        capture(new Rectangle(fb.x, fb.y, fb.width, 560), "31-language-list");
        ui(() -> langList.hidePopup());
        ui(() -> Settings.setTarget(Target.DEFAULT));
        System.out.println("tour done");
        System.exit(0);
    }

    static Block[] enterExit(Method m) {
        Block en = null, ex = null;
        for (Block b : m.body.getBlocks()) { if (b.type == BlockType.ENTER) en = b; if (b.type == BlockType.EXIT) ex = b; }
        return new Block[]{en, ex};
    }
    static void wire(Method m, BlockType t, String code) {
        Block[] eb = enterExit(m);
        Block mid = m.body.addBlock(t, code, null, 60, 125);
        eb[1].place(60, 230);
        m.body.connect(eb[0], 0, mid); m.body.connect(mid, 0, eb[1]);
    }
}
