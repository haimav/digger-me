/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package digger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import general.sound.ResourceTunePlayer;
import general.ui.MenuEvent;
import general.ui.MenuItem;
import general.ui.MenuListener;
import general.ui.MenuScreen;
import general.ui.TextMenuItem;

/**
 * @author Haim Avron
 *
 * Android port: the activity takes the place of DiggerMidlet. It asks to accept
 * the no-warranty terms on the first run, starts the game, opens the options and
 * settings menus (from the back key/gesture, the MENU key or the on-screen MENU
 * button, where JavaME used the soft keys), and tells the game when it is hidden
 * (JavaME: hideNotify()). The settings are kept between runs.
 */
public class DiggerActivity extends Activity implements MenuListener {

    static private String WARRENTY_TEXT_1 = "" +
"1. BECAUSE THE PROGRAM IS LICENSED FREE OF CHARGE, THERE IS NO WARRANTY " +
"FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW. EXCEPT WHEN " +
"OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES " +
"PROVIDE THE PROGRAM 'AS IS' WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED " +
"OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF " +
"MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE ENTIRE RISK AS " +
"TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH YOU. SHOULD THE " +
"PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL NECESSARY SERVICING, " +
"REPAIR OR CORRECTION. ";
    
    static private String WARRENTY_TEXT_2 = "" + 
"2. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING " +
"WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY AND/OR " +
"REDISTRIBUTE THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES, " +
"INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING " +
"OUT OF THE USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED " +
"TO LOSS OF DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY " +
"YOU OR THIRD PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER " +
"PROGRAMS), EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE " +
"POSSIBILITY OF SUCH DAMAGES.";

    // Android port: the game, with its thread, lives as long as the process does;
    // a new activity (after "Quit", or if Android recreates it) just shows it again.
    private static DiggerCanvas gameCanvas;

    private FrameLayout screen;
    private DiggerView gameView;
    private AlertDialog warrantyDialog;
    private Object backCallback;    // the OnBackInvokedCallback, on Android 13 and later
    private SharedPreferences settings;
    private boolean started;        // between onStart() and onStop()
    private boolean showingGame;    // this activity told the game it is visible
    private int menuKey = KeyEvent.KEYCODE_UNKNOWN;     // the key pressed down in a menu (only it repeats there)

    // Settings, kept between runs (JavaME forgot them)
    private static final String MUTE_SETTING = "mute";
    private static final String MUSIC_SETTING = "music";
    private static final String AREA_SETTING = "drawingArea";
    private static final String SCREEN_SETTING = "screen";
    private static final int LANDSCAPE = 0, PORTRAIT = 1, AUTO = 2;
    private static final String[] SCREEN_NAMES = {"LANDSCAPE", "PORTRAIT", "AUTO"};

    // Menu items
    private final TextMenuItem restartMenuItem = new TextMenuItem("Restart");
    private final TextMenuItem muteMenuItem = new TextMenuItem("Mute: OFF");
    private final TextMenuItem soundModeMenuItem = new TextMenuItem("Music: MIDI");
    private final TextMenuItem rotateMenuItem = new TextMenuItem("Screen: LANDSCAPE");    // JavaME: "Rotate: OFF"
    private final TextMenuItem activeAreaMenuItem = new TextMenuItem("Drawing Area: 4:3"); // JavaME: FULL or SMALL
    private final TextMenuItem settingsMenuItem = new TextMenuItem("Settings");
    private final TextMenuItem backMenuItem = new TextMenuItem("Back");
    private final TextMenuItem exitOptionsMenuItem = new TextMenuItem("Exit Menu");
    private final TextMenuItem exitMenuItem = new TextMenuItem("Quit");

    // Menu screens
    private MenuScreen optionsMenu;
    private MenuScreen settingsMenu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = getSharedPreferences("digger", MODE_PRIVATE);
        applyScreenSetting();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        // (The screen is kept on while the game is played, see DiggerView.keepScreenOn().)

