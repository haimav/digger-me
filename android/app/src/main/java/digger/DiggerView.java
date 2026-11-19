package digger;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import general.display.MemoryDisplayUpdater;

/**
 * Android port: the view that shows the game, in place of the lcdui GameCanvas
 * of the JavaME version.
 * <ul>
 * <li>It puts the frames of the 320x200 virtual CGA screen on the display, scaled
 * with sharp pixels, from a thread of its own, so the game never waits for the
 * display.</li>
 * <li>It turns keys (D-pad, keyboard, gamepad, the keypad of a phone) and touches
 * on its on-screen controls into the game actions that the JavaME version got
 * from getGameAction().</li>
 * </ul>
 */
public class DiggerView extends SurfaceView implements SurfaceHolder.Callback, MemoryDisplayUpdater.FrameListener {

    /** The "Drawing Area" setting: how the virtual screen fills the view. */
    public static final int AREA_4_3 = 0;   // the shape of the PC monitor Digger was made for
    public static final int AREA_FULL = 1;  // all of the screen, as on the JavaME phones

    /** Told when the on-screen MENU button is pressed. */
    public interface MenuButtonListener {
        void onMenuButton();
    }

    private static final int[] ARROW_ACTIONS = {DiggerCanvas.UP, DiggerCanvas.DOWN, DiggerCanvas.LEFT, DiggerCanvas.RIGHT};
    private static final float[][] ARROW_DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    // Used on the UI thread only
    private DiggerCanvas dig;
    private MenuButtonListener menuButtonListener;
    private final boolean[] keyHeld = new boolean[DiggerCanvas.ANY_KEY + 1];
    private RenderThread renderThread;

    // Everything below is shared with the render thread and guarded by lock
    private final Object lock = new Object();
    private MemoryDisplayUpdater updater;
    private SurfaceHolder holder;           // set while the surface exists
    private boolean renderRequested;
    private final Bitmap[] frames = new Bitmap[2];  // two, so a frame is never changed while being drawn
    private int currentFrame = 0;
    private int[] frameData;
    private final Paint pixelPaint = new Paint();   // no filtering: sharp CGA pixels
    private final Paint controlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF labelRect = new RectF();
    private final Bitmap fireLabel = cgaText("FIRE");
    private final Bitmap menuLabel = cgaText("MENU");

    // Layout
    private int drawingArea = AREA_4_3;
    private final Rect insets = new Rect();         // the display cutout
    private final Rect gameRect = new Rect();
    private float dp = 1;                           // pixels per density independent pixel
    private boolean controlsOverGame;               // no room beside the game: controls drawn on it
    private boolean sideControls;                   // the controls are on both sides of the game
    private float padX, padY, padR;                 // the direction pad
    private float fireX, fireY, fireR;              // the fire button
    private final RectF menuRect = new RectF();     // the menu button
    private final Path[] arrows = {new Path(), new Path(), new Path(), new Path()};

    // Touch
    private boolean controlsVisible;
    private int padPointer = -1;
    private int padAction = 0;                      // the direction the pad holds down
    private int firePointer = -1;
    private int menuPointer = -1;
    private int tapPointer = -1;                    // a finger on the game itself

