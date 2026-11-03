package evia.paradigma;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.UndoableEditEvent;
import javax.swing.text.*;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Vim-style modal editing for any Swing text component.
 *
 * <p>Supported: NORMAL / INSERT / VISUAL / VISUAL LINE modes; counts; motions
 * {@code h j k l w W b B e E ge 0 ^ $ gg G f F t T ; , % { } n N} and Enter/Backspace;
 * operators {@code d c y > <} with motions, doubled ({@code dd cc yy >> <<}) and text objects
 * {@code iw aw iW aW i" a" i' a' i( a( ib i{ a{ iB i[ a[};
 * {@code x X s S D C Y p P J r ~ u Ctrl-R .} and {@code i a I A o O};
 * registers {@code "a..."z} and {@code "+} (system clipboard); search {@code / ? * #};
 * ex commands {@code :N :$ :s/a/b/g :%s/a/b/gi :'<,'>s/...} plus whatever the host handles
 * ({@code :w :q :wq :make} in the code viewer). Patterns are Java regular expressions.
 */
final class VimKeys extends KeyAdapter {

    /** The component's owner: handles ex commands like :w and shows the status line. */
    interface Host {
        default boolean ex(String command) { return false; }
        void status(String text);
    }

    enum Mode { NORMAL, INSERT, VISUAL, VISUAL_LINE, CMDLINE }

    private static final char ESC = 27;
    private static final int SHIFT_WIDTH = 4;
    private static final int EXCL = 0, INCL = 1, LINE = 2;

    /** Thrown while a command is still being typed (e.g. after "d" or "f"). */
    private static final class Incomplete extends RuntimeException {
        Incomplete() { super(null, null, false, false); }
    }

    /** Thrown for keys that don't form a valid command. */
    private static final class Bad extends RuntimeException {
        Bad() { super(null, null, false, false); }
    }

    private record Motion(int pos, int kind) {}
    private record Range(int start, int end, boolean lines) {}

    private final JTextComponent t;
    private final Host host;
    private final boolean multiline;
    private final VimCaret caret = new VimCaret();
    private final Caret originalCaret;
    private boolean enabled = true;
    private Mode mode = Mode.NORMAL;

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder cmdline = new StringBuilder();
    private char cmdPrefix;
    private int visualAnchor, visualPos;
    private Object visualTag;
    private int lastVisualStartLine = -1, lastVisualEndLine = -1;
    private int goalCol = -1;
    private String message = "";

    private final java.util.Map<Character, String> registers = new java.util.HashMap<>();
    private final java.util.Map<Character, Boolean> registerLines = new java.util.HashMap<>();
    private String lastSearch;
    private boolean lastSearchForward = true;
    private char lastFindCmd, lastFindChar;

    private final UndoManager undo = new UndoManager();
    private Group group;
    private boolean recordUndo = true;

    private String lastChange = "";
    private String insertCmd;
    private final StringBuilder inserted = new StringBuilder();
    private int insertStart;
    private boolean replaying;

    private static final class Group extends CompoundEdit {
        boolean isEmpty() { return edits.isEmpty(); }
    }

    private VimKeys(JTextComponent t, Host host) {
        this.t = t;
        this.host = host;
        this.multiline = !(t instanceof JTextField);
        this.originalCaret = t.getCaret();
        undo.setLimit(2000);
    }

    /** Adds Vim editing to {@code t}. Small fields usually start in INSERT mode, code editors in NORMAL. */
    static VimKeys install(JTextComponent t, Host host, boolean startInInsert) {
        VimKeys v = new VimKeys(t, host);
        t.addKeyListener(v);
        v.caret.setBlinkRate(v.originalCaret != null ? v.originalCaret.getBlinkRate() : 500);
        t.setCaret(v.caret);
        t.getDocument().addUndoableEditListener(v::undoableEdit);
        t.getDocument().addDocumentListener(v.insertRecorder);
        t.addCaretListener(v::clampCaret);
        if (startInInsert) {
            v.beginGroup();
            v.enterInsert(null);
        } else {
            v.setMode(Mode.NORMAL);
        }
        return v;
    }

    /** Turns Vim mode on or off (off = ordinary Swing editing). */
    void setEnabled(boolean on) {
        enabled = on;
        pending.setLength(0);
        clearVisual();
        if (on) setMode(Mode.NORMAL);
        else {
            caret.block = false;
            t.repaint();
            host.status("");
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    Mode mode() {
        return mode;
    }

    /** Forget undo history (e.g. after the text was replaced programmatically). */
    void resetUndo() {
        undo.discardAllEdits();
        group = null;
    }

    /** Runs a document change (such as re-coloring) without recording it for undo. */
    void withoutUndo(Runnable r) {
        boolean old = recordUndo;
        recordUndo = false;
        try {
            r.run();
        } finally {
            recordUndo = old;
        }
    }

    // ================================================================ key handling

    @Override
    public void keyPressed(KeyEvent e) {
        if (!enabled) return;
        int k = e.getKeyCode();

        if (mode == Mode.CMDLINE) {
            e.consume();
            if (k == KeyEvent.VK_ENTER) runCmdline();
            else if (k == KeyEvent.VK_ESCAPE) setMode(Mode.NORMAL);
            else if (k == KeyEvent.VK_BACK_SPACE) {
                if (cmdline.isEmpty()) setMode(Mode.NORMAL);
                else cmdline.setLength(cmdline.length() - 1);
                updateStatus();
            }
            return;
        }

        boolean ctrlBracket = e.isControlDown() && k == KeyEvent.VK_OPEN_BRACKET;
        if (k == KeyEvent.VK_ESCAPE || ctrlBracket) {
            if (mode == Mode.INSERT) {
                e.consume();
                leaveInsert();
            } else if (mode == Mode.VISUAL || mode == Mode.VISUAL_LINE) {
                e.consume();
                exitVisual();
            } else if (!pending.isEmpty()) {
                e.consume();
                pending.setLength(0);
                updateStatus();
            }
            // Esc in NORMAL mode is left for the window (e.g. closes a dialog)
            return;
        }

        if (mode == Mode.INSERT) {
            if (k == KeyEvent.VK_ENTER && multiline && !e.isControlDown()) {
                e.consume();
                String text = text();
                int pos = t.getCaretPosition();
                t.replaceSelection("\n" + indentOf(text, lineStart(text, pos)));
            }
            return;
        }

        // NORMAL / VISUAL: map special keys to Vim keys, block Swing's own editing
        if (e.isControlDown() && k == KeyEvent.VK_R) {
            e.consume();
            if (mode == Mode.NORMAL) runCommand(() -> redo());
            return;
        }
        String mapped = switch (k) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_BACK_SPACE -> "h";
            case KeyEvent.VK_RIGHT -> "l";
            case KeyEvent.VK_UP -> "k";
            case KeyEvent.VK_DOWN -> "j";
            case KeyEvent.VK_HOME -> "0";
            case KeyEvent.VK_END -> "$";
            case KeyEvent.VK_DELETE -> "x";
            case KeyEvent.VK_ENTER -> multiline ? "+" : null;
            case KeyEvent.VK_TAB -> "";
            default -> null;
        };
        if (mapped == null) return;     // e.g. Enter in a one-line field: let the dialog accept
        if (e.isControlDown() || e.isMetaDown()) return;
        e.consume();
        for (char ch : mapped.toCharArray()) feed(ch);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        if (!enabled || mode == Mode.INSERT) return;
        char ch = e.getKeyChar();
        if ((e.isControlDown() && !e.isAltGraphDown()) || e.isMetaDown()) return;   // Ctrl+C etc. keep working
        e.consume();
        if (ch == KeyEvent.CHAR_UNDEFINED || ch < 32 || ch == 127) return;
        if (mode == Mode.CMDLINE) {
            cmdline.append(ch);
            updateStatus();
            return;
        }
        feed(ch);
    }

    /** Processes one key in NORMAL or VISUAL mode (also used to replay "."). */
    private void feed(char ch) {
        if (mode == Mode.INSERT) {          // only while replaying "."
            if (ch == ESC) leaveInsert();
            else t.replaceSelection(String.valueOf(ch));
            return;
        }
        message = "";
        pending.append(ch);
        String keys = pending.toString();
        boolean wasVisual = mode == Mode.VISUAL || mode == Mode.VISUAL_LINE;
        beginGroup();
        try {
            if (wasVisual) visual(keys);
            else normal(keys);
            pending.setLength(0);
        } catch (Incomplete x) {
            // wait for more keys
        } catch (Bad x) {
            pending.setLength(0);
            if (!replaying) UIManager.getLookAndFeel().provideErrorFeedback(t);
        } catch (BadLocationException x) {
            pending.setLength(0);
        }
        if (mode != Mode.INSERT) endGroup();
        updateStatus();
    }

    private void runCommand(Runnable r) {
        beginGroup();
        r.run();
        endGroup();
        updateStatus();
    }

    // ================================================================ NORMAL mode

    private void normal(String keys) throws BadLocationException {
        P p = new P(keys);
        Character reg = null;
        if (p.peek() == '"') {
            p.next();
            reg = p.next();
        }
        int n1 = p.count();
        int count = Math.max(1, n1);
        char c = p.next();
        String text = text();
        int pos = Math.min(t.getCaretPosition(), text.length());
        int ls = lineStart(text, pos), le = lineEnd(text, pos);

        switch (c) {
            case 'i' -> enterInsertAt(pos, keys);
            case 'a' -> enterInsertAt(pos < le ? pos + 1 : pos, keys);
            case 'I' -> enterInsertAt(firstNonBlank(text, ls), keys);
            case 'A' -> enterInsertAt(le, keys);
            case 'o', 'O' -> {
                if (!multiline) throw new Bad();
                String indent = indentOf(text, ls);
                if (c == 'o') {
                    insert(le, "\n" + indent);
                    enterInsertAt(le + 1 + indent.length(), keys);
                } else {
                    insert(ls, indent + "\n");
                    enterInsertAt(ls + indent.length(), keys);
                }
            }
            case 'v' -> enterVisual(false);
            case 'V' -> enterVisual(true);
            case 'x' -> {
                if (pos >= le) throw new Bad();
                apply('d', new Range(pos, Math.min(pos + count, le), false), reg, keys);
            }
            case 'X' -> {
                if (pos <= ls) throw new Bad();
                apply('d', new Range(Math.max(ls, pos - count), pos, false), reg, keys);
            }
            case 's' -> apply('c', new Range(pos, Math.min(pos + count, le), false), reg, keys);
            case 'S' -> apply('c', lineRange(text, pos, count), reg, keys);
            case 'D', 'C' -> {
                int endLine = Math.min(lineOf(text, pos) + count - 1, lineCount(text) - 1);
                int end = lineEnd(text, lineStartOf(text, endLine));
                apply(c == 'D' ? 'd' : 'c', new Range(pos, end, false), reg, keys);
            }
            case 'Y' -> apply('y', lineRange(text, pos, count), reg, keys);
            case 'p', 'P' -> {
                put(count, c == 'p', reg);
                lastChange = keys;
            }
            case 'J' -> {
                join(Math.max(2, count));
                lastChange = keys;
            }
            case 'r' -> {
                char r = p.next();
                if (pos + count > le) throw new Bad();
                replace(pos, pos + count, String.valueOf(r).repeat(count));
                setCaretNormal(pos + count - 1);
                lastChange = keys;
            }
            case '~' -> {
                int end = Math.min(pos + count, le);
                if (pos >= end) throw new Bad();
                replace(pos, end, toggleCase(text.substring(pos, end)));
                setCaretNormal(end);
                lastChange = keys;
            }
            case 'u' -> {
                for (int i = 0; i < count; i++) undoOnce();
            }
            case '.' -> repeatLastChange();
            case ':', '/', '?' -> startCmdline(c);
            case 'n', 'N' -> searchNext(c == 'n' == lastSearchForward, count, pos, true);
            case '*', '#' -> {
                String w = wordAt(text, pos);
                if (w == null) throw new Bad();
                lastSearch = "\\b" + Pattern.quote(w) + "\\b";
                lastSearchForward = c == '*';
                searchNext(lastSearchForward, count, pos, true);
            }
            case 'd', 'c', 'y', '<', '>' -> operator(c, n1, p, reg, keys, text, pos);
            default -> {
                p.back();
                Object m = motion(p, count, n1 > 0, pos, text, (char) 0);
                if (!(m instanceof Motion mo)) throw new Bad();
                setCaretNormal(mo.pos());
            }
        }
    }

    private void operator(char op, int n1, P p, Character reg, String keys, String text, int pos)
            throws BadLocationException {
        if (p.peek() == op) {
            p.next();
            apply(op, lineRange(text, pos, Math.max(1, n1)), reg, keys);
            return;
        }
        int n2 = p.count();
        int count = Math.max(1, n1) * Math.max(1, n2);
        Object m = motion(p, count, n1 > 0 || n2 > 0, pos, text, op);
        Range r;
        if (m instanceof Range rr) {
            r = rr;
        } else {
            Motion mo = (Motion) m;
            if (mo.kind() == LINE) {
                int a = Math.min(pos, mo.pos()), b = Math.max(pos, mo.pos());
                r = linesBetween(text, a, b);
            } else {
                int a = Math.min(pos, mo.pos()), b = Math.max(pos, mo.pos());
                if (mo.kind() == INCL) b = Math.min(b + 1, text.length());
                r = new Range(a, b, false);
            }
        }
        apply(op, r, reg, keys);
    }

    /** Applies operator d / c / y / &gt; / &lt; / ~ to a range. */
    private void apply(char op, Range r, Character reg, String keys) throws BadLocationException {
        String text = text();
        int start = r.start(), end = Math.min(r.end(), text.length());
        if (op == '>' || op == '<') {
            shift(r, op == '>');
            lastChange = keys;
            return;
        }
        if (op == '~') {
            replace(start, end, toggleCase(text.substring(start, end)));
            setCaretNormal(start);
            return;
        }
        String yanked = text.substring(start, end);
        if (r.lines() && !yanked.endsWith("\n")) yanked += "\n";
        store(reg, yanked, r.lines(), op != 'y');

        switch (op) {
            case 'y' -> {
                if (!r.lines()) setCaretNormal(start);
                int n = r.lines() ? (int) yanked.chars().filter(ch -> ch == '\n').count() : 0;
                if (n > 2) message = n + " lines yanked";
            }
            case 'd' -> {
                if (r.lines()) {
                    int s = start, e = end;
                    if (e >= text.length() && s > 0) s--;     // last line: remove the newline before it
                    remove(s, e - s);
                    String nt = text();
                    int at = Math.min(start, nt.length());
                    setCaretNormal(firstNonBlank(nt, lineStart(nt, at)));
                } else {
                    remove(start, end - start);
                    setCaretNormal(start);
                }
                lastChange = keys;
            }
            case 'c' -> {
                if (r.lines()) {
                    String indent = indentOf(text, start);
                    int e = end > start && text.charAt(end - 1) == '\n' ? end - 1 : end;
                    replace(start, e, indent);
                    enterInsertAt(start + indent.length(), keys);
                } else {
                    remove(start, end - start);
                    enterInsertAt(start, keys);
                }
            }
            default -> throw new Bad();
        }
    }

    // ================================================================ motions

    /** Parses a motion or text object at {@code p}. Returns a {@link Motion} or a {@link Range}. */
    private Object motion(P p, int count, boolean hasCount, int pos, String text, char op) {
        int len = text.length();
        int ls = lineStart(text, pos), le = lineEnd(text, pos);
        char m = p.next();
        boolean keepGoal = false;
        Motion result;
        switch (m) {
            case 'h' -> result = new Motion(Math.max(ls, pos - count), EXCL);
            case 'l', ' ' -> result = new Motion(Math.min(op != 0 ? le : Math.max(ls, le - 1), pos + count), EXCL);
            case '0' -> result = new Motion(ls, EXCL);
            case '^' -> result = new Motion(firstNonBlank(text, ls), EXCL);
            case '$' -> {
                int line = Math.min(lineOf(text, pos) + count - 1, lineCount(text) - 1);
                int e = lineEnd(text, lineStartOf(text, line));
                int s = lineStartOf(text, line);
                result = new Motion(Math.max(s, e - 1), INCL);
                goalCol = Integer.MAX_VALUE;
                keepGoal = true;
            }
            case 'j', 'k' -> {
                if (!multiline) throw new Bad();
                int line = lineOf(text, pos) + (m == 'j' ? count : -count);
                if (line < 0 || line >= lineCount(text)) throw new Bad();
                if (goalCol < 0) goalCol = pos - ls;
                int s = lineStartOf(text, line), e = lineEnd(text, s);
                int col = goalCol == Integer.MAX_VALUE ? Integer.MAX_VALUE : goalCol;
                int target = (int) Math.min((long) s + col, Math.max(s, op != 0 ? e : e - 1));
                result = new Motion(Math.max(s, target), LINE);
                keepGoal = true;
            }
            case '+', '-' -> {
                if (!multiline) throw new Bad();
                int line = lineOf(text, pos) + (m == '+' ? count : -count);
                if (line < 0 || line >= lineCount(text)) throw new Bad();
                result = new Motion(firstNonBlank(text, lineStartOf(text, line)), LINE);
            }
            case 'G' -> {
                int line = hasCount ? Math.min(count, lineCount(text)) - 1 : lineCount(text) - 1;
                result = new Motion(firstNonBlank(text, lineStartOf(text, line)), LINE);
            }
            case 'g' -> {
                char g = p.next();
                if (g == 'g') {
                    int line = hasCount ? Math.min(count, lineCount(text)) - 1 : 0;
                    result = new Motion(firstNonBlank(text, lineStartOf(text, line)), LINE);
                } else if (g == 'e' || g == 'E') {
                    int q = pos;
                    for (int i = 0; i < count; i++) q = wordEndBackward(text, q, g == 'E');
                    result = new Motion(q, INCL);
                } else {
                    throw new Bad();
                }
            }
            case 'w', 'W' -> {
                boolean big = m == 'W';
                if (op == 'c' && pos < len && !Character.isWhitespace(text.charAt(pos))) {
                    // "cw" changes to the end of the word, like "ce"
                    int q = pos;
                    for (int i = 0; i < count; i++) q = i == 0 && atWordEnd(text, q, big) ? q : wordEnd(text, q, big);
                    result = new Motion(q, INCL);
                } else {
                    int q = pos;
                    for (int i = 0; i < count; i++) q = wordForward(text, q, big);
                    if (op != 0) {
                        // an operator never crosses into the next line's leading word
                        int lsq = lineStart(text, q);
                        if (lsq > pos && text.substring(lsq, q).isBlank()) q = Math.max(pos, lsq - 1);
                    }
                    result = new Motion(q, EXCL);
                }
            }
            case 'b', 'B' -> {
                int q = pos;
                for (int i = 0; i < count; i++) q = wordBackward(text, q, m == 'B');
                result = new Motion(q, EXCL);
            }
            case 'e', 'E' -> {
                int q = pos;
                for (int i = 0; i < count; i++) q = wordEnd(text, q, m == 'E');
                result = new Motion(q, INCL);
            }
            case 'f', 'F', 't', 'T' -> {
                char ch = p.next();
                lastFindCmd = m;
                lastFindChar = ch;
                result = find(text, pos, m, ch, count);
            }
            case ';', ',' -> {
                if (lastFindCmd == 0) throw new Bad();
                char cmd = lastFindCmd;
                if (m == ',') cmd = switch (cmd) { case 'f' -> 'F'; case 'F' -> 'f'; case 't' -> 'T'; default -> 't'; };
                result = find(text, pos, cmd, lastFindChar, count);
            }
            case '%' -> result = new Motion(matchBracket(text, pos), INCL);
            case '}' -> {
                int q = pos;
                for (int i = 0; i < count; i++) q = paragraph(text, q, true);
                result = new Motion(q, EXCL);
            }
            case '{' -> {
                int q = pos;
                for (int i = 0; i < count; i++) q = paragraph(text, q, false);
                result = new Motion(q, EXCL);
            }
            case 'n', 'N' -> {
                int q = searchFrom(text, pos, m == 'n' == lastSearchForward, count);
                if (q < 0) throw new Bad();
                result = new Motion(q, EXCL);
            }
            case 'i', 'a' -> {
                if (op == 0) throw new Bad();
                return textObject(text, pos, p.next(), m == 'a');
            }
            default -> throw new Bad();
        }
        if (!keepGoal) goalCol = -1;
        return result;
    }

    private Motion find(String text, int pos, char cmd, char ch, int count) {
        int ls = lineStart(text, pos), le = lineEnd(text, pos);
        int q = pos;
        for (int i = 0; i < count; i++) {
            if (cmd == 'f' || cmd == 't') {
                int from = q + 1 + (cmd == 't' && i == 0 && q + 1 < le && text.charAt(q + 1) == ch ? 1 : 0);
                int f = text.indexOf(ch, from);
                if (f < 0 || f >= le) throw new Bad();
                q = f;
            } else {
                int from = q - 1 - (cmd == 'T' && i == 0 && q - 1 >= ls && text.charAt(q - 1) == ch ? 1 : 0);
                int f = from < ls ? -1 : text.lastIndexOf(ch, from);
                if (f < ls) throw new Bad();
                q = f;
            }
        }
        if (cmd == 't') q--;
        if (cmd == 'T') q++;
        return new Motion(q, cmd == 'f' || cmd == 't' ? INCL : EXCL);
    }

    private Range textObject(String text, int pos, char obj, boolean around) {
        switch (obj) {
            case 'w', 'W' -> {
                boolean big = obj == 'W';
                if (pos >= text.length()) throw new Bad();
                int cls = cls(text.charAt(pos), big);
                int s = pos, e = pos;
                while (s > 0 && cls(text.charAt(s - 1), big) == cls && text.charAt(s - 1) != '\n') s--;
                while (e < text.length() && cls(text.charAt(e), big) == cls && text.charAt(e) != '\n') e++;
                if (around) {
                    int e2 = e;
                    while (e2 < text.length() && (text.charAt(e2) == ' ' || text.charAt(e2) == '\t')) e2++;
                    if (e2 > e) e = e2;
                    else while (s > 0 && (text.charAt(s - 1) == ' ' || text.charAt(s - 1) == '\t')) s--;
                }
                return new Range(s, e, false);
            }
            case '"', '\'', '`' -> {
                int ls = lineStart(text, pos), le = lineEnd(text, pos);
                int open = -1, close = -1;
                for (int i = ls; i < le; i++) {
                    if (text.charAt(i) != obj || (i > ls && text.charAt(i - 1) == '\\')) continue;
                    if (open < 0) open = i;
                    else {
                        if (pos >= open && pos <= i || open > pos) { close = i; break; }
                        open = -1;
                    }
                }
                if (open < 0 || close < 0) throw new Bad();
                return around ? new Range(open, close + 1, false) : new Range(open + 1, close, false);
            }
            case '(', ')', 'b', '{', '}', 'B', '[', ']', '<', '>' -> {
                char o = switch (obj) { case '{', '}', 'B' -> '{'; case '[', ']' -> '['; case '<', '>' -> '<'; default -> '('; };
                char c = switch (o) { case '{' -> '}'; case '[' -> ']'; case '<' -> '>'; default -> ')'; };
                int depth = 0, open = -1;
                for (int i = pos; i >= 0; i--) {
                    char ch = text.charAt(Math.min(i, text.length() - 1));
                    if (i < text.length() && ch == c && i != pos) depth++;
                    else if (i < text.length() && ch == o) {
                        if (depth == 0) { open = i; break; }
                        depth--;
                    }
                }
                if (open < 0) throw new Bad();
                int close = matchBracket(text, open);
                if (close <= open) throw new Bad();
                return around ? new Range(open, close + 1, false) : new Range(open + 1, close, false);
            }
            default -> throw new Bad();
        }
    }

    // ================================================================ VISUAL mode

    private void enterVisual(boolean lines) {
        visualAnchor = visualPos = t.getCaretPosition();
        setMode(lines ? Mode.VISUAL_LINE : Mode.VISUAL);
        showVisual();
    }

    private void visual(String keys) throws BadLocationException {
        P p = new P(keys);
        Character reg = null;
        if (p.peek() == '"') {
            p.next();
            reg = p.next();
        }
        int n1 = p.count();
        int count = Math.max(1, n1);
        char c = p.next();
        String text = text();
        switch (c) {
            case 'v' -> {
                if (mode == Mode.VISUAL) exitVisual();
                else { setMode(Mode.VISUAL); showVisual(); }
            }
            case 'V' -> {
                if (mode == Mode.VISUAL_LINE) exitVisual();
                else { setMode(Mode.VISUAL_LINE); showVisual(); }
            }
            case 'o' -> {
                int a = visualAnchor;
                visualAnchor = visualPos;
                visualPos = a;
                showVisual();
            }
            case 'd', 'x', 'y', 'c', 's', '>', '<', '~', 'U', 'u', 'J', 'D', 'X', 'Y' -> {
                Range r = visualRange(text);
                rememberVisualLines(text);
                char op = switch (c) {
                    case 'x' -> 'd';
                    case 's' -> 'c';
                    case 'D', 'X' -> 'L';
                    case 'Y' -> 'Y';
                    default -> c;
                };
                clearVisual();
                setMode(Mode.NORMAL);
                switch (op) {
                    case 'L' -> apply('d', linesBetween(text, r.start(), Math.max(r.start(), r.end() - 1)), reg, "");
                    case 'Y' -> apply('y', linesBetween(text, r.start(), Math.max(r.start(), r.end() - 1)), reg, "");
                    case 'U', 'u' -> {
                        String s = text.substring(r.start(), r.end());
                        replace(r.start(), r.end(), op == 'U' ? s.toUpperCase() : s.toLowerCase());
                        setCaretNormal(r.start());
                    }
                    case 'J' -> {
                        t.setCaretPosition(r.start());
                        int lines = lineOf(text, Math.max(r.start(), r.end() - 1)) - lineOf(text, r.start()) + 1;
                        join(Math.max(2, lines));
                    }
                    default -> apply(op, r, reg, "");
                }
                lastChange = "";
            }
            case 'r' -> {
                char ch = p.next();
                Range r = visualRange(text);
                StringBuilder sb = new StringBuilder();
                for (int i = r.start(); i < r.end(); i++) sb.append(text.charAt(i) == '\n' ? '\n' : ch);
                clearVisual();
                setMode(Mode.NORMAL);
                replace(r.start(), r.end(), sb.toString());
                setCaretNormal(r.start());
            }
            case 'p', 'P' -> {
                Range r = visualRange(text);
                String content = regText(reg);
                clearVisual();
                setMode(Mode.NORMAL);
                replace(r.start(), r.end(), content);
                setCaretNormal(r.start() + Math.max(0, content.length() - 1));
            }
            case ':' -> {
                rememberVisualLines(text);
                clearVisual();
                setMode(Mode.NORMAL);
                startCmdline(':');
                cmdline.append("'<,'>");
                updateStatus();
            }
            default -> {
                p.back();
                Object m = motion(p, count, n1 > 0, visualPos, text, 'v');
                if (m instanceof Range r) {
                    visualAnchor = r.start();
                    visualPos = Math.max(r.start(), r.end() - 1);
                } else {
                    visualPos = Math.min(((Motion) m).pos(), Math.max(0, text.length() - 1));
                }
                showVisual();
            }
        }
    }

    private Range visualRange(String text) {
        int a = Math.min(visualAnchor, visualPos), b = Math.max(visualAnchor, visualPos);
        if (mode == Mode.VISUAL_LINE) return linesBetween(text, a, b);
        return new Range(a, Math.min(b + 1, text.length()), false);
    }

    private void rememberVisualLines(String text) {
        lastVisualStartLine = lineOf(text, Math.min(visualAnchor, visualPos));
        lastVisualEndLine = lineOf(text, Math.max(visualAnchor, visualPos));
    }

    private void showVisual() {
        clearVisualHighlight();
        Range r = visualRange(text());
        try {
            Color sel = t.getSelectionColor() != null ? t.getSelectionColor() : new Color(180, 200, 240);
            visualTag = t.getHighlighter().addHighlight(r.start(), r.end(),
                    new DefaultHighlighter.DefaultHighlightPainter(sel));
        } catch (BadLocationException ignored) {
            // nothing to show
        }
        t.setCaretPosition(Math.min(visualPos, text().length()));
    }

    private void exitVisual() {
        clearVisual();
        setMode(Mode.NORMAL);
        setCaretNormal(visualPos);
        updateStatus();
    }

    private void clearVisual() {
        clearVisualHighlight();
    }

    private void clearVisualHighlight() {
        if (visualTag != null) {
            t.getHighlighter().removeHighlight(visualTag);
            visualTag = null;
        }
    }

    // ================================================================ INSERT mode

    private void enterInsertAt(int pos, String keys) {
        t.setCaretPosition(Math.min(pos, text().length()));
        enterInsert(keys);
    }

    private void enterInsert(String keys) {
        insertCmd = keys;
        inserted.setLength(0);
        insertStart = t.getCaretPosition();
        setMode(Mode.INSERT);
    }

    private void leaveInsert() {
        if (!replaying && insertCmd != null) lastChange = insertCmd + inserted + ESC;
        insertCmd = null;
        setMode(Mode.NORMAL);
        endGroup();
        String text = text();
        int pos = t.getCaretPosition();
        if (pos > lineStart(text, pos)) pos--;
        setCaretNormal(pos);
        updateStatus();
    }

    /** Remembers what was typed during INSERT so "." can repeat it. */
    private final DocumentListener insertRecorder = new DocumentListener() {
        @Override public void insertUpdate(DocumentEvent e) {
            if (mode != Mode.INSERT || replaying) return;
            int rel = e.getOffset() - insertStart;
            if (rel < 0 || rel > inserted.length()) return;
            try {
                inserted.insert(rel, e.getDocument().getText(e.getOffset(), e.getLength()));
            } catch (BadLocationException ignored) {
                // ignore
            }
        }
        @Override public void removeUpdate(DocumentEvent e) {
            if (mode != Mode.INSERT || replaying) return;
            int rel = e.getOffset() - insertStart;
            if (rel < 0) {
                insertStart = Math.max(0, insertStart - e.getLength());
                return;
            }
            int end = Math.min(inserted.length(), rel + e.getLength());
            if (rel < end) inserted.delete(rel, end);
        }
        @Override public void changedUpdate(DocumentEvent e) {}
    };

    // ================================================================ command line, search, ex

    private void startCmdline(char prefix) {
        cmdPrefix = prefix;
        cmdline.setLength(0);
        setMode(Mode.CMDLINE);
        updateStatus();
    }

    private void runCmdline() {
        String cmd = cmdline.toString();
        char pre = cmdPrefix;
        setMode(Mode.NORMAL);
        beginGroup();
        try {
            if (pre == '/' || pre == '?') {
                if (!cmd.isEmpty()) lastSearch = cmd;
                lastSearchForward = pre == '/';
                searchNext(lastSearchForward, 1, t.getCaretPosition(), false);
            } else {
                ex(cmd.trim());
            }
        } finally {
            endGroup();
        }
        updateStatus();
    }

    private static final Pattern SUBST = Pattern.compile(
            "(%|'<,'>|\\.|\\$|\\d+(?:,(?:\\d+|\\$))?)?s/((?:\\\\/|[^/])*)/((?:\\\\/|[^/])*)(?:/([gi]*))?");

    private void ex(String cmd) {
        if (cmd.isEmpty()) return;
        String text = text();
        if (cmd.matches("\\d+") || cmd.equals("$")) {
            int line = cmd.equals("$") ? lineCount(text) - 1 : Math.max(0, Math.min(Integer.parseInt(cmd), lineCount(text)) - 1);
            setCaretNormal(firstNonBlank(text, lineStartOf(text, line)));
            return;
        }
        Matcher m = SUBST.matcher(cmd);
        if (m.matches()) {
            substitute(text, m.group(1), m.group(2).replace("\\/", "/"), m.group(3).replace("\\/", "/"),
                    m.group(4) == null ? "" : m.group(4));
            return;
        }
        if (cmd.equals("noh") || cmd.equals("nohlsearch")) return;
        if (!host.ex(cmd)) message = "E492: Not an editor command: " + cmd;
    }

    private void substitute(String text, String range, String pat, String rep, String flags) {
        int cur = lineOf(text, t.getCaretPosition());
        int from = cur, to = cur, last = lineCount(text) - 1;
        if (range != null) {
            switch (range) {
                case "%" -> { from = 0; to = last; }
                case "'<,'>" -> {
                    if (lastVisualStartLine < 0) { message = "E20: Mark not set"; return; }
                    from = lastVisualStartLine;
                    to = Math.min(lastVisualEndLine, last);
                }
                case "." -> { }
                case "$" -> from = to = last;
                default -> {
                    String[] parts = range.split(",");
                    from = Integer.parseInt(parts[0]) - 1;
                    to = parts.length > 1 ? (parts[1].equals("$") ? last : Integer.parseInt(parts[1]) - 1) : from;
                }
            }
        }
        if (pat.isEmpty()) {
            if (lastSearch == null) { message = "E35: No previous regular expression"; return; }
            pat = lastSearch;
        }
        Pattern p;
        try {
            p = Pattern.compile(pat, flags.contains("i") ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            message = "E486: Bad pattern: " + pat;
            return;
        }
        lastSearch = pat;
        // Vim replacement syntax -> Java: & = whole match, \1..\9 = groups
        String javaRep = rep.replace("$", "\\$").replaceAll("(?<!\\\\)&", "\\$0").replaceAll("\\\\(\\d)", "\\$$1");
        from = Math.max(0, from);
        to = Math.min(to, last);
        if (from > to) return;
        int s = lineStartOf(text, from), e = lineEnd(text, lineStartOf(text, to));
        StringBuilder out = new StringBuilder();
        int changed = 0;
        String[] lines = text.substring(s, e).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher mm = p.matcher(lines[i]);
            String nl = flags.contains("g") ? mm.replaceAll(javaRep) : mm.replaceFirst(javaRep);
            if (!nl.equals(lines[i])) changed++;
            out.append(i == 0 ? "" : "\n").append(nl);
        }
        if (changed == 0) {
            message = "E486: Pattern not found: " + pat;
            return;
        }
        try {
            replace(s, e, out.toString());
        } catch (BadLocationException ex) {
            return;
        }
        setCaretNormal(firstNonBlank(text(), lineStartOf(text(), to)));
        message = changed + " line" + (changed == 1 ? "" : "s") + " changed";
    }

    private void searchNext(boolean forward, int count, int pos, boolean bad) {
        if (lastSearch == null) {
            message = "E35: No previous regular expression";
            return;
        }
        int q = searchFrom(text(), pos, forward, count);
        if (q < 0) {
            message = "E486: Pattern not found: " + lastSearch;
            if (bad && !replaying) UIManager.getLookAndFeel().provideErrorFeedback(t);
            return;
        }
        setCaretNormal(q);
    }

    private int searchFrom(String text, int pos, boolean forward, int count) {
        if (lastSearch == null) return -1;
        Pattern p;
        try {
            p = Pattern.compile(lastSearch);
        } catch (PatternSyntaxException e) {
            p = Pattern.compile(Pattern.quote(lastSearch));
        }
        Matcher m = p.matcher(text);
        int q = pos;
        for (int i = 0; i < count; i++) {
            int found = -1;
            if (forward) {
                if (m.find(Math.min(q + 1, text.length()))) found = m.start();
                else if (m.find(0)) found = m.start();
            } else {
                int lastBefore = -1, lastAny = -1;
                m.reset();
                while (m.find()) {
                    if (m.start() < q) lastBefore = m.start();
                    lastAny = m.start();
                    if (m.end() == m.start() && m.end() >= text.length()) break;
                }
                found = lastBefore >= 0 ? lastBefore : lastAny;
            }
            if (found < 0) return -1;
            q = found;
        }
        return q;
    }

    // ================================================================ put, join, shift, undo, repeat

    private void put(int count, boolean after, Character reg) throws BadLocationException {
        String s = regText(reg);
        boolean lines = regLines(reg);
        if (s.isEmpty()) throw new Bad();
        String text = text();
        int pos = Math.min(t.getCaretPosition(), text.length());
        if (lines && multiline) {
            String block = s.repeat(count);
            if (!block.endsWith("\n")) block += "\n";
            int le = lineEnd(text, pos);
            if (after && le >= text.length()) {
                insert(text.length(), "\n" + block.substring(0, block.length() - 1));
                setCaretNormal(firstNonBlank(text(), text.length() + 1));
            } else {
                int at = after ? le + 1 : lineStart(text, pos);
                insert(at, block);
                setCaretNormal(firstNonBlank(text(), at));
            }
        } else {
            String chunk = (lines ? s.replace("\n", " ").strip() : s).repeat(count);
            int at = after && pos < lineEnd(text, pos) ? pos + 1 : pos;
            insert(at, chunk);
            setCaretNormal(at + chunk.length() - 1);
        }
    }

    private void join(int lines) throws BadLocationException {
        if (!multiline) throw new Bad();
        for (int i = 1; i < lines; i++) {
            String text = text();
            int pos = t.getCaretPosition();
            int le = lineEnd(text, pos);
            if (le >= text.length()) {
                if (i == 1) throw new Bad();
                break;
            }
            int next = le + 1, ns = next;
            while (ns < text.length() && (text.charAt(ns) == ' ' || text.charAt(ns) == '\t')) ns++;
            int te = le;
            while (te > lineStart(text, pos) && (text.charAt(te - 1) == ' ' || text.charAt(te - 1) == '\t')) te--;
            boolean noSpace = ns >= text.length() || text.charAt(ns) == '\n' || text.charAt(ns) == ')' || te == lineStart(text, pos);
            replace(te, ns, noSpace ? "" : " ");
            t.setCaretPosition(te);
        }
        setCaretNormal(t.getCaretPosition());
    }

    private void shift(Range r, boolean right) throws BadLocationException {
        String text = text();
        int first = lineOf(text, r.start()), last = lineOf(text, Math.max(r.start(), r.end() - 1));
        int count = last - first + 1;
        int times = 1;
        for (int line = first; line <= last; line++) {
            String nt = text();
            int s = lineStartOf(nt, line), e = lineEnd(nt, s);
            if (right) {
                if (e > s) insert(s, " ".repeat(SHIFT_WIDTH * times));
            } else {
                int n = 0;
                while (n < SHIFT_WIDTH * times && s + n < e && (nt.charAt(s + n) == ' ' || nt.charAt(s + n) == '\t')) n++;
                if (n > 0) remove(s, n);
            }
        }
        String nt = text();
        setCaretNormal(firstNonBlank(nt, lineStartOf(nt, first)));
        if (count > 2) message = count + " lines " + (right ? ">" : "<") + "ed 1 time";
    }

    private void undoOnce() {
        endGroup();
        if (undo.canUndo()) {
            undo.undo();
            setCaretNormal(t.getCaretPosition());
        } else {
            message = "Already at oldest change";
        }
    }

    private void redo() {
        if (undo.canRedo()) {
            undo.redo();
            setCaretNormal(t.getCaretPosition());
        } else {
            message = "Already at newest change";
        }
    }

    private void repeatLastChange() {
        if (lastChange.isEmpty() || replaying) throw new Bad();
        String keys = lastChange;
        replaying = true;
        try {
            pending.setLength(0);
            for (char ch : keys.toCharArray()) {
                if (mode == Mode.INSERT) {
                    if (ch == ESC) leaveInsert();
                    else t.replaceSelection(String.valueOf(ch));
                } else {
                    pending.append(ch);
                    try {
                        normal(pending.toString());
                        pending.setLength(0);
                    } catch (Incomplete x) {
                        // continue
                    } catch (BadLocationException | Bad x) {
                        pending.setLength(0);
                        break;
                    }
                }
            }
            if (mode == Mode.INSERT) leaveInsert();
        } finally {
            replaying = false;
            pending.setLength(0);
        }
    }

    // ================================================================ registers

    private void store(Character reg, String s, boolean lines, boolean delete) {
        if (reg != null && (reg == '+' || reg == '*')) {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
            } catch (Exception ignored) {
                // no clipboard
            }
        }
        char key = reg == null ? '"' : Character.toLowerCase(reg);
        if (reg != null && Character.isUpperCase(reg)) s = registers.getOrDefault(key, "") + s;   // "A appends
        registers.put(key, s);
        registerLines.put(key, lines);
        if (key != '"') { registers.put('"', s); registerLines.put('"', lines); }
        if (!delete) { registers.put('0', s); registerLines.put('0', lines); }
    }

    private String regText(Character reg) {
        if (reg != null && (reg == '+' || reg == '*')) {
            try {
                Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                return o == null ? "" : o.toString();
            } catch (Exception e) {
                return "";
            }
        }
        return registers.getOrDefault(reg == null ? '"' : Character.toLowerCase(reg), "");
    }

    private boolean regLines(Character reg) {
        if (reg != null && (reg == '+' || reg == '*')) return regText(reg).endsWith("\n");
        return registerLines.getOrDefault(reg == null ? '"' : Character.toLowerCase(reg), false);
    }

    // ================================================================ undo grouping

    private void undoableEdit(UndoableEditEvent e) {
        if (!recordUndo || !enabled) {
            if (!enabled) undo.addEdit(e.getEdit());
            return;
        }
        if (group != null) group.addEdit(e.getEdit());
        else undo.addEdit(e.getEdit());
    }

    private void beginGroup() {
        if (group == null) group = new Group();
    }

    private void endGroup() {
        if (group == null) return;
        Group g = group;
        group = null;
        g.end();
        if (!g.isEmpty()) undo.addEdit(g);
    }

    // ================================================================ document helpers

    private String text() {
        try {
            Document d = t.getDocument();
            return d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    private void insert(int at, String s) throws BadLocationException {
        t.getDocument().insertString(at, s, null);
    }

    private void remove(int at, int len) throws BadLocationException {
        if (len > 0) t.getDocument().remove(at, len);
    }

    private void replace(int start, int end, String s) throws BadLocationException {
        Document d = t.getDocument();
        if (d instanceof AbstractDocument ad) ad.replace(start, end - start, s, null);
        else {
            d.remove(start, end - start);
            d.insertString(start, s, null);
        }
    }

    /** Normal mode never rests on the line break, except on an empty line. */
    private void setCaretNormal(int pos) {
        String text = text();
        pos = Math.max(0, Math.min(pos, text.length()));
        if (mode != Mode.INSERT) {
            int ls = lineStart(text, pos), le = lineEnd(text, pos);
            if (pos >= le && le > ls) pos = le - 1;
        }
        t.setCaretPosition(pos);
    }

    private void clampCaret(javax.swing.event.CaretEvent e) {
        if (!enabled || mode != Mode.NORMAL || e.getDot() != e.getMark()) return;
        String text = text();
        int pos = e.getDot();
        if (pos > 0 && pos <= text.length() && pos == lineEnd(text, pos) && pos > lineStart(text, pos)) {
            SwingUtilities.invokeLater(() -> {
                if (t.getCaretPosition() == pos && mode == Mode.NORMAL) t.setCaretPosition(pos - 1);
            });
        }
    }

    static int lineStart(String s, int pos) {
        int i = s.lastIndexOf('\n', Math.min(pos, s.length()) - 1);
        return i + 1;
    }

    static int lineEnd(String s, int pos) {
        int i = s.indexOf('\n', pos);
        return i < 0 ? s.length() : i;
    }

    static int lineOf(String s, int pos) {
        int n = 0;
        for (int i = 0; i < Math.min(pos, s.length()); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    static int lineCount(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    static int lineStartOf(String s, int line) {
        int pos = 0;
        for (int i = 0; i < line; i++) {
            int nl = s.indexOf('\n', pos);
            if (nl < 0) return pos;
            pos = nl + 1;
        }
        return pos;
    }

    private static int firstNonBlank(String s, int ls) {
        int i = ls;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    private static String indentOf(String s, int ls) {
        return s.substring(ls, firstNonBlank(s, ls));
    }

    private Range lineRange(String text, int pos, int count) {
        int first = lineOf(text, pos);
        int last = Math.min(first + count - 1, lineCount(text) - 1);
        return linesBetween(text, pos, lineStartOf(text, last));
    }

    private static Range linesBetween(String text, int a, int b) {
        int s = lineStart(text, a), e = lineEnd(text, b);
        return new Range(s, Math.min(e + 1, text.length()), true);
    }

    /** 0 = whitespace, 1 = word characters, 2 = other punctuation (W/B/E: 1 = any non-blank). */
    private static int cls(char c, boolean big) {
        if (Character.isWhitespace(c)) return 0;
        if (big) return 1;
        return Character.isLetterOrDigit(c) || c == '_' ? 1 : 2;
    }

    private static int wordForward(String s, int pos, boolean big) {
        int n = s.length();
        if (pos >= n) return n;
        int c = cls(s.charAt(pos), big);
        int i = pos;
        if (c != 0) while (i < n && cls(s.charAt(i), big) == c) i++;
        while (i < n && cls(s.charAt(i), big) == 0) {
            if (s.charAt(i) == '\n' && i + 1 < n && s.charAt(i + 1) == '\n') return i + 1;   // empty line is a word
            i++;
        }
        return i;
    }

    private static int wordBackward(String s, int pos, boolean big) {
        int i = pos - 1;
        while (i > 0 && cls(s.charAt(i), big) == 0) i--;
        if (i <= 0) return 0;
        int c = cls(s.charAt(i), big);
        while (i > 0 && cls(s.charAt(i - 1), big) == c) i--;
        return i;
    }

    private static int wordEnd(String s, int pos, boolean big) {
        int n = s.length();
        int i = pos + 1;
        while (i < n && cls(s.charAt(i), big) == 0) i++;
        if (i >= n) return Math.max(0, n - 1);
        int c = cls(s.charAt(i), big);
        while (i + 1 < n && cls(s.charAt(i + 1), big) == c) i++;
        return i;
    }

    private static boolean atWordEnd(String s, int pos, boolean big) {
        if (pos + 1 >= s.length()) return true;
        int c = cls(s.charAt(pos), big);
        return c != 0 && cls(s.charAt(pos + 1), big) != c;
    }

    private static int wordEndBackward(String s, int pos, boolean big) {
        int i = pos;
        if (i >= s.length()) i = s.length() - 1;
        if (i <= 0) return 0;
        int c = cls(s.charAt(i), big);
        while (i > 0 && c != 0 && cls(s.charAt(i - 1), big) == c) i--;
        i--;
        while (i > 0 && cls(s.charAt(i), big) == 0) i--;
        return Math.max(0, i);
    }

    private static int paragraph(String s, int pos, boolean forward) {
        int line = lineOf(s, pos), lines = lineCount(s);
        int step = forward ? 1 : -1;
        int l = line + step;
        while (l >= 0 && l < lines && isBlankLine(s, l)) l += step;
        while (l >= 0 && l < lines && !isBlankLine(s, l)) l += step;
        if (l < 0) return 0;
        if (l >= lines) return s.length();
        return lineStartOf(s, l);
    }

    private static boolean isBlankLine(String s, int line) {
        int st = lineStartOf(s, line);
        return s.substring(st, lineEnd(s, st)).isBlank();
    }

    private static int matchBracket(String s, int pos) {
        int le = lineEnd(s, pos);
        int i = pos;
        while (i < le && "()[]{}".indexOf(s.charAt(i)) < 0) i++;
        if (i >= le) throw new Bad();
        char c = s.charAt(i);
        int k = "()[]{}".indexOf(c);
        char open = "([{".charAt(k / 2), close = ")]}".charAt(k / 2);
        int depth = 0;
        if (k % 2 == 0) {
            for (int j = i; j < s.length(); j++) {
                if (s.charAt(j) == open) depth++;
                else if (s.charAt(j) == close && --depth == 0) return j;
            }
        } else {
            for (int j = i; j >= 0; j--) {
                if (s.charAt(j) == close) depth++;
                else if (s.charAt(j) == open && --depth == 0) return j;
            }
        }
        throw new Bad();
    }

    private static String wordAt(String s, int pos) {
        if (pos >= s.length() || cls(s.charAt(pos), false) != 1) {
            int i = pos;
            while (i < s.length() && s.charAt(i) != '\n' && cls(s.charAt(i), false) != 1) i++;
            if (i >= s.length() || cls(s.charAt(i), false) != 1) return null;
            pos = i;
        }
        int a = pos, b = pos;
        while (a > 0 && cls(s.charAt(a - 1), false) == 1) a--;
        while (b < s.length() && cls(s.charAt(b), false) == 1) b++;
        return s.substring(a, b);
    }

    private static String toggleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return sb.toString();
    }

    // ================================================================ mode and status

    private void setMode(Mode m) {
        mode = m;
        caret.block = enabled && m != Mode.INSERT && m != Mode.CMDLINE;
        t.repaint();
        updateStatus();
    }

    private void updateStatus() {
        if (!enabled) return;
        String s = switch (mode) {
            case INSERT -> "-- INSERT --";
            case VISUAL -> "-- VISUAL --";
            case VISUAL_LINE -> "-- VISUAL LINE --";
            case CMDLINE -> cmdPrefix + cmdline.toString();
            case NORMAL -> message.isEmpty() ? "NORMAL" : message;
        };
        if (mode != Mode.CMDLINE && !pending.isEmpty()) s += "    " + pending;
        if (mode != Mode.CMDLINE && mode != Mode.NORMAL && !message.isEmpty()) s += "    " + message;
        if (mode == Mode.NORMAL && !message.isEmpty() && !pending.isEmpty()) s = message + "    " + pending;
        host.status(s);
    }

    /** Small cursor over the key string being parsed. */
    private static final class P {
        final String s;
        int i;

        P(String s) {
            this.s = s;
        }

        char next() {
            if (i >= s.length()) throw new Incomplete();
            return s.charAt(i++);
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : 0;
        }

        void back() {
            i--;
        }

        int count() {
            int start = i, n = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i)) && !(i == start && s.charAt(i) == '0')) {
                n = n * 10 + (s.charAt(i) - '0');
                i++;
            }
            return n;
        }
    }

    // ================================================================ block caret

    /** Block cursor in NORMAL/VISUAL mode, thin line in INSERT mode. */
    static final class VimCaret extends DefaultCaret {
        boolean block = true;

        @Override
        public void paint(Graphics g) {
            if (!block) {
                super.paint(g);
                return;
            }
            JTextComponent c = getComponent();
            if (c == null || !isVisible()) return;
            try {
                Rectangle r = c.modelToView2D(getDot()).getBounds();
                FontMetrics fm = c.getFontMetrics(c.getFont());
                int w = fm.charWidth('m');
                if (getDot() < c.getDocument().getLength()) {
                    char ch = c.getDocument().getText(getDot(), 1).charAt(0);
                    if (ch != '\n' && ch != '\t') w = Math.max(2, fm.charWidth(ch));
                }
                g.setColor(c.getCaretColor());
                g.setXORMode(c.getBackground());
                g.fillRect(r.x, r.y, w, r.height);
                g.setPaintMode();
            } catch (BadLocationException ignored) {
                // nothing to paint
            }
        }

        @Override
        protected synchronized void damage(Rectangle r) {
            if (r == null) return;
            x = r.x;
            y = r.y;
            width = 24;
            height = r.height;
            repaint();
        }
    }
}
