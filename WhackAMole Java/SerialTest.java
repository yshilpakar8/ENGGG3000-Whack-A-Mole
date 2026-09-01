import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class SerialTest implements SerialPortDataListener {

    SerialPort serialPort;
    private final Consumer<String> onLine;

    private static final String PORT_NAME = "/dev/cu.usbserial-0001";
    private static final int DATA_RATE = 115200;

    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

    public SerialTest(Consumer<String> onLine) {
        this.onLine = onLine;
    }

    public SerialTest() {
        this(line -> System.out.println(line)); 
    }


    public void initialize() {
        SerialPort chosenPort = SerialPort.getCommPort(PORT_NAME);

        if (chosenPort == null) {
            System.out.println("Could not find COM port.");
            return;
        }

        serialPort = chosenPort;
        serialPort.setBaudRate(DATA_RATE);
        serialPort.setNumDataBits(8);
        serialPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        serialPort.setParity(SerialPort.NO_PARITY);

        // Critical: without this, reads are non-blocking by default and
        // can misbehave with BufferedReader / event-based reading on macOS.
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);

        if (!serialPort.openPort()) {
            System.err.println("Failed to open port.");
            return;
        }

        serialPort.addDataListener(this);
        System.out.println("Opened " + PORT_NAME + " at " + DATA_RATE + " baud.");
    }

    public synchronized void close() {
        if (serialPort != null) {
            serialPort.removeDataListener();
            serialPort.closePort();
        }
    }

    @Override
    public int getListeningEvents() {
        // DATA_RECEIVED is far more reliable than DATA_AVAILABLE on macOS usbserial adapters
        return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
    }

    @Override
    public synchronized void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_RECEIVED) return;

        byte[] newData = event.getReceivedData();
        for (byte b : newData) {
            if (b == '\n') {
                String line = lineBuffer.toString(StandardCharsets.UTF_8).trim();
                lineBuffer.reset();
                if (!line.isEmpty() && onLine != null) {
                    onLine.accept(line);
                }
            } else if (b != '\r') {
                lineBuffer.write(b);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        SerialTest main = new SerialTest();
        main.initialize();

        Runtime.getRuntime().addShutdownHook(new Thread(main::close));

        System.out.println("Started");
        Thread.sleep(1000000);
    }
}