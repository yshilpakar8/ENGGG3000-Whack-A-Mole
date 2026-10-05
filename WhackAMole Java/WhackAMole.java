/*import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WhackAMole {

    final int width = 900;
    final int height = 700;

    int playWidth = 600, playHeight = 600;
    final int timeFrame = 15;
    int lastLoc = 0;

    int score;
    int clicked = 0;

    String lastSerialLine = "";   

    static final float BASELINE_CM = 150f; // distance between sensors    

    // X,Y boundaries
    static final float PLAY_X_MIN = 0f;
    static final float PLAY_X_MAX = BASELINE_CM;
    static final float NEAR_LIMIT_CM = 30f;
    static final float PLAY_Y_MIN = NEAR_LIMIT_CM;
    static final float PLAY_Y_MAX = 200f;

    static final boolean MIRROR_X = true; // x needs to be mirrored if recv esp on the right of player

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

    static final class PlayerTracker {
        static final int MEDIAN_WINDOW = 1;        
        static final float MAX_JUMP_CM = 50f;      // biggest jump in position allowed
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
    }

    final PlayerTracker tracker = new PlayerTracker();

    JPanel sensorPanel;
    SerialTest serialTest;

    JFrame frame = new JFrame("Whack A Mole");

    JLabel timerLabel = new JLabel();
    JPanel timerPanel = new JPanel();
    JLabel scoreLabel = new JLabel();
    JPanel scorePanel = new JPanel();

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
    int num;
    int lastMoleTile;

    Random random = new Random();
    Timer setMoleTimer;
    int gameTimeSec = 0;

    WhackAMole() {
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

        boardPanel.add(startButton, BorderLayout.CENTER);
        frame.add(boardPanel, BorderLayout.CENTER);

        scoreLabel.setFont(new Font("Arial", Font.PLAIN, 20));
        scoreLabel.setHorizontalAlignment(JLabel.CENTER);
        scoreLabel.setText("Score: 0");
        scoreLabel.setOpaque(true);

        timerLabel.setFont(new Font("Arial", Font.PLAIN, 20));
        timerLabel.setHorizontalAlignment(JLabel.CENTER);
        timerLabel.setText("Timer: " + timeFrame);

        timerLabel.setOpaque(true);

        timerPanel.setLayout(new BorderLayout());
        timerPanel.add(timerLabel);

        scorePanel.setLayout(new BorderLayout());
        scorePanel.add(scoreLabel);

        frame.add(scorePanel, BorderLayout.SOUTH);
        frame.add(timerPanel, BorderLayout.NORTH);

        boardPanel.setLayout(new GridLayout(3, 3));


        Image moleImg = new ImageIcon(getClass().getResource("./mole.png")).getImage();
        moleIcon = new ImageIcon(moleImg.getScaledInstance(150, 150, Image.SCALE_SMOOTH));

        sensorPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
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

                int r = 12; // marker radius
                g2.setColor(fill);
                g2.fillOval(markerPt.x - r, markerPt.y - r, r * 2, r * 2);
                g2.setColor(line);
                g2.drawLine(markerPt.x - r - 6, markerPt.y, markerPt.x + r + 6, markerPt.y);
                g2.drawLine(markerPt.x, markerPt.y - r - 6, markerPt.x, markerPt.y + r + 6);

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
            checkForHit();
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

        frame.setVisible(true);

        serialTest = new SerialTest(WhackAMole.this::handleSerialLine);
        serialTest.initialize();
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
        if (currMoleTile == null || clicked == 1) return;

        Fix fix = tracker.get();
        if (zoneFor(fix) != Zone.ON_BOARD) return;

        int idx = boardIndexForSensorReading(fix.x, fix.y);
        if (idx < 0 || idx >= board.length) return;

        if (board[idx] == currMoleTile) {
            score += 10;
            scoreLabel.setText("Score: " + score);
            clicked = 1;
            currMoleTile.setIcon(null);
        }
    }


    private void startGame() {
        sensorPanel.setVisible(true);

        score = 0;
        gameTimeSec = 0;
        clicked = 0;
        currMoleTile = null;
        lastMoleTile = -1;
        scoreLabel.setText("Score: 0");
        timerLabel.setText("Timer: " + timeFrame);

        boardPanel.removeAll();

        for (int i = 0; i < 9; i++) {
            JButton tile = new JButton(){
                protected void paintComponent(Graphics g) {
                    if(holeImg != null) {
                        g.drawImage(holeImg, 10, 10, getWidth() -25, getHeight() - 25, this);
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
        }

        boardPanel.revalidate();
        boardPanel.repaint();

        if(setMoleTimer != null) setMoleTimer.stop();

        setMoleTimer = new Timer(5000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (gameTimeSec == timeFrame) {
                    setMoleTimer.stop();
                    for (int i = 0; i < 9; i++) {
                        board[i].setEnabled(false);
                    }
                    scoreLabel.setText("GAME OVER - SCORE: " + score);
                    showRetryButton();
                    return;

                }

                if (currMoleTile != null) {
                    currMoleTile.setIcon(null);
                    currMoleTile = null;
                }

                gameTimeSec++;
                timerLabel.setText("Timer: " + (timeFrame - gameTimeSec));

                num = random.nextInt(9);
                if (num == lastMoleTile) num = random.nextInt(9);

                JButton tile = board[num];
                currMoleTile = tile;
                currMoleTile.setIcon(moleIcon);

                clicked = 0;
                lastMoleTile = num;

                checkForHit();
            }
        });
        setMoleTimer.start();
    }

    private void showRetryButton() {
        boardPanel.removeAll();
        boardPanel.setLayout(new BorderLayout());
        boardPanel.add(retryButton, BorderLayout.CENTER);
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
} /* */

