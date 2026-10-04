# Agent overview: current state

A short snapshot for coding agents (humans may read it too): current state, defaults, file map, key
invariants and next steps. Read at the start of every session; at most 100 lines. Each entry says only
what is true now and where it lives: replace or remove text when it changes, never add history. Rules
and their reasons are in `AGENTS.md`; history is in git.

Last update: 2026-10-04.

## What works now

- **Sessions** on a schedule, with the screen off: daily windows for N days or a run of Z hours;
  photo bursts (step, burst length, break; the ROI square is saved upright) or video clips (clip
  length, break; full frame, 15 fps); camera off in long breaks; Camera2 or Camera1 chosen per phone;
  every event in `session.jsonl`. Tested on the Samsung M12.
- **Main screen**: status card (live counts, battery, phone time; Stop asks first), then 1 Aim
  (photos or video, aim screen), 2 Schedule (one summary in words; the settings open on Change), 3
  Start. Light theme, dark in the phone's dark mode; phase 0 tests (one photo, speed test) folded away.
- **Aim screen**: live camera view in the session's own camera setup; drag the square, slider for
  its size; focus and exposure follow the square; a triangle ("top") marks the top of the saved
  photos; "Start session here" starts with the phone already in its place.
- **Guards**: heat pause (camera off); clean stop on low battery without charging or too little storage.

## Key invariants

- Product rules (Java 8, minSdk 24, no AndroidX or other libraries in the app, screen off, ROI
  square for photos, time-named files, back camera only, camera interface per phone, schedule grid
  from each window start): `AGENTS.md`, "Hard constraints".
- Schedule maths (windows may cross midnight; days count from the first window not over) lives in
  `CapturePlan`, square maths in `RoiMath`: no Android classes, unit tests in `app/src/test/`. Every
  answer comes from the clock time passed in, never from counters.
- `SessionRunner` asks the plan for the burst at or after `max(now, end of the last burst handled)`,
  so a burst is never run twice. Grid times that pass while a photo is taken are counted as
  `skipped_photo_times`; those already over when a burst could start as `photo_times_missed_at_start`.
- The session plan starts one wake lead (5 s) after the tap, so the first burst also warms up.
- Square position: shares of the full photo in sensor orientation; the portrait-only aim screen shows
  it turned by the sensor orientation; saved squares turn by that + the phone turn at Start (flat = 0).
- Camera1 calls back on the thread that opened the camera. `SessionRunner`, `SpeedTest` and the aim
  screen open it on a plain thread without a message loop, so callbacks go to the main thread
  (never block it); `Camera1Backend` uses its own HandlerThread.
- `CaptureService.running` = a test or session runs (one at a time); `sessionRunning` = a session.
  Stop = `Thread.interrupt()` of the session thread; the camera closes, and an open clip is finished
  and recorded with `"stopped_early": true`.
- Photos are cut and saved on a saver thread (queue of 2; when full, the session thread saves itself).
- Records: `time` = phone clock time with time zone; durations in ms (`SystemClock.elapsedRealtime`).
- `app/build.gradle` excludes the Kotlin standard library that the Android Gradle plugin adds; keep
  the exclusion when plugins change (APK near 70 KB).

## Defaults

