package evia.paradigma;

import java.awt.Color;

/** The kinds of blocks. CUSTOM blocks get their name, color and ports from a {@link BlockDef}. */
public enum BlockType {
    //     title    color                      input  outputs code   default code       hint
    START("Start", new Color(126, 200, 128), false, 1, false, "",
            "Program starts here (only one allowed)."),
    SET("Set", new Color(132, 172, 240), true, 1, true, "x = 0",
            "Assign a variable:  name = expression   (inside a method: this.field = expression)"),
    INPUT("Input", new Color(196, 160, 230), true, 1, true, "n",
            "Ask the user for a value and store it in a variable:  name"),
    PRINT("Print", new Color(242, 202, 112), true, 1, true, "\"Hello\"",
            "Print an expression, e.g.  \"x = \" + x"),
    IF("If / Else", new Color(240, 142, 130), true, 2, true, "x < 10",
            "Condition. 'then' runs when it is true, 'else' when false. Wire both branches to the same block to join them."),

    // ---- loops: wire the end of the body back into the loop block; 'done' continues after the loop
    FOR("For", new Color(250, 168, 92), true, 2, true, "i = 1; i <= 10; i = i + 1",
            "init; condition; step   e.g.  i = 1; i <= 10; i = i + 1. Wire the end of the body back into this block."),
    WHILE("While", new Color(244, 186, 104), true, 2, true, "x < 10",
            "Condition, checked before every pass. Wire the end of the body back into this block."),
    DO_WHILE("Do-While", new Color(236, 154, 112), true, 2, true, "x < 10",
            "The body runs first; the condition, checked after every pass, decides whether to repeat. Wire the end of the body back."),
    END("End", new Color(165, 165, 170), true, 0, false, "",
            "Program stops here."),

    // ---- board: pins and time of a microcontroller (simulated when run on the computer)
    PIN_MODE("Pin Mode", new Color(110, 190, 150), true, 1, true, "LED_BUILTIN, OUTPUT",
            "pin, OUTPUT | INPUT | INPUT_PULLUP   e.g.  LED_BUILTIN, OUTPUT   or   4, INPUT_PULLUP"),
    DIGITAL_WRITE("Digital Write", new Color(96, 182, 142), true, 1, true, "LED_BUILTIN, HIGH",
            "pin, value   Sets a pin HIGH (any non-zero value) or LOW (0), e.g.  LED_BUILTIN, HIGH"),
    DIGITAL_READ("Digital Read", new Color(132, 202, 166), true, 1, true, "pressed = 4",
            "name = pin   Stores 1 if the pin is HIGH, 0 if LOW, e.g.  button = 4"),
    ANALOG_READ("Analog Read", new Color(142, 206, 186), true, 1, true, "level = A0",
            "name = pin   Stores the voltage on an analog pin: 0..1023 on AVR, 0..4095 on ESP32, e.g.  level = A0"),
    ANALOG_WRITE("Analog Write", new Color(104, 188, 164), true, 1, true, "5, 128",
            "pin, value   PWM output 0..255 (0 = off, 255 = always on), e.g.  5, brightness"),
    DELAY("Delay", new Color(162, 202, 140), true, 1, true, "500",
            "Waits this many milliseconds, e.g.  500"),
    MILLIS("Millis", new Color(178, 212, 150), true, 1, true, "t",
            "name   Stores the milliseconds since the program started, e.g.  t"),

    // ---- board buses: serial port, SPI and I2C of a microcontroller (simulated when run on the computer)
    UART_OPEN("UART Open", new Color(100, 176, 208), true, 1, true, "9600",
            "baud   Starts the board's serial port (the one Print uses), e.g.  9600  or  115200"),
    UART_WRITE("UART Write", new Color(112, 186, 216), true, 1, true, "65",
            "byte   Sends one byte 0..255 on the serial port, e.g.  65  or  c + 1"),
    UART_READ("UART Read", new Color(124, 196, 222), true, 1, true, "c",
            "name   Stores the next received byte 0..255, or -1 if nothing has arrived, e.g.  c"),
    SPI_OPEN("SPI Open", new Color(150, 156, 224), true, 1, true, "1000000",
            "clock   Starts SPI as master, mode 0, most significant bit first, at this clock in Hz, e.g.  1000000. Chip select is a Digital Write."),
    SPI_TRANSFER("SPI Transfer", new Color(164, 170, 230), true, 1, true, "reply = 66",
            "name = byte   Sends a byte and stores the byte received at the same time, e.g.  reply = 66"),
    I2C_OPEN("I2C Open", new Color(204, 150, 210), true, 1, true, "100000",
            "clock   Starts I2C as master at this clock in Hz, e.g.  100000  or  400000"),
    I2C_WRITE("I2C Write", new Color(214, 164, 220), true, 1, true, "80, 0, 42",
            "address, register, value   Writes a byte into a register of the device (7-bit address in decimal, 80 = 0x50), e.g.  80, 0, 42"),
    I2C_READ("I2C Read", new Color(224, 178, 228), true, 1, true, "v = 80, 0",
            "name = address, register   Reads a byte from a register of the device; -1 if it does not answer, e.g.  v = 80, 0"),

