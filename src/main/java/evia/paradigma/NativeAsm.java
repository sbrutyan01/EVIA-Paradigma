package evia.paradigma;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An x86-64 machine-code writer: the few instructions the native back end needs, encoded by hand, with labels
 * in the code and in the data. Code and data are two separate byte arrays; {@link #link} places them at their
 * addresses and fills in every jump, call and RIP-relative reference.
 */
final class NativeAsm {

    // registers, by their encoding
    static final int RAX = 0, RCX = 1, RDX = 2, RBX = 3, RSP = 4, RBP = 5, RSI = 6, RDI = 7,
            R8 = 8, R9 = 9, R10 = 10, R11 = 11, R12 = 12, R13 = 13, R14 = 14, R15 = 15;

    /** Condition codes for jcc / setcc. */
    static final int O = 0, B = 2, AE = 3, E = 4, NE = 5, BE = 6, A = 7, P = 0xA, NP = 0xB,
            L = 0xC, GE = 0xD, LE = 0xE, G = 0xF;

    final ByteArrayOutputStream code = new ByteArrayOutputStream();
    final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private final Map<String, Integer> codeLabels = new HashMap<>();
    private final Map<String, Integer> dataLabels = new HashMap<>();
    /** rel32 fields in the code: position of the field, target label, true when the target is data. */
    private record Fixup(int at, String label) {}
    private final List<Fixup> fixups = new ArrayList<>();
    private int bss;   // zero bytes after the data
    private final Map<String, Long> bssLabels = new HashMap<>();
    private int unique;

    String newLabel(String hint) {
        return "." + hint + (unique++);
    }

    int here() {
        return code.size();
    }

    void label(String name) {
        if (codeLabels.put(name, code.size()) != null) throw new IllegalStateException("label defined twice: " + name);
    }

    // ================================================================ data

    void dataLabel(String name) {
        if (dataLabels.put(name, data.size()) != null) throw new IllegalStateException("data defined twice: " + name);
    }

    void dataAlign(int n) {
        while (data.size() % n != 0) data.write(0);
    }

    void data64(long v) {
        for (int i = 0; i < 8; i++) data.write((int) (v >>> (8 * i)));
    }

    void dataBytes(byte[] b) {
        data.writeBytes(b);
    }

    /** Zeroed memory after the data, 16-byte aligned. */
    void bss(String name, long size) {
        bss = (bss + 15) & ~15;
        bssLabels.put(name, (long) bss);
        bss += (int) size;
    }

    boolean isData(String name) {
        return dataLabels.containsKey(name) || bssLabels.containsKey(name);
    }

    // ================================================================ encoding helpers

    private void b(int x) {
        code.write(x & 0xFF);
    }

    private void d32(int v) {
        for (int i = 0; i < 4; i++) b(v >>> (8 * i));
    }

    private void rex(boolean w, int reg, int index, int base) {
        int r = 0x40 | (w ? 8 : 0) | ((reg & 8) != 0 ? 4 : 0) | ((index & 8) != 0 ? 2 : 0) | ((base & 8) != 0 ? 1 : 0);
        b(r);
    }

    private void rexOpt(boolean w, int reg, int base) {
        if (w || reg >= 8 || base >= 8) rex(w, reg, 0, base);
    }

    /** ModRM for a register and [base + disp32] (always the 32-bit displacement form). */
    private void memOperand(int reg, int base, int disp) {
        b(0x80 | ((reg & 7) << 3) | (base & 7));
        if ((base & 7) == RSP) b(0x24);
        d32(disp);
    }

    private void regOperand(int reg, int rm) {
        b(0xC0 | ((reg & 7) << 3) | (rm & 7));
    }

    // ================================================================ instructions

    void movImm(int r, long v) {                 // mov r64, imm64
        rex(true, 0, 0, r);
        b(0xB8 + (r & 7));
        for (int i = 0; i < 8; i++) b((int) (v >>> (8 * i)));
    }

    void mov(int dst, int src) {                 // mov dst, src
        rex(true, src, 0, dst);
        b(0x89);
        regOperand(src, dst);
    }

    void load(int dst, int base, int disp) {     // mov dst, [base+disp]
        rex(true, dst, 0, base);
        b(0x8B);
        memOperand(dst, base, disp);
    }

    void store(int base, int disp, int src) {    // mov [base+disp], src
        rex(true, src, 0, base);
        b(0x89);
        memOperand(src, base, disp);
    }

    void load8(int dst, int base, int disp) {    // movzx dst, byte [base+disp]
        rex(true, dst, 0, base);
        b(0x0F);
        b(0xB6);
        memOperand(dst, base, disp);
    }

    void store8(int base, int disp, int src) {   // mov byte [base+disp], src8
        rex(false, src, 0, base);                // a REX prefix makes sil/dil reachable and is harmless otherwise
        b(0x88);
        memOperand(src, base, disp);
    }

    void lea(int dst, int base, int disp) {
        rex(true, dst, 0, base);
        b(0x8D);
        memOperand(dst, base, disp);
    }

    /** lea dst, [rip + label]: the address of data or code. */
    void leaLabel(int dst, String label) {
        rex(true, dst, 0, 0);
        b(0x8D);
        b(((dst & 7) << 3) | 5);
        fixups.add(new Fixup(code.size(), label));
        d32(0);
    }

    private void alu(int op, int dst, int src) {  // op dst, src (r/m64, r64 form)
        rex(true, src, 0, dst);
        b(op);
        regOperand(src, dst);
    }

    void add(int dst, int src) { alu(0x01, dst, src); }
    void sub(int dst, int src) { alu(0x29, dst, src); }
    void and(int dst, int src) { alu(0x21, dst, src); }
    void or(int dst, int src) { alu(0x09, dst, src); }
    void xor(int dst, int src) { alu(0x31, dst, src); }
    void cmp(int a, int bReg) { alu(0x39, a, bReg); }
    void test(int a, int bReg) { alu(0x85, a, bReg); }

    void addImm(int r, int imm) {                // add r, imm32
        rex(true, 0, 0, r);
        b(0x81);
        regOperand(0, r);
        d32(imm);
    }

    void subImm(int r, int imm) {
        rex(true, 0, 0, r);
        b(0x81);
        regOperand(5, r);
        d32(imm);
    }

    void imul(int dst, int src) {
        rex(true, dst, 0, src);
        b(0x0F);
        b(0xAF);
        regOperand(dst, src);
    }

    void cqo() {
        b(0x48);
        b(0x99);
    }

    private void group3(int ext, int r) {
        rex(true, 0, 0, r);
        b(0xF7);
        regOperand(ext, r);
    }

    void idiv(int r) { group3(7, r); }
    void neg(int r) { group3(3, r); }
    void not(int r) { group3(2, r); }

    private void shiftCl(int ext, int r) {
        rex(true, 0, 0, r);
        b(0xD3);
        regOperand(ext, r);
    }

    void shlCl(int r) { shiftCl(4, r); }
    void shrCl(int r) { shiftCl(5, r); }
    void sarCl(int r) { shiftCl(7, r); }

    /** setcc al; movzx rax, al: the condition as 0 or 1 in rax. */
    void setcc(int cc) {
        b(0x0F);
        b(0x90 | cc);
        b(0xC0);
        b(0x48);
        b(0x0F);
        b(0xB6);
        b(0xC0);
    }

    /** setcc cl (for combining two conditions). */
    void setccCl(int cc) {
        b(0x0F);
        b(0x90 | cc);
        b(0xC1);
    }

    void andAlCl() {            // and al, cl
        b(0x20);
        b(0xC8);
    }

    void orAlCl() {             // or al, cl
        b(0x08);
        b(0xC8);
    }

    void movzxRaxAl() {
        b(0x48);
        b(0x0F);
        b(0xB6);
        b(0xC0);
    }

    void push(int r) {
        if (r >= 8) b(0x41);
        b(0x50 + (r & 7));
    }

    void pop(int r) {
        if (r >= 8) b(0x41);
        b(0x58 + (r & 7));
    }

    void ret() { b(0xC3); }

    void syscall() {
        b(0x0F);
        b(0x05);
    }

    void jmp(String label) {
        b(0xE9);
        fixups.add(new Fixup(code.size(), label));
        d32(0);
    }

    void jcc(int cc, String label) {
        b(0x0F);
        b(0x80 | cc);
        fixups.add(new Fixup(code.size(), label));
        d32(0);
    }

    void call(String label) {
        b(0xE8);
        fixups.add(new Fixup(code.size(), label));
        d32(0);
    }

    // ---- SSE2 scalar doubles (xmm0 and xmm1 only)

    void movqXmmFromReg(int xmm, int r) {        // movq xmm, r64
        b(0x66);
        rex(true, xmm, 0, r);
        b(0x0F);
        b(0x6E);
        regOperand(xmm, r);
    }

    void movqRegFromXmm(int r, int xmm) {        // movq r64, xmm
        b(0x66);
        rex(true, xmm, 0, r);
        b(0x0F);
        b(0x7E);
        regOperand(xmm, r);
    }

    private void sse(int prefix, int op, int dst, int src) {
        b(prefix);
        rexOpt(false, dst, src);
        b(0x0F);
        b(op);
        regOperand(dst, src);
    }

    void addsd(int d, int s) { sse(0xF2, 0x58, d, s); }
    void mulsd(int d, int s) { sse(0xF2, 0x59, d, s); }
    void subsd(int d, int s) { sse(0xF2, 0x5C, d, s); }
    void divsd(int d, int s) { sse(0xF2, 0x5E, d, s); }
    void sqrtsd(int d, int s) { sse(0xF2, 0x51, d, s); }
    void ucomisd(int a, int bReg) { sse(0x66, 0x2E, a, bReg); }

    void cvtsi2sd(int xmm, int r) {              // cvtsi2sd xmm, r64
        b(0xF2);
        rex(true, xmm, 0, r);
        b(0x0F);
        b(0x2A);
        regOperand(xmm, r);
    }

    void cvttsd2si(int r, int xmm) {             // cvttsd2si r64, xmm
        b(0xF2);
        rex(true, r, 0, xmm);
        b(0x0F);
        b(0x2C);
        regOperand(r, xmm);
    }

    /**
     * rax = fmod(rax, rcx) on doubles, exactly as Java's %: the x87 partial remainder, repeated until it is
     * complete. Uses 16 bytes of stack.
     */
    void fmodRaxRcx() {
        subImm(RSP, 16);
        store(RSP, 0, RCX);
        store(RSP, 8, RAX);
        b(0xDD); memOperand(0, RSP, 0);      // fld qword [rsp]      (divisor)
        b(0xDD); memOperand(0, RSP, 8);      // fld qword [rsp+8]    (dividend)
        String again = newLabel("fprem");
        label(again);
        b(0xD9); b(0xF8);                    // fprem
        b(0xDF); b(0xE0);                    // fnstsw ax
        b(0xF6); b(0xC4); b(0x04);           // test ah, 4 (C2: not finished)
        jcc(NE, again);
        b(0xDD); memOperand(3, RSP, 8);      // fstp qword [rsp+8]
        b(0xDD); b(0xD8);                    // fstp st(0)
        load(RAX, RSP, 8);
        addImm(RSP, 16);
    }

    // ================================================================ linking

    /** The code and data placed at their addresses, with every reference filled in. */
    record Linked(byte[] code, byte[] data, long bssSize, long entry) {}

    Linked link(long codeAddr, long dataAddr, String entry) {
        byte[] c = code.toByteArray();
        byte[] d = data.toByteArray();
        long bssAddr = dataAddr + ((d.length + 15) & ~15);
        for (Fixup f : fixups) {
            long target;
            if (codeLabels.containsKey(f.label())) target = codeAddr + codeLabels.get(f.label());
            else if (dataLabels.containsKey(f.label())) target = dataAddr + dataLabels.get(f.label());
            else if (bssLabels.containsKey(f.label())) target = bssAddr + bssLabels.get(f.label());
            else throw new IllegalStateException("undefined label " + f.label());
            long rel = target - (codeAddr + f.at() + 4);
            if (rel != (int) rel) throw new IllegalStateException("jump too far: " + f.label());
            for (int i = 0; i < 4; i++) c[f.at() + i] = (byte) (rel >>> (8 * i));
        }
        Integer e = codeLabels.get(entry);
        if (e == null) throw new IllegalStateException("no entry point " + entry);
        long bssTotal = ((d.length + 15) & ~15) - d.length + bss;
        return new Linked(c, d, bssTotal, codeAddr + e);
    }
}
