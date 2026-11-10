
// ---- fast values: plain doubles, text only in Print, no checks (dividing by zero gives Infinity or NaN)

/** Input block (fast values): the line must be a number. */
fun rt_input_num(name: String): Double {
    val line = rt_read_line(name)
    return rt_parse(line) ?: throw EviaError("'$line' is not a number")
}
