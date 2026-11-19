package digger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Runs the game engine - the code of the JavaME version - on the JVM, without a
 * device: the title screen, a game, keys, the high score file and the sound modes.
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
    public static void setUp() throws Exception {
        storage = Files.createTempDirectory("digger").toFile();
        ScoreStorage.setDirectory(storage);
        frames = new File(System.getProperty("digger.frames", "build/digger-frames"));
        frames.mkdirs();
    }

    @Test
    public void titleScreenThenAGame() throws Exception {
        final DiggerCanvas dig = new DiggerCanvas();
        dig.frametime = 10;     // the title animation, 8 times faster
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
        dig.keyPressed(DiggerCanvas.ANY_KEY);
        dig.keyReleased(DiggerCanvas.ANY_KEY);
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

        // The pause (what the activity does when it is hidden or opens the menu)
        dig.pauseState(true);
        Thread.sleep(500);
        save(dig, "paused.png");
        final int pausedX = dig.diggerx, pausedY = dig.diggery;
        Thread.sleep(400);
        assertEquals(pausedX, dig.diggerx);
        assertEquals(pausedY, dig.diggery);
        dig.keyPressed(DiggerCanvas.ANY_KEY);
        dig.keyReleased(DiggerCanvas.ANY_KEY);
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

    private static int[] save(DiggerCanvas dig, String name) throws Exception {
        int[] frame = new int[W * H];
        dig.Pc.displayUpdater.getFrame(frame);
        writePng(frame, W, H, new File(frames, name));
        return frame;
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
