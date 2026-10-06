import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Coding tests C1-C6 for WhackAMole2 (no sensors needed).
 *
 * Put this file in the same folder as WhackAMole2.java, SerialTest.java and the png files, then
 * recompile EVERYTHING (the tests read constants such as PLAY_Y_MAX and MIRROR_X at compile time):
 *   javac WhackAMole2.java SerialTest.java WhackAMole2Tests.java
 *   java WhackAMole2Tests
 *
 * Needs a display (the game opens its real window). All game actions run on the Swing thread so the
 * game's own 60 fps timer cannot interfere with a test step.
 */
public class WhackAMoleTest {

    // Expected values taken from the game design
    static final int HIT_POINTS = 10;
    static final int START_LIVES = 3;
    static final int[] LEVEL_MOLES = {1, 1, 2, 2};
    static final int[] LEVEL_MS    = {5000, 4000, 3000, 2000};

    static final float ONE_CM_X = 1f / (WhackAMole2.PLAY_X_MAX - WhackAMole2.PLAY_X_MIN);
    static final float ONE_CM_Y = 1f / (WhackAMole2.PLAY_Y_MAX - WhackAMole2.PLAY_Y_MIN);

    // ------------------------------------------------------------------
    // Result bookkeeping
    // ------------------------------------------------------------------

    static final class Row {
        final String id, input, expected, actual, result;
        Row(String id, String input, String expected, String actual, String result) {
            this.id = id; this.input = input; this.expected = expected;
            this.actual = actual; this.result = result;
        }
    }

    static final List<Row> rows = new ArrayList<>();

    static void record(String id, String input, String expected, String actual, boolean pass) {
        rows.add(new Row(id, input, expected, actual, pass ? "PASS" : "FAIL"));
    }

    static void record(String id, String input, String expected, String actual, String result) {
        rows.add(new Row(id, input, expected, actual, result));
    }

    static void check(StringBuilder bad, boolean ok, String msg) {
        if (!ok) {
            if (bad.length() > 0) bad.append("; ");
            bad.append(msg);
        }
    }

    // ------------------------------------------------------------------
    // Swing-thread + reflection helpers
    // ------------------------------------------------------------------

