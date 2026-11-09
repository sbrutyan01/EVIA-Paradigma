package evia.paradigma;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortTimeoutException;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The Terminal window: a real terminal (JediTerm, the emulator of the JetBrains IDEs) on a board's serial
 * port (UART) or on another computer over SSH, one tab per session. For a board: Print and Input are at
 * 115200 baud; for a Raspberry Pi, a server or the board's own Linux: SSH with a password or a key.
 */
final class TerminalWindow extends JFrame {

    private static TerminalWindow instance;

    /** Opens the window (or brings it to the front). */
    static void open(Component parent) {
        if (instance == null || !instance.isDisplayable()) instance = new TerminalWindow();
        instance.setLocationRelativeTo(parent);
        instance.setVisible(true);
        instance.toFront();
    }

    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel status = new JLabel(" ");

    private TerminalWindow() {
        super(Text.t("Terminal") + " - EVIA Paradigma");
        AppIcon.apply(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // ---- UART
        JComboBox<String> port = new JComboBox<>();
        port.setEditable(true);
        port.setPrototypeDisplayValue("/dev/cu.usbmodem14101XXXX");
        Runnable scan = () -> {
            Object typed = port.getEditor().getItem();
            port.removeAllItems();
            for (SerialPort p : SerialPort.getCommPorts()) port.addItem(p.getSystemPortPath());
            String saved = Settings.serialPort();
            port.setSelectedItem(typed != null && !typed.toString().isBlank() ? typed : !saved.isEmpty() ? saved
                    : port.getItemCount() > 0 ? port.getItemAt(0) : "");
        };
        scan.run();
        JButton scanBtn = new JButton(Text.t("Scan"));
        scanBtn.addActionListener(e -> scan.run());
        JComboBox<Integer> baud = new JComboBox<>(new Integer[]{300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200,
                230400, 460800, 921600, 1000000, 2000000});
        baud.setEditable(true);
        baud.setSelectedItem(115200);
        JComboBox<String> enter = new JComboBox<>(new String[]{"CR", "LF", "CR LF"});
        enter.setToolTipText(Text.t("What the Enter key sends"));
        JCheckBox echo = new JCheckBox(Text.t("Local echo"));
        echo.setToolTipText(Text.t("Show what you type: most boards do not send it back"));
        JButton uartBtn = new JButton(Text.t("Open UART"));

        // ---- SSH
        JTextField host = new JTextField(14), user = new JTextField(System.getProperty("user.name", ""), 8);
        JSpinner sshPort = new JSpinner(new SpinnerNumberModel(22, 1, 65535, 1));
        sshPort.setEditor(new JSpinner.NumberEditor(sshPort, "#"));
        JPasswordField password = new JPasswordField(8);
        password.setToolTipText(Text.t("Leave empty to use a key (or to be asked)"));
        JTextField key = new JTextField(defaultKey(), 14);
        key.setToolTipText(Text.t("A private key file, e.g. ~/.ssh/id_ed25519; empty for none"));
        JButton keyBtn = new JButton("...");
        keyBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(Path.of(System.getProperty("user.home"), ".ssh").toFile());
            fc.setFileHidingEnabled(false);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) key.setText(fc.getSelectedFile().getPath());
        });
        JButton sshBtn = new JButton(Text.t("Connect SSH"));

        JPanel uartRow = row(new JLabel(Text.t("UART:")), port, scanBtn, new JLabel(Text.t("Baud:")), baud,
                new JLabel(Text.t("Enter sends:")), enter, echo, uartBtn);
        JPanel sshRow = row(new JLabel("SSH:"), new JLabel(Text.t("Host:")), host, new JLabel(Text.t("Port:")), sshPort,
                new JLabel(Text.t("User:")), user, new JLabel(Text.t("Password:")), password, new JLabel(Text.t("Key:")), key,
                keyBtn, sshBtn);
        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(uartRow);
        top.add(sshRow);

        uartBtn.addActionListener(e -> {
            String name = String.valueOf(port.getEditor().getItem()).trim();
            if (name.isEmpty()) {
                status.setText(Text.t("Choose a serial port first (Scan lists the ones plugged in)."));
                return;
            }
            int rate;
            try {
                rate = Integer.parseInt(String.valueOf(baud.getEditor().getItem()).trim());
            } catch (NumberFormatException ex) {
                status.setText(Text.t("The baud rate must be a number."));
                return;
            }
            String nl = switch (enter.getSelectedIndex()) {
                case 1 -> "\n";
                case 2 -> "\r\n";
                default -> "\r";
            };
            try {
                Serial c = Serial.open(name, rate, nl, echo.isSelected());
                Settings.setSerialPort(name);
                addSession(name + " @ " + rate, c);
                status.setText(Text.t("%s is open at %d baud. Closing the tab closes the port (Upload needs it free).", name, rate));
            } catch (IOException ex) {
                status.setText(Text.t("Could not open %s: %s", name, ex.getMessage()));
            }
        });

        sshBtn.addActionListener(e -> {
            String h = host.getText().trim(), u = user.getText().trim();
            if (h.isEmpty() || u.isEmpty()) {
                status.setText(Text.t("Give the host and the user name."));
                return;
            }
            int p = (Integer) sshPort.getValue();
            char[] pw = password.getPassword();
            String k = key.getText().trim();
            sshBtn.setEnabled(false);
            status.setText(Text.t("Connecting to %s...", h));
            new SwingWorker<Ssh, Void>() {
                @Override protected Ssh doInBackground() throws Exception {
                    return Ssh.connect(TerminalWindow.this, h, p, u, pw, k);
                }

                @Override protected void done() {
                    sshBtn.setEnabled(true);
                    java.util.Arrays.fill(pw, '\0');
                    try {
                        Ssh s = get();
                        addSession(u + "@" + h, s);
                        status.setText(Text.t("Connected to %s.", h));
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() == null ? ex : ex.getCause();
                        status.setText(Text.t("Could not connect to %s: %s", h, String.valueOf(c.getMessage())));
                    }
                }
            }.execute();
        });

        JPanel empty = new JPanel(new GridBagLayout());
        empty.add(new JLabel("<html><div style='width:420px'>" + Text.t("Open a board's serial port (UART), or connect to another computer over SSH. Each session gets a tab; closing the tab ends it.") + "</div></html>"));
        JPanel center = new JPanel(new CardLayout());
        center.add(empty, "empty");
        center.add(tabs, "tabs");
        tabs.addChangeListener(e -> ((CardLayout) center.getLayout()).show(center, tabs.getTabCount() == 0 ? "empty" : "tabs"));

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        p.add(top, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);
        p.add(status, BorderLayout.SOUTH);
        setContentPane(p);
        setSize(1200, 680);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                while (tabs.getTabCount() > 0) closeTab(0);
            }
        });
    }

    private static JPanel row(Component... cs) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Component c : cs) r.add(c);
        return r;
    }

    private static String defaultKey() {
        Path ssh = Path.of(System.getProperty("user.home"), ".ssh");
        for (String n : new String[]{"id_ed25519", "id_ecdsa", "id_rsa"}) {
            if (Files.isRegularFile(ssh.resolve(n))) return ssh.resolve(n).toString();
        }
        return "";
    }

    /** A tab with a terminal on the connection, and a close button. */
    private void addSession(String title, TtyConnector c) {
        JediTermWidget w = new JediTermWidget(100, 30, new Look());
        w.setTtyConnector(c);
        w.start();
        tabs.addTab(title, w);
        int i = tabs.indexOfComponent(w);
        JPanel tab = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tab.setOpaque(false);
        tab.add(new JLabel(title));
        JButton x = new JButton("x");
        x.setMargin(new Insets(0, 4, 0, 4));
        x.setToolTipText(Text.t("Close this session"));
        x.addActionListener(e -> closeTab(tabs.indexOfComponent(w)));
        tab.add(x);
        tabs.setTabComponentAt(i, tab);
        tabs.setSelectedIndex(i);
        w.requestFocusInWindow();
    }

    private void closeTab(int i) {
        if (i < 0) return;
        Component c = tabs.getComponentAt(i);
        tabs.removeTabAt(i);
        if (c instanceof JediTermWidget w) {
            TtyConnector t = w.getTtyConnector();
            w.close();
            if (t != null) t.close();
        }
    }

    /** The terminal in EVIA Paradigma's colours and font. */
    private static final class Look extends DefaultSettingsProvider {
        @Override public TerminalColor getDefaultForeground() {
            Color c = Theme.consoleFg();
            return TerminalColor.rgb(c.getRed(), c.getGreen(), c.getBlue());
        }

        @Override public TerminalColor getDefaultBackground() {
            Color c = Theme.consoleBg();
            return TerminalColor.rgb(c.getRed(), c.getGreen(), c.getBlue());
        }

        @Override public com.jediterm.terminal.TextStyle getDefaultStyle() {
            return new com.jediterm.terminal.TextStyle(getDefaultForeground(), getDefaultBackground());
        }

        @Override public Font getTerminalFont() {
            return Fonts.mono(Font.PLAIN, getTerminalFontSize());
        }

        @Override public float getTerminalFontSize() {
            return 14;
        }
    }

    // ================================================================ connections

    /** Reads characters from a stream for the terminal; a closed connection reads as the end. */
    private abstract static class Stream implements TtyConnector {
        protected Reader in;
        protected OutputStream out;
        protected volatile boolean open = true;

        @Override public int read(char[] buf, int offset, int length) throws IOException {
            return in.read(buf, offset, length);
        }

        @Override public void write(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        @Override public void write(String s) throws IOException {
            write(s.getBytes(StandardCharsets.UTF_8));
        }

        @Override public boolean isConnected() {
            return open;
        }

        @Override public boolean ready() throws IOException {
            return in.ready();
        }

        @Override public int waitFor() throws InterruptedException {
            while (open) Thread.sleep(200);
            return 0;
        }
    }

    /** A serial port: 8N1 at the chosen rate; Enter sends CR, LF or CR LF; local echo if the device does not echo. */
    static final class Serial extends Stream {
        private final SerialPort port;
        private final String name, newline;
        private final boolean echo;
        private final ConcurrentLinkedQueue<Character> echoed = new ConcurrentLinkedQueue<>();
        private final InputStream raw;

        private Serial(SerialPort port, String name, String newline, boolean echo) {
            this.port = port;
            this.name = name;
            this.newline = newline;
            this.echo = echo;
            this.raw = port.getInputStream();
            this.in = new InputStreamReader(raw, StandardCharsets.UTF_8);
            this.out = port.getOutputStream();
        }

        static Serial open(String name, int baud, String newline, boolean echo) throws IOException {
            SerialPort p = SerialPort.getCommPort(name);
            p.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 50, 0);
            if (!p.openPort()) {
                throw new IOException(Text.t("the port is busy, missing, or needs permission")
                        + (ExternalEditor.WINDOWS || System.getProperty("os.name", "").toLowerCase().contains("mac") ? ""
                        : " (Linux: add yourself to the dialout group, then log in again)"));
            }
            return new Serial(p, name, newline, echo);
        }

        @Override public int read(char[] buf, int offset, int length) throws IOException {
            while (open) {
                int n = 0;
                Character c;
                while (n < length && (c = echoed.poll()) != null) buf[offset + n++] = c;
                if (n > 0) return n;
                try {
                    if (raw.available() <= 0 && !in.ready()) {
                        Thread.sleep(20);                    // the echo queue is looked at again soon
                        if (!port.isOpen()) break;
                        continue;
                    }
                    return in.read(buf, offset, length);
                } catch (SerialPortTimeoutException e) {
                    // nothing arrived yet
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return -1;
        }

        @Override public void write(byte[] bytes) throws IOException {
            String s = new String(bytes, StandardCharsets.UTF_8);
            if (s.equals("\r")) s = newline;               // the Enter key
            if (echo) for (char ch : (s.equals(newline) ? "\r\n" : s).toCharArray()) echoed.add(ch == 127 ? '\b' : ch);
            super.write(s.getBytes(StandardCharsets.UTF_8));
        }

        @Override public String getName() {
            return name;
        }

        @Override public void close() {
            open = false;
            port.closePort();
        }
    }

    /** An SSH session with a shell on an xterm-256color terminal; the window size follows the tab. */
    static final class Ssh extends Stream {
        private final Session session;
        private final ChannelShell shell;
        private final String name;

        private Ssh(Session session, ChannelShell shell, String name) throws IOException {
            this.session = session;
            this.shell = shell;
            this.name = name;
            this.in = new InputStreamReader(shell.getInputStream(), StandardCharsets.UTF_8);
            this.out = shell.getOutputStream();
        }

        static Ssh connect(Component parent, String host, int port, String user, char[] password, String key) throws Exception {
            JSch jsch = new JSch();
            Path known = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
            Files.createDirectories(known.getParent());
            if (!Files.exists(known)) Files.createFile(known);
            jsch.setKnownHosts(known.toString());
            String k = key.startsWith("~") ? System.getProperty("user.home") + key.substring(1) : key;
            if (!k.isEmpty() && Files.isRegularFile(Path.of(k))) {
                try {
                    jsch.addIdentity(k);
                } catch (JSchException e) {
                    // a key with a passphrase: ask for it
                    String pass = ask(parent, Text.t("Passphrase of the key %s:", k));
                    if (pass == null) throw new IOException(Text.t("cancelled"));
                    jsch.addIdentity(k, pass);
                }
            }
            Session s = jsch.getSession(user, host, port);
            if (password.length > 0) s.setPassword(new String(password));
            s.setConfig("StrictHostKeyChecking", "ask");
            s.setUserInfo(new Asker(parent));
            s.setServerAliveInterval(30_000);
            s.connect(20_000);
            ChannelShell ch = (ChannelShell) s.openChannel("shell");
            ch.setPtyType("xterm-256color", 100, 30, 0, 0);
            Ssh ssh = new Ssh(s, ch, user + "@" + host);
            ch.connect(10_000);
            return ssh;
        }

        @Override public int read(char[] buf, int offset, int length) throws IOException {
            int n = super.read(buf, offset, length);
            if (n < 0) open = false;
            return n;
        }

        @Override public void resize(TermSize size) {
            shell.setPtySize(size.getColumns(), size.getRows(), 0, 0);
        }

        @Override public boolean isConnected() {
            return open && shell.isConnected();
        }

        @Override public String getName() {
            return name;
        }

        @Override public void close() {
            open = false;
            shell.disconnect();
            session.disconnect();
        }
    }

    /** Asks the questions of an SSH connection on the event thread: the host key, a password. */
    private record Asker(Component parent) implements UserInfo {
        @Override public String getPassphrase() {
            return null;
        }

        /** The answer to the last promptPassword (JSch asks, then reads it). */
        private static volatile String last;

        @Override public String getPassword() {
            return last;
        }

        @Override public boolean promptPassword(String message) {
            last = ask(parent, message);
            return last != null;
        }

        @Override public boolean promptPassphrase(String message) {
            return false;
        }

        @Override public boolean promptYesNo(String message) {
            int[] r = {JOptionPane.NO_OPTION};
            onEdt(() -> r[0] = JOptionPane.showConfirmDialog(parent, message, "SSH", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE));
            return r[0] == JOptionPane.YES_OPTION;
        }

        @Override public void showMessage(String message) {
            onEdt(() -> JOptionPane.showMessageDialog(parent, message, "SSH", JOptionPane.INFORMATION_MESSAGE));
        }
    }

    /** A password-style question on the event thread; null when cancelled. */
    private static String ask(Component parent, String message) {
        String[] r = {null};
        onEdt(() -> {
            JPasswordField f = new JPasswordField(24);
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.add(new JLabel(message), BorderLayout.NORTH);
            p.add(f, BorderLayout.CENTER);
            JOptionPane pane = new JOptionPane(p, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            JDialog d = pane.createDialog(parent, "SSH");
            d.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override public void windowGainedFocus(java.awt.event.WindowEvent e) {
                    f.requestFocusInWindow();
                }
            });
            d.setVisible(true);
            if (Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue())) r[0] = new String(f.getPassword());
        });
        return r[0];
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception e) {
            // the window went away
        }
    }
}
