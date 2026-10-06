import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.*;

public class WhackAMole2 {

    final int width = 1200;
    final int height = 700;

    int playWidth = 600, playHeight = 600;
    final int timeFrame = 120;
    int lastLoc = 0;

    int score;
    int clicked = 0;
    int clicked2 = 0;
    int life = 3;

    String lastSerialLine = "";   

    static final float BASELINE_CM = 150f; // distance between sensors    

    // X,Y boundaries
    static final float PLAY_X_MIN = 0f;
    static final float PLAY_X_MAX = BASELINE_CM;
    static final float NEAR_LIMIT_CM = 30f;
    static final float PLAY_Y_MIN = NEAR_LIMIT_CM;
    static final float PLAY_Y_MAX = 160f;
    static final boolean SOUND_ON = true;
    static final long WARN_REPEAT_MS = 1500;   // repeat interval while still in a warning zone

    Clip closeClip, offClip;
    Zone lastWarnZone = Zone.NO_FIX;
    long lastWarnMs = 0;

    static final boolean MIRROR_X = false; // x needs to be mirrored if recv esp on the right of player

    static final int GRID_COLS = 3;
    static final int GRID_ROWS = 3;

    static final Pattern XY_PATTERN =
            Pattern.compile("x:\\s*(-?[0-9]*\\.?[0-9]+).*?y:\\s*(-?[0-9]*\\.?[0-9]+)"); // x,y reading regex


    static final class Fix {
        // player location is distributed with one Fix snippet
        final float x, y;
        final boolean valid;

        Fix(float x, float y, boolean valid) {
            this.x = x;
            this.y = y;
            this.valid = valid;
        }
    }

    /*static final class PlayerTracker {
        static final int MEDIAN_WINDOW = 1;        
        static final float MAX_JUMP_CM = 20f;      // biggest jump in position allowed
        static final int RELOCK_SAMPLES = 5;       // consecutive reposition tracker
        static final float MEASURE_ALPHA = 0.7f;   
        static final float DISPLAY_TAU_MS = 60f;   
        static final long STALE_MS = 500;          // hold the last position this long with no valid data

        private final float[] winX = new float[MEDIAN_WINDOW];
        private final float[] winY = new float[MEDIAN_WINDOW];
        private int winCount = 0;
        private int winHead = 0;

        private boolean init = false;
        private float targetX, targetY;   
        private float dispX, dispY;       
        private long lastFixMs = 0;
        private int rejectStreak = 0;
        private long lastTickNs = 0;

        
        synchronized void onSample(float rx, float ry, long nowMs) {
            if (Float.isNaN(rx) || Float.isNaN(ry)
                    || Float.isInfinite(rx) || Float.isInfinite(ry) || ry < 0) {
                return;
            }

        
            if (!init || nowMs - lastFixMs > STALE_MS) {
                reset(rx, ry, true);
                lastFixMs = nowMs;
                return;
            }

            float jump = (float) Math.hypot(rx - targetX, ry - targetY);
            if (jump > MAX_JUMP_CM) {
                rejectStreak++;
                if (rejectStreak < RELOCK_SAMPLES) {
                    return;
                }
                reset(rx, ry, false);
                lastFixMs = nowMs;
                return;
            }
            rejectStreak = 0;

            winX[winHead] = rx;
            winY[winHead] = ry;
            winHead = (winHead + 1) % MEDIAN_WINDOW;
            if (winCount < MEDIAN_WINDOW) winCount++;
            float mx = median(winX, winCount);
            float my = median(winY, winCount);

            // smooting
            targetX += MEASURE_ALPHA * (mx - targetX);
            targetY += MEASURE_ALPHA * (my - targetY);
            lastFixMs = nowMs;
        }

        
        synchronized void tick() {
            long nowNs = System.nanoTime();
            if (!init) {
                lastTickNs = nowNs;
                return;
            }
            float dtMs = (nowNs - lastTickNs) / 1_000_000f;
            lastTickNs = nowNs;
            dtMs = Math.max(0f, Math.min(dtMs, 100f));

            float a = 1f - (float) Math.exp(-dtMs / DISPLAY_TAU_MS);
            dispX += (targetX - dispX) * a;
            dispY += (targetY - dispY) * a;
        }

        
        synchronized Fix get() {
            boolean valid = init && (System.currentTimeMillis() - lastFixMs) < STALE_MS;
            return new Fix(dispX, dispY, valid);
        }

        private void reset(float rx, float ry, boolean snapDisplay) {
            Arrays.fill(winX, rx);
            Arrays.fill(winY, ry);
            winCount = 1;
            winHead = 1 % MEDIAN_WINDOW;
            targetX = rx;
            targetY = ry;
            if (snapDisplay || !init) {
                dispX = rx;
                dispY = ry;
            }
            rejectStreak = 0;
            init = true;
        }

        private static float median(float[] buf, int n) {
            float[] tmp = Arrays.copyOf(buf, n);
            Arrays.sort(tmp);
            return (n % 2 == 1) ? tmp[n / 2] : (tmp[n / 2 - 1] + tmp[n / 2]) / 2f;
        }
    }*/

     static final class PlayerTracker {
    static final int AVG_WINDOW = 8;           // readings averaged (use 6-10)
    static final int MIN_SAMPLES = 6;          // readings needed before a position is reported
    static final float MAX_JUMP_CM = 50f;      // biggest jump from the current average allowed
    static final int RELOCK_SAMPLES = 5;       // consecutive far-away readings before re-averaging
    static final float DISPLAY_TAU_MS = 60f;
    static final long STALE_MS = 500;          // no readings for this long -> start over

