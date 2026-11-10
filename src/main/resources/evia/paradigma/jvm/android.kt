
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

/** Waiting in a Delay block; closing the app ends it. */
fun rt_sleep(ms: Long) {
    try {
        Thread.sleep(ms)
    } catch (e: InterruptedException) {
        throw RtStop()
    }
}

/** The newest reading of each sensor, kept by MainActivity (GPS_FIX is 0 until the position is known). */
val rt_sensors = java.util.concurrent.ConcurrentHashMap<String, Double>()

fun rt_sensor(name: String): Double = rt_sensors[name] ?: 0.0