import javax.sound.sampled.Clip;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.*;

public class WhackAMole {

    final int width = 900;
    final int height = 700;

    // ---- Game rules ----
    static final int START_SCORE   = 50;   // game ends when score reaches 0
    static final int HIT_POINTS    = 10;   // earned for catching a mole
    static final int MISS_PENALTY  = 5;    // lost when a mole disappears uncaught
    static final int LEVEL_SECONDS = 30;   // level up every 30 s
    static final int BASE_MOLE_MS  = 3000; // how long a mole stays up on level 1
    static final int SPEEDUP_MS    = 400;  // faster by this much each level
    static final int MIN_MOLE_MS   = 600;  // never faster than this
    static final boolean USE_MOUSE = true;   // set to false for real sensors
    volatile boolean mouseInBoard = false;
    volatile float mouseRawX, mouseRawY;

    static final boolean SOUND_ON = true;
    static final long WARN_REPEAT_MS = 1500;   // repeat interval while still in a warning zone

    Clip closeClip, offClip;
    Zone lastWarnZone = Zone.NO_FIX;
    long lastWarnMs = 0;

    int score;
    int clicked = 0;
    int level = 1;
    int elapsedSec = 0;

    String lastSerialLine = "";

    static final float BASELINE_CM = 150f; // distance between sensors

    // X,Y boundaries
    static final float PLAY_X_MIN = 0f;
    static final float PLAY_X_MAX = BASELINE_CM;
    static final float NEAR_LIMIT_CM = 30f;
    static final float PLAY_Y_MIN = NEAR_LIMIT_CM;
    static final float PLAY_Y_MAX = 200f;

    static final boolean MIRROR_X = true; // x needs to be mirrored if recv esp on the right of player

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

    static final class PlayerTracker {
        static final int MEDIAN_WINDOW = 1;
        static final float MAX_JUMP_CM = 50f;      // biggest jump in position allowed
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

            // smoothing
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
    }

    final PlayerTracker tracker = new PlayerTracker();

    JPanel sensorPanel;
    SerialTest serialTest;
    JPanel sidePanel;

    JFrame frame = new JFrame("Whack A Mole");

    JLabel timerLabel = new JLabel();
    JLabel scoreLabel = new JLabel();
    JLabel levelLabel = new JLabel();