        // Where the game finds what JavaME gave it with RecordStore and getResourceAsStream()
        ScoreStorage.setDirectory(getFilesDir());
        ResourceTunePlayer.setAssets(getApplicationContext().getAssets());

        screen = new FrameLayout(this);
        gameView = new DiggerView(this);
        gameView.setDrawingArea(settings.getInt(AREA_SETTING, DiggerView.AREA_4_3) == DiggerView.AREA_FULL
                ? DiggerView.AREA_FULL : DiggerView.AREA_4_3);
        gameView.setMenuButtonListener(new DiggerView.MenuButtonListener() {
            public void onMenuButton() {
                openMenu();
            }
        });
        screen.addView(gameView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(screen);
        hideSystemBars();

        //optionsMenu.setTitle("Options");
        optionsMenu = new MenuScreen(this, 4);
        optionsMenu.setMenuListener(this);

        optionsMenu.setMenuBackgroundColor(255, 255, 255, 200);
        optionsMenu.setItemsColor(0, 0, 0);
        optionsMenu.setMenuItem(0, settingsMenuItem);
        optionsMenu.setMenuItem(1, restartMenuItem);
        optionsMenu.setMenuItem(2, exitOptionsMenuItem);
        optionsMenu.setMenuItem(3, exitMenuItem);

        //settingsMenu.setTitle("Settings");
        settingsMenu = new MenuScreen(this, 5);
        settingsMenu.setMenuListener(this);

        settingsMenu.setMenuBackgroundColor(255, 255, 255, 200);
        settingsMenu.setItemsColor(0, 0, 0);
        settingsMenu.setMenuItem(0, activeAreaMenuItem);
        settingsMenu.setMenuItem(1, rotateMenuItem);
        settingsMenu.setMenuItem(2, soundModeMenuItem);
        settingsMenu.setMenuItem(3, muteMenuItem);
        settingsMenu.setMenuItem(4, backMenuItem);

        registerBackCallback();

        if (gameCanvas != null || ScoreStorage.scoreStorageExists()) {
            startGame();
        } else {
            showWarranty();
        }
    }

