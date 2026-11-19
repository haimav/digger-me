/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package general.sound;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

/**
 *
 * Notes: <br>
 * 1. On many devices only one file based tune can be used at a time. This class enforces this.
 * 2. On some devices there is a minimum wait between tune playing. This class enforces this.
 * 2. On most devices and no tones can be used while playing a file based tune. <br>
 * 2. Not thread safe. <br>
 * 3. On some devices pausePlay will not work correctly (it will work exactly like stopPlay). <br>
 * <br>
 * Android port: the tune is played by a MediaPlayer (Android plays MIDI files by
 * itself). The files are assets of the application, under the same paths the
 * JavaME version loaded as resources ("/audiofiles/..."). Android has none of the
 * limits in the notes above, so nothing is enforced; and this version is thread safe.
 * <br>
 * @author Haim Avron
 */
public class ResourceTunePlayer implements TunePlayer {

    private static AssetManager assets = null;

    /**
     * Android port: where the files are found. Set once, before the first player
     * is created (by DiggerActivity). Without it, players stay silent.
     */
    public static void setAssets(AssetManager assetManager) {
        assets = assetManager;
    }

    private MediaPlayer player;     // null if the tune could not be loaded, or after close()
    private int volume;

    public ResourceTunePlayer(String fileName) {
        this(fileName, -1);
    }

    public ResourceTunePlayer(String fileName, int volume) {
        MediaPlayer builtPlayer = null;
        if (assets != null) {
            try {
                String path = fileName.startsWith("/") ? fileName.substring(1) : fileName;
                AssetFileDescriptor file = assets.openFd(path);
                try {
                    builtPlayer = new MediaPlayer();
                    builtPlayer.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build());
                    builtPlayer.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
                    builtPlayer.prepare();
                } finally {
                    file.close();
                }
            } catch (Exception e) {
                // No player??? do nothing!
                if (builtPlayer != null)
                    builtPlayer.release();
                builtPlayer = null;
            }
        }
        this.player = builtPlayer;
        this.volume = volume;
    }

    public synchronized void playInLoop() {
        play(true);
    }

    public synchronized void playSingle() {
        play(false);
    }

    public synchronized void stopPlaying() {
        if (player == null)
            return;
        try {
            if (player.isPlaying())
                player.pause();
            player.seekTo(0);
        } catch (IllegalStateException e) {

        }
    }

    public synchronized void pausePlaying() {
        if (player == null)
            return;
        try {
            if (player.isPlaying())
                player.pause();
        } catch (IllegalStateException e) {

        }
    }

    public synchronized void rewind() {
        if (player == null)
            return;
        try {
            player.seekTo(0);
        } catch (IllegalStateException e) {

        }
    }

    public synchronized void setVolume(int volume) {
        this.volume = volume;
    }

    public void setPriority(int priority) {
        // IGNORED!!! NOT SUPPORTED!!!
    }

    public synchronized boolean isPlaying() {
        if (player == null)
            return false;
        try {
            return player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public synchronized void close() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    public synchronized boolean isClosed() {
        return player == null;
    }

    /** Starts the tune where it was paused (or from its start, if it was stopped or played to its end). */
    private void play(boolean loop) {
        if (player == null || isPlaying())
            return;
        try {
            setupVolume();
            player.setLooping(loop);
            player.start();
        } catch (IllegalStateException e) {

        }
    }

    private void setupVolume() {
        if (volume != -1) {
            float level = Math.max(0, Math.min(100, volume)) / 100f;
            player.setVolume(level, level);
        }
    }
}