    static void edt(Runnable r) {
        try {
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeAndWait(r);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T edtGet(Supplier<T> s) {
        Object[] box = new Object[1];
        edt(() -> box[0] = s.get());
        return (T) box[0];
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    static Object callPrivate(WhackAMole2 g, String name) {
        try {
            Method m = WhackAMole2.class.getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(g);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String zoneOf(WhackAMole2 g, WhackAMole2.Fix fix) {
        try {
            Method m = WhackAMole2.class.getDeclaredMethod("zoneFor", WhackAMole2.Fix.class);
            m.setAccessible(true);
            return String.valueOf(m.invoke(g, fix));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void setTracker(WhackAMole2 g, WhackAMole2.PlayerTracker t) {
        try {
            Field f = WhackAMole2.class.getDeclaredField("tracker");
            f.setAccessible(true);
            f.set(g, t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void fire(javax.swing.Timer t) {
        for (ActionListener l : t.getActionListeners()) {
            l.actionPerformed(new ActionEvent(t, ActionEvent.ACTION_PERFORMED, ""));
        }
    }

    static void stopTimers(WhackAMole2 g) {
        if (g.gameTimer != null) g.gameTimer.stop();
        if (g.moleTimer != null) g.moleTimer.stop();
    }

    // ------------------------------------------------------------------
    // Board / player helpers
    // ------------------------------------------------------------------

    /** Normalised board position (0..1, as drawn on screen) -> sensor cm. */
    static float fromNormX(float nx) {
        if (WhackAMole2.MIRROR_X) nx = 1f - nx;
        return WhackAMole2.PLAY_X_MIN + nx * (WhackAMole2.PLAY_X_MAX - WhackAMole2.PLAY_X_MIN);
    }

    static float fromNormY(float ny) {
        return WhackAMole2.PLAY_Y_MIN + ny * (WhackAMole2.PLAY_Y_MAX - WhackAMole2.PLAY_Y_MIN);
    }

    static float tileX(int idx) { return fromNormX(((idx % 3) + 0.5f) / 3f); }
    static float tileY(int idx) { return fromNormY(((idx / 3) + 0.5f) / 3f); }

    static void clearMoles(WhackAMole2 g) {
        for (JButton b : g.board) if (b != null) b.setIcon(null);
        g.currMoleTile = null;
        g.secondMoleTile = null;
    }

    static void placeMole(WhackAMole2 g, int idx) {
        clearMoles(g);
        g.currMoleTile = g.board[idx];
        g.currMoleTile.setIcon(g.moleIcon);
        g.clicked = 0;
        g.clicked2 = 0;
    }

    /** Fresh tracker holding exactly one fix, so the displayed position is exactly (x, y). */
    static void placePlayer(WhackAMole2 g, float x, float y) {
        WhackAMole2.PlayerTracker t = new WhackAMole2.PlayerTracker();
        t.onSample(x, y, System.currentTimeMillis());
        setTracker(g, t);
    }

    static void clearPlayer(WhackAMole2 g) {
        setTracker(g, new WhackAMole2.PlayerTracker());
    }

    static boolean hit(WhackAMole2 g, int moleIdx, float x, float y) {
        boolean[] result = {false};
        edt(() -> {
            g.score = 0;
            placeMole(g, moleIdx);
            placePlayer(g, x, y);
            callPrivate(g, "checkForHit");
            result[0] = (g.score == HIT_POINTS && g.clicked == 1);
        });
        return result[0];
    }

    static int scoreAfterAttempt(WhackAMole2 g, int startScore, int moleIdx, float x, float y) {
        int[] out = new int[1];
        edt(() -> {
            g.score = startScore;
            placeMole(g, moleIdx);
            placePlayer(g, x, y);
            callPrivate(g, "checkForHit");
            out[0] = g.score;
        });
        return out[0];
    }

    static int visibleMoles(WhackAMole2 g) {
        int n = 0;
        for (JButton b : g.board) if (b != null && b.getIcon() != null) n++;
        return n;
    }

    static int countTiles(Container c) {
        int n = 0;
        for (Component k : c.getComponents()) if (k instanceof JButton) n++;
        return n;
    }

    static void collectText(Container c, List<String> out) {
        for (Component k : c.getComponents()) {
            if (k instanceof JLabel) out.add(((JLabel) k).getText());
            if (k instanceof Container) collectText((Container) k, out);
        }
    }

    static boolean hasText(Container c, String s) {
        List<String> texts = new ArrayList<>();
        collectText(c, texts);
        return texts.contains(s);
    }

    static String fmt(float v) { return String.format("%.1f", v); }

    // ------------------------------------------------------------------
    // C1 - Hit detection
    // ------------------------------------------------------------------

    static void testC1(WhackAMole2 g) {
        int[] moles = {0, 4, 8, 2, 6};
        int num = 0;

        for (int idx : moles) {
            int c = idx % 3, r = idx / 3;
            int far = (idx == 4) ? 0 : 8 - idx;
            float cx = (c + 0.5f) / 3f, cy = (r + 0.5f) / 3f;
            float rightEdge = (c + 1) / 3f, bottomEdge = (r + 1) / 3f;

            Object[][] cases = {
                {"centre of mole tile",        cx,                            cy,                             true},
                {"1 cm inside right edge",     rightEdge - ONE_CM_X,          cy,                             true},
                {"1 cm outside right edge",    rightEdge + ONE_CM_X,          cy,                             false},
                {"1 cm inside bottom edge",    cx,                            bottomEdge - ONE_CM_Y,          true},
                {"1 cm outside bottom edge",   cx,                            bottomEdge + ONE_CM_Y,          false},
                {"centre of a different tile", ((far % 3) + 0.5f) / 3f,       ((far / 3) + 0.5f) / 3f,        false},
            };

            for (Object[] cs : cases) {
                float x = fromNormX((Float) cs[1]);
                float y = fromNormY((Float) cs[2]);
                boolean expHit = (Boolean) cs[3];
                boolean actHit = hit(g, idx, x, y);
                record(String.format("C1-%02d", ++num),
                        "mole T" + idx + " (col " + c + ",row " + r + ") | player (" + fmt(x) + ", " + fmt(y) + ") " + cs[0],
                        expHit ? "Hit" : "Miss", actHit ? "Hit" : "Miss", actHit == expHit);
            }
        }

        // Player outside the playable zone must never hit
        float[][] bad = {{75f, 10f}, {200f, 100f}};
        String[] why = {"too close to sensors", "off the board"};
        for (int i = 0; i < bad.length; i++) {
            boolean actHit = hit(g, 4, bad[i][0], bad[i][1]);
            record(String.format("C1-%02d", ++num),
                    "mole T4 (col 1,row 1) | player (" + fmt(bad[i][0]) + ", " + fmt(bad[i][1]) + ") " + why[i],
                    "Miss", actHit ? "Hit" : "Miss", !actHit);
        }
    }

    // ------------------------------------------------------------------
    // C2 - Level / difficulty logic
    // ------------------------------------------------------------------

    static void testC2(WhackAMole2 g) {
        // {game time in seconds, expected level}
        int[][] steps = {{0, 1}, {29, 1}, {30, 2}, {59, 2}, {60, 3}, {89, 3}, {90, 4}, {119, 4}};
        int num = 0;

        for (int[] st : steps) {
            int t = st[0], lvl = st[1];
            int expMoles = LEVEL_MOLES[lvl - 1], expMs = LEVEL_MS[lvl - 1];
            String[] actual = new String[1];
            boolean[] ok = new boolean[1];

            edt(() -> {
                g.gameTimeSec = t;
                callPrivate(g, "updateLevel");

                // spawnMoles() takes a life for an unhit mole, so start from a clean board
                g.life = START_LIVES;
                clearMoles(g);
                callPrivate(g, "spawnMoles");

                int vis = visibleMoles(g);
                int delay = g.moleTimer.getDelay();
                String label = g.levelLabel.getText();
                actual[0] = "level " + g.level + ", moles " + g.numberOfMoles + " (" + vis + " shown), "
                        + g.moleDisplayTime + " ms (timer " + delay + " ms)";
                ok[0] = g.level == lvl && g.numberOfMoles == expMoles && vis == expMoles
                        && g.moleDisplayTime == expMs && delay == expMs
                        && label.equals("Level: " + lvl);
            });

            record(String.format("C2-%02d", ++num),
                    "game time " + t + " s",
                    "level " + lvl + ", moles " + expMoles + ", " + expMs + " ms",
                    actual[0], ok[0]);
        }
    }

    // ------------------------------------------------------------------
    // C3 - Scoring logic
    // ------------------------------------------------------------------

    static void testC3(WhackAMole2 g) {
        // C3-01 hit
        int s = scoreAfterAttempt(g, 0, 4, tileX(4), tileY(4));
        record("C3-01", "Hit one mole, start score 0", "score 10", "score " + s, s == 10);

        // C3-02 hit
        s = scoreAfterAttempt(g, 10, 4, tileX(4), tileY(4));
        record("C3-02", "Hit one mole, start score 10", "score 20", "score " + s, s == 20);

        // C3-03 miss (player on a different tile)
        s = scoreAfterAttempt(g, 20, 4, tileX(0), tileY(0));
        record("C3-03", "Miss (player on wrong tile), start score 20", "score 20 (no change)", "score " + s, s == 20);

        // C3-04 three hits in a row
        int[] seq = {1, 5, 7};
        int running = 0;
        for (int idx : seq) running = scoreAfterAttempt(g, running, idx, tileX(idx), tileY(idx));
        record("C3-04", "3 hits (tiles 1, 5, 7), start score 0", "score 30", "score " + running, running == 30);

        // C3-05 mole expires uncaught (mole timer fires with no hit)
        int[] out = new int[1];
        edt(() -> {
            g.score = 20;
            g.life = START_LIVES;              // an uncaught mole costs a life, keep the game running
            placeMole(g, 3);
            placePlayer(g, tileX(8), tileY(8));
            fire(g.moleTimer);                 // mole time is up
            out[0] = g.score;
            clearPlayer(g);
        });
        record("C3-05", "Mole expires uncaught, start score 20", "score 20 (no change)", "score " + out[0], out[0] == 20);

        // C3-06 standing on a mole after the hit must not score again
        edt(() -> {
            g.score = 0;
            placeMole(g, 4);
            placePlayer(g, tileX(4), tileY(4));
            callPrivate(g, "checkForHit");
            callPrivate(g, "checkForHit");
            callPrivate(g, "checkForHit");
            out[0] = g.score;
            clearPlayer(g);
        });
        record("C3-06", "Stand on one mole for 3 checks, start score 0", "score 10 (scored once)", "score " + out[0], out[0] == 10);
    }

    // ------------------------------------------------------------------
    // C4 - Life system
    // ------------------------------------------------------------------

    static void testC4(WhackAMole2 g) {
        int[] out = new int[1];
        String[] label = new String[1];

        // C4-01 / C4-02: one miss each (a miss = the mole's time runs out, unhit)
        for (int i = 1; i <= 2; i++) {
            int before = START_LIVES - (i - 1), expected = before - 1;
            edt(() -> {
                g.life = before;
                placeMole(g, 4);
                clearPlayer(g);
                fire(g.moleTimer);
                out[0] = g.life;
                label[0] = g.lifeLabel.getText();
            });
            record("C4-0" + i, "Miss, start " + before + " lives",
                    "-1/3 of total (" + expected + " lives)",
                    out[0] + " lives (" + label[0] + ")",
                    out[0] == expected && label[0].equals("Lives: " + expected));
        }

        // C4-03: hit, then the mole's time runs out -> no life lost
        edt(() -> {
            g.life = START_LIVES;
            placeMole(g, 4);
            placePlayer(g, tileX(4), tileY(4));
            callPrivate(g, "checkForHit");
            fire(g.moleTimer);
            out[0] = g.life;
            clearPlayer(g);
        });
        record("C4-03", "Hit, start " + START_LIVES + " lives", "No change (" + START_LIVES + " lives)",
                out[0] + " lives", out[0] == START_LIVES);

        // C4-04: keep missing until the game ends
        boolean[] over = new boolean[1];
        edt(() -> {
            g.life = START_LIVES;
            g.gameTimer.restart();             // running, so we can check they get stopped
            g.moleTimer.restart();
            int misses = 0;
            while (g.life > 0 && misses < 10) {
                placeMole(g, 4);
                clearPlayer(g);
                fire(g.moleTimer);
                misses++;
            }
            out[0] = misses;
            over[0] = g.life == 0 && hasText(g.boardPanel, "GAME OVER")
                    && !g.gameTimer.isRunning() && !g.moleTimer.isRunning();
        });
        record("C4-04", "Miss until game over (from " + START_LIVES + " lives)",
                "Game ends after " + START_LIVES + " misses",
                over[0] ? "game over after " + out[0] + " misses"
                        : "game did not end correctly (" + out[0] + " misses)",
                over[0] && out[0] == START_LIVES);
    }

    // ------------------------------------------------------------------
    // C5 - Game-state progression
    // ------------------------------------------------------------------

    static void testC5Start(WhackAMole2 g) {
        // C5-01 start screen
        String[] actual = new String[1];
        boolean[] ok = new boolean[1];
        edt(() -> {
            StringBuilder bad = new StringBuilder();
            check(bad, hasText(g.boardPanel, "Gameplay Rules"), "rules heading missing");
            check(bad, hasText(g.boardPanel, "WHACK A MOLE"), "title missing");
            check(bad, g.startButton.isShowing(), "start button not showing");
            check(bad, !g.sensorPanel.isVisible(), "sensor marker visible");
            check(bad, g.moleTimer == null || !g.moleTimer.isRunning(), "game already running");
            ok[0] = bad.length() == 0;
            actual[0] = ok[0] ? "start screen shown with rules and button" : bad.toString();
        });
        record("C5-01", "Launch game", "Start screen", actual[0], ok[0]);

        // C5-02 game starts at level 1 (click the real START button)
        edt(() -> {
            g.startButton.doClick();
            StringBuilder bad = new StringBuilder();
            check(bad, g.level == 1, "level " + g.level);
            check(bad, g.score == 0, "score " + g.score);
            check(bad, g.life == START_LIVES, "lives " + g.life);
            check(bad, countTiles(g.boardPanel) == 9, "tiles " + countTiles(g.boardPanel));
            check(bad, visibleMoles(g) == 1, "moles shown " + visibleMoles(g));
            check(bad, g.gameTimer.isRunning() && g.moleTimer.isRunning(), "timers not running");
            check(bad, g.sensorPanel.isVisible(), "sensor marker hidden");
            check(bad, g.timerLabel.getText().equals("Timer: " + g.timeFrame), "timer label '" + g.timerLabel.getText() + "'");
            check(bad, g.levelLabel.getText().equals("Level: 1"), "level label '" + g.levelLabel.getText() + "'");
            check(bad, g.lifeLabel.getText().equals("Lives: " + START_LIVES), "lives label '" + g.lifeLabel.getText() + "'");
            ok[0] = bad.length() == 0;
            actual[0] = ok[0] ? "level 1, " + START_LIVES + " lives, 9 tiles, 1 mole, timers running" : bad.toString();
            stopTimers(g);
        });
        record("C5-02", "Click START", "Level 1", actual[0], ok[0]);
    }

    static void testC5Rest(WhackAMole2 g) {
        // fresh game, then step the real 1-second game timer to each level boundary
        edt(() -> {
            callPrivate(g, "startGame");
            stopTimers(g);
        });

        int[][] steps = {{30, 2}, {60, 3}, {90, 4}};
        int num = 2;
        for (int[] st : steps) {
            int t = st[0], lvl = st[1];
            String[] actual = new String[1];
            boolean[] ok = new boolean[1];
            edt(() -> {
                g.gameTimeSec = t - 1;
                fire(g.gameTimer);                     // one real clock tick
                StringBuilder bad = new StringBuilder();
                check(bad, g.gameTimeSec == t, "time " + g.gameTimeSec);
                check(bad, g.level == lvl, "level " + g.level);
                check(bad, g.levelLabel.getText().equals("Level: " + lvl), "label '" + g.levelLabel.getText() + "'");
                check(bad, g.timerLabel.getText().equals("Timer: " + (g.timeFrame - t)), "timer '" + g.timerLabel.getText() + "'");
                check(bad, g.moleTimer.getDelay() == LEVEL_MS[lvl - 1], "delay " + g.moleTimer.getDelay());
                ok[0] = bad.length() == 0;
                actual[0] = ok[0] ? "level " + lvl + " at " + t + " s" : bad.toString();
            });
            record(String.format("C5-%02d", ++num), "Clock reaches " + t + " s", "Level " + lvl, actual[0], ok[0]);
        }

        // C5-06 game over at the end of the timer
        String[] actual = new String[1];
        boolean[] ok = new boolean[1];
        edt(() -> {
            g.score = 40;
            g.gameTimeSec = g.timeFrame - 1;
            fire(g.gameTimer);                         // reaches 120 s -> game over
            StringBuilder bad = new StringBuilder();
            check(bad, hasText(g.boardPanel, "GAME OVER"), "GAME OVER missing");
            check(bad, hasText(g.boardPanel, "Final Score: 40"), "final score wrong");
            check(bad, hasText(g.boardPanel, "Level Reached: 4"), "level reached wrong");
            check(bad, g.retryButton.isShowing(), "retry button not showing");
            check(bad, !g.sensorPanel.isVisible(), "sensor marker visible");
            check(bad, !g.gameTimer.isRunning() && !g.moleTimer.isRunning(), "timers still running");
            ok[0] = bad.length() == 0;
            actual[0] = ok[0] ? "end screen: GAME OVER, score 40, level 4" : bad.toString();
        });
        record("C5-06", "Clock reaches " + 120 + " s", "End screen (score + level shown)", actual[0], ok[0]);

        // C5-07 play again restarts
        edt(() -> {
            g.retryButton.doClick();
            StringBuilder bad = new StringBuilder();
            check(bad, g.level == 1, "level " + g.level);
            check(bad, g.score == 0, "score " + g.score);
            check(bad, g.life == START_LIVES, "lives " + g.life);
            check(bad, countTiles(g.boardPanel) == 9, "tiles " + countTiles(g.boardPanel));
            check(bad, g.gameTimer.isRunning() && g.moleTimer.isRunning(), "timers not running");
            ok[0] = bad.length() == 0;
            actual[0] = ok[0] ? "new game at level 1, score 0, " + START_LIVES + " lives" : bad.toString();
            stopTimers(g);
        });
        record("C5-07", "Click PLAY AGAIN", "New game, level 1, score 0, full lives", actual[0], ok[0]);
    }

    // ------------------------------------------------------------------
    // C6 - Warning logic
    // ------------------------------------------------------------------

    static int frames(int beepMs, int beeps) {
        float rate = 44100f;
        int bs = (int) (rate * beepMs / 1000);
        int gap = (int) (rate * 0.06f);
        return beeps * bs + (beeps - 1) * gap;
    }

    static void stopClips(WhackAMole2 g) {
        g.closeClip.stop();
        g.closeClip.setFramePosition(0);
        g.offClip.stop();
        g.offClip.setFramePosition(0);
    }

    static String beepReport(WhackAMole2 g) {
        boolean c = g.closeClip.isRunning(), o = g.offClip.isRunning();
        if (!c && !o) return "no beep";
        if (c && o) return "both clips playing";
        if (c) return g.closeClip.getFrameLength() == frames(150, 2) ? "2 beeps" : "close clip, wrong length";
        return g.offClip.getFrameLength() == frames(300, 1) ? "1 beep" : "off clip, wrong length";
    }

    static void c6Case(WhackAMole2 g, String id, String label, boolean hasFix, boolean gameRunning,
                       float x, float y, String expZone, String expBeep) {
        boolean audio = g.closeClip != null && g.offClip != null;
        String[] out = new String[2];

        edt(() -> {
            if (audio) stopClips(g);

            WhackAMole2.PlayerTracker t = new WhackAMole2.PlayerTracker();
            if (hasFix) t.onSample(x, y, System.currentTimeMillis());
            setTracker(g, t);

            if (gameRunning) {
                g.moleTimer.setInitialDelay(60000);
                g.moleTimer.setDelay(60000);
                g.moleTimer.restart();
            } else {
                g.moleTimer.stop();
            }

            out[0] = zoneOf(g, g.tracker.get());
            callPrivate(g, "updateWarningSound");
            sleep(40);
            out[1] = audio ? beepReport(g) : "n/a (no audio device)";

            // reset so the next case starts clean
            if (audio) stopClips(g);
            clearPlayer(g);
            callPrivate(g, "updateWarningSound");
            g.moleTimer.stop();
        });

        boolean zoneOk = out[0].equals(expZone);
        String actual = out[1] + " (zone " + out[0] + ")";
        String expected = expBeep + " (zone " + expZone + ")";
        if (!audio) {
            record(id, label, expected, actual, zoneOk ? "SKIP" : "FAIL");
        } else {
            record(id, label, expected, actual, zoneOk && out[1].equals(expBeep));
        }
    }

    static void c6Repeat(WhackAMole2 g, String id, String label, long waitMs, String expBeep) {
        boolean audio = g.closeClip != null && g.offClip != null;
        if (!audio) {
            record(id, label, expBeep, "n/a (no audio device)", "SKIP");
            return;
        }
        String[] out = new String[1];
        edt(() -> {
            stopClips(g);
            placePlayer(g, 75f, 10f);                  // too close
            g.moleTimer.setInitialDelay(60000);
            g.moleTimer.setDelay(60000);
            g.moleTimer.restart();

            callPrivate(g, "updateWarningSound");      // first beep
            stopClips(g);
            sleep(waitMs);
            placePlayer(g, 75f, 10f);                  // keep the fix fresh
            callPrivate(g, "updateWarningSound");      // second call
            sleep(40);
            out[0] = beepReport(g);

            stopClips(g);
            clearPlayer(g);
            callPrivate(g, "updateWarningSound");
            g.moleTimer.stop();
        });
        record(id, label, expBeep, out[0], out[0].equals(expBeep));
    }

    static void testC6(WhackAMole2 g) {
        c6Case(g, "C6-01", "Safe, centre of board (75, 100)",             true,  true,  75f, 100f, "ON_BOARD",  "no beep");
        c6Case(g, "C6-02", "Safe, just outside near limit (75, 31)",      true,  true,  75f,  31f, "ON_BOARD",  "no beep");
        c6Case(g, "C6-03", "Too close (75, 10)",                          true,  true,  75f,  10f, "TOO_CLOSE", "2 beeps");
        c6Case(g, "C6-04", "Too close, near limit (75, 29)",              true,  true,  75f,  29f, "TOO_CLOSE", "2 beeps");
        c6Case(g, "C6-05", "Off-board, x too large (160, 100)",           true,  true, 160f, 100f, "OFF_BOARD", "1 beep");
        c6Case(g, "C6-06", "Off-board, x negative (-10, 100)",            true,  true, -10f, 100f, "OFF_BOARD", "1 beep");
        c6Case(g, "C6-07", "Off-board, y too large (75, 230)",            true,  true,  75f, 230f, "OFF_BOARD", "1 beep");
        c6Case(g, "C6-08", "No sensor fix",                               false, true,   0f,   0f, "NO_FIX",    "no beep");
        c6Case(g, "C6-09", "Too close but game not running (start/end screen)", true, false, 75f, 10f, "TOO_CLOSE", "no beep");
        c6Repeat(g, "C6-10", "Stay too close, second check right away (repeat suppressed)", 0, "no beep");
        c6Repeat(g, "C6-11", "Stay too close, second check after " + (WhackAMole2.WARN_REPEAT_MS + 100) + " ms (repeat)",
                WhackAMole2.WARN_REPEAT_MS + 100, "2 beeps");
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    static void printResults() {
        String line = "%-7s | %-70s | %-34s | %-62s | %s%n";
        String last = "";
        for (Row r : rows) {
            String group = r.id.substring(0, 2);
            if (!group.equals(last)) {
                System.out.println();
                System.out.println("=== " + group + " ===");
                System.out.printf(line, "Test", "Input", "Expected", "Actual", "Result");
                last = group;
            }
            System.out.printf(line, r.id, r.input, r.expected, r.actual, r.result);
        }

        Map<String, int[]> summary = new LinkedHashMap<>();   // group -> {pass, total, skip}
        for (Row r : rows) {
            int[] s = summary.computeIfAbsent(r.id.substring(0, 2), k -> new int[3]);
            if (r.result.equals("SKIP")) { s[2]++; continue; }
            s[1]++;
            if (r.result.equals("PASS")) s[0]++;
        }

        System.out.println();
        System.out.println("=== SUMMARY ===");
        for (Map.Entry<String, int[]> e : summary.entrySet()) {
            int[] s = e.getValue();
            System.out.printf("%s: %d / %d passed%s%n", e.getKey(), s[0], s[1], s[2] > 0 ? " (" + s[2] + " skipped)" : "");
        }
        int[] c1 = summary.get("C1");
        if (c1 != null && c1[1] > 0) {
            System.out.printf("C1 detection accuracy: %d / %d x 100 = %.1f%%%n", c1[0], c1[1], 100.0 * c1[0] / c1[1]);
        }
    }

    static void writeCsv(String path) {
        try (PrintWriter w = new PrintWriter(path, "UTF-8")) {
            w.println("Test,Input,Expected,Actual,Result");
            for (Row r : rows) {
                w.println(q(r.id) + "," + q(r.input) + "," + q(r.expected) + "," + q(r.actual) + "," + q(r.result));
            }
            System.out.println("Results written to " + path);
        } catch (Exception e) {
            System.err.println("Could not write CSV: " + e.getMessage());
        }
    }

    static String q(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }

    // ------------------------------------------------------------------
    // Main
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("These tests open the game window, so they need a display (not headless).");
            System.exit(2);
        }

        WhackAMole2 game;
        try {
            game = edtGet(() -> new WhackAMole2());
        } catch (RuntimeException e) {
            System.err.println("Could not create the game: " + e.getCause());
            System.err.println("Check that grass.png, hole.png and mole.png are next to the class files, and that");
            System.err.println("SerialTest.initialize() can run without the sensor hardware.");
            e.printStackTrace();
            System.exit(2);
            return;
        }
        final WhackAMole2 g = game;

        // wait for the window to be laid out (tile mapping needs the board size)
        long end = System.currentTimeMillis() + 5000;
        while (g.boardPanel.getWidth() == 0 && System.currentTimeMillis() < end) sleep(50);
        sleep(300);

        testC5Start(g);
        testC1(g);
        testC2(g);
        testC3(g);
        testC4(g);
        testC5Rest(g);
        testC6(g);

        rows.sort((a, b) -> a.id.compareTo(b.id));
        printResults();
        writeCsv("test_results.csv");

        boolean anyFail = false;
        for (Row r : rows) if (r.result.equals("FAIL")) anyFail = true;
        System.exit(anyFail ? 1 : 0);
    }
}