    private void showWarranty() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("No-Warranty Terms");
        builder.setMessage("Please read the following no-warranty terms carefully. You must accept them to use the game. They will be displayed only on the first run."
                + "\n\n" + WARRENTY_TEXT_1 + "\n\n" + WARRENTY_TEXT_2);
        builder.setCancelable(false);
        builder.setPositiveButton("Accept", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                warrantyDialog = null;
                startGame();
            }
        });
        builder.setNegativeButton("Quit", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                warrantyDialog = null;
                finish();
            }
        });
        warrantyDialog = builder.show();
    }

    public void startGame() {
        if (gameCanvas == null) {
            gameCanvas = new DiggerCanvas();
            gameCanvas.newSound.setMuteState(settings.getBoolean(MUTE_SETTING, false));
            if (settings.getInt(MUSIC_SETTING, NewSound.MIDI_MUSIC_MODE) == NewSound.TONE_MUSICE_MODE)
                gameCanvas.newSound.setMusicMode(NewSound.TONE_MUSICE_MODE);
            gameCanvas.start();
        }
        gameView.attach(gameCanvas);
        updateGameVisibility();
        updateMenuTexts();
    }

    /** Tells the game whether this activity shows it; the game sleeps while no activity does. */
    private void updateGameVisibility() {
        boolean show = started && gameCanvas != null;
        if (show != showingGame) {
            showingGame = show;
            gameCanvas.setForeground(show);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        updateGameVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseGame();    // JavaME: DiggerCanvas.hideNotify()
    }

    @Override
    protected void onStop() {
        started = false;
        updateGameVisibility();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (warrantyDialog != null) {
            warrantyDialog.dismiss();
            warrantyDialog = null;
        }
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
        gameView.detach();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")    // the newer one (API 26) calls this one
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        gameView.requestApplyInsets();  // the room the system bars take changes with it
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            hideSystemBars();
        else if (currentMenu() == null)
            pauseGame();    // e.g. the notification shade was pulled down
    }

    private void pauseGame() {
        if (gameCanvas != null) {
            gameView.releaseControls();     // before the pause, which forgets the keys pressed so far
            gameCanvas.pauseState(true);
        }
    }

    // ------------------------------------------------------------------
    // Keys and the back button

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        MenuScreen menu = currentMenu();

        // Back, before Android 13. (Since Android 13 back comes to the
        // OnBackInvokedCallback, see registerBackCallback().)
        if (keyCode == KeyEvent.KEYCODE_BACK && Build.VERSION.SDK_INT < 33) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                handleBack();
            return true;
        }

        // MENU (or START on a gamepad) opens the menu, as the phone's soft keys did, and closes it.
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            if (down && event.getRepeatCount() == 0) {
                if (menu != null) {
                    settingsMenu.close();
                    optionsMenu.close();
                } else {
                    openMenu();
                }
            }
            return true;
        }

        if (menu != null) {
            int action = DiggerView.gameAction(keyCode);
            if (action == DiggerCanvas.UP || action == DiggerCanvas.DOWN || action == DiggerCanvas.LEFT
                    || action == DiggerCanvas.RIGHT || action == DiggerCanvas.FIRE) {
                if (down && event.getRepeatCount() == 0) {
                    menuKey = keyCode;
                    menu.keyPressed(action);
                } else if (down) {
                    // Held down, up and down move on; but not a key that was already held
                    // when the menu opened (e.g. to dig), nor a gamepad stick pushed then.
                    if (keyCode == menuKey && (action == DiggerCanvas.UP || action == DiggerCanvas.DOWN))
                        menu.keyPressed(action);
                } else if (keyCode == menuKey) {
                    menuKey = KeyEvent.KEYCODE_UNKNOWN;
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (gameView.handleKey(event))
            return true;
        return super.dispatchKeyEvent(event);
    }

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedCallback callback = new OnBackInvokedCallback() {
                public void onBackInvoked() {
                    handleBack();
                }
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            backCallback = callback;
        }
    }

    /** Back closes the menu that is open, or opens the options menu. */
    private void handleBack() {
        if (settingsMenu.isOpen())
            settingsMenu.close();
        else if (optionsMenu.isOpen())
            optionsMenu.close();
        else if (gameCanvas != null)
            openMenu();
        else
            finish();
    }

    // ------------------------------------------------------------------
    // Menus

    private MenuScreen currentMenu() {
        if (settingsMenu != null && settingsMenu.isOpen())
            return settingsMenu;
        if (optionsMenu != null && optionsMenu.isOpen())
            return optionsMenu;
        return null;
    }

    /** JavaME: the "Menu" command. The game is paused under the menu, as it was when the menu hid it. */
    private void openMenu() {
        if (gameCanvas == null || optionsMenu.isOpen())
            return;
        menuKey = KeyEvent.KEYCODE_UNKNOWN;
        pauseGame();
        updateMenuTexts();
        optionsMenu.open(screen);
    }

    public void onMenuEvent(MenuItem item, MenuEvent event, MenuScreen menu) {

        // Android port: the back key, or a tap outside the box, leaves the menu.
        if (event == MenuEvent.BACK_EVENT) {
            menu.close();
            return;
        }

        // Android port: on a touch screen a tap (SELECT) changes a setting too;
        // LEFT and RIGHT still do, as on the keypad of the phone.
        boolean change = event == MenuEvent.LEFT_EVENT || event == MenuEvent.RIGHT_EVENT || event == MenuEvent.SELECT_EVENT;

        // Android port: Quit and Restart only when chosen; not with left/right too, as a
        // gamepad stick pushed a little sideways while moving up and down does.
        if (item == exitMenuItem && event == MenuEvent.SELECT_EVENT) {
            quit();
        }

        if (item == restartMenuItem && event == MenuEvent.SELECT_EVENT) {
            gameCanvas.endgame();
            if (optionsMenu.isOpen()) {
                optionsMenu.close();
            }
        }

        if (item == muteMenuItem && change) {
            boolean newMuteState = !gameCanvas.newSound.isMute();
            gameCanvas.newSound.setMuteState(newMuteState);
            settings.edit().putBoolean(MUTE_SETTING, newMuteState).apply();
        }

        if (item == soundModeMenuItem && change) {
            int newMusicMode = NewSound.MIDI_MUSIC_MODE;
            switch(gameCanvas.newSound.getMusicMode()) {
            case NewSound.MIDI_MUSIC_MODE:
                newMusicMode = NewSound.TONE_MUSICE_MODE;
                break;
            case NewSound.TONE_MUSICE_MODE:
                newMusicMode = NewSound.MIDI_MUSIC_MODE;
                break;
            }
            gameCanvas.newSound.setMusicMode(newMusicMode);
            settings.edit().putInt(MUSIC_SETTING, newMusicMode).apply();
        }

        if (item == rotateMenuItem && change) {
            // LANDSCAPE -> PORTRAIT -> AUTO (Android rotates; JavaME rotated the picture itself)
            int screenSetting = settings.getInt(SCREEN_SETTING, LANDSCAPE);
            screenSetting = (screenSetting + (event == MenuEvent.LEFT_EVENT ? 2 : 1)) % 3;
            settings.edit().putInt(SCREEN_SETTING, screenSetting).apply();
            applyScreenSetting();
        }

        if (item == activeAreaMenuItem && change) {
            int area = gameView.getDrawingArea() == DiggerView.AREA_FULL ? DiggerView.AREA_4_3 : DiggerView.AREA_FULL;
            gameView.setDrawingArea(area);
            settings.edit().putInt(AREA_SETTING, area).apply();
        }

        if (item == settingsMenuItem) {
            settingsMenu.open(screen);
        }

        if (item == backMenuItem) {
            settingsMenu.close();
        }

        if (item == exitOptionsMenuItem) {
            optionsMenu.close();
        }

        updateMenuTexts();
    }

    /**
     * JavaME: notifyDestroyed(). On Android the activity finishes; the game is given
     * up, as with Restart, and sleeps until the game is launched again: on its title
     * screen (or, if a new high score is being entered, at the initials, which are
     * then saved).
     */
    private void quit() {
        settingsMenu.close();
        optionsMenu.close();
        gameCanvas.endgame();
        finish();
    }

    private void updateMenuTexts() {
        if (gameCanvas != null) {
            muteMenuItem.setText("Mute: " + (gameCanvas.newSound.isMute() ? "ON" : "OFF"));
            soundModeMenuItem.setText("Music: " + (gameCanvas.newSound.getMusicMode() == NewSound.TONE_MUSICE_MODE ? "TONE" : "MIDI"));
        }
        rotateMenuItem.setText("Screen: " + SCREEN_NAMES[settings.getInt(SCREEN_SETTING, LANDSCAPE) % 3]);
        activeAreaMenuItem.setText("Drawing Area: " + (gameView.getDrawingArea() == DiggerView.AREA_FULL ? "FULL" : "4:3"));
        optionsMenu.repaint();
        settingsMenu.repaint();
    }

    // ------------------------------------------------------------------
    // The screen

    @SuppressLint("SourceLockedOrientationActivity")   // the player chooses it (Settings, "Screen")
    private void applyScreenSetting() {
        switch (settings.getInt(SCREEN_SETTING, LANDSCAPE)) {
            case PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                break;
            case AUTO:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    /** All of the screen is the game's: the system bars come only when swiped in. */
    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }
}
