/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package general.display;

/**
 * This class manages a display that is backed-up by a virtual memory background display.
 * The background display consists of an array of pixel values, each value is an index in a pallete.
 * The updater is notified of changes to this array and will update the actual display based on the
 * pixels values. <br><br>
 *
 * This class is inteded for use when porting old-games that update the display using actual pixel values.
 * <br><br>
 *
 * Android port: the JavaME version scaled, rotated and drew the pixels on a GameCanvas
 * itself. Here the updater keeps an RGB image of the virtual display, in the virtual
 * display's own resolution, and {@link #flush()} publishes it as a frame. The view
 * that listens to the frames (see {@link FrameListener}) scales them to the screen,
 * and Android takes care of the rotation. Nothing in this class depends on Android.
 *
 * @author Haim Avron
 */
public class MemoryDisplayUpdater {

    /**
     * Android port: told about every flushed frame. It is called on the thread that
     * flushed (usually the game thread) and must return quickly.
     */
    public interface FrameListener {
        void frameReady();
    }

    private final int width;
    private final int height;
    private final int[] pixels;

    private final int[] rgbData;    // RGB image of the virtual display, kept up to date by newPixels()
    private final int[] frameData;  // the last flushed frame

    private final int[][] rgbMaps;
    private int[] currentRgbMap;

    private FrameListener frameListener;
    private int frameNumber = 0;

    /**
     * @param pixels - the virtual display, row by row, one pallete index per pixel.
     * @param width - the virtual display's width.
     * @param height - the virtual display's height.
     * @param numberPalletes - how many palletes will be defined.
     */
    public MemoryDisplayUpdater(int[] pixels, int width, int height, int numberPalletes) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        this.rgbData = new int[width * height];
        this.frameData = new int[width * height];
        this.rgbMaps = new int[numberPalletes][];
        this.currentRgbMap = null;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Sets the color values of a pallete.
     *
     * @param index - index of the pallete to change.
     * @param r - red values for pallete.
     * @param g - green values for pallete.
     * @param b - blue values for pallete.
     */
    public void setPallete(int index, int[] r, int[] g, int[] b) {
        boolean changingActivePallete;
        synchronized (this) {
            changingActivePallete = (rgbMaps[index] == currentRgbMap);

            rgbMaps[index] = new int[r.length];
            for (int i = 0; i < r.length; i++) {
                rgbMaps[index][i] = 0xff000000 | (b[i] + (g[i] << 8) + (r[i] << 16));
            }
            if (currentRgbMap == null) {
                currentRgbMap = rgbMaps[index];
            }
        }
        if (changingActivePallete)
            newPixels(true);
    }

    /**
     * Switches the currently active pallete.
     *
     * @param newPalleteIndex - new pallete to be active.
     */
    public void switchPallete(int newPalleteIndex) {
        synchronized (this) {
            if (rgbMaps[newPalleteIndex] == null) {
                throw new RuntimeException("Pallete " + newPalleteIndex + " is undefined.");
            }
            currentRgbMap = rgbMaps[newPalleteIndex];
            convert(0, 0, width, height);
        }
        flush();
    }

    /**
     * Notify the updater of new pixel values for all the display.
     *
     * @see newPixels(int x, int y, int w, int h)
     */
    public void newPixels(boolean immediateFlush) {
        newPixels(immediateFlush, 0, 0, width, height);
    }

    /**
     * Notify the update of new pixels values for a rectangular region of the pixels.
     *
     * @param immediateFlush - whether to flush the display immediatly
     * @param x - the x value of the top-left corner of the region.
     * @param y - the y value of the top-left corner of the region.
     * @param w - the region's width.
     * @param h - the region's height.
     */
    public void newPixels(boolean immediateFlush, int x, int y, int w, int h) {
        synchronized (this) {
            if (currentRgbMap == null) {
                throw new RuntimeException("Need to add at least one pallete.");
            }
            // The game sometimes reports regions that cross the edges of the display.
            int x1 = Math.min(x + w, width);
            int y1 = Math.min(y + h, height);
            int x0 = Math.max(x, 0);
            int y0 = Math.max(y, 0);
            if (x1 > x0 && y1 > y0)
                convert(x0, y0, x1 - x0, y1 - y0);
        }
        if (immediateFlush)
            flush();
    }

    /**
     * Publishes the current image of the display as a frame.
     */
    public void flush() {
        synchronized (this) {
            System.arraycopy(rgbData, 0, frameData, 0, frameData.length);
            frameNumber++;
        }
        notifyFrameListener();
    }

    /**
     * Asks the listener to show the last frame again (e.g. after its surface was recreated).
     */
    public void repaint() {
        notifyFrameListener();
    }

    /**
     * Android port: copies the last flushed frame, as width * height ARGB values.
     *
     * @return the number of the frame (it grows with every flush).
     */
    public synchronized int getFrame(int[] destination) {
        System.arraycopy(frameData, 0, destination, 0, frameData.length);
        return frameNumber;
    }

    public synchronized void setFrameListener(FrameListener listener) {
        frameListener = listener;
    }

    /** Removes the listener, if it is still the given one. */
    public synchronized void removeFrameListener(FrameListener listener) {
        if (frameListener == listener)
            frameListener = null;
    }

    /////////////////// Privates - used by the methods above

    private void notifyFrameListener() {
        FrameListener listener;
        synchronized (this) {
            listener = frameListener;
        }
        if (listener != null)
            listener.frameReady();
    }

    private void convert(int x0, int y0, int w, int h) {
        int[] map = currentRgbMap;
        for (int y = y0; y < y0 + h; y++) {
            int index = y * width + x0;
            for (int x = 0; x < w; x++, index++) {
                rgbData[index] = map[pixels[index]];
            }
        }
    }
}
