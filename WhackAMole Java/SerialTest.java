import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class SerialTest {

    private final Consumer<String> onLine;

    // The receiver ESP32's AP IP is 192.168.4.1 by default (see WiFi.softAPIP() in its Serial log).
    private static final String HOST = "192.168.4.1";
    private static final int PORT = 80;

    private static final int RECONNECT_DELAY_MS = 1000;

    private volatile boolean running = false;
    private Thread readerThread;
    private Socket socket;

    public SerialTest(Consumer<String> onLine) {
        this.onLine = onLine;
    }

    public SerialTest() {
        this(line -> System.out.println(line));
    }

    public void initialize() {
        running = true;
        readerThread = new Thread(this::connectAndReadLoop, "esp32-wifi-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void connectAndReadLoop() {
        while (running) {
            try (Socket s = new Socket(HOST, PORT)) {
                socket = s;
                System.out.println("Connected to receiver ESP32 at " + HOST + ":" + PORT);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

                String line;
                while (running && (line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && onLine != null) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException e) {
                System.err.println("WiFi connection to ESP32 lost/unavailable: " + e.getMessage());
            }

            socket = null;
            if (running) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public synchronized void close() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        if (readerThread != null) readerThread.interrupt();
    }

    public static void main(String[] args) throws Exception {
        SerialTest main = new SerialTest();
        main.initialize();

        Runtime.getRuntime().addShutdownHook(new Thread(main::close));

        System.out.println("Started");
        Thread.sleep(1000000);
    }
}