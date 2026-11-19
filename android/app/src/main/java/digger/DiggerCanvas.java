package digger;

import general.display.MemoryDisplayUpdater;

/* WARNING! This code is ugly and highly non-object-oriented.
It was ported from C almost mechanically! */

/**
 * Android port: in the JavaME version this class was the GameCanvas of the game.
 * On Android the on-screen view is {@link DiggerView}. This class keeps what the
 * rest of the code knows as "dig": the global state and the functions of digger.c
 * (unchanged), the game thread, and the virtual 320x200 CGA screen (Pc.pixels and
 * Pc.displayUpdater) that the view shows.
 * <p>
 * It does not depend on Android at all, so it lives as long as the process does:
 * activities come and go (screen rotation, "Quit" and relaunch, ...) and just
 * attach a view to it.
 */
public class DiggerCanvas implements Runnable {

    static int MAX_RATE = 200,  MIN_RATE = 66;
    int width = 320, height = 200, frametime = 80;
    String subaddr;
    Bags Bags;
    Main Main;
    Sound Sound;
    NewSound newSound;
    Monster Monster;
    Scores Scores;
    Sprite Sprite;
    Drawing Drawing;
    Input Input;
    Pc Pc;
    boolean killed = false;

    // Game actions, as delivered by DiggerView (the values of the
    // javax.microedition.lcdui.Canvas game actions of the JavaME version).
    static final int UP = 1, LEFT = 2, RIGHT = 5, DOWN = 6, FIRE = 8;
    // Android port: keys of the PC original that had no JavaME game action.
    static final int ESCAPE = 100, FASTER = 101, SLOWER = 102, ANY_KEY = 103;

    // -----