    JPanel boardPanel = new JPanel() {
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (grassImg != null) {
                g.drawImage(grassImg, 0, 0, getWidth(), getHeight(), this);
            }
        }
    };

    JButton[] board = new JButton[9];
    JButton startButton = new JButton();
    JButton retryButton = new JButton();

    ImageIcon moleIcon;
    Image grassImg;
    Image holeImg;

    JButton currMoleTile;
    int num;
    int lastMoleTile = -1;

    Random random = new Random();
    Timer setMoleTimer;
    Timer clockTimer;

    WhackAMole() {
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // ---- Side panel: title, level, time, score ----
        sidePanel = new JPanel();
        sidePanel.setBackground(Color.LIGHT_GRAY);
        sidePanel.setPreferredSize(new Dimension(250, height));
        sidePanel.setLayout(new BoxLayout(sidePanel, BoxLayout.Y_AXIS));
        sidePanel.setBorder(BorderFactory.createEmptyBorder(30, 10, 10, 10));

        JLabel title = new JLabel("WHACK A MOLE");
        title.setFont(new Font("Arial", Font.BOLD, 24));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        for (JLabel l : new JLabel[] { timerLabel, scoreLabel, levelLabel }) {
            l.setFont(new Font("Arial", Font.BOLD, 28));
            l.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        timerLabel.setText("Time: 0:00");
        scoreLabel.setText("Score: " + START_SCORE);
        levelLabel.setText("Level: 1");

        sidePanel.add(title);
        sidePanel.add(Box.createVerticalStrut(40));
        sidePanel.add(levelLabel);
        sidePanel.add(Box.createVerticalStrut(30));
        sidePanel.add(timerLabel);
        sidePanel.add(Box.createVerticalStrut(30));
        sidePanel.add(scoreLabel);
        frame.add(sidePanel, BorderLayout.EAST);

        // ---- Board ----
        grassImg = new ImageIcon(getClass().getResource("./grass.png")).getImage();
        holeImg = new ImageIcon(getClass().getResource("./hole.png")).getImage();
        boardPanel.setOpaque(false);
        boardPanel.setPreferredSize(new Dimension(600, 600));

        startButton.setText("START");
        retryButton.setText("RETRY");

        // start screen: single big button filling the board
        boardPanel.setLayout(new BorderLayout());
        boardPanel.add(startButton, BorderLayout.CENTER);
        frame.add(boardPanel, BorderLayout.CENTER);

        

        Image moleImg = new ImageIcon(getClass().getResource("./mole.png")).getImage();
        moleIcon = new ImageIcon(moleImg.getScaledInstance(150, 150, Image.SCALE_SMOOTH));

        if (SOUND_ON) {
        closeClip = makeTone(880, 150, 2);   // TOO_CLOSE: two high beeps
        offClip   = makeTone(440, 300, 1);   // OFF_BOARD: one long low beep
        }

         // ---- Glass pane: player marker + warning banners ----
        sensorPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Fix fix = tracker.get();
                new Timer(16, e -> {
                if (USE_MOUSE && mouseInBoard) {
                    tracker.onSample(mouseRawX, mouseRawY, System.currentTimeMillis());
                }
                tracker.tick();
                updateWarningSound();      // <-- new
                checkForHit();
                sensorPanel.repaint();
            }).start();
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

                int r = 12; // marker radius
                g2.setColor(fill);
                g2.fillOval(markerPt.x - r, markerPt.y - r, r * 2, r * 2);
                g2.setColor(line);
                g2.drawLine(markerPt.x - r - 6, markerPt.y, markerPt.x + r + 6, markerPt.y);
                g2.drawLine(markerPt.x, markerPt.y - r - 6, markerPt.x, markerPt.y + r + 6);

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

/** Beeps on entering TOO_CLOSE / OFF_BOARD and repeats while the player stays there. */
private void updateWarningSound() {
    if (!SOUND_ON) return;

    boolean running = setMoleTimer != null && setMoleTimer.isRunning();
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


        // ~60 fps: ease the displayed position, check for hits, repaint the marker.
        new Timer(16, e -> {
            tracker.tick();
            checkForHit();
            sensorPanel.repaint();
        }).start();

        startButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                startGame();
            }
        });

        retryButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                startGame();
            }
        });

            frame.pack();
    frame.setLocationRelativeTo(null);

    // Step 2: the mouse listener block goes here, before setVisible
    if (USE_MOUSE) {
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
        
        }, AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    frame.setVisible(true);

    // Step 4: replaces the old serialTest lines
    if (!USE_MOUSE) {
        serialTest = new SerialTest(WhackAMole.this::handleSerialLine);
        serialTest.initialize();
    }


        if (USE_MOUSE) {
    Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
        if (!(ev instanceof MouseEvent)) return;
        MouseEvent me = (MouseEvent) ev;
        if (me.getID() != MouseEvent.MOUSE_MOVED
                && me.getID() != MouseEvent.MOUSE_DRAGGED) return;
        if (me.getComponent() == null) return;

        int w = boardPanel.getWidth(), h = boardPanel.getHeight();
        Point p = SwingUtilities.convertPoint(me.getComponent(), me.getPoint(), boardPanel);
        if (w == 0 || h == 0 || p.x < 0 || p.y < 0 || p.x > w || p.y > h) {
            mouseInBoard = false;      // cursor off the board -> fix goes stale
            return;
        }

        // Inverse of sensorToBoardNorm(): board pixels -> sensor cm
        float nx = (float) p.x / w;
        float ny = (float) p.y / h;
        if (MIRROR_X) nx = 1f - nx;
        mouseRawX = PLAY_X_MIN + nx * (PLAY_X_MAX - PLAY_X_MIN);
        mouseRawY = PLAY_Y_MIN + ny * (PLAY_Y_MAX - PLAY_Y_MIN);
        mouseInBoard = true;
    }, AWTEvent.MOUSE_MOTION_EVENT_MASK);
}

        serialTest = new SerialTest(WhackAMole.this::handleSerialLine);
        serialTest.initialize();
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
        if (currMoleTile == null || clicked == 1) return;

        Fix fix = tracker.get();
        if (zoneFor(fix) != Zone.ON_BOARD) return;

        int idx = boardIndexForSensorReading(fix.x, fix.y);
        if (idx < 0 || idx >= board.length) return;

        if (board[idx] == currMoleTile) {
            score += HIT_POINTS;
            updateScoreLabel();
            clicked = 1;
            currMoleTile.setIcon(null);
        }
    }

    // ------------------------------------------------------------------
    // Game flow
    // ------------------------------------------------------------------

    private void startGame() {
        sensorPanel.setVisible(true);

        score = START_SCORE;
        elapsedSec = 0;
        level = 1;
        clicked = 0;
        currMoleTile = null;
        lastMoleTile = -1;
        updateScoreLabel();
        updateClockLabels();

        boardPanel.removeAll();
        boardPanel.setLayout(new GridLayout(3, 3));

        for (int i = 0; i < 9; i++) {
            JButton tile = new JButton() {
                protected void paintComponent(Graphics g) {
                    if (holeImg != null) {
                        g.drawImage(holeImg, 10, 10, getWidth() - 25, getHeight() - 25, this);
                    }
                    super.paintComponent(g);
                }
            };
            tile.setOpaque(false);
            tile.setContentAreaFilled(false);
            tile.setBorderPainted(false);
            tile.setFocusPainted(false);
            board[i] = tile;
            boardPanel.add(tile);
        }
        boardPanel.revalidate();
        boardPanel.repaint();

        if (setMoleTimer != null) setMoleTimer.stop();
        if (clockTimer != null) clockTimer.stop();

        // 1-second game clock: drives elapsed time and level
        clockTimer = new Timer(1000, e -> {
            elapsedSec++;
            level = elapsedSec / LEVEL_SECONDS + 1;
            updateClockLabels();
        });

        // Mole timer: delay is recalculated from the level after every mole
        setMoleTimer = new Timer(moleDelayMs(), e -> nextMole());

        nextMole();            // first mole appears immediately
        clockTimer.start();
        setMoleTimer.start();
    }

    // Called each time the current mole's time is up. 
    private void nextMole() {
        if (currMoleTile != null) {
            if (clicked == 0) {            // mole escaped -> penalty
                score -= MISS_PENALTY;
                updateScoreLabel();
                if (score <= 0) {
                    endGame();
                    return;
                }
            }
            currMoleTile.setIcon(null);
            currMoleTile = null;
        }

        do {
            num = random.nextInt(9);
        } while (num == lastMoleTile);

        currMoleTile = board[num];
        currMoleTile.setIcon(moleIcon);
        lastMoleTile = num;
        clicked = 0;

        setMoleTimer.setDelay(moleDelayMs());  // faster at higher levels
        checkForHit();
    }

    private int moleDelayMs() {
        return Math.max(MIN_MOLE_MS, BASE_MOLE_MS - (level - 1) * SPEEDUP_MS);
    }

    private void endGame() {
        setMoleTimer.stop();
        clockTimer.stop();
        currMoleTile = null;
        score = Math.max(0, score);
        scoreLabel.setText("Score: 0 - GAME OVER");
        retryButton.setText("GAME OVER (Level " + level + ", "
                + formatTime(elapsedSec) + ") - RETRY");
        showRetryButton();
    }

    private void updateScoreLabel() {
        scoreLabel.setText("Score: " + score);
    }

    private void updateClockLabels() {
        timerLabel.setText("Time: " + formatTime(elapsedSec));
        levelLabel.setText("Level: " + level);
    }

    private static String formatTime(int sec) {
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    private void showRetryButton() {
        boardPanel.removeAll();
        boardPanel.setLayout(new BorderLayout());
        boardPanel.add(retryButton, BorderLayout.CENTER);
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
