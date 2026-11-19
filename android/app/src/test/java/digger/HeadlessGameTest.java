package digger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import general.sound.TunePlayer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Runs the game engine - the code of the JavaME version - on the JVM, without a
 * device: the title screen, a game, keys, the high score file and the sound.
 * The frames it shows are saved as PNG files (in build/digger-frames, or where the
 * digger.frames system property says) to be looked at.
 * <p>
 * The few Android classes the engine meets (MediaPlayer, AudioTrack) do nothing
 * here, see unitTests.isReturnDefaultValues in build.gradle.kts.
 */
public class HeadlessGameTest {

    private static final int W = 320, H = 200;
    private static File storage;
    private static File frames;

    @BeforeClass
    public static void setUpFrames() {
        frames = new File(System.getProperty("digger.frames", "build/digger-frames"));
        frames.mkdirs();
    }

    @Before
    public void setUpStorage() throws Exception {
        storage = Files.createTempDirectory("digger").toFile();     // each test starts with no high scores
        ScoreStorage.setDirectory(storage);
    }

    @Test
    public void titleScreenThenAGame() throws Exception {
        final DiggerCanvas dig = new DiggerCanvas();
        dig.frametime = 10;     // the title animation, 8 times faster
        assertFalse(new File(storage, "digger.scores").exists());
        dig.start();
        dig.setForeground(true);    // what a visible activity does

        // The title screen, with its animation (monsters, digger, gold, emerald, bonus)
        Thread.sleep(3500);
        int[] title = save(dig, "title.png");
        assertTrue("the title screen is in CGA colors", colors(title).size() >= 3);
        assertTrue("the high score file was created", new File(storage, "digger.scores").exists());
        assertEquals(1, dig.Main.nplayers);

        // Any key starts a game
        dig.frametime = 80;
        tap(dig);
        waitFor(new BooleanSupplier() {
            public boolean getAsBoolean() {
                return dig.digonscr && dig.diggervisible;
            }
        }, 5000);
        Thread.sleep(500);
        save(dig, "level1.png");
        assertEquals(3, dig.Main.getlives(1));
        assertEquals(1, dig.Main.levno());
        assertTrue("level 1 has emeralds", dig.countem() > 0);

        // The digger starts at the bottom middle, and the left key moves it left
        final int startX = dig.diggerx;
        assertEquals(7 * 20 + 12, startX);
        assertEquals(9 * 18 + 18, dig.diggery);
        dig.keyPressed(DiggerCanvas.LEFT);
        waitFor(new BooleanSupplier() {
            public boolean getAsBoolean() {
                return dig.diggerx <= startX - 40;
            }
        }, 5000);
        dig.keyReleased(DiggerCanvas.LEFT);
        save(dig, "moved.png");

        // ...and the up key digs a new tunnel
        final int startY = dig.diggery;
        dig.keyPressed(DiggerCanvas.UP);
        waitFor(new BooleanSupplier() {
            public boolean getAsBoolean() {
                return dig.diggery <= startY - 36;
            }
        }, 5000);
        dig.keyReleased(DiggerCanvas.UP);
        Thread.sleep(300);
        save(dig, "dug.png");

        // The pause (what the activity does when it is hidden or opens the menu): the
        // game waits at PRESS ANY KEY, and shows no new frames, until a key
        dig.pauseState(true);
        Thread.sleep(500);
        int pausedFrame = save(dig, "paused.png", null);
        Thread.sleep(400);
        assertTrue("still paused", dig.paused);
        assertEquals("no frames while paused", pausedFrame, save(dig, null, null));
        tap(dig);
        waitFor(new BooleanSupplier() {
            public boolean getAsBoolean() {
                return !dig.paused;
            }
        }, 2000);

        // "Restart" goes back to the title screen
        dig.endgame();
        waitFor(new BooleanSupplier() {
            public boolean getAsBoolean() {
                return !dig.Input.escape;
            }
        }, 5000);
        Thread.sleep(1500);
        save(dig, "title-again.png");
    }