    private final double[] winX = new double[AVG_WINDOW];
    private final double[] winY = new double[AVG_WINDOW];
    private int winCount = 0;
    private int winHead = 0;

    private boolean init = false;              // true once MIN_SAMPLES have been averaged
    private float targetX, targetY;
    private float dispX, dispY;
    private long lastFixMs = 0;
    private int rejectStreak = 0;
    private long lastTickNs = 0;

    synchronized void onSample(float rx, float ry, long nowMs) {
        if (Float.isNaN(rx) || Float.isNaN(ry)
                || Float.isInfinite(rx) || Float.isInfinite(ry) || ry < 0) {
            return;
        }

        // No readings for a while: forget everything and start a fresh average
        if (nowMs - lastFixMs > STALE_MS) {
            clearWindow();
            init = false;
        }

        // Reject wild jumps compared with the current average
        if (winCount > 0) {
            double jump = Math.hypot(rx - avgX(), ry - avgY());
            if (jump > MAX_JUMP_CM) {
                rejectStreak++;
                if (rejectStreak < RELOCK_SAMPLES) {
                    return;
                }
                clearWindow();   // the player really moved: re-average from here
            }
        }
        rejectStreak = 0;

        // Add to the window (the oldest value is overwritten once it is full)
        winX[winHead] = rx;
        winY[winHead] = ry;
        winHead = (winHead + 1) % AVG_WINDOW;
        if (winCount < AVG_WINDOW) winCount++;
        lastFixMs = nowMs;

        // Only report a position once enough readings have been averaged
        if (winCount >= MIN_SAMPLES) {
            targetX = (float) avgX();
            targetY = (float) avgY();
            if (!init) {                 // first lock: show it straight away
                dispX = targetX;
                dispY = targetY;
                init = true;
            }
        }
    }

    private double avgX() { return Arrays.stream(winX, 0, winCount).average().orElse(0); }
    private double avgY() { return Arrays.stream(winY, 0, winCount).average().orElse(0); }

    private void clearWindow() {
        winCount = 0;
        winHead = 0;
        rejectStreak = 0;
    }

    synchronized void tick() {
        long nowNs = System.nanoTime();
        if (!init) {
            lastTickNs = nowNs;
            return;
        }
        float dtMs = (nowNs - lastTickNs) / 1_000_000f;
        lastTickNs = nowNs;
        dtMs = Math.max(0f, Math.min(dtMs, 100f));

        float a = 1f - (float) Math.exp(-dtMs / DISPLAY_TAU_MS);
        dispX += (targetX - dispX) * a;
        dispY += (targetY - dispY) * a;
    }

    synchronized Fix get() {
        boolean valid = init && (System.currentTimeMillis() - lastFixMs) < STALE_MS;
        return new Fix(dispX, dispY, valid);
    }
}   

    final PlayerTracker tracker = new PlayerTracker();
    JPanel sensorPanel;
    SerialTest serialTest;

    JFrame frame = new JFrame("Whack A Mole");

    //added
    JPanel sidePanel = new JPanel();
    JLabel levelLabel = new JLabel();
    JPanel levelPanel = new JPanel();
    JLabel lifeLabel = new JLabel();
    JPanel lifePanel = new JPanel();

    JLabel timerLabel = new JLabel();
    JPanel timerPanel = new JPanel();
    JLabel scoreLabel = new JLabel();
    JPanel scorePanel = new JPanel();

    // --- Sensor test mode ---
    boolean testMode = false;
    int highlightIdx = -1;                       // tile the player is currently standing on (test mode)
    JButton testButton = new JButton();
    JButton backButton = new JButton();
    JLabel testTitleLabel = new JLabel();
    JLabel testZoneLabel = new JLabel();
    JLabel testReadingLabel = new JLabel();

