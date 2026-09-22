import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
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

    String lastSerialLine = "";   // raw data read from the ESP32
    
    volatile float sensorX = -1;
    volatile float sensorY = -1;

    static final float SENSOR_X_MAX = 100f; // cm
    static final float SENSOR_Y_MAX = 100f; 
 
    static final int GRID_COLS = 3;
    static final int GRID_ROWS = 3;
    
    static final Pattern XY_PATTERN =
            Pattern.compile("x:\\s*(-?[0-9]*\\.?[0-9]+).*?y:\\s*(-?[0-9]*\\.?[0-9]+)");




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
                if (sensorX < 0 || sensorY < 0) {
                    return; // no fix yet / invalid reading -> draw nothing
                }
                if (boardPanel.getWidth() == 0 || boardPanel.getHeight() == 0) {
                    return; // board not laid out yet
                }


                Graphics2D g2 = (Graphics2D) g;

                // g2.setColor(Color.RED);
                // g2.fillOval((int) sensorX, (int) sensorY, 19, 19);
                // g2.drawOval((int) sensorX, (int) sensorY, 20, 20);


                //g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
 
                Point markerPt = boardPixelForSensorReading(sensorX, sensorY);
 
                int r = 12; // marker radius
                g2.setColor(new Color(255, 0, 0, 200));
                g2.fillOval(markerPt.x - r, markerPt.y - r, r * 2, r * 2);
                g2.setColor(Color.BLUE);
                
 
                g2.setColor(new Color(255, 0, 0, 120));
                g2.drawLine(markerPt.x - r - 6, markerPt.y, markerPt.x + r + 6, markerPt.y);
                g2.drawLine(markerPt.x, markerPt.y - r - 6, markerPt.x, markerPt.y + r + 6);
            }
        };
        sensorPanel.setOpaque(false);
        frame.setGlassPane(sensorPanel);
        sensorPanel.setVisible(true);
        

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

    private Point boardPixelForSensorReading(float rawX, float rawY) {
        float normX = clamp(rawX / SENSOR_X_MAX, 0f, 1f);
        float normY = clamp(rawY / SENSOR_Y_MAX, 0f, 1f);
 
        Point boardOrigin = SwingUtilities.convertPoint(boardPanel, 0, 0, sensorPanel);
 
        int px = boardOrigin.x + Math.round(normX * boardPanel.getWidth());
        int py = boardOrigin.y + Math.round(normY * boardPanel.getHeight());
        return new Point(px, py);
    }

    private int boardIndexForSensorReading(float rawX, float rawY) {
        if (rawX < 0 || rawY < 0) return -1;
        if (boardPanel.getWidth() == 0 || boardPanel.getHeight() == 0) return -1;
 
        float normX = clamp(rawX / SENSOR_X_MAX, 0f, 1f);
        float normY = clamp(rawY / SENSOR_Y_MAX, 0f, 1f);
 
        int col = Math.min(GRID_COLS - 1, (int) (normX * GRID_COLS));
        int row = Math.min(GRID_ROWS - 1, (int) (normY * GRID_ROWS));
        return row * GRID_COLS + col;
    }

    private void checkForHit() {
        if (currMoleTile == null || clicked == 1) return;
 
        int idx = boardIndexForSensorReading(sensorX, sensorY);
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

        setMoleTimer = new Timer(3000, new ActionListener() {
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


    private void handleSerialLine(String line) {
        lastSerialLine = line;

        Matcher m = XY_PATTERN.matcher(line);
        if (m.find()) {
            try {
                float newX = Float.parseFloat(m.group(1));
                float newY = Float.parseFloat(m.group(2));
                sensorX = newX;
                sensorY = newY;
                SwingUtilities.invokeLater(() -> {
                    checkForHit();
                    if (sensorPanel != null) sensorPanel.repaint();
                });
            } catch (NumberFormatException ex) {
                // malformed number in an otherwise-matching line; ignore
            }
        }
    }
}