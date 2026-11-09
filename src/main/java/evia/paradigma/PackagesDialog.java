package evia.paradigma;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Settings, Packages and Updates: the packages of the EVIA server (the application and its toolchains)
 * with what is installed, to install, update or remove each one. Opened by itself the first time an
 * installed copy starts ("welcome": pick the toolchains for your boards), and from the Get buttons of
 * Settings for one tool.
 */
final class PackagesDialog {
    private PackagesDialog() {}

    /** The index as last read, so Settings can tell whether the server has a tool at all. */
    private static volatile List<Packages.Pkg> lastIndex;

    /** True when the last index read lists a package with this id for this computer. */
    static boolean served(String id) {
        List<Packages.Pkg> idx = lastIndex;
        if (idx == null) return false;
        for (Packages.Pkg p : idx) if (p.id().equals(id) && p.available()) return true;
        return false;
    }

    // ================================================================ one tool, for a Get button

    /**
     * Installs (or updates) the package of a tool from the EVIA server and returns its program, or null
     * when the server has no such package (then the caller falls back to the tool's own releases) or the
     * user cancelled.
     */
    static Path installTool(Component parent, Toolchains.Tool t) {
        List<Packages.Pkg> idx = fetchWithDialog(parent);
        if (idx == null) return null;
        Packages.Pkg pkg = null;
        for (Packages.Pkg p : idx) if (p.id().equals(t.folder) && p.available()) pkg = p;
        if (pkg == null) return null;
        String have = Packages.installed().get(pkg.id());
        if (pkg.version().equals(have)) {
            Path exe = Bundled.tool(t.folder, t.exe);
            if (exe != null) {
                int a = JOptionPane.showConfirmDialog(parent, "<html>" + pkg.name() + " " + have + " is installed:<br><code>" + exe
                        + "</code><br><br>" + Text.t("Install it again?") + "</html>", pkg.name(), JOptionPane.YES_NO_OPTION);
                if (a != JOptionPane.YES_OPTION) return exe;
            }
        } else {
            int a = JOptionPane.showConfirmDialog(parent, "<html><b>" + pkg.name() + " " + pkg.version() + "</b><br>"
                    + wrap(pkg.about()) + "<br><br>" + Text.t("Download") + ": " + Packages.mb(pkg.size())
                    + (pkg.unpacked() > 0 ? ", " + Text.t("installed") + ": " + Packages.mb(pkg.unpacked()) : "")
                    + "<br>" + Text.t("From") + ": " + Packages.repo() + "<br>" + Text.t("Into") + ": " + Packages.dir(pkg.id())
                    + "</html>", Text.t("Install %s", pkg.name()), JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (a != JOptionPane.OK_OPTION) return null;
        }
        if (!runInstall(parent, List.of(pkg))) return null;
        return Bundled.tool(t.folder, t.exe);
    }

    /**
     * Installs (or updates) package {@code id} from the EVIA server after asking, for a Get button that is not a
     * tool's (the Linux build systems' sources). True when it is installed afterwards.
     */
    static boolean installPackage(Component parent, String id) {
        List<Packages.Pkg> idx = fetchWithDialog(parent);
        if (idx == null) return false;
        Packages.Pkg pkg = null;
        for (Packages.Pkg p : idx) if (p.id().equals(id)) pkg = p;
        if (pkg == null || !pkg.available()) {
            JOptionPane.showMessageDialog(parent, Text.t("%s has no package %s for this computer (%s).", Packages.repo(), id,
                    Packages.platform()), Text.t("Packages"), JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        String have = Packages.installed().get(pkg.id());
        String q = pkg.version().equals(have)
                ? "<html>" + pkg.name() + " " + have + " " + Text.t("is installed.") + "<br><br>" + Text.t("Install it again?") + "</html>"
                : "<html><b>" + pkg.name() + " " + pkg.version() + "</b><br>" + wrap(pkg.about()) + "<br><br>" + Text.t("Download")
                  + ": " + Packages.mb(pkg.size()) + (pkg.unpacked() > 0 ? ", " + Text.t("installed") + ": " + Packages.mb(pkg.unpacked()) : "")
                  + "<br>" + Text.t("From") + ": " + Packages.repo() + "<br>" + Text.t("Into") + ": " + Packages.dir(pkg.id()) + "</html>";
        int a = JOptionPane.showConfirmDialog(parent, q, Text.t("Install %s", pkg.name()), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (a != JOptionPane.OK_OPTION) return pkg.version().equals(have);
        return runInstall(parent, List.of(pkg));
    }

    /** Reads the index with a small "please wait" window; null (after saying why) when the server can't be reached. */
    private static List<Packages.Pkg> fetchWithDialog(Component parent) {
        Window owner = owner(parent);
        JDialog d = new JDialog(owner, Text.t("Packages"), Dialog.ModalityType.APPLICATION_MODAL);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        p.add(new JLabel(Text.t("Asking %s what is available...", Packages.repo())), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(parent);
        Object[] result = {null};
        SwingWorker<List<Packages.Pkg>, Void> w = new SwingWorker<>() {
            @Override protected List<Packages.Pkg> doInBackground() throws Exception {
                return Packages.fetchIndex();
            }

            @Override protected void done() {
                d.dispose();
                try {
                    result[0] = get();
                } catch (Exception e) {
                    result[0] = e.getCause() == null ? e : e.getCause();
                }
            }
        };
        w.execute();
        d.setVisible(true);
        if (result[0] instanceof Throwable t) {
            lastIndex = null;
            JOptionPane.showMessageDialog(parent, "<html>" + Text.t("The package server could not be reached:") + "<br>"
                    + wrap(String.valueOf(t.getMessage())) + "</html>", Text.t("Packages"), JOptionPane.WARNING_MESSAGE);
            return null;
        }
        @SuppressWarnings("unchecked") List<Packages.Pkg> idx = (List<Packages.Pkg>) result[0];
        lastIndex = idx;
        return idx;
    }

    // ================================================================ the window

    /** The Packages and Updates window; {@code welcome} is the first start of an installed copy. */
    static void show(Component parent, boolean welcome) {
        Window owner = owner(parent);
        JDialog d = new JDialog(owner, welcome ? Text.t("Welcome to EVIA Paradigma") : Text.t("Packages and Updates"),
                Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);
        Model model = new Model();
        JTable table = new JTable(model);
        table.setRowHeight(table.getRowHeight() + 6);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(36);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(190);
        JTextArea about = new JTextArea(3, 60);
        about.setEditable(false);
        about.setLineWrap(true);
        about.setWrapStyleWord(true);
        about.setOpaque(false);
        table.getSelectionModel().addListSelectionListener(e -> {
            int r = table.getSelectedRow();
            about.setText(r < 0 ? "" : model.rows.get(r).about());
        });

        JLabel status = new JLabel(Text.t("Asking %s what is available...", Packages.repo()));
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setIndeterminate(true);
        JButton updates = new JButton(Text.t("Select Updates"));
        JButton install = new JButton(Text.t("Install / Update"));
        JButton remove = new JButton(Text.t("Remove"));
        JButton restart = new JButton(Text.t("Restart Now"));
        restart.setVisible(false);
        JButton close = new JButton(welcome ? Text.t("Later") : Text.t("Close"));
        install.setEnabled(false);
        updates.setEnabled(false);
        remove.setEnabled(false);

        String intro = welcome
                ? "<html>" + Text.t("<b>Choose the toolchains for your boards.</b> They are downloaded from the EVIA server and kept up to date here; nothing else needs to be installed. You can come back any time: Settings, Packages and Updates.") + "</html>"
                : "<html>" + Text.t("From") + " <b>" + Packages.repo() + "</b>, " + Text.t("for") + " " + Packages.platform()
                        + ". " + Text.t("Installed into") + " " + Packages.home() + "</html>";
        JPanel top = new JPanel(new BorderLayout());
        top.add(new JLabel(intro), BorderLayout.CENTER);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
        p.add(top, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 6));
        mid.add(new JScrollPane(table), BorderLayout.CENTER);
        mid.add(about, BorderLayout.SOUTH);
        p.add(mid, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        JPanel prog = new JPanel(new BorderLayout(0, 2));
        prog.add(status, BorderLayout.NORTH);
        prog.add(bar, BorderLayout.CENTER);
        south.add(prog, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(updates);
        buttons.add(remove);
        buttons.add(install);
        buttons.add(restart);
        buttons.add(close);
        south.add(buttons, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.setSize(860, 520);
        d.setLocationRelativeTo(parent);

        Runnable refreshButtons = () -> {
            boolean any = !model.checked.isEmpty();
            install.setEnabled(any);
            updates.setEnabled(model.rows.stream().anyMatch(Packages::hasUpdate));
            int r = table.getSelectedRow();
            remove.setEnabled(r >= 0 && !model.rows.get(r).app() && Packages.installed().containsKey(model.rows.get(r).id()));
        };
        model.addTableModelListener(e -> refreshButtons.run());
        table.getSelectionModel().addListSelectionListener(e -> refreshButtons.run());

        new SwingWorker<List<Packages.Pkg>, Void>() {
            @Override protected List<Packages.Pkg> doInBackground() throws Exception {
                return Packages.fetchIndex();
            }

            @Override protected void done() {
                bar.setIndeterminate(false);
                try {
                    List<Packages.Pkg> idx = get();
                    lastIndex = idx;
                    model.set(idx);
                    for (Packages.Pkg pk : idx) {
                        if (Packages.hasUpdate(pk) || (welcome && pk.recommended() && !Packages.installed().containsKey(pk.id())
                                && !pk.app() && pk.available())) {
                            model.checked.add(pk.id());
                        }
                    }
                    model.fireTableDataChanged();
                    long n = idx.stream().filter(Packages::hasUpdate).count();
                    status.setText(n > 0 ? Text.t("%d updates are available.", n) : Text.t("Everything installed is up to date."));
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    status.setText("<html><font color='#c0392b'>" + Text.t("The package server could not be reached:") + " "
                            + esc(String.valueOf(c.getMessage())) + "</font></html>");
                }
                refreshButtons.run();
            }
        }.execute();

        updates.addActionListener(e -> {
            for (Packages.Pkg pk : model.rows) if (Packages.hasUpdate(pk)) model.checked.add(pk.id());
            model.fireTableDataChanged();
        });
        remove.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r < 0) return;
            Packages.Pkg pk = model.rows.get(r);
            if (JOptionPane.showConfirmDialog(d, Text.t("Remove %s from this computer?", pk.name()), Text.t("Remove"),
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                Packages.remove(pk.id());
                status.setText(Text.t("%s was removed.", pk.name()));
                Settings.changed();
            } catch (Exception ex) {
                status.setText(Text.t("Could not remove %s: %s", pk.name(), String.valueOf(ex.getMessage())));
            }
            model.fireTableDataChanged();
        });
        install.addActionListener(e -> {
            List<Packages.Pkg> todo = new ArrayList<>();
            for (Packages.Pkg pk : model.rows) if (model.checked.contains(pk.id()) && pk.available()) todo.add(pk);
            if (todo.isEmpty()) return;
            install.setEnabled(false);
            remove.setEnabled(false);
            updates.setEnabled(false);
            close.setText(Text.t("Cancel"));
            boolean[] cancel = {false};
            Worker w = new Worker(todo, status, bar, cancel, done -> {
                close.setText(Text.t("Close"));
                model.checked.clear();
                model.fireTableDataChanged();
                if (done.contains(Packages.APP)) {
                    restart.setVisible(Restart.possible());
                    status.setText(Text.t("EVIA Paradigma %s is installed: it starts the next time.",
                            Packages.installed().get(Packages.APP)) + (Restart.possible() ? " " + Text.t("Restart now?") : ""));
                }
                Settings.changed();
            });
            close.putClientProperty("worker", w);
            close.putClientProperty("cancel", cancel);
            w.execute();
        });
        close.addActionListener(e -> {
            Object w = close.getClientProperty("worker");
            if (w instanceof Worker wk && !wk.isDone()) {
                ((boolean[]) close.getClientProperty("cancel"))[0] = true;
                status.setText(Text.t("Cancelling..."));
                return;
            }
            d.dispose();
        });
        restart.addActionListener(e -> Restart.now(d));
        d.setVisible(true);
    }

    /** Installs the packages one after the other, with progress; true when all of them made it. */
    private static boolean runInstall(Component parent, List<Packages.Pkg> todo) {
        Window owner = owner(parent);
        JDialog d = new JDialog(owner, Text.t("Packages"), Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);
        JLabel status = new JLabel(" ");
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setStringPainted(true);
        JButton cancel = new JButton(Text.t("Cancel"));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        p.add(status, BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        JPanel s = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        s.add(cancel);
        p.add(s, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.setSize(560, 150);
        d.setLocationRelativeTo(parent);
        boolean[] stop = {false};
        boolean[] ok = {false};
        Worker w = new Worker(todo, status, bar, stop, done -> {
            ok[0] = done.size() == todo.size();
            if (ok[0]) d.dispose();
            else cancel.setText(Text.t("Close"));
        });
        cancel.addActionListener(e -> {
            if (w.isDone()) d.dispose();
            else stop[0] = true;
        });
        w.execute();
        d.setVisible(true);
        if (ok[0]) Settings.changed();
        return ok[0];
    }

    /** Downloads and installs a list of packages in the background, reporting into a label and a bar. */
    private static final class Worker extends SwingWorker<Set<String>, Object[]> {
        private final List<Packages.Pkg> todo;
        private final JLabel status;
        private final JProgressBar bar;
        private final boolean[] cancel;
        private final Consumer<Set<String>> after;
        private final List<String> errors = new ArrayList<>();

        Worker(List<Packages.Pkg> todo, JLabel status, JProgressBar bar, boolean[] cancel, Consumer<Set<String>> after) {
            this.todo = todo;
            this.status = status;
            this.bar = bar;
            this.cancel = cancel;
            this.after = after;
        }

        @Override protected Set<String> doInBackground() {
            Set<String> done = new HashSet<>();
            for (Packages.Pkg pk : todo) {
                if (cancel[0]) break;
                try {
                    Packages.install(pk, new Packages.Progress() {
                        @Override public void report(String text, long d, long total) {
                            publish(new Object[]{text, total > 0 ? (int) (d * 1000 / total) : -1});
                        }

                        @Override public boolean cancelled() {
                            return cancel[0];
                        }
                    });
                    done.add(pk.id());
                } catch (Exception e) {
                    errors.add(pk.name() + ": " + e.getMessage());
                }
            }
            return done;
        }

        @Override protected void process(List<Object[]> chunks) {
            Object[] last = chunks.get(chunks.size() - 1);
            status.setText((String) last[0]);
            int v = (Integer) last[1];
            bar.setIndeterminate(v < 0);
            if (v >= 0) {
                bar.setValue(v);
                bar.setString(v / 10 + " %");
            }
        }

        @Override protected void done() {
            bar.setIndeterminate(false);
            Set<String> done;
            try {
                done = get();
            } catch (Exception e) {
                done = Set.of();
                errors.add(String.valueOf(e.getMessage()));
            }
            bar.setValue(done.size() == todo.size() ? 1000 : 0);
            bar.setString(done.size() + " / " + todo.size());
            if (errors.isEmpty()) {
                status.setText(cancel[0] ? Text.t("Cancelled.") : Text.t("Installed: %s.",
                        String.join(", ", todo.stream().map(Packages.Pkg::name).toList())));
            } else {
                status.setText("<html><font color='#c0392b'>" + esc(String.join("; ", errors)) + "</font></html>");
            }
            after.accept(done);
        }
    }

    /** The table: a check box, the package, what it is for, installed and available versions, size. */
    private static final class Model extends AbstractTableModel {
        final List<Packages.Pkg> rows = new ArrayList<>();
        final Set<String> checked = new HashSet<>();
        private Map<String, String> installed = Map.of();

        void set(List<Packages.Pkg> idx) {
            rows.clear();
            rows.addAll(idx);
            fireTableDataChanged();
        }

        @Override public void fireTableDataChanged() {
            installed = Packages.installed();
            super.fireTableDataChanged();
        }

        @Override public int getRowCount() {
            return rows.size();
        }

        @Override public int getColumnCount() {
            return 6;
        }

        @Override public String getColumnName(int c) {
            return switch (c) {
                case 0 -> "";
                case 1 -> Text.t("Package");
                case 2 -> Text.t("For");
                case 3 -> Text.t("Installed");
                case 4 -> Text.t("Available");
                default -> Text.t("Download");
            };
        }

        @Override public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override public boolean isCellEditable(int r, int c) {
            return c == 0 && rows.get(r).available() && (!rows.get(r).app() || Packages.hasUpdate(rows.get(r)));
        }

        @Override public Object getValueAt(int r, int c) {
            Packages.Pkg p = rows.get(r);
            return switch (c) {
                case 0 -> checked.contains(p.id());
                case 1 -> p.name();
                case 2 -> p.group();
                case 3 -> p.app() ? Packages.runningVersion() + (installed.containsKey(p.id()) ? "" : " (" + Text.t("installer") + ")")
                        : installed.getOrDefault(p.id(), "-");
                case 4 -> p.available() ? p.version() + (Packages.hasUpdate(p) ? "  *" : "") : Text.t("not for %s", Packages.platform());
                default -> p.available() ? Packages.mb(p.size()) : "";
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            if (c != 0) return;
            if (Boolean.TRUE.equals(v)) checked.add(rows.get(r).id());
            else checked.remove(rows.get(r).id());
            fireTableRowsUpdated(r, r);
        }
    }

    // ================================================================ the quiet check at start

    /**
     * Once a day, in the background: are there updates? {@code notice} gets a short sentence when there
     * are (on the event thread); nothing happens when there are none or the server can't be reached.
     */
    static void checkInBackground(Consumer<String> notice) {
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(PackagesDialog.class);
        long last = prefs.getLong("updatesChecked", 0), now = System.currentTimeMillis();
        if (now - last < 20L * 3600 * 1000 || Boolean.getBoolean("evia.noUpdateCheck")) return;
        Thread t = new Thread(() -> {
            try {
                List<Packages.Pkg> idx = Packages.fetchIndex();
                lastIndex = idx;
                prefs.putLong("updatesChecked", now);
                List<String> names = idx.stream().filter(Packages::hasUpdate).map(p -> p.name() + " " + p.version()).toList();
                if (!names.isEmpty()) {
                    SwingUtilities.invokeLater(() -> notice.accept(Text.t("Updates are available: %s. Settings, Packages and Updates installs them.",
                            String.join(", ", names))));
                }
            } catch (Exception ignored) {
                // offline: ask again next time
            }
        }, "evia-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** True the first time an installed copy (not the jar on its own) starts, until the welcome has been shown. */
    static boolean welcomeDue() {
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(PackagesDialog.class);
        if (System.getProperty("jpackage.app-path") == null || Boolean.getBoolean("evia.noWelcome")) return false;
        if (prefs.getBoolean("welcomeShown", false)) return false;
        prefs.putBoolean("welcomeShown", true);
        return true;
    }

    // ================================================================ restarting after an update

    /** Starts the application again (the installer's launcher, which hands over to the newest version) and exits. */
    static final class Restart {
        private Restart() {}

        static boolean possible() {
            return System.getProperty("jpackage.app-path") != null;
        }

        static void now(Component parent) {
            String launcher = System.getProperty("jpackage.app-path");
            if (launcher == null) return;
            for (Window w : Window.getWindows()) {
                if (w instanceof MainFrame f && !f.confirmDiscardForRestart()) return;
            }
            try {
                new ProcessBuilder(launcher).start();
                System.exit(0);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(parent, Text.t("Could not restart: %s. Close and open EVIA Paradigma again.",
                        String.valueOf(e.getMessage())), Text.t("Restart"), JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private static Window owner(Component parent) {
        return parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String wrap(String s) {
        return "<div style='width:430px'>" + esc(s) + "</div>";
    }
}