    JPanel boardPanel = new JPanel(){
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if(grassImg != null) {
                g.drawImage(grassImg, 0, 0, getWidth(), getHeight(), this);
            }
        }
    };
    

    JButton[] board = new JButton[9];
    JButton startButton = new JButton();
    JButton retryButton = new JButton();

    ImageIcon moleIcon;
    ImageIcon grassIcon;
    Image grassImg;
    Image holeImg;


    JButton currMoleTile;
    JButton secondMoleTile;
    int num;
    int lastMoleTile;
    int prevFirstIdx = -1;    // tiles used by the previous spawn
    int prevSecondIdx = -1;

    Random random = new Random();
    Timer gameTimer;
    Timer moleTimer;

    int gameTimeSec = 0;
    int level = 1;

    // Mole lifetime: 5 s on level 1, 1 s less for every level reached (min 1 s)
    static final int BASE_MOLE_TIME_MS = 5000;
    static final int MOLE_TIME_STEP_MS = 1000;
    static final int MIN_MOLE_TIME_MS  = 1000;

    static int moleTimeForLevel(int lvl) {
        return Math.max(MIN_MOLE_TIME_MS, BASE_MOLE_TIME_MS - (lvl - 1) * MOLE_TIME_STEP_MS);
    }

    int moleDisplayTime = moleTimeForLevel(1); // milliseconds
    int numberOfMoles = 1;


    WhackAMole2() {
        frame.setSize(width, height);
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        grassImg = new ImageIcon(getClass().getResource("./grass.png")).getImage();
        holeImg = new ImageIcon(getClass().getResource("./hole.png")).getImage();
        boardPanel.setOpaque(false);
        boardPanel.setSize(600, 600);
        

        startButton.setText("START");
        startButton.setSize(50, 50);

        retryButton.setText("RETRY");
        retryButton.setSize(50, 50);    

        //boardPanel.add(startButton, BorderLayout.CENTER);
        frame.add(boardPanel, BorderLayout.CENTER);

        scoreLabel.setFont(new Font("Arial", Font.PLAIN, 20));
        scoreLabel.setHorizontalAlignment(JLabel.CENTER);
        scoreLabel.setText("Score: 0");
        scoreLabel.setOpaque(true);

        timerLabel.setFont(new Font("Arial", Font.PLAIN, 20));
        timerLabel.setHorizontalAlignment(JLabel.CENTER);
        timerLabel.setText("Timer: " + timeFrame);

        timerLabel.setOpaque(true);

        //added
        levelLabel.setFont(new Font("Arial", Font.PLAIN, 20));
        levelLabel.setHorizontalAlignment(JLabel.CENTER);
        levelLabel.setText("Level: 1");
        levelLabel.setOpaque(true);

        lifeLabel.setFont(new Font("Arial", Font.PLAIN, 20));
        lifeLabel.setHorizontalAlignment(JLabel.CENTER);
        lifeLabel.setText("Lives: 3");
        lifeLabel.setOpaque(true);

        levelPanel.setLayout(new BorderLayout());
        levelPanel.add(levelLabel);

        lifePanel.setLayout(new BorderLayout());
        lifePanel.add(lifeLabel);
        //

        timerPanel.setLayout(new BorderLayout());
        timerPanel.add(timerLabel);

        scorePanel.setLayout(new BorderLayout());
        scorePanel.add(scoreLabel);

        // sensor test side panel widgets
        testTitleLabel.setFont(new Font("Arial", Font.BOLD, 28));
        testTitleLabel.setHorizontalAlignment(JLabel.CENTER);
        testTitleLabel.setText("SENSOR TEST");

        testZoneLabel.setFont(new Font("Arial", Font.BOLD, 20));
        testZoneLabel.setHorizontalAlignment(JLabel.CENTER);

        testReadingLabel.setFont(new Font("Arial", Font.PLAIN, 18));
        testReadingLabel.setHorizontalAlignment(JLabel.CENTER);

        backButton.setText("BACK TO MENU");
        backButton.setFont(new Font("Arial", Font.BOLD, 22));
        backButton.setFocusPainted(false);

        testButton.setText("TEST SENSORS");
        testButton.setFont(new Font("Arial", Font.BOLD, 22));
        testButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        testButton.setMaximumSize(new Dimension(220, 60));
        testButton.setPreferredSize(new Dimension(220, 60));
        testButton.setFocusPainted(false);

        //changed
        sidePanel.setPreferredSize(new Dimension(450, 0));
        showGameSidePanel();

        frame.add(sidePanel, BorderLayout.EAST);      
        
        boardPanel.setLayout(new GridLayout(3, 3));
        //

        Image moleImg = new ImageIcon(getClass().getResource("./mole.png")).getImage();
        moleIcon = new ImageIcon(moleImg.getScaledInstance(150, 150, Image.SCALE_SMOOTH));

        if (SOUND_ON) {
            closeClip = makeTone(880, 150, 2);   // TOO_CLOSE: two high beeps
            offClip   = makeTone(440, 300, 1);   // OFF_BOARD: one long low beep
        }
        sensorPanel = new JPanel() {
            // Let mouse clicks fall through the glass pane to the buttons underneath
            // (needed for the Back button and the on-screen tiles).
            @Override
            public boolean contains(int x, int y) {
                return false;
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintBamEffects((Graphics2D) g);
                Fix fix = tracker.get();
                Zone zone = zoneFor(fix);
                if (zone == Zone.NO_FIX) {
                    return;
                }
                if (boardPanel.getWidth() == 0 || boardPanel.getHeight() == 0) {
                    return;
                }

                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Point markerPt = boardPixelForSensorReading(fix.x, fix.y);

                Color fill, line;
                switch (zone) {
                    case TOO_CLOSE:
                        fill = new Color(255, 140, 0, 220);  line = new Color(255, 140, 0, 130); break;
                    case OFF_BOARD:
                        fill = new Color(70, 90, 220, 220);  line = new Color(70, 90, 220, 130); break;
                    default:
                        fill = new Color(255, 0, 0, 200);    line = new Color(255, 0, 0, 120);  break;
                }

                if (testMode) {
                    // Sensor test: plain dot + crosshair for checking coverage
                    int r = 12; // marker radius
                    g2.setColor(fill);
                    g2.fillOval(markerPt.x - r, markerPt.y - r, r * 2, r * 2);
                    g2.setColor(line);
                    g2.drawLine(markerPt.x - r - 6, markerPt.y, markerPt.x + r + 6, markerPt.y);
                    g2.drawLine(markerPt.x, markerPt.y - r - 6, markerPt.x, markerPt.y + r + 6);
                } else {
                    // In the game the player is a top-down mallet; its centre is the hit point
                    drawMallet(g2, markerPt, zone);
                }

                if (zone == Zone.TOO_CLOSE) {
                    drawBanner(g2, "TOO CLOSE! Stand at least " + (int) NEAR_LIMIT_CM
                            + " cm from the sensors", new Color(230, 110, 0, 235));
                } else if (zone == Zone.OFF_BOARD) {
                    drawBanner(g2, "OFF THE PLAY BOARD - move back onto the board",
                            new Color(60, 75, 200, 235));
                }
            }
        };
        sensorPanel.setOpaque(false);
        frame.setGlassPane(sensorPanel);
        sensorPanel.setVisible(true);

       // ~60 fps: ease the displayed position, check for hits, repaint the marker.
    new Timer(16, e -> {
    tracker.tick();
    updateWarningSound();      
    checkForHit();
    if (testMode) updateTestPanel();
    sensorPanel.repaint();
    }).start(); 

        startButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                boardPanel.remove(startButton);
                startGame();
            }
        });

        retryButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                boardPanel.remove(retryButton);
                boardPanel.setLayout(new GridLayout(3, 3));
                startGame();
            }
        });

        testButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                startSensorTest();
            }
        });

        backButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showStartScreen();
            }
        });

        showStartScreen();
        frame.setVisible(true);

        frame.setVisible(true);

        serialTest = new SerialTest(WhackAMole2.this::handleSerialLine);
        serialTest.initialize();
    }

    /** Builds a short beep (or several) as an in-memory audio clip. */
