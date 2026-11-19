/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package general.sound;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

/**
 * A note player that synthesizes the notes itself and plays them with an AudioTrack.
 * <br><br>
 * Android port: it takes the place of MobileMediaAPINotePlayer, since Android has
 * no Mobile Media API (JSR-135) and so no Manager.playTone(). Like playTone it is
 * non-blocking: playNote() only tells a synthesizer thread which note to play and
 * for how long. The notes are square waves - the sound of the PC speaker Digger
 * was written for - and, as on the speaker, one note sounds at a time
 * (NoteSeriallizer sees to that).
 *
 * @author Haim Avron
 */
public final class AudioTrackNotePlayer implements NotePlayer {

    private final int ASSUMED_DELAY = 10;

    private static final double LOUDNESS = 0.22;    // at volume 100, of the 16 bit range
    private static final int CHUNK = 256;           // frames synthesized at a time (5-6 ms)
    private static final int IDLE_MILLIS = 1500;    // after this much silence the track rests

    // Guarded by this
    private boolean started = false;
    private boolean failed = false;
    private AudioTrack track;
    private int sampleRate;
    private double frequency;
    private double amplitude;
    private long framesLeft;        // still to play of the current note
    private boolean newNote;

    private AudioTrackNotePlayer() {

    }

    private static AudioTrackNotePlayer instance = new AudioTrackNotePlayer();
    public static AudioTrackNotePlayer getInstance() {
      return instance;
    }

    public boolean isBlocking() {
        return false;
    }

    public int assumedDelay() {
        return ASSUMED_DELAY;
    }

    public void playNote(int note, int duration, int volume) throws NotePlayingException {
        synchronized (this) {
            if (!start())
                throw new NotePlayingException();
            frequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0);    // MIDI note numbers, as in playTone
            amplitude = Math.max(0, Math.min(100, volume)) / 100.0 * LOUDNESS * Short.MAX_VALUE;
            framesLeft = (long) duration * sampleRate / 1000;
            newNote = true;
            notifyAll();
        }
    }

    /** Creates the track and the synthesizer thread, the first time. Called with the lock held. */
    private boolean start() {
        if (started)
            return !failed;
        started = true;
        try {
            int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
            if (rate <= 0)
                rate = 44100;
            int minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) {
                failed = true;
                return false;
            }
            AudioTrack newTrack = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    Math.max(minBuffer, 8 * CHUNK),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (newTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                newTrack.release();
                failed = true;
                return false;
            }
            track = newTrack;
            sampleRate = rate;
        } catch (RuntimeException e) {
            failed = true;
            return false;
        }
        new Thread(new Runnable() {
            public void run() {
                synthesize();
            }
        }, "DiggerTones").start();
        return true;
    }

    private void synthesize() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] buffer = new short[CHUNK];
        double phase = 0;
        double gain = 0;
        double smoothing = 1 - Math.exp(-1.0 / (0.0015 * sampleRate));   // notes start and end in ~1.5 ms: no clicks
        long idleLimit = (long) IDLE_MILLIS * sampleRate / 1000;
        long idleFrames = 0;
        boolean playing = false;
        try {
            while (true) {
                double step, level;
                long left;
                synchronized (this) {
                    if (framesLeft <= 0 && idleFrames >= idleLimit) {
                        // Nothing to play for a while: let the track rest until the next note.
                        if (playing) {
                            track.pause();
                            track.flush();
                            playing = false;
                        }
                        while (framesLeft <= 0)
                            wait();
                        idleFrames = 0;
                        gain = 0;
                    }
                    newNote = false;
                    step = frequency / sampleRate;
                    level = amplitude;
                    left = framesLeft;
                }
                if (!playing) {
                    track.play();
                    playing = true;
                }

                for (int i = 0; i < CHUNK; i++) {
                    double target = 0;
                    if (left > 0) {
                        target = level;
                        left--;
                    }
                    gain += (target - gain) * smoothing;
                    // A square wave, its edges smoothed (PolyBLEP) so high notes do not alias
                    double half = phase < 0.5 ? phase + 0.5 : phase - 0.5;
                    double value = (phase < 0.5 ? 1 : -1) + polyBlep(phase, step) - polyBlep(half, step);
                    phase += step;
                    if (phase >= 1)
                        phase -= 1;
                    buffer[i] = (short) (value * gain);
                }

                synchronized (this) {
                    if (!newNote)
                        framesLeft = left;
                    idleFrames = framesLeft <= 0 ? idleFrames + CHUNK : 0;
                }
                // Blocks while the track is full, which keeps this thread in step with the sound.
                if (track.write(buffer, 0, CHUNK) < 0)
                    Thread.sleep(1 + CHUNK * 1000L / sampleRate);   // the track broke; do not spin
            }
        } catch (InterruptedException e) {
            // Just exit
        } catch (RuntimeException e) {
            // The track cannot play; next notes are ignored.
            synchronized (this) {
                failed = true;
                track.release();
            }
        }
    }

    /** The PolyBLEP correction of a step at phase 0, for a wave advancing dt per frame. */
    private static double polyBlep(double t, double dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1;
        }
        if (t > 1 - dt) {
            t = (t - 1) / dt;
            return t * t + t + t + 1;
        }
        return 0;
    }
}
