// ===================== EVIA Paradigma runtime (Kotlin) =====================

/** An error the interpreter reports too: the program stops with ">> error: ..." and exit code 1. */
class EviaError(message: String) : RuntimeException(message, null, false, false)

/** The number in a text (Java's own rules, as the interpreter reads it), or null when it is not one. */
fun rt_parse(s: String): Double? =
    try {
        java.lang.Double.parseDouble(s)   // spaces around the number are allowed, as String.trim() removes them
    } catch (e: NumberFormatException) {
        null
    }

/** Text like the interpreter: whole numbers without ".0", others as Double.toString writes them. */
fun rt_text(v: Double): String =
    if (v == Math.rint(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

fun rt_print_line(text: String) {
    rt_write(text + "\n")
}

fun rt_read_line(name: String): String {
    rt_write("Enter a value for $name: ")
    var line = rt_read() ?: throw EviaError("input cancelled")
    if (line.endsWith("\r")) line = line.substring(0, line.length - 1)
    return line
}

/** Delay and Millis: milliseconds since the program started (Run again starts the count again), and waiting. */
var rt_start = System.nanoTime()

fun rt_millis(): Double = ((System.nanoTime() - rt_start) / 1_000_000L).toDouble()

fun rt_delay(ms: Double) {
    if (ms > 0) rt_sleep(ms.toLong())
}

/** A loop with no condition; not the constant true, so the code after it still counts as reachable. */
var rt_forever = true