private static Clip makeTone(int hz, int beepMs, int beeps) {
    try {
        float rate = 44100f;
        int beepSamples = (int) (rate * beepMs / 1000);
        int gapSamples  = (int) (rate * 0.06f);
        int total = beeps * beepSamples + (beeps - 1) * gapSamples;
        byte[] buf = new byte[total * 2];          // 16-bit mono

        int pos = 0;
        for (int b = 0; b < beeps; b++) {
            for (int i = 0; i < beepSamples; i++) {
                // 5 ms fade in/out so it doesn't click
                double env = Math.min(1.0, Math.min(i, beepSamples - i) / (rate * 0.005));
                short v = (short) (Math.sin(2 * Math.PI * hz * i / rate) * env * 0.4 * Short.MAX_VALUE);
                buf[pos++] = (byte) (v & 0xff);            // little-endian
                buf[pos++] = (byte) ((v >> 8) & 0xff);
            }
            if (b < beeps - 1) pos += gapSamples * 2;      // silence between beeps
        }

        AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
        Clip clip = AudioSystem.getClip();
        clip.open(fmt, buf, 0, buf.length);
        return clip;
    } catch (Exception ex) {
        System.err.println("Audio unavailable: " + ex.getMessage());
        return null;                                        // game still works silently
    }
}

private void playClip(Clip c) {
    if (c == null) return;
    c.stop();
    c.setFramePosition(0);
    c.start();                                              // asynchronous, won't freeze the UI
}