    int diggerx = 0, diggery = 0, diggerh = 0, diggerv = 0, diggerrx = 0, diggerry = 0, digmdir = 0, digdir = 0, digtime = 0, rechargetime = 0, firex = 0, firey = 0, firedir = 0, expsn = 0, deathstage = 0, deathbag = 0, deathani = 0, deathtime = 0, startbonustimeleft = 0, bonustimeleft = 0, eatmsc = 0, emocttime = 0;
    int emmask = 0;
    byte emfield[] = { //[150]
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    boolean digonscr = false, notfiring = false, bonusvisible = false, bonusmode = false, diggervisible = false;
    long time, ftime = 50;
    int embox[] = {8, 12, 12, 9, 16, 12, 6, 9};	// [8]

    int deatharc[] = {3, 5, 6, 6, 5, 3, 0};			// [7]
    volatile boolean paused = false;    // Android port: volatile, it is set from the UI thread

    // Android port: while no activity shows the game, the game thread sleeps
    // in newframe(), so a hidden game uses neither CPU nor speakers.
    private final Object foregroundLock = new Object();
    private int visibleActivities = 0;

    public DiggerCanvas() {
        // Older applet version used key pressed and released events.
        // I will continue to use that.
        Bags = new Bags(this);
        Main = new Main(this);
        Sound = new Sound(this);
        newSound = new NewSound();
        Monster = new Monster(this);
        Scores = new Scores(this);
        Sprite = new Sprite(this);
        Drawing = new Drawing(this);
        Input = new Input(this);
        Pc = new Pc(this);
    }

    boolean checkdiggerunderbag(int h, int v) {
        if (digmdir == 2 || digmdir == 6) {
            if ((diggerx - 12) / 20 == h) {
                if ((diggery - 18) / 18 == v || (diggery - 18) / 18 + 1 == v) {
                    return true;
                }
            }
        }
        return false;
    }

    int countem() {
        int x,
         y,
         n = 0;
        for (x = 0; x < 15; x++) {
            for (y = 0; y < 10; y++) {
                if ((emfield[y * 15 + x] & emmask) != 0) {
                    n++;
                }
            }
        }
        return n;
    }

    void createbonus() {
        bonusvisible = true;
        Drawing.drawbonus(292, 18);
    }

    void diggerdie() {


        int clbits;
        switch (deathstage) {
            case 1:
                if (Bags.bagy(deathbag) + 6 > diggery) {
                    diggery = Bags.bagy(deathbag) + 6;
                }
                Drawing.drawdigger(15, diggerx, diggery, false);
                Main.incpenalty();
                if (Bags.getbagdir(deathbag) + 1 == 0) {

                    Sound.soundddie();
                    
                    deathtime = 5;
                    deathstage = 2;
                    deathani = 0;
                    diggery -= 6;
                }
                break;
            case 2:
                if (deathtime != 0) {
                    deathtime--;
                    break;
                }
                if (deathani == 0) {
                    Sound.music(2);
                }
                clbits = Drawing.drawdigger(14 - deathani, diggerx, diggery, false);
                Main.incpenalty();
                if (deathani == 0 && ((clbits & 0x3f00) != 0)) {
                    Monster.killmonsters(clbits);
                }
                if (deathani < 4) {
                    deathani++;
                    deathtime = 2;
                } else {
                    deathstage = 4;
                    if (Sound.musicflag) {
                        deathtime = 60;
                    } else {
                        deathtime = 10;
                    }
                }
                break;
            case 3:
                deathstage = 5;
                deathani = 0;
                deathtime = 0;
                break;
            case 5:
                if (deathani >= 0 && deathani <= 6) {
                    Drawing.drawdigger(15, diggerx, diggery - deatharc[deathani], false);
                    if (deathani == 6) {
                        Sound.musicoff();
                        //newSound.killAll();
                    }
                    Main.incpenalty();
                    deathani++;
                    if (deathani == 1) {
                        Sound.soundddie();
                    }
                    if (deathani == 7) {
                        deathtime = 5;
                        deathani = 0;
                        deathstage = 2;
                    }
                }
                break;
            case 4:
                if (deathtime != 0) {
                    deathtime--;
                } else {
                    Main.setdead(true);
                }
        }
    }

    void dodigger() {
        newframe();
        if (expsn != 0) {
            drawexplosion();
        } else {
            updatefire();
        }
        if (diggervisible) {
            if (digonscr) {
                if (digtime != 0) {
                    Drawing.drawdigger(digmdir, diggerx, diggery, notfiring && rechargetime == 0);
                    Main.incpenalty();
                    digtime--;
                } else {
                    updatedigger();
                }
            } else {
                diggerdie();
            }
        }
        if (bonusmode && digonscr) {
            // Can be redundent...
            newSound.stopNormalBackgroundMusic();


            if (bonustimeleft != 0) {
                bonustimeleft--;
                if (startbonustimeleft != 0 || bonustimeleft < 20) {
                    startbonustimeleft--;
                    if ((bonustimeleft & 1) != 0) {
                        Pc.ginten(0);
                        Sound.soundbonus();
                        newSound.stopBonusBackgroundMusic();
                        newSound.startBonusPulse();
                    } else {
                        newSound.stopBonusBackgroundMusic();
                        newSound.startBonusPulse();
                        Pc.ginten(1);
                        Sound.soundbonus();
                    }
                    if (startbonustimeleft == 0) {
                        newSound.endBonusPulse();
                        Sound.music(0);
                        Sound.soundbonusoff();
                        Pc.ginten(1);
                        newSound.startBonusBackgroundMusic();
                    }
                }
            } else {
                endbonusmode();
                Sound.soundbonusoff();
                Sound.music(1);
                newSound.endBonusPulse();
                newSound.stopBonusBackgroundMusic();
                newSound.startNormalBackgroundMusic();
            }
        }
        if (bonusmode && !digonscr) {
            endbonusmode();
            newSound.endBonusPulse();
            newSound.stopBonusBackgroundMusic();
            newSound.startNormalBackgroundMusic();
            Sound.soundbonusoff();
            Sound.music(1);
        }
        if (emocttime > 0) {
            emocttime--;
        }
    }

    void drawemeralds() {
        int x,
         y;
        emmask = 1 << Main.getcplayer();
        for (x = 0; x < 15; x++) {
            for (y = 0; y < 10; y++) {
                if ((emfield[y * 15 + x] & emmask) != 0) {
                    Drawing.drawemerald(x * 20 + 12, y * 18 + 21);
                }
            }
        }
    }

    void drawexplosion() {
        switch (expsn) {
            case 1:
                newSound.fireEnd();
                Sound.soundexplode();
                newSound.playExplode();
            case 2:
            case 3:
                Drawing.drawfire(firex, firey, expsn);
                Main.incpenalty();
                expsn++;
                break;
            default:
                killfire();
                expsn = 0;
        }
    }

    void endbonusmode() {
        bonusmode = false;
        Pc.ginten(0);

    }

    void erasebonus() {
        if (bonusvisible) {
            bonusvisible = false;
            Sprite.erasespr(14);
        }
        Pc.ginten(0);
    }

    void erasedigger() {
        Sprite.erasespr(0);
        diggervisible = false;
    }

    public String getAppletInfo() {
        return "The Digger Remastered -- http://www.digger.org, Copyright (c) Andrew Jenner & Marek Futrega / MAF";

    }

    boolean getfirepflag() {
        return Input.firepflag;
    }

    boolean hitemerald(int x, int y, int rx, int ry, int dir) {
        boolean hit = false;
        int r;
        if (dir < 0 || dir > 6 || ((dir & 1) != 0)) {
            return hit;
        }
        if (dir == 0 && rx != 0) {
            x++;
        }
        if (dir == 6 && ry != 0) {
            y++;
        }
        if (dir == 0 || dir == 4) {
            r = rx;
        } else {
            r = ry;
        }
        if ((emfield[y * 15 + x] & emmask) != 0) {
            if (r == embox[dir]) {
                Drawing.drawemerald(x * 20 + 12, y * 18 + 21);
                Main.incpenalty();
            }
            if (r == embox[dir + 1]) {
                Drawing.eraseemerald(x * 20 + 12, y * 18 + 21);
                Main.incpenalty();
                hit = true;
                emfield[y * 15 + x] &= ~emmask;
            }
        }
        return hit;
    }

    public void init() {

        // I do not support parameters. Just use defaults.

        Pc.pixels = new int[65536];
        // Android port: the updater only keeps the RGB image of the virtual
        // screen; DiggerView scales it to the real screen (and Android rotates it).
        Pc.displayUpdater = new MemoryDisplayUpdater(Pc.pixels, Pc.width, Pc.height, 2);
        Pc.displayUpdater.setPallete(0, Pc.pal[0][0], Pc.pal[0][1], Pc.pal[0][2]);
        Pc.displayUpdater.setPallete(1, Pc.pal[1][0], Pc.pal[1][1], Pc.pal[1][2]);
    }

    void initbonusmode() {
        bonusmode = true;
        erasebonus();
        Pc.ginten(1);
        bonustimeleft = 250 - Main.levof10() * 20;
        startbonustimeleft = 20;
        eatmsc = 1;
    }

    void initdigger() {
        diggerv = 9;
        digmdir = 4;
        diggerh = 7;
        diggerx = diggerh * 20 + 12;
        digdir = 0;
        diggerrx = 0;
        diggerry = 0;
        digtime = 0;
        digonscr = true;
        deathstage = 1;
        diggervisible = true;
        diggery = diggerv * 18 + 18;
        Sprite.movedrawspr(0, diggerx, diggery);
        notfiring = true;
        emocttime = 0;
        bonusvisible = bonusmode = false;
        Input.firepressed = false;
        expsn = 0;
        rechargetime = 0;
    }

    /**
     * Android port: DiggerView reports game actions here. The JavaME version got
     * key codes and translated them with getGameAction() (twice, for the rotated
     * display; Android rotates the screen by itself).
     */
    void keyPressed(int action) {
        switch (action) {
            case LEFT:
                Input.processkey(0x4b);
                break;
            case RIGHT:
                Input.processkey(0x4d);
                break;
            case UP:
                Input.processkey(0x48);
                break;
            case DOWN:
                Input.processkey(0x50);
                break;
            case FIRE:
                Input.processkey(0x3b);
                break;
            case ESCAPE:    // one/two players, on the title screen
                Input.processkey(0x1b);
                break;
            case FASTER:
                Input.processkey(0x2b);
                break;
            case SLOWER:
                Input.processkey(0x2d);
                break;
            case ANY_KEY:   // starts a game, ends a pause
                Input.processkey(0x39);
                break;
        }
    }

    void keyReleased(int action) {
        switch (action) {
            case LEFT:
                Input.processkey(0xcb);
                break;
            case RIGHT:
                Input.processkey(0xcd);
                break;
            case UP:
                Input.processkey(0xc8);
                break;
            case DOWN:
                Input.processkey(0xd0);
                break;
            case FIRE:
                Input.processkey(0xbb);
                break;
            case ESCAPE:
                Input.processkey(0x9b);
                break;
            case FASTER:
                Input.processkey(0xab);
                break;
            case SLOWER:
                Input.processkey(0xad);
                break;
            case ANY_KEY:
                // Nothing: teststart() looks only at the last key, so the release
                // of a quick tap would hide the press from it. Nothing needs this release.
                break;
        }
    }

    void killdigger(int stage, int bag) {
        if (deathstage < 2 || deathstage > 4) {
            newSound.stopNormalBackgroundMusic();
            newSound.stopBonusBackgroundMusic();
            newSound.playDeath();
            digonscr = false;
            deathstage = stage;
            deathbag = bag;
        }
    }

    void killemerald(int x, int y) {
        if ((emfield[y * 15 + x + 15] & emmask) != 0) {
            emfield[y * 15 + x + 15] &= ~emmask;
            Drawing.eraseemerald(x * 20 + 12, (y + 1) * 18 + 21);
        }
    }

    void killfire() {
        if (!notfiring) {
            notfiring = true;
            Sprite.erasespr(15);
            Sound.soundfireoff();
            newSound.fireEnd();
        }
    }

    void makeemfield() {
        int x,
         y;
        emmask = 1 << Main.getcplayer();
        for (x = 0; x < 15; x++) {
            for (y = 0; y < 10; y++) {
                if (Main.getlevch(x, y, Main.levplan()) == 'C') {
                    emfield[y * 15 + x] |= emmask;
                } else {
                    emfield[y * 15 + x] &= ~emmask;
                }
            }
        }
    }

    void newframe() {
        Input.checkkeyb();
        time += frametime;
        long l = time - Pc.gethrt();
        if (l > 0) {
            try {
                Thread.sleep((int) l);
            } catch (Exception e) {
            }
        }
        Pc.displayUpdater.flush();
        //Pc.displayUpdater.newPixels();
        // Android port: a paused game is silent - also if, on its way to the pause
        // (testpause() comes at the end of a frame), it started a tune.
        if (paused)
            newSound.changePauseState(true);
        waitForForeground();    // Android port
    }

    void endgame() {
        Input.escape = true;
    }

    void pauseState(boolean state) {
        paused = state;
        newSound.changePauseState(state);
    }

    int reversedir(int dir) {
        switch (dir) {
            case 0:
                return 4;
            case 4:
                return 0;
            case 2:
                return 6;
            case 6:
                return 2;
        }
        return dir;
    }

    /** Android port: creates the virtual screen and starts the game thread (JavaME: DiggerMidlet.startGame()). */
    void start() {
        init();
        new Thread(this, "Digger").start();
    }

    public void run() {
        Main.main();
    }

    /**
     * Android port: called by a DiggerActivity with true when it becomes visible,
     * and with false when it is hidden again. The calls are counted, since two
     * activities may overlap for a moment (e.g. "Quit" and a quick relaunch).
     * (There is no stop(): the game thread lives as long as the process.)
     */
    void setForeground(boolean foreground) {
        synchronized (foregroundLock) {
            visibleActivities += foreground ? 1 : -1;
            foregroundLock.notifyAll();
        }
    }

    private void waitForForeground() {
        synchronized (foregroundLock) {
            if (visibleActivities > 0)
                return;
        }
        // Normally the activity paused the game when it was hidden (hideNotify() in
        // JavaME), and newframe() silenced it. But a game that is being left (Restart,
        // Quit) is unpaused, and may have turned its music back on.
        if (!paused)
            newSound.killAll();
        synchronized (foregroundLock) {
            while (visibleActivities <= 0) {
                try {
                    foregroundLock.wait();
                } catch (InterruptedException e) {
                }
            }
        }
        time = Pc.gethrt();     // do not try to catch up with the time spent in the background
    }

    void updatedigger() {
        int dir,
         ddir,
         clbits,
         diggerox,
         diggeroy,
         nmon;
        boolean push = false;
        Input.readdir();
        dir = Input.getdir();
        if (dir == 0 || dir == 2 || dir == 4 || dir == 6) {
            ddir = dir;
        } else {
            ddir = -1;
        }
        if (diggerrx == 0 && (ddir == 2 || ddir == 6)) {
            digdir = digmdir = ddir;
        }
        if (diggerry == 0 && (ddir == 4 || ddir == 0)) {
            digdir = digmdir = ddir;
        }
        if (dir == -1) {
            digmdir = -1;
        } else {
            digmdir = digdir;
        }
        if ((diggerx == 292 && digmdir == 0) || (diggerx == 12 && digmdir == 4) ||
                (diggery == 180 && digmdir == 6) || (diggery == 18 && digmdir == 2)) {
            digmdir = -1;
        }
        diggerox = diggerx;
        diggeroy = diggery;
        if (digmdir != -1) {
            Drawing.eatfield(diggerox, diggeroy, digmdir);
        }
        switch (digmdir) {
            case 0:
                Drawing.drawrightblob(diggerx, diggery);
                diggerx += 4;
                break;
            case 4:
                Drawing.drawleftblob(diggerx, diggery);
                diggerx -= 4;
                break;
            case 2:
                Drawing.drawtopblob(diggerx, diggery);
                diggery -= 3;
                break;
            case 6:
                Drawing.drawbottomblob(diggerx, diggery);
                diggery += 3;
                break;
        }
        if (hitemerald((diggerx - 12) / 20, (diggery - 18) / 18, (diggerx - 12) % 20,
                (diggery - 18) % 18, digmdir)) {
            Scores.scoreemerald();
            Sound.soundem();
            Sound.soundemerald(emocttime);
            newSound.playEatEmerald(emocttime <= 0);
            emocttime = 9;
        }
        clbits = Drawing.drawdigger(digdir, diggerx, diggery, notfiring && rechargetime == 0);
        Main.incpenalty();
        if ((Bags.bagbits() & clbits) != 0) {
            if (digmdir == 0 || digmdir == 4) {
                push = Bags.pushbags(digmdir, clbits);
                digtime++;
            } else if (!Bags.pushudbags(clbits)) {
                push = false;
            }
            if (!push) { /* Strange, push not completely defined */
                diggerx = diggerox;
                diggery = diggeroy;
                Drawing.drawdigger(digmdir, diggerx, diggery, notfiring && rechargetime == 0);
                Main.incpenalty();
                digdir = reversedir(digmdir);
            }
        }
        if (((clbits & 0x3f00) != 0) && bonusmode) {
            for (nmon = Monster.killmonsters(clbits); nmon != 0; nmon--) {
                Sound.soundeatm();
                Scores.scoreeatm();
                newSound.playMonsterEat();
            }
        }
        if ((clbits & 0x4000) != 0) {
            Scores.scorebonus();
            initbonusmode();
        }
        diggerh = (diggerx - 12) / 20;
        diggerrx = (diggerx - 12) % 20;
        diggerv = (diggery - 18) / 18;
        diggerry = (diggery - 18) % 18;
    }

    void updatefire() {
        int clbits,
         b,
         mon,
         pix = 0;
        if (notfiring) {
            if (rechargetime != 0) {
                rechargetime--;
            } else if (getfirepflag()) {
                if (digonscr) {
                    rechargetime = Main.levof10() * 3 + 60;
                    notfiring = false;
                    switch (digdir) {
                        case 0:
                            firex = diggerx + 8;
                            firey = diggery + 4;
                            break;
                        case 4:
                            firex = diggerx;
                            firey = diggery + 4;
                            break;
                        case 2:
                            firex = diggerx + 4;
                            firey = diggery;
                            break;
                        case 6:
                            firex = diggerx + 4;
                            firey = diggery + 8;
                    }
                    firedir = digdir;
                    Sprite.movedrawspr(15, firex, firey);
                    Sound.soundfire();
                    newSound.fireStart();
                }
            }
        } else {
            switch (firedir) {
                case 0:
                    firex += 8;
                    pix = Pc.ggetpix(firex, firey + 4) | Pc.ggetpix(firex + 4, firey + 4);
                    break;
                case 4:
                    firex -= 8;
                    pix = Pc.ggetpix(firex, firey + 4) | Pc.ggetpix(firex + 4, firey + 4);
                    break;
                case 2:
                    firey -= 7;
                    pix = (Pc.ggetpix(firex + 4, firey) | Pc.ggetpix(firex + 4, firey + 1) |
                            Pc.ggetpix(firex + 4, firey + 2) | Pc.ggetpix(firex + 4, firey + 3) |
                            Pc.ggetpix(firex + 4, firey + 4) | Pc.ggetpix(firex + 4, firey + 5) |
                            Pc.ggetpix(firex + 4, firey + 6)) & 0xc0;
                    break;
                case 6:
                    firey += 7;
                    pix = (Pc.ggetpix(firex, firey) | Pc.ggetpix(firex, firey + 1) |
                            Pc.ggetpix(firex, firey + 2) | Pc.ggetpix(firex, firey + 3) |
                            Pc.ggetpix(firex, firey + 4) | Pc.ggetpix(firex, firey + 5) |
                            Pc.ggetpix(firex, firey + 6)) & 3;
                    break;
            }
            clbits = Drawing.drawfire(firex, firey, 0);
            Main.incpenalty();
            if ((clbits & 0x3f00) != 0) {
                for (mon = 0, b = 256; mon < 6; mon++, b <<= 1) {
                    if ((clbits & b) != 0) {
                        Monster.killmon(mon);
                        Scores.scorekill();
                        expsn = 1;
                    }
                }
            }
            if ((clbits & 0x40fe) != 0) {
                expsn = 1;
            }
            switch (firedir) {
                case 0:
                    if (firex > 296) {
                        expsn = 1;
                    } else if (pix != 0 && clbits == 0) {
                        expsn = 1;
                        firex -= 8;
                        Drawing.drawfire(firex, firey, 0);
                    }
                    break;
                case 4:
                    if (firex < 16) {
                        expsn = 1;
                    } else if (pix != 0 && clbits == 0) {
                        expsn = 1;
                        firex += 8;
                        Drawing.drawfire(firex, firey, 0);
                    }
                    break;
                case 2:
                    if (firey < 15) {
                        expsn = 1;
                    } else if (pix != 0 && clbits == 0) {
                        expsn = 1;
                        firey += 7;
                        Drawing.drawfire(firex, firey, 0);
                    }
                    break;
                case 6:
                    if (firey > 183) {
                        expsn = 1;
                    } else if (pix != 0 && clbits == 0) {
                        expsn = 1;
                        firey -= 7;
                        Drawing.drawfire(firex, firey, 0);
                    }
            }
        }
    }
}
