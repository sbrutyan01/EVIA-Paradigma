package evia.paradigma;
import java.nio.file.*;
import java.util.*;
/**
 * The native runtime's numbers against Java's: a small native program reads number texts and writes them back
 * (parse_text, then num_text); this writes it, the input (edge cases, random doubles, long decimals) and the
 * expected output, which is Expr.str(Double.parseDouble(text)) or ERR. scripts/check-native.sh compares them.
 *
 * Usage: java -cp jar:classes evia.paradigma.NumberCheck OUTPUT-EXECUTABLE [CASES]
 */
public class NumberCheck {
    static final String PROGRAM = """
// reads lines; prints num_text(parse_text(line)) or "ERR"
fn main() {
    runtime_init();
    while (1) {
        var c = in_byte();
        if (c < 0) { break; }
        var s = str_new(4096);
        var n = 0;
        while (c >= 0 && c != '\\n') {
            store8(s + 8 + n, c);
            n = n + 1;
            c = in_byte();
        }
        store64(s, n);
        var x = parse_text(s);
        if (parse_ok) { out_line(num_text(x)); } else { out_line("ERR"); }
    }
    exit_program(0);
}
""";

    public static void main(String[] a) throws Exception {
        NativeLang l = new NativeLang();
        l.add(NativeCompiler.read("common.er"), "common.er");
        l.add(PROGRAM, "numbers.er");
        NativeAsm asm = l.compile("main");
        NativeAsm.Linked k = asm.link(NativeElf.codeAddr(), NativeElf.dataAddr(asm.code.size()), "_start");
        Files.write(Path.of(a[0]), NativeElf.write(k));
        Path.of(a[0]).toFile().setExecutable(true);
        // test input and the expected output
        Random r = new Random(42);
        StringBuilder in = new StringBuilder(), exp = new StringBuilder();
        List<String> cases = new ArrayList<>(List.of("0", "-0", "1", "0.1", "0.2", "0.30000000000000004", "1e23", "9.999999999999999e22",
            "4.9e-324", "5e-324", "2.2250738585072014E-308", "2.2250738585072011e-308", "1.7976931348623157e308", "1.8e308",
            "123456789012345678901234567890", "0.001", "0.0009999", "1e7", "9999999.999", "1e15", "1e16", "-1.5", "NaN", "-Infinity",
            "  42  ", "3.14f", "2d", ".5", "5.", "abc", "", "1e", "--1", "0x10", "1e-400", "2.5e-324", "2.4703282292062328e-324",
            "100", "1234567.125", "0.1e1", "7e-10", "123e-20"));
        int cases_n = a.length > 1 ? Integer.parseInt(a[1]) : 100000;
        for (int i = 0; i < cases_n; i++) {
            long bits = r.nextLong();
            double d = Double.longBitsToDouble(bits);
            if (Double.isNaN(d)) continue;
            cases.add(Double.toString(d));
            cases.add(String.valueOf(r.nextInt(1000000) / Math.pow(10, r.nextInt(12))));
        }
        for (int i = 0; i < 2000; i++) {   // long, unrounded decimals
            StringBuilder sb = new StringBuilder();
            int n = 1 + r.nextInt(40);
            for (int j = 0; j < n; j++) sb.append((char) ('0' + r.nextInt(10)));
            sb.append('e').append(r.nextInt(640) - 340);
            cases.add(sb.toString());
        }
        for (String c : cases) {
            in.append(c).append('\n');
            String want;
            try { want = Expr.str(Double.parseDouble(c.trim())); } catch (NumberFormatException e) { want = "ERR"; }
            if (c.trim().startsWith("0x")) want = "ERR";   // hexadecimal floats: not read natively
            exp.append(want).append('\n');
        }
        Files.writeString(Path.of(a[0] + ".in"), in);
        Files.writeString(Path.of(a[0] + ".exp"), exp);
    }
}