private void updateWarningSound() {
    if (!SOUND_ON) return;

    // Warnings play during a game and also during the sensor test.
    boolean running = testMode || (moleTimer != null && moleTimer.isRunning());
    Zone z = zoneFor(tracker.get());
    boolean warn = running && (z == Zone.TOO_CLOSE || z == Zone.OFF_BOARD);

    if (!warn) {
        lastWarnZone = Zone.NO_FIX;      // so re-entering a zone beeps immediately
        return;
    }

    long now = System.currentTimeMillis();
    if (z != lastWarnZone || now - lastWarnMs >= WARN_REPEAT_MS) {
        playClip(z == Zone.TOO_CLOSE ? closeClip : offClip);
        lastWarnMs = now;
    }
    lastWarnZone = z;
}

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private enum Zone { NO_FIX, TOO_CLOSE, OFF_BOARD, ON_BOARD }

    private Zone zoneFor(Fix f) {
        if (!f.valid) return Zone.NO_FIX;
        if (f.y < NEAR_LIMIT_CM) return Zone.TOO_CLOSE;   // dead zone right in front of the sensors
        if (f.x < PLAY_X_MIN || f.x > PLAY_X_MAX || f.y > PLAY_Y_MAX) return Zone.OFF_BOARD;
        return Zone.ON_BOARD;
    }
    
    /** Draws a simple top-down (circular) mallet head centred on point p (the player's position). */
    private void drawMallet(Graphics2D g0, Point p, Zone zone) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color face, rim;
        switch (zone) {
            case TOO_CLOSE: face = new Color(255, 160, 40);  rim = new Color(190, 95, 0);   break; // warning colours
            case OFF_BOARD: face = new Color(100, 120, 235); rim = new Color(40, 55, 160);  break;
            default:        face = new Color(190, 130, 65);  rim = new Color(95, 55, 20);   break; // wood
        }

        int R = 48;   // mallet radius in pixels
        g.setColor(rim);
        g.fillRect(p.x - 13, p.y, 26, 100);
        g.setColor(face);
        g.fillRect(p.x - 8, p.y, 16, 95);
        

        // soft shadow
        g.setColor(new Color(0, 0, 0, 60));
        g.fillOval(p.x - R + 4, p.y - R + 5, R * 2, R * 2);

        // outer rim
        g.setColor(rim);
        g.fillOval(p.x - R, p.y - R, R * 2, R * 2);

        // striking face
        int f = R - 5;
        g.setColor(face);
        g.fillOval(p.x - f, p.y - f, f * 2, f * 2);

        // inner ring
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(rim.getRed(), rim.getGreen(), rim.getBlue(), 140));
        int ring = R - 13;
        g.drawOval(p.x - ring, p.y - ring, ring * 2, ring * 2);

        

        // handle end seen from above (centre = hit point)
        // int h = 6;
        // g.setColor(rim);
        // g.fillOval(p.x - h, p.y - h, h * 2, h * 2);
        // g.setColor(new Color(255, 255, 255, 90));
        // g.fillOval(p.x - 3, p.y - 4, 4, 4);

        g.dispose();
    }

    private void drawBanner(Graphics2D g2, String text, Color bg) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(new Font("Arial", Font.BOLD, 20));
        FontMetrics fm = g2.getFontMetrics();

        int padX = 20, padY = 10;
        int w = fm.stringWidth(text) + padX * 2;
        int h = fm.getHeight() + padY * 2;

        Point o = SwingUtilities.convertPoint(boardPanel, 0, 0, sensorPanel);
        int x = o.x + (boardPanel.getWidth() - w) / 2;
        int y = o.y + 15;

        g2.setColor(bg);
        g2.fillRoundRect(x, y, w, h, 20, 20);
        g2.setColor(Color.WHITE);
        g2.drawString(text, x + padX, y + padY + fm.getAscent());
    }

    private static float[] sensorToBoardNorm(float rawX, float rawY) {
        float nx = (rawX - PLAY_X_MIN) / (PLAY_X_MAX - PLAY_X_MIN);
        float ny = (rawY - PLAY_Y_MIN) / (PLAY_Y_MAX - PLAY_Y_MIN);

        if (MIRROR_X) nx = 1f - nx;

        return new float[] { clamp(nx, 0f, 1f), clamp(ny, 0f, 1f) };
    }

    private Point boardPixelForSensorReading(float rawX, float rawY) {
        float[] n = sensorToBoardNorm(rawX, rawY);

        Point boardOrigin = SwingUtilities.convertPoint(boardPanel, 0, 0, sensorPanel);

        int px = boardOrigin.x + Math.round(n[0] * boardPanel.getWidth());
        int py = boardOrigin.y + Math.round(n[1] * boardPanel.getHeight());
        return new Point(px, py);
    }

    private int boardIndexForSensorReading(float rawX, float rawY) {
        if (Float.isNaN(rawX) || Float.isNaN(rawY)) return -1;
        if (boardPanel.getWidth() == 0 || boardPanel.getHeight() == 0) return -1;

        float[] n = sensorToBoardNorm(rawX, rawY);

        int col = Math.min(GRID_COLS - 1, (int) (n[0] * GRID_COLS));
        int row = Math.min(GRID_ROWS - 1, (int) (n[1] * GRID_ROWS));
        return row * GRID_COLS + col;
    }

    private void checkForHit() {
        if (testMode) return;

        // A mole is only "active" if it exists and hasn't been hit yet.
        boolean firstActive  = currMoleTile != null && clicked != 1;
        boolean secondActive = secondMoleTile != null && clicked2 != 1;
        if (!firstActive && !secondActive) return;

        Fix fix = tracker.get();
        if (zoneFor(fix) != Zone.ON_BOARD) return;

        int idx = boardIndexForSensorReading(fix.x, fix.y);
        if (idx < 0 || idx >= board.length) return;

        if (firstActive && board[idx] == currMoleTile) {
            score += 10;
            scoreLabel.setText("Score: " + score);
            clicked = 1;
            triggerBam(currMoleTile);
            currMoleTile.setIcon(null);
        } else if (secondActive && board[idx] == secondMoleTile) {
            score += 10;
            scoreLabel.setText("Score: " + score);
            clicked2 = 1;
            triggerBam(secondMoleTile);
            secondMoleTile.setIcon(null);
        }
    }


    //added
    private void updateLevel() {

        int newLevel;
    
        if (gameTimeSec < 30) {
            newLevel = 1;
        } else if (gameTimeSec < 60) {
            newLevel = 2;
        } else if (gameTimeSec < 90) {
            newLevel = 3;
        } else {
            newLevel = 4;
        }
    
        if (newLevel != level) {
            level = newLevel;
    
            moleDisplayTime = moleTimeForLevel(level);
            numberOfMoles = (level >= 3) ? 2 : 1;   // two moles from level 3
    
            levelLabel.setText("Level: " + level);
    
            // Restart the mole timer with the new speed
            if (moleTimer != null) {
                moleTimer.setDelay(moleDisplayTime);
            }
        }
    }

    //added
    private void loseLife() {
        life--;
    
        updateLife();
    
        if (life <= 0) {
            life = 0;
            updateLife();
    
            if (gameTimer != null) {
                gameTimer.stop();
            }
    
            if (moleTimer != null) {
                moleTimer.stop();
            }
    
            for (int i = 0; i < board.length; i++) {
                board[i].setEnabled(false);
                board[i].setIcon(null);
            }
    
            showRetryButton();
        }
    }

    private void updateLife() {
        lifeLabel.setText("Lives: " + life);
    }

    //added
    private void spawnMoles() {

        // Check whether the previous mole(s) were hit.
        // If not, the player loses a life.
    
        boolean missedMole = false;
    
        if (currMoleTile != null && clicked != 1) {
            missedMole = true;
        }
    
        if (secondMoleTile != null && clicked2 != 1) {
            missedMole = true;
        }
    
        // Remove old moles
        if (currMoleTile != null) {
            currMoleTile.setIcon(null);
            currMoleTile = null;
        }
    
        if (secondMoleTile != null) {
            secondMoleTile.setIcon(null);
            secondMoleTile = null;
        }
    
        // Reset hit states
        clicked = 0;
        clicked2 = 0;
    
        // Lose a life if a mole was missed
        if (missedMole) {
            loseLife();
    
            // If the player has no lives left,
            // don't spawn another mole.
            if (life <= 0) {
                return;
            }
        }
    
        // Spawn first mole (never on a tile the previous spawn used)
        int firstNum = pickTile(prevFirstIdx, prevSecondIdx);
    
        currMoleTile = board[firstNum];
        currMoleTile.setIcon(moleIcon);

        int secondNum = -1;
    
        // Spawn second mole for Levels 3 and 4
        if (numberOfMoles == 2) {
    
            // Not on the same tile as the first mole, nor a previous tile
            secondNum = pickTile(prevFirstIdx, prevSecondIdx, firstNum);
    
            secondMoleTile = board[secondNum];
            secondMoleTile.setIcon(moleIcon);
        }

        prevFirstIdx = firstNum;
        prevSecondIdx = secondNum;
    }

    /** Picks a random tile index (0-8) that is not in the excluded list. */
    private int pickTile(int... excluded) {
        int n;
        boolean bad;
        do {
            n = random.nextInt(9);
            bad = false;
            for (int ex : excluded) {
                if (n == ex) { bad = true; break; }
            }
        } while (bad);
        return n;
    }

    // ------------------------------------------------------------------
    // "BAM!" hit effect (drawn on the glass pane over the tile that was hit)
    // ------------------------------------------------------------------

    static final int BAM_MS = 450;   // how long the effect lasts

    private static final class Bam {
        final JButton tile;
        final long startMs;
        Bam(JButton tile, long startMs) {
            this.tile = tile;
            this.startMs = startMs;
        }
    }

    private final java.util.List<Bam> bamEffects = new java.util.ArrayList<>();

    private void triggerBam(JButton tile) {
        if (tile != null) bamEffects.add(new Bam(tile, System.currentTimeMillis()));
    }

    private void paintBamEffects(Graphics2D g0) {
        if (bamEffects.isEmpty()) return;

        Graphics2D g2 = (Graphics2D) g0.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        long now = System.currentTimeMillis();
        java.util.Iterator<Bam> it = bamEffects.iterator();
        while (it.hasNext()) {
            Bam b = it.next();
            long age = now - b.startMs;
            if (age >= BAM_MS || b.tile.getParent() == null) {
                it.remove();
                continue;
            }

            float t = age / (float) BAM_MS;                          // 0 -> 1
            // pop in (0.5x -> 1.3x), then settle to 1.0x
            float scale = (t < 0.2f) ? 0.5f + (t / 0.2f) * 0.8f
                                     : 1.3f - ((t - 0.2f) / 0.8f) * 0.3f;
            // fully visible, then fade out over the last 40%
            float alpha = (t < 0.6f) ? 1f : 1f - (t - 0.6f) / 0.4f;

            Point c = SwingUtilities.convertPoint(b.tile.getParent(),
                    b.tile.getX() + b.tile.getWidth() / 2,
                    b.tile.getY() + b.tile.getHeight() / 2,
                    sensorPanel);

            Graphics2D e = (Graphics2D) g2.create();
            e.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    Math.max(0f, Math.min(1f, alpha))));
            e.translate(c.x, c.y);
            e.rotate(Math.toRadians(-10));
            e.scale(scale, scale);

            // comic starburst
            int spikes = 14;
            double outer = 78, inner = 46;
            Polygon star = new Polygon();
            for (int i = 0; i < spikes * 2; i++) {
                double r = (i % 2 == 0) ? outer : inner;
                double a = Math.PI * i / spikes;
                star.addPoint((int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r));
            }
            e.setColor(new Color(255, 225, 0));
            e.fillPolygon(star);
            e.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            e.setColor(new Color(235, 60, 20));
            e.drawPolygon(star);

            // outlined "BAM!" text
            Font font = new Font("Arial", Font.BOLD, 36);
            Shape text = font.createGlyphVector(e.getFontRenderContext(), "BAM!").getOutline();
            Rectangle2D tb = text.getBounds2D();
            e.translate(-tb.getCenterX(), -tb.getCenterY());
            e.setColor(new Color(180, 20, 20));
            e.fill(text);
            e.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            e.setColor(Color.WHITE);
            e.draw(text);

            e.dispose();
        }
        g2.dispose();
    }

    /** Creates the 3x3 grid of hole tiles. Used by both the game and the sensor test. */
    private void buildBoard() {
        bamEffects.clear();
        boardPanel.removeAll();
        boardPanel.setLayout(new GridLayout(3, 3));

        for (int i = 0; i < 9; i++) {

            final int tileIdx = i;

            JButton tile = new JButton() {
                protected void paintComponent(Graphics g) {

                    if (holeImg != null) {
                        g.drawImage(
                            holeImg,
                            10,
                            10,
                            getWidth() - 25,
                            getHeight() - 25,
                            this
                        );
                    }

                    // Sensor test: tint the tile the player is standing on
                    if (testMode && tileIdx == highlightIdx) {
                        g.setColor(new Color(255, 230, 0, 110));
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }

                    super.paintComponent(g);
                }
            };

            tile.setOpaque(false);
            tile.setContentAreaFilled(false);
            tile.setBorderPainted(false);
            tile.setFocusPainted(false);
            tile.setEnabled(true);

            board[i] = tile;
            boardPanel.add(tile);

            tile.addActionListener(new ActionListener() {

                public void actionPerformed(ActionEvent e) {

                    JButton clickedTile = (JButton) e.getSource();

                    if (clickedTile == currMoleTile && clicked != 1) {

                        score += 10;
                        scoreLabel.setText("Score: " + score);

                        triggerBam(clickedTile);
                        clickedTile.setIcon(null);

                        clicked = 1;
                    } else if (clickedTile == secondMoleTile && clicked2 != 1) {
                        score += 10;
                        scoreLabel.setText("Score: " + score);

                        triggerBam(clickedTile);
                        clickedTile.setIcon(null);

                        clicked2 = 1;
                    }
                }
            });
        }

        boardPanel.revalidate();
        boardPanel.repaint();
    }

    //edited
    private void startGame() {

        testMode = false;
        highlightIdx = -1;
        showGameSidePanel();

        sensorPanel.setVisible(true);
    
        score = 0;
        gameTimeSec = 0;
        clicked = 0;
        clicked2 = 0;
        life = 3;

        updateLife();
    
        level = 1;
        moleDisplayTime = moleTimeForLevel(1);
        numberOfMoles = 1;
    
        currMoleTile = null;
        secondMoleTile = null;
        prevFirstIdx = -1;
        prevSecondIdx = -1;
    
        scoreLabel.setText("Score: 0");
        timerLabel.setText("Timer: " + timeFrame);
        levelLabel.setText("Level: 1");
    
        buildBoard();
    
        // Stop old timers
        if (gameTimer != null) {
            gameTimer.stop();
        }
    
        if (moleTimer != null) {
            moleTimer.stop();
        }
    
        // Spawn the first mole immediately
        spawnMoles();
    
        // --------------------------------
        // GAME TIMER
        // --------------------------------
    
        gameTimer = new Timer(1000, new ActionListener() {
    
            public void actionPerformed(ActionEvent e) {
    
                gameTimeSec++;
    
                timerLabel.setText(
                    "Timer: " + (timeFrame - gameTimeSec)
                );
    
                updateLevel();
    
                if (gameTimeSec >= timeFrame) {
    
                    gameTimer.stop();
                    moleTimer.stop();
    
                    for (int i = 0; i < 9; i++) {
                        board[i].setEnabled(false);
                        board[i].setIcon(null);
                    }
    
                    showRetryButton();
                    return;
                }
            }
        });
    
        // --------------------------------
        // MOLE TIMER
        // --------------------------------
    
        moleTimer = new Timer(moleDisplayTime, new ActionListener() {
    
            public void actionPerformed(ActionEvent e) {
                // NOTE: do NOT reset clicked/clicked2 here. spawnMoles() needs them
                // to tell whether the previous mole was hit, and resets them itself.
                spawnMoles();
            }
        });
    
        gameTimer.start();
        moleTimer.start();
    }

    // ------------------------------------------------------------------
    // Sensor test mode
    // ------------------------------------------------------------------

    /** Shows the play board and live player position, with no moles, for checking sensor coverage. */
    private void startSensorTest() {
        testMode = true;
        highlightIdx = -1;

        if (gameTimer != null) gameTimer.stop();
        if (moleTimer != null) moleTimer.stop();

        currMoleTile = null;
        secondMoleTile = null;
        clicked = 0;
        clicked2 = 0;

        buildBoard();
        showTestSidePanel();
        updateTestPanel();
        sensorPanel.setVisible(true);
    }

    /** Refreshes the live readout and highlighted tile in the sensor test side panel. */
    private void updateTestPanel() {
        Fix fix = tracker.get();
        Zone z = zoneFor(fix);

        int newIdx = (z == Zone.ON_BOARD) ? boardIndexForSensorReading(fix.x, fix.y) : -1;
        if (newIdx != highlightIdx) {
            highlightIdx = newIdx;
            boardPanel.repaint();
        }

        switch (z) {
            case NO_FIX:
                testZoneLabel.setText("No sensor signal");
                testZoneLabel.setForeground(Color.DARK_GRAY);
                testReadingLabel.setText("x: --   y: --");
                break;
            case TOO_CLOSE:
                testZoneLabel.setText("TOO CLOSE TO SENSORS");
                testZoneLabel.setForeground(new Color(230, 110, 0));
                testReadingLabel.setText(String.format("x: %.0f cm   y: %.0f cm", fix.x, fix.y));
                break;
            case OFF_BOARD:
                testZoneLabel.setText("OFF THE PLAY BOARD");
                testZoneLabel.setForeground(new Color(60, 75, 200));
                testReadingLabel.setText(String.format("x: %.0f cm   y: %.0f cm", fix.x, fix.y));
                break;
            default:
                testZoneLabel.setText("ON BOARD");
                testZoneLabel.setForeground(new Color(30, 130, 60));
                testReadingLabel.setText(String.format("x: %.0f cm   y: %.0f cm   Tile: %d",
                        fix.x, fix.y, newIdx + 1));
                break;
        }
    }

    private void showGameSidePanel() {
        sidePanel.removeAll();
        sidePanel.setLayout(new GridLayout(4, 1, 0, 20));
        sidePanel.add(timerPanel);
        sidePanel.add(lifePanel);
        sidePanel.add(scorePanel);
        sidePanel.add(levelPanel);
        sidePanel.revalidate();
        sidePanel.repaint();
    }

    private void showTestSidePanel() {
        sidePanel.removeAll();
        sidePanel.setLayout(new GridLayout(4, 1, 0, 20));
        sidePanel.add(testTitleLabel);
        sidePanel.add(testZoneLabel);
        sidePanel.add(testReadingLabel);
        sidePanel.add(backButton);
        sidePanel.revalidate();
        sidePanel.repaint();
    }

    private JLabel makeLabel(String text, int style, int size, Color color) {
    JLabel l = new JLabel(text);
    l.setFont(new Font("Arial", style, size));
    l.setForeground(color);
    l.setAlignmentX(Component.CENTER_ALIGNMENT);
    return l;
}

