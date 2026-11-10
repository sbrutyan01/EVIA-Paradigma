/**
 * The app's only screen: the program's output, a text field for Input, and Run Again. The program runs on
 * a thread of its own (with a large stack, for deep method calls), so the screen stays responsive.
 */
class MainActivity : android.app.Activity(), RtScreen, android.hardware.SensorEventListener,
    android.location.LocationListener {
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val lines = java.util.concurrent.LinkedBlockingQueue<String>()
    /** Text the program wrote that is not on the screen yet; guarded by itself. */
    private val pending = android.text.SpannableStringBuilder()
    private var flushPosted = false
    @Volatile private var closing = false
    private var worker: Thread? = null
    private lateinit var output: android.widget.TextView
    private lateinit var scroll: android.widget.ScrollView
    private lateinit var field: android.widget.EditText
    private lateinit var enter: android.widget.Button
    private lateinit var again: android.widget.Button
    private lateinit var status: android.widget.TextView

    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        title = @APP_NAME@
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val root = android.widget.LinearLayout(this)
        root.orientation = android.widget.LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        // Android 15 draws apps under the system bars: keep the content clear of them and of the keyboard
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom)
            insets
        }

        output = android.widget.TextView(this)
        output.typeface = android.graphics.Typeface.MONOSPACE
        output.textSize = 15f
        output.setTextIsSelectable(true)
        scroll = android.widget.ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        status = android.widget.TextView(this)
        status.textSize = 13f
        root.addView(status)

        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        field = android.widget.EditText(this)
        field.isSingleLine = true
        field.hint = "Input"
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        field.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        row.addView(field, android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        enter = android.widget.Button(this)
        enter.text = "OK"
        enter.setOnClickListener { submit() }
        row.addView(enter)
        again = android.widget.Button(this)
        again.text = "Run again"
        again.setOnClickListener { start() }
        row.addView(again)
        root.addView(row)

        setContentView(root)
        startSensors()
        start()
    }

    // ---- sensors: the accelerometer and gyroscope always, the position when the program reads it

    private var sensorManager: android.hardware.SensorManager? = null
    private var locationManager: android.location.LocationManager? = null

    private fun startSensors() {
        val sm = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        sensorManager = sm
        for (type in intArrayOf(android.hardware.Sensor.TYPE_ACCELEROMETER, android.hardware.Sensor.TYPE_GYROSCOPE)) {
            val s = sm.getDefaultSensor(type) ?: continue
            sm.registerListener(this, s, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        rt_sensors["GPS_FIX"] = 0.0
        if (!RT_GPS) return
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(fine) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startLocation()
        } else {
            requestPermissions(arrayOf(fine, android.Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) startLocation()
        else status.text = "Without the location permission GPS_FIX stays 0."
    }

    private fun startLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        locationManager = lm
        try {
            for (p in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                if (!lm.allProviders.contains(p)) continue
                lm.getLastKnownLocation(p)?.let { if (rt_sensors["GPS_FIX"] != 1.0) onLocationChanged(it) }
                lm.requestLocationUpdates(p, 1000L, 0f, this)
            }
        } catch (e: SecurityException) {
            status.text = "Without the location permission GPS_FIX stays 0."
        }
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            android.hardware.Sensor.TYPE_ACCELEROMETER -> {
                rt_sensors["ACCEL_X"] = round(v[0].toDouble(), 2)
                rt_sensors["ACCEL_Y"] = round(v[1].toDouble(), 2)
                rt_sensors["ACCEL_Z"] = round(v[2].toDouble(), 2)
            }
            android.hardware.Sensor.TYPE_GYROSCOPE -> {
                rt_sensors["GYRO_X"] = round(v[0].toDouble(), 3)
                rt_sensors["GYRO_Y"] = round(v[1].toDouble(), 3)
                rt_sensors["GYRO_Z"] = round(v[2].toDouble(), 3)
            }
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor, accuracy: Int) {}

    override fun onLocationChanged(location: android.location.Location) {
        // the GPS fix wins over a network position that arrives later
        if (location.provider != android.location.LocationManager.GPS_PROVIDER && rt_sensors["GPS_PROVIDER"] == 1.0) return
        rt_sensors["GPS_PROVIDER"] = if (location.provider == android.location.LocationManager.GPS_PROVIDER) 1.0 else 0.0
        rt_sensors["LATITUDE"] = round(location.latitude, 6)
        rt_sensors["LONGITUDE"] = round(location.longitude, 6)
        rt_sensors["ALTITUDE"] = round(location.altitude, 1)
        rt_sensors["GPS_ACCURACY"] = round(location.accuracy.toDouble(), 1)
        rt_sensors["GPS_FIX"] = 1.0
    }

    // Android 7 to 10 call these too (they have no default there)
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun round(v: Double, decimals: Int): Double {
        val f = Math.pow(10.0, decimals.toDouble())
        return Math.round(v * f) / f
    }

    /** Runs the program from the beginning: variables unassigned, objects new, output cleared. */
    private fun start() {
        output.text = ""
        lines.clear()
        waiting(false)
        again.isEnabled = false
        status.text = "Running..."
        rt_reset()
        rt_screen = this
        val t = Thread(null, { runProgram() }, "evia-program", 64L shl 20)
        worker = t
        t.start()
    }

    private fun runProgram() {
        try {
            Program().run()
        } catch (e: RtStop) {
            return
        } catch (e: EviaError) {
            rt_error(e.message ?: "")
        } catch (e: StackOverflowError) {
            rt_error("too many nested method calls (endless recursion?)")
        }
        ui.post {
            if (!closing) {
                flush()
                waiting(false)
                again.isEnabled = true
                status.text = "The program has finished."
            }
        }
    }

    override fun write(text: String, error: Boolean) {
        if (closing) throw RtStop()
        synchronized(pending) {
            val from = pending.length
            pending.append(text)
            if (error) {
                pending.setSpan(android.text.style.ForegroundColorSpan(0xFFD32F2F.toInt()), from, pending.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (!flushPosted) {
                flushPosted = true
                ui.post { flush() }
            }
        }
    }

    /** Moves what was written onto the screen, keeping the last 200,000 characters. */
    private fun flush() {
        val chunk: CharSequence
        synchronized(pending) {
            chunk = android.text.SpannableStringBuilder(pending)
            pending.clear()
            pending.clearSpans()
            flushPosted = false
        }
        output.append(chunk)
        val text = output.editableText ?: (output.text as? android.text.Editable)
        if (text != null && text.length > 200_000) text.delete(0, text.length - 200_000)
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun read(): String? {
        if (closing) throw RtStop()
        ui.post {
            flush()
            waiting(true)
        }
        return try {
            lines.take()
        } catch (e: InterruptedException) {
            throw RtStop()
        }
    }

    private fun waiting(on: Boolean) {
        field.isEnabled = on
        enter.isEnabled = on
        status.text = if (on) "Type a value and press OK." else status.text
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (on) {
            field.requestFocus()
            keyboard.showSoftInput(field, 0)
        } else {
            keyboard.hideSoftInputFromWindow(field.windowToken, 0)
        }
    }

    private fun submit() {
        if (!field.isEnabled) return
        val s = field.text.toString()
        field.setText("")
        waiting(false)
        status.text = "Running..."
        write(s + "\n", false)   // what was typed stays in the output, as on a console
        lines.put(s)
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        try {
            locationManager?.removeUpdates(this)
        } catch (e: SecurityException) {
            // nothing was registered
        }
        closing = true
        worker?.interrupt()
        if (rt_screen === this) rt_screen = null
        super.onDestroy()
    }
}