    // ---- MQTT over a network: the ESP32's own WiFi, or an ESP8266/ESP32 AT module or Ethernet on a PIC32
    MQTT_CONNECT("MQTT Connect", new Color(118, 198, 196), true, 1, true, "\"network\", \"password\", \"broker.example.com\", 1883",
            "\"network\", \"password\", \"broker\", port   Joins the network and connects to the MQTT broker (the texts are fixed, not variables)"),
    MQTT_PUBLISH("MQTT Publish", new Color(132, 208, 204), true, 1, true, "\"evia/value\", 42",
            "\"topic\", value   Sends a message: a number or a text, e.g.  \"evia/temperature\", t"),
    MQTT_SUBSCRIBE("MQTT Subscribe", new Color(146, 216, 210), true, 1, true, "\"evia/command\"",
            "\"topic\"   Asks the broker for the messages of this topic (up to four topics in a program)"),
    MQTT_HAS("MQTT Has Message", new Color(152, 220, 214), true, 1, true, "waiting = \"evia/command\"",
            "name = \"topic\"   Stores 1 when a message of that topic is waiting, 0 when none is, e.g.  waiting = \"evia/command\""),
    MQTT_READ("MQTT Read", new Color(160, 224, 218), true, 1, true, "cmd = \"evia/command\"",
            "name = \"topic\"   Stores the number in the next message of that topic, or -1 if none has arrived (MQTT Has Message tells a waiting -1 apart), e.g.  cmd = \"evia/command\""),

    // ---- a phone: the sensors of an Android phone (simulated when run on the computer)
    SENSOR("Phone Sensor", new Color(120, 180, 232), true, 1, true, "ax = ACCEL_X",
            "name = sensor   ACCEL_X, ACCEL_Y, ACCEL_Z (m/s\u00b2), GYRO_X, GYRO_Y, GYRO_Z (rad/s), LATITUDE, LONGITUDE (degrees),"
                    + " ALTITUDE (m), GPS_ACCURACY (m), GPS_FIX (1 when the position is known), e.g.  lat = LATITUDE"),

    // ---- only inside custom block definitions
    ENTER("Enter", new Color(112, 204, 196), false, 1, false, "",
            "Where the method starts executing (one per method)."),
    EXIT("Exit", new Color(112, 204, 196), true, 0, true, "next",
            "Leaves the method. Each distinct exit name becomes an output port of the calling block."),

    // ---- an object: an instance of a user-defined block
    CUSTOM("Custom", Color.GRAY, true, -1, true, "",
            "Arguments for the method, separated by commas");

    final String title;
    final Color color;
    final boolean hasInput;
    final int outputs;
    final boolean hasCode;
    final String defaultCode;
    final String hint;

    /** Pin, time and bus blocks (the Board group of the palette). */
    boolean isBoard() {
        return switch (this) {
            case PIN_MODE, DIGITAL_WRITE, DIGITAL_READ, ANALOG_READ, ANALOG_WRITE, DELAY, MILLIS,
                 UART_OPEN, UART_WRITE, UART_READ, SPI_OPEN, SPI_TRANSFER, I2C_OPEN, I2C_WRITE, I2C_READ,
                 MQTT_CONNECT, MQTT_PUBLISH, MQTT_SUBSCRIBE, MQTT_HAS, MQTT_READ -> true;
            default -> false;
        };
    }

    /** The Phone group of the palette: blocks for Language Android. */
    boolean isPhone() {
        return this == SENSOR;
    }

    boolean isLoop() {
        return this == FOR || this == WHILE || this == DO_WHILE;
    }

    BlockType(String title, Color color, boolean hasInput, int outputs,
              boolean hasCode, String defaultCode, String hint) {
        this.title = title;
        this.color = color;
        this.hasInput = hasInput;
        this.outputs = outputs;
        this.hasCode = hasCode;
        this.defaultCode = defaultCode;
        this.hint = hint;
    }
}
