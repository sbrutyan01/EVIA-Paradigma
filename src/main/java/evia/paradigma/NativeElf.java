package evia.paradigma;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A Linux x86-64 executable (ELF64) from linked code and data: two loadable segments, the code read and execute,
 * the data (and the zeroed memory after it) read and write. No interpreter, no shared libraries: the program
 * talks to the kernel with system calls only, so it runs on any x86-64 Linux.
 */
final class NativeElf {
    private NativeElf() {}

    static final long BASE = 0x400000;
    private static final int PAGE = 0x1000, HEADERS = 64 + 2 * 56;

    /** Where the code starts in memory (right after the headers, in the first page). */
    static long codeAddr() {
        return BASE + HEADERS;
    }

    /** Where the data starts: the next page after the code, at the same offset within the page as in the file. */
    static long dataAddr(int codeSize) {
        long fileOff = align(HEADERS + codeSize, 16);
        return align(BASE + fileOff, PAGE) + PAGE + (fileOff % PAGE);
    }

    static byte[] write(NativeAsm.Linked l) {
        long fileOffData = align(HEADERS + l.code().length, 16);
        long dataAddr = dataAddr(l.code().length);
        int size = (int) (fileOffData + l.data().length);
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        // ELF header
        b.put(new byte[]{0x7F, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        b.putShort((short) 2);          // ET_EXEC
        b.putShort((short) 62);         // EM_X86_64
        b.putInt(1);                    // version
        b.putLong(l.entry());
        b.putLong(64);                  // program headers right after this header
        b.putLong(0);                   // no section headers
        b.putInt(0);                    // flags
        b.putShort((short) 64);
        b.putShort((short) 56);
        b.putShort((short) 2);
        b.putShort((short) 64);
        b.putShort((short) 0);
        b.putShort((short) 0);
        // code: offset 0, from the start of the file, R+X
        segment(b, 5, 0, BASE, HEADERS + l.code().length, HEADERS + l.code().length);
        // data and zeroed memory: R+W
        segment(b, 6, fileOffData, dataAddr, l.data().length, l.data().length + l.bssSize());
        b.position(HEADERS);
        b.put(l.code());
        b.position((int) fileOffData);
        b.put(l.data());
        return b.array();
    }

    private static void segment(ByteBuffer b, int flags, long off, long addr, long fileSize, long memSize) {
        b.putInt(1);                    // PT_LOAD
        b.putInt(flags);
        b.putLong(off);
        b.putLong(addr);
        b.putLong(addr);
        b.putLong(fileSize);
        b.putLong(memSize);
        b.putLong(PAGE);
    }

    private static long align(long v, long a) {
        return (v + a - 1) / a * a;
    }
}
