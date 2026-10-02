import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
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
    static final float PLAY_Y_MAX = 200f;
    static final boolean SOUND_ON = true;
    static final long WARN_REPEAT_MS = 1500;   // repeat interval while still in a warning zone

    Clip closeClip, offClip;
    Zone lastWarnZone = Zone.NO_FIX;
    long lastWarnMs = 0;

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

    Random random = new Random();
    Timer gameTimer;
    Timer moleTimer;

    int gameTimeSec = 0;
    int level = 1;

    int moleDisplayTime = 1000; // milliseconds
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


        //changed
        sidePanel.setLayout(new GridLayout(4, 1, 0, 20));
        sidePanel.setPreferredSize(new Dimension(450, 0));

        sidePanel.add(timerPanel);
        sidePanel.add(lifePanel);
        sidePanel.add(scorePanel);
        sidePanel.add(levelPanel);

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
    updateWarningSound();      // <-- the only new line
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

/** Beeps on entering TOO_CLOSE / OFF_BOARD and repeats while the player stays there. */
private void updateWarningSound() {
    if (!SOUND_ON) return;

    boolean running = moleTimer != null && moleTimer.isRunning();
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
        if (currMoleTile == null || clicked == 1) {
            if (secondMoleTile == null || clicked2 == 1) return;
        }

        Fix fix = tracker.get();
        if (zoneFor(fix) != Zone.ON_BOARD) return;

        int idx = boardIndexForSensorReading(fix.x, fix.y);
        if (idx < 0 || idx >= board.length) return;

        if (board[idx] == currMoleTile) {
            score += 10;
            scoreLabel.setText("Score: " + score);
            clicked = 1;
            currMoleTile.setIcon(null);
        } else if (board[idx] == secondMoleTile) {
            score += 10;
            scoreLabel.setText("Score: " + score);
            clicked2 = 1;
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
    
            switch (level) {
                case 1:
                    moleDisplayTime = 2000;
                    numberOfMoles = 1;
                    break;
    
                case 2:
                    moleDisplayTime = 1000;
                    numberOfMoles = 1;
                    break;
    
                case 3:
                    moleDisplayTime = 1000; 
                    numberOfMoles = 2;
                    break;
    
                case 4:
                    moleDisplayTime = 500;
                    numberOfMoles = 2;
                    break;
            }
    
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
    
        // Spawn first mole
        int firstNum = random.nextInt(9);
    
        currMoleTile = board[firstNum];
        currMoleTile.setIcon(moleIcon);
    
        // Spawn second mole for Levels 3 and 4
        if (numberOfMoles == 2) {
    
            int secondNum = random.nextInt(9);
    
            // Make sure the two moles aren't in the same tile
            while (secondNum == firstNum) {
                secondNum = random.nextInt(9);
            }
    
            secondMoleTile = board[secondNum];
            secondMoleTile.setIcon(moleIcon);
        }
    }

    //edited
    private void startGame() {

        sensorPanel.setVisible(true);
    
        score = 0;
        gameTimeSec = 0;
        clicked = 0;
        clicked2 = 0;
        life = 3;

        updateLife();
    
        level = 1;
        moleDisplayTime = 2000;
        numberOfMoles = 1;
    
        currMoleTile = null;
        secondMoleTile = null;
    
        scoreLabel.setText("Score: 0");
        timerLabel.setText("Timer: " + timeFrame);
        levelLabel.setText("Level: 1");
    
        boardPanel.removeAll();
        boardPanel.setLayout(new GridLayout(3, 3));  
    
        for (int i = 0; i < 9; i++) {
    
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

                        clickedTile.setIcon(null);

                        clicked = 1;
                    } else if (clickedTile == secondMoleTile && clicked2 != 1) {
                        score += 10;
                        scoreLabel.setText("Score: " + score);

                        clickedTile.setIcon(null);

                        clicked2 = 1;
                    }
                }
            });
        }
    
        boardPanel.revalidate();
        boardPanel.repaint();
    
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
    
                clicked = 0;
                clicked2 = 0;
    
                spawnMoles();
            }
        });
    
        gameTimer.start();
        moleTimer.start();
    }

    private JLabel makeLabel(String text, int style, int size, Color color) {
    JLabel l = new JLabel(text);
    l.setFont(new Font("Arial", style, size));
    l.setForeground(color);
    l.setAlignmentX(Component.CENTER_ALIGNMENT);
    return l;
}

private void showStartScreen() {
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