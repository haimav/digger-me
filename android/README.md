# DiggerME for Android

This directory holds the Android port of DiggerME. It is one more step in the
life of the original code:

1. **Digger**, Windmill Software, 1983 (IBM PC, CGA, 8088 assembly)
2. **Digger Remastered**, Andrew Jenner (C)
3. **Java applet port**, Marek Futrega (Java, ported from C "almost mechanically")
4. **DiggerME**, Haim Avron (Java ME / MIDP 2.0, the `src/` directory of this repository)
5. **DiggerME for Android** (this directory)

As with the step before it, the game is not rewritten: the game code of the
Java ME version is carried over, most of it unchanged and the rest with small,
marked changes, and only the platform layer under it (display, keys, sound,
storage, menus, application lifecycle) is replaced.

## Building

You need JDK 17 to 24 (Gradle 8.14 does not run on newer ones) and the Android
SDK with the Android 16 (API 36) platform.

* **Android Studio**: open this `android/` directory and run the `app` configuration.
* **Command line** (the Gradle wrapper downloads Gradle 8.14.8 by itself):

  ```bash
  cd android
  ./gradlew assembleDebug
  ```

  To install the result on a device or emulator:

  ```bash
  adb install app/build/outputs/apk/debug/app-debug.apk
  ```

  If Gradle cannot find the SDK, set `ANDROID_HOME`, or put `sdk.dir=/path/to/sdk`
  in `android/local.properties`.

The app runs on Android 5.0 (API 21) and later.

### Tests

```bash
cd android
./gradlew test
```

`HeadlessGameTest` runs the real game engine on the JVM, without a device: the
title screen, a game (keys, digging, pause, restart), the high score file, and
the rules of the sound (a paused game starts no tunes, music that is over does
not come back after a pause, no music over the funeral tune, the sound settings
can change at any time). It also saves the frames the game shows as PNG files in
`app/build/digger-frames/`.

## Playing

| | Touch screen | Keys |
|---|---|---|
| Move | the direction pad | arrows, D-pad, gamepad, the phone keypad `2` `4` `6` `8`, numeric keypad |
| Fire | FIRE | `F1` (as on the PC), `Enter`, `Space`, `5`, gamepad A/X/Y/L1/R1 |
| Start a game, end a pause | tap anywhere | any key |
| Menu | MENU, or Back | Back, `Menu`, gamepad START |
| One or two players (title screen) | | `Tab`, gamepad SELECT (ESC in the original) |
| Faster / slower | | `+` / `-` (as in the Java applet) |

The on-screen controls hide themselves when a key is used, and come back on the
next touch. When the game is hidden (another app, the screen goes off, the
notification shade...) it pauses at "PRESS ANY KEY", exactly as the Java ME
version did when its canvas was hidden. The screen stays on while you play; on
the title screen, at "PRESS ANY KEY" and under a menu it times out as usual.

### Menus

The menus are the Java ME menus, drawn the same way (a translucent white box,
the selection in red):

* **Options**: Settings, Restart, Exit Menu, Quit.
* **Settings**:
  * **Drawing Area**: `4:3` keeps the shape of the PC monitor Digger was made for
    (the controls sit beside or under the game); `FULL` stretches the game over
    the whole screen, like on the Java ME phones.
  * **Screen**: `LANDSCAPE`, `PORTRAIT` or `AUTO` (Java ME: *Rotate*).
  * **Music**: `MIDI` plays the MIDI tunes of DiggerME; `TONE` plays the note
    sequences and the sound effects as square waves, like the PC speaker.
  * **Mute**.

Use up/down and fire, or tap. A setting changes with left/right (as on the phone)
or with a tap; Restart and Quit need fire or a tap. The settings are kept between
runs. Quit gives up the game, as Restart does, and closes the app.

## How the code is organized

The packages are those of the Java ME version: `digger` for the game and
`general.*` for the reusable helpers.

### Carried over from Java ME without any change

`Bags`, `Drawing`, `Input`, `Monster`, `Pc`, `Sound`, `Sprite`, `_bag`,
`_game`, `_monster`, `alpha` (the font), `cgagrafx1`-`3` (the original CGA
graphics), `general.sound.NoteSequenceTunePlayer`, `TunePlayer`,
`NotePlayingException`, `general.ui.GaugableResource`, and the MIDI files (now
in `assets/audiofiles/`, under the same paths). These files are byte for byte
those of `src/`.

