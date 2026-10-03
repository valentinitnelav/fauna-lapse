# Agent overview: current state

A short snapshot for coding agents (humans may read it too): current defaults, file map, key
invariants and pointers. Read it at the start of every session. Each entry says only what is true now
and where it lives. When a change alters a default, an invariant or a file, replace or remove the old
text here instead of adding to it. History is in git; reasons are in `DECISIONS.md`.

Last update: 2026-10-03.

## What works now

- **Sessions** on a schedule, with the screen off: daily windows for N days or a run of Z hours;
  photo bursts (step, burst length, break; the centred ROI square is saved) or video clips (clip
  length, break; full frame, 15 fps); camera off in long breaks; Camera2 or Camera1 chosen per phone;
  every event in `session.jsonl`. Settings screen with Start and Stop. Tested on the Samsung M12
  (photo and video runs).
- **Phase 0 tests** (screen off): one test photo, and the speed test (`PHONES.md`).
- Not yet: choosing the square by dragging, upright rotation of saved squares, DCIM output, alarms
  instead of a wake lock in long breaks, thermal and battery guards, time-lapse video below 15 fps.

## Key invariants

- Product rules (Java 8, minSdk 19, no AndroidX or other libraries in the app, screen off, ROI
  square for photos, time-named files, back camera only, camera interface per phone, schedule grid
  from each window start): `AGENTS.md`, "Hard constraints".
- Schedule maths lives in `CapturePlan` and square maths in `RoiMath`: no Android classes there, unit
  tests in `app/src/test/`. Every answer comes from the clock time passed in, never from counters.
- `SessionRunner` asks the plan for the burst at or after `max(now, end of the last burst handled)`,
  so a burst is never run twice. Grid times that pass while a photo is taken are counted as
  `skipped_photo_times`; those already over when a burst could start as `photo_times_missed_at_start`.
- The session plan starts one wake lead (5 s) after the tap, so the first burst also warms up.
- Camera1 calls back on the thread that opened the camera. `SessionRunner`, `SpeedTest` and
  `Camera1Session` open it on a plain thread without a message loop, so callbacks go to the main
  thread; `Camera1Backend` uses its own HandlerThread. Never block the main thread during a session.
- Classes that use Camera2 (`Camera2Session`, `Camera2Facts`, `Camera2StillTest`) are only reached
  behind `Build.VERSION.SDK_INT >= 21` checks; Android 4.4 must never load them.
- `CaptureService.running` = a test or session runs (one at a time); `sessionRunning` = a session.
  Stop = `Thread.interrupt()` of the session thread; the camera closes, and an open clip is finished
  and recorded with `"stopped_early": true`.
- Photos are cut and saved on one saver thread with a queue of 2; when it is full the session thread
  saves itself (slows photos rather than filling memory).
- Records: `time` fields are phone clock time with time zone; durations are milliseconds
  (`SystemClock.elapsedRealtime`).
- `app/build.gradle` excludes the Kotlin standard library that the Android Gradle plugin adds; keep
  the exclusion when plugins change (APK near 70 KB).

## Defaults

| Default | Value | Where |
|---|---|---|
| Session settings (mode photo, step 2 s, burst 60 s, clip 5 min, break 4 min, run 2 h, windows 05:00-21:00, 1 day, square 100 % of the height, saved side 1024 px, video 1080p) | reasons on the screen | `SessionSettings` fields, `strings.xml` hints |
| Video frame rate | 15 fps, bit rate = phone preset scaled to 15 fps | `SessionSettings.VIDEO_FPS`, `Camera1Session.bitRate` |
| Wake lead / autofocus lead / keep camera on if off for less than | 5 s / 3 s / 30 s | `SessionRunner` constants |
| Stop after camera errors in a row | 3 | `SessionRunner.MAX_ERRORS_IN_A_ROW` |
| Saved square JPEG quality; Camera2 photo JPEG quality | 90; 90 | `PhotoSaver.JPEG_QUALITY`, `Camera2Session.JPEG_QUALITY` |
| Camera while waiting | lowest frame-rate range, focus mode `auto` (lens moves only when asked) | `Camera2Session`, `Camera1Session` |
| Output | `Android/data/com.faunalapse.app/files/sessions/<start time>/`; tests in `.../files/phase0/` | `CaptureService.sessionsDir`, `outputDir` |
| Wake lock | held for the whole session (plan end + 10 min) | `CaptureService.startSession` |
| Phase 0 test defaults (start delay 10 s, wake lead 5 s, speed test sizes) | | `CaptureService`, `Camera1Backend`, `SpeedTest` |

## File map

| File | Job |
|---|---|
| `MainActivity.java` | Settings screen (session values, Start, Stop, phone time, status) and phase 0 test buttons; permissions; adb hooks in debug builds |
| `CaptureService.java` | Foreground service of type camera + partial wake lock; starts the session thread or a test; writes `phase0/log.jsonl` |
| `SessionSettings.java` | Session values: defaults, saving, checks, `CapturePlan` and the record line |
| `CapturePlan.java` | Schedule maths: periods, bursts, photo times, window parsing (unit tested) |
| `SessionRunner.java` | Follows the plan: camera on and off, bursts and clips, saver thread, `session.jsonl`, screen status |
| `SessionCamera.java` | Interface of a session camera; `Camera2Session.java` and `Camera1Session.java` implement it |
| `RoiMath.java`, `PhotoSaver.java` | Square position and saved size (unit tested); cutting and saving the square |
| `SessionLog.java` | `session.jsonl` lines, time formats, file names |
| `Camera1Backend.java`, `SpeedTest.java`, `Camera2StillTest.java`, `CameraFacts.java`, `Camera2Facts.java` | Phase 0 tests and shared Camera1 size helpers |
| `tools/phase0_speed_report.py` | PC: copy the newest speed test from a phone and summarise it |

All Java files are in `app/src/main/java/com/faunalapse/app/`.

## Open problems and next steps

1. Square chosen by dragging on a live preview (setup screen); rotate saved squares upright.
2. Save sessions to `DCIM/FaunaLapse/` (MediaStore on Android 10+) so they survive an uninstall.
3. Long breaks: alarms instead of holding the wake lock; thermal pause (43 °C, resume 40 °C) and
   low battery and storage guards.
4. Lock exposure and white balance for each burst (no jumps); meter on the square; Camera2 photos
   are about one stop brighter than Camera1 (bright parts clip).
5. Time-lapse video below 15 fps (5 asked gave 3.7 on the Samsung M12: ask a little more).
6. Back-to-back bursts or clips (break 0) refocus at each start, which costs about 2 s.
7. More test phones (Android 4.4 to 6, 7 to 9, the Xiaomi) and a USB power meter (phase 0 gate).
8. Lint warnings left on purpose: `allowBackup`, `TargetApi` (the suggested `RequiresApi` needs
   AndroidX), `QUALITY_2160P` (harmless on Android 4.4), one text concatenation on the screen.

## Pointers

- Rules, commands, git and writing style: `AGENTS.md`.
- Design decisions with reasons: `docs/DECISIONS.md`.
- Phone facts and measured speeds: `docs/PHONES.md`.
- Human introduction: `README.md`.
- History of a file: `git log --oneline -- <file>`.
