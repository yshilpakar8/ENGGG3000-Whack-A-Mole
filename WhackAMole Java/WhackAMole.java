import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.*;
import java.util.Random;
import java.util.random.*;


public class WhackAMole {

    final int width = 600;
    final int height = 700;
    final int timeFrame = 16;

    int score;
    int clicked = 0;
    int run = 0;
    
    JFrame frame = new JFrame("Whack A Mole");
    
    JLabel timerLabel = new JLabel();
    JPanel timerPanel = new JPanel();
    JLabel scoreLabel = new JLabel();
    JPanel scorePanel = new JPanel();
    JPanel boardPanel = new JPanel();

    JButton[] board = new JButton[9]; 
    JButton startButton = new JButton();
    ImageIcon moleIcon; 


    JButton currMoleTile;
    int num;
    int lastMoleTile;

    Random random = new Random();
    Timer setMoleTimer;
    int gameTimeSec = 0;
    int timerSec = 0;


    WhackAMole() {
        frame.setSize(width, height);
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        startButton.setText("START");
        startButton.setSize(50,50);

        boardPanel.add(startButton);
        frame.add(boardPanel);
        frame.setVisible(true);
        while(run == 0) {
            startButton.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    run = 1;
                    boardPanel.remove(startButton);
                }
            });
        }

        scoreLabel.setFont(new Font("Arial", Font.PLAIN, 20));
        scoreLabel.setHorizontalAlignment((JLabel.CENTER));
        scoreLabel.setText("Score: 0");
        scoreLabel.setOpaque(true);

        timerLabel.setFont(new Font("Arial", Font.PLAIN, 20));
        timerLabel.setHorizontalAlignment((JLabel.CENTER));
        timerLabel.setText("Timer: " + timeFrame);
        timerLabel.setOpaque(true);

        timerPanel.setLayout(new BorderLayout());
        timerPanel.add(timerLabel);

        scorePanel.setLayout(new BorderLayout());
        scorePanel.add(scoreLabel);
        
        
        frame.add(scorePanel, BorderLayout.SOUTH);
        frame.add(timerPanel, BorderLayout.NORTH);

        boardPanel.setLayout(new GridLayout(3, 3));
        
        Image moleImg = new ImageIcon(getClass().getResource("./monty.png")).getImage();
        moleIcon = new ImageIcon(moleImg.getScaledInstance(150, 150, java.awt.Image.SCALE_SMOOTH));
        

        

        if(run == 1) {
            score = 0;
            for(int i = 0; i < 9; i++) {
                JButton tile = new JButton();
                board[i] = tile;
                boardPanel.add(tile);

                tile.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        JButton tile = (JButton) e.getSource();
                        if(tile == currMoleTile && clicked != 1) {
                            score += 10;
                            scoreLabel.setText("Score: " + Integer.toString(score));
                            clicked = 1;
                        }
                    }
                });
            }



            
            setMoleTimer = new Timer(1000, new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    if(gameTimeSec == timeFrame) {
                        setMoleTimer.stop();
                        for(int i = 0; i < 9; i++) {
                            board[i].setEnabled(false);
                        }

                        scoreLabel.setText("GAME OVER - SCORE: " + Integer.toString(score));

                        return;
                    }

                    if(currMoleTile != null) {
                        currMoleTile.setIcon(null);
                        currMoleTile = null;
                    }



                    gameTimeSec ++;
                    timerLabel.setText("Timer: " + Integer.toString(timeFrame - gameTimeSec));

                    num = random.nextInt(9);

                    if(num == lastMoleTile) num = random.nextInt(9);


                    JButton tile = board[num];
                    currMoleTile = tile;
                    currMoleTile.setIcon(moleIcon);

                    clicked = 0;

                    lastMoleTile = num;
                }
            });
            setMoleTimer.start();
        }

        
    }
}