private void showStartScreen() {
    // Leave sensor test / stop anything running
    testMode = false;
    highlightIdx = -1;
    if (gameTimer != null) gameTimer.stop();
    if (moleTimer != null) moleTimer.stop();
    currMoleTile = null;
    secondMoleTile = null;
    showGameSidePanel();

    sensorPanel.setVisible(false);

    boardPanel.removeAll();
    boardPanel.setLayout(new BorderLayout());

    JPanel startPanel = new JPanel();
    startPanel.setLayout(new BoxLayout(startPanel, BoxLayout.Y_AXIS));
    startPanel.setBackground(new Color(220, 235, 245));
    startPanel.setBorder(BorderFactory.createEmptyBorder(30, 40, 30, 40));

    Color dark  = new Color(40, 40, 40);
    Color green = new Color(30, 130, 60);
    Color red   = new Color(190, 30, 30);

    startPanel.add(Box.createVerticalGlue());
    startPanel.add(makeLabel("WHACK A MOLE", Font.BOLD, 44, red));
    startPanel.add(Box.createVerticalStrut(8));
    startPanel.add(makeLabel("Gameplay Rules", Font.BOLD, 24, dark));
    startPanel.add(Box.createVerticalStrut(18));

    startPanel.add(makeLabel("DO", Font.BOLD, 20, green));
    startPanel.add(Box.createVerticalStrut(4));
    String[] dos = {
        "Step onto the tile with a mole to whack it (+10 points)",
        "Stay on the play board, at least " + (int) NEAR_LIMIT_CM + " cm from the sensors",
        "Listen for the warning beeps if you leave the zone",
        "Score as much as you can before the timer runs out"
    };
    for (String d : dos) {
        startPanel.add(makeLabel("\u2022 " + d, Font.PLAIN, 16, dark));
        startPanel.add(Box.createVerticalStrut(3));
    }

    startPanel.add(Box.createVerticalStrut(14));

    startPanel.add(makeLabel("DON'T", Font.BOLD, 20, red));
    startPanel.add(Box.createVerticalStrut(4));
    String[] donts = {
        "Don't stand too close to the sensors, you'll get a warning beep",
        "Don't walk off the play board, you'll get a warning beep",
        "Don't stand still - moles get faster every 30 seconds!"
    };
    for (String d : donts) {
        startPanel.add(makeLabel("\u2022 " + d, Font.PLAIN, 16, dark));
        startPanel.add(Box.createVerticalStrut(3));
    }

    startPanel.add(Box.createVerticalStrut(25));

    startButton.setText("START GAME");
    startButton.setFont(new Font("Arial", Font.BOLD, 22));
    startButton.setAlignmentX(Component.CENTER_ALIGNMENT);
    startButton.setMaximumSize(new Dimension(220, 60));
    startButton.setPreferredSize(new Dimension(220, 60));
    startButton.setFocusPainted(false);
    startPanel.add(startButton);

    startPanel.add(Box.createVerticalStrut(12));
    startPanel.add(testButton);
    startPanel.add(Box.createVerticalGlue());

    boardPanel.add(startPanel, BorderLayout.CENTER);
    boardPanel.revalidate();
    boardPanel.repaint();
}

    private void showRetryButton() {
    // Hide the red sensor dot on the end screen
    sensorPanel.setVisible(false);

    

    // Remove the game board
    boardPanel.removeAll();
    boardPanel.setLayout(new BorderLayout());

    // Main end screen
    JPanel endPanel = new JPanel();
    endPanel.setLayout(new BoxLayout(endPanel, BoxLayout.Y_AXIS));
    endPanel.setBackground(new Color(220, 235, 245));
    endPanel.setBorder(BorderFactory.createEmptyBorder(60, 60, 60, 60));

    // GAME OVER
    JLabel gameOverLabel = new JLabel("GAME OVER");
    gameOverLabel.setFont(new Font("Arial", Font.BOLD, 50));
    gameOverLabel.setForeground(Color.RED);
    gameOverLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

    // Final score
    JLabel finalScoreLabel = new JLabel("Final Score: " + score);
    finalScoreLabel.setFont(new Font("Arial", Font.BOLD, 30));
    finalScoreLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

    // Level reached
    JLabel finalLevelLabel = new JLabel("Level Reached: " + level);
    finalLevelLabel.setFont(new Font("Arial", Font.PLAIN, 26));
    finalLevelLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

    // Retry button
    retryButton.setText("PLAY AGAIN");
    retryButton.setFont(new Font("Arial", Font.BOLD, 22));
    retryButton.setAlignmentX(Component.CENTER_ALIGNMENT);
    retryButton.setMaximumSize(new Dimension(220, 60));
    retryButton.setPreferredSize(new Dimension(220, 60));
    retryButton.setFocusPainted(false);

    // everything else 
    endPanel.add(Box.createVerticalGlue());
    endPanel.add(gameOverLabel);
    endPanel.add(Box.createVerticalStrut(35));
    endPanel.add(finalScoreLabel);
    endPanel.add(Box.createVerticalStrut(20));
    endPanel.add(finalLevelLabel);
    endPanel.add(Box.createVerticalStrut(45));
    endPanel.add(retryButton);
    endPanel.add(Box.createVerticalGlue());
    boardPanel.add(endPanel, BorderLayout.CENTER);

    boardPanel.revalidate();
    boardPanel.repaint();
}

    // Runs on the serial reader thread; only parses and hands the raw sample to the tracker. 
    private void handleSerialLine(String line) {
        lastSerialLine = line;

        Matcher m = XY_PATTERN.matcher(line);
        if (m.find()) {
            try {
                float newX = Float.parseFloat(m.group(1));
                float newY = Float.parseFloat(m.group(2));
                tracker.onSample(newX, newY, System.currentTimeMillis());
            } catch (NumberFormatException ex) {
                // malformed number in an otherwise-matching line; ignore
            }
        }
    }
}