    public DiggerView(Context context) {
        super(context);
        getHolder().setFormat(PixelFormat.RGBA_8888);
        getHolder().addCallback(this);
        controlsVisible = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    // ------------------------------------------------------------------
    // The game

    /** Shows the given game, and sends it the keys and touches. */
    void attach(DiggerCanvas game) {
        dig = game;
        MemoryDisplayUpdater u = game.Pc.displayUpdater;
        synchronized (lock) {
            updater = u;
            int w = u.getWidth(), h = u.getHeight();
            if (frameData == null || frameData.length != w * h) {
                frameData = new int[w * h];
                frames[0] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                frames[1] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
        }
        u.setFrameListener(this);
        requestRender();
    }

    void detach() {
        releaseControls();
        MemoryDisplayUpdater u;
        synchronized (lock) {
            u = updater;
            updater = null;
        }
        if (u != null)
            u.removeFrameListener(this);
        dig = null;
    }

    void setMenuButtonListener(MenuButtonListener listener) {
        menuButtonListener = listener;
    }

    void setDrawingArea(int area) {
        synchronized (lock) {
            drawingArea = area;
        }
        relayout();
    }

    int getDrawingArea() {
        synchronized (lock) {
            return drawingArea;
        }
    }

    // ------------------------------------------------------------------
    // Drawing

    /** Called (on the game thread) for every frame the game flushes. */
    public void frameReady() {
        requestRender();
    }

    private void requestRender() {
        synchronized (lock) {
            renderRequested = true;
            lock.notifyAll();
        }
    }

    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        synchronized (lock) {
            holder = surfaceHolder;
            renderRequested = true;
        }
        renderThread = new RenderThread();
        renderThread.start();
    }

    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
        relayout();
    }

    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        // The surface may not be touched once this returns: stop the render thread first.
        RenderThread thread = renderThread;
        renderThread = null;
        if (thread != null) {
            thread.quit();
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted)
                Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            holder = null;
        }
    }

    private class RenderThread extends Thread {
        private boolean running = true;     // guarded by lock

        RenderThread() {
            super("DiggerDisplay");
        }

        void quit() {
            synchronized (lock) {
                running = false;
                lock.notifyAll();
            }
        }

        public void run() {
            while (true) {
                synchronized (lock) {
                    while (running && !renderRequested) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (!running)
                        return;
                    renderRequested = false;
                }
                render();
            }
        }
    }

    /** Draws the last frame of the game and the controls (on the render thread). */
    private void render() {
        SurfaceHolder surface;
        Bitmap frame = null;
        synchronized (lock) {
            surface = holder;
            if (surface == null)
                return;
            if (updater != null) {
                currentFrame = 1 - currentFrame;
                frame = frames[currentFrame];
                updater.getFrame(frameData);
                frame.setPixels(frameData, 0, frame.getWidth(), 0, 0, frame.getWidth(), frame.getHeight());
            }
        }

        Canvas canvas = null;
        try {
            canvas = Build.VERSION.SDK_INT >= 28 ? surface.lockHardwareCanvas() : surface.lockCanvas();
            if (canvas == null)
                return;
            canvas.drawColor(Color.BLACK);
            synchronized (lock) {
                if (frame != null)
                    canvas.drawBitmap(frame, null, gameRect, pixelPaint);
                if (controlsVisible)
                    drawControls(canvas);
            }
        } catch (RuntimeException e) {
            // The surface went away while we were drawing; there is nothing to draw on.
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (RuntimeException e) {
                    // Same as above.
                }
            }
        }
    }

    /** Called with lock held. */
    private void drawControls(Canvas canvas) {
        int idle = controlsOverGame ? 0x38ffffff : 0x50ffffff;
        int pressed = 0xb0ffffff;
        int outline = controlsOverGame ? 0x60ffffff : 0x90ffffff;
        Paint paint = controlPaint;
        paint.setStrokeWidth(2 * dp);

        // The direction pad
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(outline);
        canvas.drawCircle(padX, padY, padR, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            paint.setColor(padAction == ARROW_ACTIONS[i] ? pressed : idle);
            canvas.drawPath(arrows[i], paint);
        }

        // The fire button
        paint.setColor(firePointer != -1 ? pressed : idle);
        canvas.drawCircle(fireX, fireY, fireR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(outline);
        canvas.drawCircle(fireX, fireY, fireR, paint);
        drawLabel(canvas, fireLabel, fireX, fireY, fireR * 1.1f, firePointer != -1);

        // The menu button
        float corner = 8 * dp;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(menuPointer != -1 ? pressed : idle);
        canvas.drawRoundRect(menuRect, corner, corner, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(outline);
        canvas.drawRoundRect(menuRect, corner, corner, paint);
        drawLabel(canvas, menuLabel, menuRect.centerX(), menuRect.centerY(), menuRect.width() * 0.6f, menuPointer != -1);
    }

    /** Draws a label made by cgaText(), in whole multiples of its pixels, as wide as width at most. */
    private void drawLabel(Canvas canvas, Bitmap label, float x, float y, float width, boolean pressed) {
        float scale = Math.max(1, (float) Math.floor(width / label.getWidth()));
        float w = label.getWidth() * scale;
        float h = label.getHeight() * scale * 1.2f;   // CGA pixels were taller than wide
        labelRect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        pixelPaint.setAlpha(pressed ? 0xff : 0xc0);
        canvas.drawBitmap(label, null, labelRect, pixelPaint);
        pixelPaint.setAlpha(0xff);
    }

    /** The text in the font of the game (alpha.java), white on transparent. */
    private static Bitmap cgaText(String text) {
        Bitmap bitmap = Bitmap.createBitmap(12 * text.length(), 12, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < text.length(); i++) {
            short[] glyph = alpha.ascii2cga[text.charAt(i) - 32];
            if (glyph == null)
                continue;
            for (int row = 0; row < 12; row++) {
                for (int col = 0; col < 12; col++) {
                    // Four pixels of two bits in each byte, the leftmost in the top bits (as in Pc.gwrite)
                    if ((glyph[row * 3 + col / 4] >> (6 - 2 * (col % 4)) & 3) != 0)
                        bitmap.setPixel(i * 12 + col, row, Color.WHITE);
                }
            }
        }
        return bitmap;
    }

    // ------------------------------------------------------------------
    // Layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets windowInsets) {
        int left = 0, top = 0, right = 0, bottom = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cutout = windowInsets.getDisplayCutout();
            if (cutout != null) {
                left = cutout.getSafeInsetLeft();
                top = cutout.getSafeInsetTop();
                right = cutout.getSafeInsetRight();
                bottom = cutout.getSafeInsetBottom();
            }
        }
        synchronized (lock) {
            insets.set(left, top, right, bottom);
        }
        relayout();
        return super.onApplyWindowInsets(windowInsets);
    }

    private void relayout() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0)
            return;
        synchronized (lock) {
            layout(w, h, getResources().getDisplayMetrics().density);
        }
        requestRender();
    }

    /** Places the game and the controls in a view of w x h pixels. Called with lock held. */
    private void layout(int w, int h, float density) {
        dp = density;
        int left = insets.left, top = insets.top, right = w - insets.right, bottom = h - insets.bottom;
        int sw = right - left, sh = bottom - top;
        float minRadius = 32 * dp;      // a smaller direction pad is hard to use with a thumb
        float menuW = 76 * dp, menuH = 34 * dp;

        controlsOverGame = true;
        sideControls = false;
        if (drawingArea == AREA_FULL) {
            gameRect.set(left, top, right, bottom);
        } else if (sw * 3 >= sh * 4) {
            // Wider than 4:3: the game in the middle, the controls on its sides
            int gw = sh * 4 / 3;
            int gx = left + (sw - gw) / 2;
            gameRect.set(gx, top, gx + gw, bottom);
            float side = (sw - gw) / 2f;
            float r = Math.min(side * 0.42f, sh * 0.3f);
            if (r >= minRadius) {
                controlsOverGame = false;
                sideControls = true;
                padR = r;
                padX = left + side / 2;
                padY = top + sh * 0.62f;
                fireR = r * 0.8f;
                fireX = gameRect.right + side / 2;
                fireY = padY;
                menuW = Math.min(menuW, side * 0.8f);
                menuRect.set(fireX - menuW / 2, top + 12 * dp, fireX + menuW / 2, top + 12 * dp + menuH);
            }
        } else {
            // Taller than 4:3: the game at the top, the controls under it
            int gh = sw * 3 / 4;
            gameRect.set(left, top, right, top + gh);
            float below = sh - gh;
            float r = Math.min(sw * 0.2f, below * 0.32f);
            if (r >= minRadius) {
                controlsOverGame = false;
                padR = r;
                padX = left + sw * 0.27f;
                padY = gameRect.bottom + below * 0.55f;
                fireR = r * 0.8f;
                fireX = left + sw * 0.75f;
                fireY = padY;
                float menuY = gameRect.bottom + Math.min(24 * dp, below * 0.08f);
                menuRect.set(left + sw / 2f - menuW / 2, menuY, left + sw / 2f + menuW / 2, menuY + menuH);
            }
        }
        if (controlsOverGame) {
            float r = Math.max(minRadius, Math.min(64 * dp, Math.min(gameRect.height() * 0.17f, gameRect.width() * 0.12f)));
            padR = r;
            padX = gameRect.left + r * 1.35f;
            padY = gameRect.bottom - r * 1.35f;
            fireR = r * 0.8f;
            fireX = gameRect.right - r * 1.35f;
            fireY = padY;
            menuRect.set(gameRect.right - 8 * dp - menuW, gameRect.top + 8 * dp, gameRect.right - 8 * dp, gameRect.top + 8 * dp + menuH);
        }

        // The arrows of the direction pad
        for (int i = 0; i < 4; i++) {
            float ux = ARROW_DIRECTIONS[i][0], uy = ARROW_DIRECTIONS[i][1];
            float cx = padX + ux * padR * 0.58f, cy = padY + uy * padR * 0.58f;
            Path arrow = arrows[i];
            arrow.reset();
            arrow.moveTo(cx + ux * padR * 0.3f, cy + uy * padR * 0.3f);
            arrow.lineTo(cx - ux * padR * 0.16f - uy * padR * 0.3f, cy - uy * padR * 0.16f + ux * padR * 0.3f);
            arrow.lineTo(cx - ux * padR * 0.16f + uy * padR * 0.3f, cy - uy * padR * 0.16f - ux * padR * 0.3f);
            arrow.close();
        }
    }

    // ------------------------------------------------------------------
    // Keys

    /**
     * The game action of a key, or 0. The JavaME version got these from
     * getGameAction(); on Android the keypad of a phone (2, 4, 6, 8 and 5), a PC
     * keyboard (arrows, numeric keypad, and F1, the fire key of the original),
     * a D-pad and a gamepad are understood.
     */
    static int gameAction(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_NUMPAD_8:
                return DiggerCanvas.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_NUMPAD_2:
                return DiggerCanvas.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_NUMPAD_4:
                return DiggerCanvas.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_NUMPAD_6:
                return DiggerCanvas.RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_F1:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_NUMPAD_5:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                return DiggerCanvas.FIRE;
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return DiggerCanvas.ESCAPE;
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
                return DiggerCanvas.FASTER;
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                return DiggerCanvas.SLOWER;
        }
        return 0;
    }

    /** Sends a key to the game; returns false if the key is not the game's. */
    boolean handleKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = gameAction(keyCode);
        if (action == 0 && keyCode != KeyEvent.KEYCODE_BUTTON_B
                && (event.isPrintingKey() || KeyEvent.isGamepadButton(keyCode)))
            action = DiggerCanvas.ANY_KEY;      // "PRESS ANY KEY"
        if (action == 0 || dig == null)
            return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0 && !keyHeld[action]) {
                keyHeld[action] = true;
                hideTouchControls();
                dig.keyPressed(action);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (keyHeld[action]) {
                keyHeld[action] = false;
                dig.keyReleased(action);
            }
        }
        return true;
    }

    /**
     * Lets go of every key and control that is held down, so nothing stays
     * pressed while a menu is open or the game is hidden.
     */
    void releaseControls() {
        int pad;
        boolean fire, tap;
        synchronized (lock) {
            pad = padAction;
            fire = firePointer != -1;
            tap = tapPointer != -1;
            padAction = 0;
            padPointer = firePointer = menuPointer = tapPointer = -1;
        }
        if (dig != null) {
            if (pad != 0)
                dig.keyReleased(pad);
            if (fire)
                dig.keyReleased(DiggerCanvas.FIRE);
            if (tap)
                dig.keyReleased(DiggerCanvas.ANY_KEY);
            for (int action = 0; action < keyHeld.length; action++) {
                if (keyHeld[action]) {
                    keyHeld[action] = false;
                    dig.keyReleased(action);
                }
            }
        }
        requestRender();
    }

    private void hideTouchControls() {
        synchronized (lock) {
            if (!controlsVisible)
                return;
            controlsVisible = false;
        }
        requestRender();
    }

    // ------------------------------------------------------------------
    // Touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = event.getActionIndex();
                touchDown(event.getPointerId(i), event.getX(i), event.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++)
                    touchMove(event.getPointerId(i), event.getX(i), event.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = event.getActionIndex();
                touchUp(event.getPointerId(i), event.getX(i), event.getY(i));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseControls();
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void touchDown(int id, float x, float y) {
        if (dig == null)
            return;
        int target;     // 0 - the game, 1 - the pad, 2 - fire, 3 - menu
        synchronized (lock) {
            controlsVisible = true;
            if (menuPointer == -1 && onMenu(x, y)) {
                menuPointer = id;
                target = 3;
            } else if (padPointer == -1 && onPad(x, y)) {
                padPointer = id;
                target = 1;
            } else if (firePointer == -1 && onFire(x, y)) {
                firePointer = id;
                target = 2;
            } else if (tapPointer == -1) {
                tapPointer = id;
                target = 0;
            } else {
                return;
            }
        }
        if (target == 1)
            setPadAction(directionAt(x, y, 0));
        else if (target == 2)
            dig.keyPressed(DiggerCanvas.FIRE);
        else if (target == 0)
            dig.keyPressed(DiggerCanvas.ANY_KEY);   // starts a game, ends a pause
        requestRender();
    }

    private void touchMove(int id, float x, float y) {
        int action;
        synchronized (lock) {
            if (id != padPointer)
                return;
            action = directionAt(x, y, padAction);
        }
        setPadAction(action);
    }

    private void touchUp(int id, float x, float y) {
        if (dig == null)
            return;
        boolean pad = false, fire = false, menu = false, tap = false;
        synchronized (lock) {
            if (id == padPointer) {
                padPointer = -1;
                pad = true;
            } else if (id == firePointer) {
                firePointer = -1;
                fire = true;
            } else if (id == menuPointer) {
                menuPointer = -1;
                menu = onMenu(x, y);
            } else if (id == tapPointer) {
                tapPointer = -1;
                tap = true;
            }
        }
        if (pad)
            setPadAction(0);
        if (fire)
            dig.keyReleased(DiggerCanvas.FIRE);
        if (tap) {
            dig.keyReleased(DiggerCanvas.ANY_KEY);
            performClick();
        }
        requestRender();
        if (menu && menuButtonListener != null) {
            performClick();
            menuButtonListener.onMenuButton();
        }
    }

    /** Holds the pad down in a new direction (0 for none). */
    private void setPadAction(int action) {
        int old;
        synchronized (lock) {
            old = padAction;
            padAction = action;
        }
        if (old == action || dig == null)
            return;
        // The new direction first, so that the digger does not stop in between.
        if (action != 0)
            dig.keyPressed(action);
        if (old != 0)
            dig.keyReleased(old);
        requestRender();
    }

    /** The direction of a point on the pad; near its center the current one stays. Called with lock held. */
    private int directionAt(float x, float y, int current) {
        float dx = x - padX, dy = y - padY;
        if (dx * dx + dy * dy < padR * padR * 0.04f)
            return current;
        if (Math.abs(dx) > Math.abs(dy))
            return dx > 0 ? DiggerCanvas.RIGHT : DiggerCanvas.LEFT;
        return dy > 0 ? DiggerCanvas.DOWN : DiggerCanvas.UP;
    }

    // Generous touch areas: beside or under the game, all of its side is the
    // pad or the fire button. Called with lock held.

    private boolean onMenu(float x, float y) {
        float slop = 8 * dp;
        return x >= menuRect.left - slop && x <= menuRect.right + slop
                && y >= menuRect.top - slop && y <= menuRect.bottom + slop;
    }

    private boolean onPad(float x, float y) {
        if (!controlsOverGame)
            return sideControls ? x < gameRect.left : y > gameRect.bottom && x < getWidth() / 2f;
        float dx = x - padX, dy = y - padY;
        return dx * dx + dy * dy <= padR * padR * 2.25f;
    }

    private boolean onFire(float x, float y) {
        if (!controlsOverGame)
            return sideControls ? x >= gameRect.right : y > gameRect.bottom && x >= getWidth() / 2f;
        float dx = x - fireX, dy = y - fireY;
        return dx * dx + dy * dy <= fireR * fireR * 2.25f;
    }
}
