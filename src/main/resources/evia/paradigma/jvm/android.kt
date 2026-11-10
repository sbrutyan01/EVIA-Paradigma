
// ---- Android: Print goes to the screen and Input asks in a text field (see MainActivity at the end)

/** Where the program writes and reads: the activity on the screen while the program runs. */
interface RtScreen {
    fun write(text: String, error: Boolean)

    /** One line typed by the user, or null when the app is closing. */
    fun read(): String?
}

@Volatile var rt_screen: RtScreen? = null

fun rt_write(text: String) {
    rt_screen?.write(text, false)
}

fun rt_read(): String? = rt_screen?.read()

/** ">> error: ..." in red after the output so far, as the interpreter reports it. */
fun rt_error(message: String) {
    rt_screen?.write(">> error: $message\n", true)
}

/** Stops the program when the app closes (thrown from Print or Input). */
class RtStop : RuntimeException(null, null, false, false)
