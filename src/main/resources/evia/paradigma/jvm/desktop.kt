
// ---- the console: UTF-8 in and out, "\n" line ends, like the interpreter

val rt_out = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), false, "UTF-8")
val rt_in = java.io.BufferedReader(java.io.InputStreamReader(System.`in`, Charsets.UTF_8))

fun rt_write(text: String) {
    rt_out.print(text)
}

/** One line without its "\n", or null at the end of the input. A prompt is shown first. */
fun rt_read(): String? {
    rt_out.flush()
    val line = StringBuilder()
    var c = rt_in.read()
    if (c < 0) return null
    while (c >= 0 && c != '\n'.code) {
        line.append(c.toChar())
        c = rt_in.read()
    }
    return line.toString()
}

/** ">> error: ..." on stderr after the output so far, as the interpreter reports it. */
fun rt_error(message: String) {
    rt_out.flush()
    val err = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8")
    err.print(">> error: $message\n")
    err.flush()
}