    @Test
    public void highScoresAreKept() {
        String[] names = {"...", "ABC", "DEF", "GHI", "JKL", "MNO", "PQR", "STU", "VWX", "YZA", "BCD"};
        long[] scores = {0, 0, 10000, 9000, 8000, 7000, 6000, 5000, 4000, 3000, 2000, 1000};
        ScoreStorage.writeToStorage(names, scores);
        assertTrue(ScoreStorage.scoreStorageExists());
        String[] readNames = new String[11];
        long[] readScores = new long[12];
        assertTrue(ScoreStorage.readFromStorage(readNames, readScores));
        assertArrayEquals(names, readNames);
        assertArrayEquals(scores, readScores);
    }

    @Test
    public void aReleasedKeyStartsNoGame() {
        // A tap during GAME OVER must not start a game as soon as the title screen is up
        DiggerCanvas dig = new DiggerCanvas();
        dig.keyPressed(DiggerCanvas.ANY_KEY);
        dig.keyReleased(DiggerCanvas.ANY_KEY);
        assertFalse(dig.Input.teststart());
        dig.keyPressed(DiggerCanvas.ANY_KEY);
        assertTrue(dig.Input.teststart());
    }

    @Test(timeout = 10000)
    public void aHiddenGameWaits() throws Exception {
        final DiggerCanvas dig = new DiggerCanvas();
        Thread waiter = new Thread(new Runnable() {
            public void run() {
                dig.waitWhileHidden();
            }
        });
        waiter.start();
        waiter.join(300);
        assertTrue("it waits while no activity shows the game", waiter.isAlive());
        dig.setForeground(true);
        waiter.join(5000);
        assertFalse(waiter.isAlive());
        dig.setForeground(false);
    }

    @Test
    public void musicThatIsOverIsNotResumed() throws Exception {
        NewSound sound = new NewSound();
        sound.setMusicMode(NewSound.TONE_MUSICE_MODE);     // players that can be watched here
        TunePlayer normal = player(sound, "normalBackgroundPlayer");
        TunePlayer bonus = player(sound, "bonusBackgroundPlayer");
        TunePlayer death = player(sound, "deathPlayer");

        // A level ends in bonus mode; the next level is paused and goes on
        sound.startBonusBackgroundMusic();
        assertTrue(bonus.isPlaying());
        sound.killAll();
        sound.startNormalBackgroundMusic();
        sound.changePauseState(true);
        assertFalse(normal.isPlaying());
        sound.changePauseState(false);
        assertTrue(normal.isPlaying());
        assertFalse("no bonus tune over the music of the next level", bonus.isPlaying());

        // The digger dies, the game is paused and given up (Quit); the next game starts
        sound.killAll();
        sound.playDeath();
        sound.changePauseState(true);
        sound.killAll();
        sound.changePauseState(false);
        assertFalse("no funeral tune at the start of the next game", death.isPlaying());
        assertFalse(normal.isPlaying());
        sound.killAll();
    }

    @Test
    public void aPausedGameStartsNoTunes() throws Exception {
        NewSound sound = new NewSound();
        sound.setMusicMode(NewSound.TONE_MUSICE_MODE);
        TunePlayer normal = player(sound, "normalBackgroundPlayer");
        TunePlayer death = player(sound, "deathPlayer");

        // Between the pause and testpause() the game thread starts a new level
        sound.changePauseState(true);
        sound.startNormalBackgroundMusic();
        assertFalse(normal.isPlaying());
        sound.changePauseState(false);
        assertTrue(normal.isPlaying());

        // ...or the digger dies then
        sound.changePauseState(true);
        sound.stopNormalBackgroundMusic();
        sound.playDeath();
        assertFalse(death.isPlaying());
        sound.changePauseState(false);
        assertTrue(death.isPlaying());
        assertFalse(normal.isPlaying());
        sound.killAll();
    }

    @Test
    public void noMusicOverTheFuneralTune() throws Exception {
        NewSound sound = new NewSound();
        sound.setMusicMode(NewSound.TONE_MUSICE_MODE);
        TunePlayer normal = player(sound, "normalBackgroundPlayer");
        TunePlayer death = player(sound, "deathPlayer");

        // A bag kills the digger in bonus mode: the end of the bonus mode asks for the music
        sound.playDeath();
        sound.startNormalBackgroundMusic();
        assertTrue(death.isPlaying());
        assertFalse(normal.isPlaying());
        sound.changePauseState(true);
        sound.changePauseState(false);
        assertTrue(death.isPlaying());
        assertFalse(normal.isPlaying());

        // ...nor once the funeral tune is over, and the digger still lies there
        death.stopPlaying();
        sound.changePauseState(true);
        sound.changePauseState(false);
        assertFalse(normal.isPlaying());
        sound.killAll();
    }

