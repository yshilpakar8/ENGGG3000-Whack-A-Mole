import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Random;

public class WhackAMole2 {

    final int width = 1200;
    final int height = 700;
    final int timeFrame = 120;
    int lastLoc = 0;

    int score;
    int clicked = 0;

    String lastSerialLine = "";   // raw data read from the ESP32
    int circleY = 0;
    JPanel sensorPanel;
    SerialTest serialTest;

    JFrame frame = new JFrame("Whack A Mole");

    //added
    JPanel sidePanel = new JPanel();
    JLabel levelLabel = new JLabel();
    JPanel levelPanel = new JPanel();

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

    //JButton currMoleTile;
    int num;
    int lastMoleTile;

    Random random = new Random();
    Timer gameTimer;
    Timer moleTimer;

    int gameTimeSec = 0;
    int level = 1;

    int moleDisplayTime = 1000; // milliseconds
    int numberOfMoles = 1;

    JButton currMoleTile;
    JButton secondMoleTile;

    WhackAMole2() {
        frame.setSize(width, height);
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        grassImg = new ImageIcon(getClass().getResource("./grass.png")).getImage();
        holeImg = new ImageIcon(getClass().getResource("./hole.png")).getImage();
        boardPanel.setOpaque(false);
        

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

        //added
        levelLabel.setFont(new Font("Arial", Font.PLAIN, 20));
        levelLabel.setHorizontalAlignment(JLabel.CENTER);
        levelLabel.setText("Level: 1");
        levelLabel.setOpaque(true);

        levelPanel.setLayout(new BorderLayout());
        levelPanel.add(levelLabel);
        //

        timerPanel.setLayout(new BorderLayout());
        timerPanel.add(timerLabel);

        scorePanel.setLayout(new BorderLayout());
        scorePanel.add(scoreLabel);

        //added
        levelPanel.setLayout(new BorderLayout());
        levelPanel.add(levelLabel);
        //

        //changed
        sidePanel.setLayout(new GridLayout(3, 1, 0, 20));
        sidePanel.setPreferredSize(new Dimension(450, 0));

        sidePanel.add(timerPanel);
        sidePanel.add(scorePanel);
        sidePanel.add(levelPanel);

        frame.add(sidePanel, BorderLayout.EAST);      
        
        boardPanel.setLayout(new GridLayout(3, 3));
        //

        Image moleImg = new ImageIcon(getClass().getResource("./mole.png")).getImage();
        moleIcon = new ImageIcon(moleImg.getScaledInstance(150, 150, Image.SCALE_SMOOTH));

        sensorPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                int x = width / 2;
                int y = circleY;
                g.setColor(Color.RED);
                g.fillOval(x, y, 20, 20);
            }
        };
        sensorPanel.setOpaque(false);
        frame.setGlassPane(sensorPanel);;
        

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

        serialTest = new SerialTest(WhackAMole2.this::handleSerialLine);
        serialTest.initialize();
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
    private void spawnMoles() {

        // Remove old moles
        if (currMoleTile != null) {
            currMoleTile.setIcon(null);
            currMoleTile = null;
        }
    
        if (secondMoleTile != null) {
            secondMoleTile.setIcon(null);
            secondMoleTile = null;
        }
    
        // First mole
        int firstNum = random.nextInt(9);
    
        currMoleTile = board[firstNum];
        currMoleTile.setIcon(moleIcon);
    
        // Second mole for Levels 3 and 4
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
    
        level = 1;
        moleDisplayTime = 2000;
        numberOfMoles = 1;
    
        currMoleTile = null;
        secondMoleTile = null;
    
        scoreLabel.setText("Score: 0");
        timerLabel.setText("Timer: " + timeFrame);
        levelLabel.setText("Level: 1");
    
        boardPanel.removeAll();
    
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
    
                    if ((clickedTile == currMoleTile ||
                         clickedTile == secondMoleTile)
                         && clicked != 1) {
    
                        score += 10;
                        scoreLabel.setText("Score: " + score);
    
                        clicked = 1;
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
    
                    scoreLabel.setText(
                        "GAME OVER - SCORE: " + score
                    );
    
                    showRetryButton();
                }
            }
        });
    
        // --------------------------------
        // MOLE TIMER
        // --------------------------------
    
        moleTimer = new Timer(moleDisplayTime, new ActionListener() {
    
            public void actionPerformed(ActionEvent e) {
    
                clicked = 0;
    
                spawnMoles();
            }
        });
    
        gameTimer.start();
        moleTimer.start();
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
        try {
            int value = Integer.parseInt(line.trim());
            if((value < lastLoc + 25) && (value > lastLoc - 25)) {
                lastLoc = circleY;
                circleY = value;
            }
            SwingUtilities.invokeLater(() -> {
                if (sensorPanel != null) sensorPanel.repaint();
            });
        } catch (NumberFormatException ex) {
            // ignore non-integer lines
        }
    }
}