The functions of `digger.c` that live in `DiggerCanvas` (digging, firing, dying,
the bonus mode, ...) are unchanged as well, except `newframe()`, which also waits
while the game is hidden. Of the other methods that came with the class,
`endgame()` (Restart, Quit) also stops the music, and `pauseState()` pauses the
game and its sound together (the UI thread may pause the game just as the game
thread unpauses it).

### Carried over with small, marked changes

Every change is marked with an `Android port:` comment.

* `Main`: a new credit line on the title screen; a "Restart" chosen on the title
  screen no longer ends the next game as soon as it starts; the pause ("PRESS ANY
  KEY") and the title line wait while the game is hidden.
* `Scores`: the entry of the initials of a new high score waits while the game
  is hidden.
* `NewSound`:
  * A paused game starts no tunes. What the game asks for between the pause and
    "PRESS ANY KEY" (the music of a new level, the funeral tune, ...) plays when
    the game goes on; the new level tune is skipped, as a pause stopped it.
  * `killAll()`, which the game calls when a life, a level or a game is over (or
    given up), also forgets what is to be resumed after a pause. The bonus tune
    came back over the music of the next level, and the funeral tune at the start
    of the next game. A change of the sound settings keeps it, so the music goes
    on after it.
  * The music does not start during the funeral tune: a bag can kill the digger
    in bonus mode, and the end of the bonus mode then asks for the music. (In
    Java ME the second tune made `ResourceTunePlayer` throw.) The next life asks
    for it again.
  * The methods that start and stop tunes are synchronized, like the pause.
  * Switching the music from TONE back to MIDI threw a `NullPointerException`.
* `general.sound.MusicNotes`, `NotePlayer`, `NoteSeriallizer`: no more Mobile
  Media API types.
* `general.ui.MenuItem`, `TextMenuItem`, `MenuListener`, `MenuEvent`: Android
  `Canvas`/`Paint` instead of `Graphics`; a back event.

### The new platform layer

| Java ME | Android |
|---|---|
| `DiggerMidlet` (MIDlet) | `DiggerActivity`: the no-warranty terms on the first run, the menus, the lifecycle |
| `DiggerCanvas` as a `GameCanvas` | `DiggerCanvas` keeps the game state and the game thread (no Android dependency, so the game lives as long as the process); `DiggerView`, a `SurfaceView`, shows it and turns keys and touches into game actions |
| `MemoryDisplayUpdater` (scales and rotates the 320x200 pixels on a `GameCanvas`) | `MemoryDisplayUpdater` keeps an RGB image of the 320x200 CGA screen and publishes frames; `DiggerView` draws them, scaled with sharp pixels, from its own thread |
| `MenuScreen` (a full screen `Canvas`) | `MenuScreen`, a view laid over the paused game |
| `ScoreStorage` (RMS record store) | `ScoreStorage`, a file in the app's private storage, with the same content |
| `ResourceTunePlayer` (MMAPI `Player`) | `ResourceTunePlayer`, Android's `MediaPlayer` (it plays MIDI files) |
| `MobileMediaAPINotePlayer` (`Manager.playTone`) | `AudioTrackNotePlayer`, a square wave synthesizer on an `AudioTrack` |
| `ExtendedGameCanvas` (soft keys) | not needed: Back, the `Menu` key and the on-screen MENU button open the menu |

`general.sound.SimpleToneSequencePlayer`, `SoundUtils` and
`general.ui.GaugeMenuItem` were not used by the Java ME game and are not part
of the port.

### Threads

* The **game thread** runs `Main.main()`, the original main loop, at the
  original 80 ms per frame. While the app is in the background it sleeps,
  silent: in `newframe()`, or in the loops that do not call it (the pause, the
  initials of a new high score).
* The **title line thread** of the Java ME version (`Main.TitleUpdater`) sleeps
  then as well.
* The **display thread** of `DiggerView` draws the last frame the game flushed,
  so the game never waits for the screen.
* The sound threads of the Java ME version (`NoteSeriallizer`,
  `NoteSequenceTunePlayer`), plus the synthesizer thread of `AudioTrackNotePlayer`.