    @Test
    public void theMusicGoesOnAfterAChangeOfTheSoundSettings() throws Exception {
        NewSound sound = new NewSound();
        sound.setMusicMode(NewSound.TONE_MUSICE_MODE);
        sound.startNormalBackgroundMusic();

        // Mute and unmute in the menu (the game is paused under it)
        sound.changePauseState(true);
        sound.setMuteState(true);
        sound.setMuteState(false);
        sound.changePauseState(false);
        assertTrue(player(sound, "normalBackgroundPlayer").isPlaying());

        // The music mode, there and back
        sound.changePauseState(true);
        sound.setMusicMode(NewSound.MIDI_MUSIC_MODE);
        sound.setMusicMode(NewSound.TONE_MUSICE_MODE);
        sound.changePauseState(false);
        assertTrue(player(sound, "normalBackgroundPlayer").isPlaying());
        sound.killAll();
    }

    @Test
    public void musicModesCanBeSwitchedBackAndForth() {
        NewSound sound = new NewSound();
        sound.setMusicMode(NewSound.TONE_MUSICE_MODE);
        sound.startNormalBackgroundMusic();
        sound.fireStart();
        sound.fireEnd();
        sound.setMusicMode(NewSound.MIDI_MUSIC_MODE);     // threw a NullPointerException in JavaME
        assertEquals(NewSound.MIDI_MUSIC_MODE, sound.getMusicMode());
        sound.setMuteState(true);
        assertTrue(sound.isMute());
    }

    // ------------------------------------------------------------------

    /** A tap, as DiggerView sends it: the press, and its release a frame (and more) later. */
    private static void tap(DiggerCanvas dig) throws InterruptedException {
        dig.keyPressed(DiggerCanvas.ANY_KEY);
        Thread.sleep(400);
        dig.keyReleased(DiggerCanvas.ANY_KEY);
    }

    private static TunePlayer player(NewSound sound, String name) throws Exception {
        Field field = NewSound.class.getDeclaredField(name);
        field.setAccessible(true);
        return (TunePlayer) field.get(sound);
    }

    private static int[] save(DiggerCanvas dig, String name) throws Exception {
        int[] frame = new int[W * H];
        save(dig, name, frame);
        return frame;
    }

    /** Saves the last frame the game showed (if a name is given); returns its number. */
    private static int save(DiggerCanvas dig, String name, int[] frame) throws Exception {
        if (frame == null)
            frame = new int[W * H];
        int number = dig.Pc.displayUpdater.getFrame(frame);
        if (name != null)
            writePng(frame, W, H, new File(frames, name));
        return number;
    }

    /** A minimal PNG writer (unit tests compile against android.jar, which has no javax.imageio). */
    private static void writePng(int[] argb, int w, int h, File file) throws IOException {
        ByteArrayOutputStream rows = new ByteArrayOutputStream();
        for (int y = 0; y < h; y++) {
            rows.write(0);      // no filter
            for (int x = 0; x < w; x++) {
                int pixel = argb[y * w + x];
                rows.write(pixel >> 16 & 0xff);
                rows.write(pixel >> 8 & 0xff);
                rows.write(pixel & 0xff);
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        DeflaterOutputStream deflater = new DeflaterOutputStream(compressed);
        deflater.write(rows.toByteArray());
        deflater.close();

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream ihdr = new DataOutputStream(header);
        ihdr.writeInt(w);
        ihdr.writeInt(h);
        ihdr.write(new byte[] {8, 2, 0, 0, 0});    // 8 bit RGB, no interlace

        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));
        try {
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            pngChunk(out, "IHDR", header.toByteArray());
            pngChunk(out, "IDAT", compressed.toByteArray());
            pngChunk(out, "IEND", new byte[0]);
        } finally {
            out.close();
        }
    }

    private static void pngChunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes("US-ASCII");
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeInt(data.length);
        out.write(name);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }

    private static Set<Integer> colors(int[] frame) {
        Set<Integer> colors = new HashSet<Integer>();
        for (int pixel : frame)
            colors.add(pixel);
        return colors;
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            assertTrue("timed out", System.currentTimeMillis() < end);
            Thread.sleep(20);
        }
    }
}
