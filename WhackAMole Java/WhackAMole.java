import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Random;

public class WhackAMole {

    final int width = 600;
    final int height = 700;
    final int timeFrame = 15;
    int lastLoc = 0;

    int score;
    int clicked = 0;

    String lastSerialLine = "";   // raw data read from the ESP32
    int circleY = 0;
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

        serialTest = new SerialTest(WhackAMole.this::handleSerialLine);
        serialTest.initialize();
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

            tile.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    JButton clickedTile = (JButton) e.getSource();
                    if (clickedTile == currMoleTile && clicked != 1) {
                        score += 10;
                        scoreLabel.setText("Score: " + score);
                        clicked = 1;
                    }
                }
            });
        }

        boardPanel.revalidate();
        boardPanel.repaint();
        
        if(setMoleTimer != null) setMoleTimer.stop();

        setMoleTimer = new Timer(1000, new ActionListener() {
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