| Default | Value | Where |
|---|---|---|
| Session settings (mode photo, step 2 s, burst 60 s, clip 5 min, break 4 min, run 2 h, windows 05:00-21:00, 1 day, square 100 % of the short side in the middle, saved side 1024 px, video 1080p) | reasons on the screen | `SessionSettings` fields, `strings.xml` hints |
| Video frame rate | 15 fps, bit rate = phone preset scaled to 15 fps | `SessionSettings.VIDEO_FPS`, `Camera1Session.bitRate` |
| Wake lead / autofocus lead / keep camera on if off for less than | 5 s / 3 s / 30 s | `SessionRunner` constants |
| Stop after camera errors in a row | 3 | `SessionRunner.MAX_ERRORS_IN_A_ROW` |
| Heat pause / resume; low battery stop; storage reserve; heat checks | 45 °C (a setting, 35 to 50) / 3 °C lower; 15 % when not charging; 200 MB; every 10 s in a burst, 30 s in a pause | `SessionSettings.heatLimitC`, `Guards`, `SessionRunner` |
| Saved square JPEG quality; Camera2 photo JPEG quality | 90; 90 | `PhotoSaver.JPEG_QUALITY`, `Camera2Session.JPEG_QUALITY` |
| Camera while waiting | lowest frame-rate range, focus mode `auto` (lens moves only when asked) | `Camera2Session`, `Camera1Session` |
| Output | `Documents/FaunaLapse/<start time>[_<phone name>]/` (survives an uninstall; not in the Gallery; Android 10 and older need the storage permission, else `Android/data/com.faunalapse.app/files/sessions/`); tests in `Android/data/.../files/phase0/` | `CaptureService.sessionsDir`, `outputDir` |
| Wake lock | held for the whole session (plan end + 10 min) | `CaptureService.startSession` |
| Phase 0 test defaults (start delay 10 s, wake lead 5 s, speed test sizes) | | `CaptureService`, `Camera1Backend`, `SpeedTest` |

## File map

| File | Job |
|---|---|
| `MainActivity.java` | Main screen (status card, aim and schedule cards, Start, Stop) and phase 0 test buttons; permissions; phone turn at Start; adb hooks in debug builds |
| `AimActivity.java`, `SquareView.java` | Aim screen: camera view (a visible preview for the session camera), the square and its size, focus check |
| `CaptureService.java` | Foreground service of type camera + partial wake lock; starts the session thread or a test; writes `phase0/log.jsonl` |
| `SessionSettings.java` | Session values: defaults, saving, checks, `CapturePlan` and the record line |
| `CapturePlan.java` | Schedule maths: periods, bursts, photo times, window parsing (unit tested) |
| `Guards.java` | Heat pause, low battery and storage rules (unit tested) |
| `SessionRunner.java` | Follows the plan: camera on and off, bursts and clips, saver thread, `session.jsonl`, screen status |
| `SessionCamera.java` | Interface of a session camera; `Camera2Session.java` and `Camera1Session.java` implement it |
| `RoiMath.java`, `PhotoSaver.java` | Square position, saved size, screen and focus-area maps, upright turn (unit tested); cutting, turning and saving the square |
| `SessionLog.java` | `session.jsonl` lines, time formats, file names |
| `Camera1Backend.java`, `SpeedTest.java`, `Camera2StillTest.java`, `CameraFacts.java`, `Camera2Facts.java` | Phase 0 tests and shared Camera1 size helpers |
| `tools/phase0_speed_report.py` | PC: copy the newest speed test from a phone and summarise it |

All Java files are in `app/src/main/java/com/faunalapse/app/`.

## Open problems and next steps

1. Long breaks: alarms instead of holding the wake lock (measure first with the USB power meter).
2. Lock exposure and white balance for each burst (no jumps; exposure already meters on the square);
   Camera2 photos are about one stop brighter than Camera1 (bright parts clip).
3. Time-lapse video below 15 fps (5 asked gave 3.7 on the Samsung M12: ask a little more).
4. Back-to-back bursts or clips (break 0) refocus at each start, which costs about 2 s.
5. More test phones (three Android 7 and 8.1 phones at work, the Xiaomi) and a USB power meter
   (phase 0 gate). Check the battery first, then the speed test (`sensor_pixels` against
   `jpeg_largest` shows photos enlarged in software).
6. Lint warnings left on purpose: `allowBackup` (backup rules file not needed), newer Gradle
   available (update with the Android build plugin).

## Pointers

Rules, commands, git and writing style: `AGENTS.md`. Phone facts and measured speeds:
`docs/PHONES.md`. Human introduction: `README.md`. History of a file: `git log --oneline -- <file>`.
