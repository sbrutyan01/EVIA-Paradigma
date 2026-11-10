package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

/**
 * Settings > Android > Get Android SDK...: shows Google's licence (the text from Google's own SDK index),
 * and only when the person accepts it downloads the platform, build-tools and platform-tools into the folder.
 */
final class AndroidSdkDialog {
    private AndroidSdkDialog() {}

    /** True when the SDK was installed. */
    static boolean install(Component parent, Path sdk) {
        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, Text.t("Get Android SDK"), Dialog.ModalityType.APPLICATION_MODAL);
        AppIcon.apply(d);
        JLabel head = new JLabel("<html><b>" + Text.t("Android SDK from Google") + "</b><br>"
                + Text.t("Platform %s, build-tools %s and platform-tools (adb), into:", AndroidBuild.PLATFORM, AndroidBuild.BUILD_TOOLS)
                + "<br><code>" + sdk + "</code></html>");
        JTextArea text = new JTextArea(Text.t("Reading Google's SDK index..."));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(Fonts.mono(Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(720, 380));
        JCheckBox accept = new JCheckBox(Text.t("I accept the terms of Google's licence above"));
        accept.setEnabled(false);
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setStringPainted(true);
        bar.setString("");
        JButton download = new JButton(Text.t("Download"));
        download.setEnabled(false);
        JButton cancel = new JButton(Text.t("Cancel"));

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.add(accept, BorderLayout.NORTH);
        south.add(bar, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(download);
        buttons.add(cancel);
        south.add(buttons, BorderLayout.SOUTH);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        p.add(head, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(owner);

        AndroidBuild.Plan[] plan = new AndroidBuild.Plan[1];
        boolean[] done = {false}, stop = {false}, running = {false};
        accept.addActionListener(e -> download.setEnabled(accept.isSelected() && plan[0] != null && !running[0]));
        cancel.addActionListener(e -> {
            stop[0] = true;
            if (!running[0]) d.dispose();
        });
        new Thread(() -> {
            try {
                AndroidBuild.Plan pl = AndroidBuild.plan();
                StringBuilder sb = new StringBuilder();
                for (var l : pl.licenses().entrySet()) sb.append(l.getValue().strip()).append("\n\n");
                SwingUtilities.invokeLater(() -> {
                    plan[0] = pl;
                    text.setText(sb.toString().strip());
                    text.setCaretPosition(0);
                    accept.setEnabled(true);
                    bar.setString(Text.t("Download: %s", Packages.mb(pl.bytes())));
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> text.setText(Text.t("Google's SDK index could not be read:") + "\n" + ex.getMessage()));
            }
        }, "evia-android-index").start();

        download.addActionListener(e -> {
            running[0] = true;
            download.setEnabled(false);
            accept.setEnabled(false);
            text.setText("");
            new Thread(() -> {
                try {
                    AndroidBuild.installSdk(plan[0], sdk,
                            (got, total) -> SwingUtilities.invokeLater(() -> {
                                bar.setValue(total > 0 ? (int) (got * 1000 / total) : 0);
                                bar.setString(Packages.mb(got) + " / " + Packages.mb(total));
                            }),
                            () -> stop[0],
                            line -> SwingUtilities.invokeLater(() -> text.append(line + "\n")));
                    done[0] = true;
                    Settings.setAndroidSdk(sdk.toString());
                    SwingUtilities.invokeLater(() -> {
                        bar.setValue(1000);
                        bar.setString(Text.t("Done"));
                        cancel.setText(Text.t("Close"));
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        text.append("\n>> " + ex.getMessage() + "\n");
                        bar.setString(Text.t("Failed"));
                        cancel.setText(Text.t("Close"));
                    });
                } finally {
                    running[0] = false;
                }
            }, "evia-android-sdk").start();
        });
        d.setVisible(true);
        return done[0];
    }
}
