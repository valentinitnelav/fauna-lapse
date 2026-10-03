# Agent overview: current state

A short snapshot for coding agents (humans may read it too): current defaults, file map, key
invariants and pointers. Read it at the start of every session. Each entry says only what is true now
and where it lives. When a change alters a default, an invariant or a file, replace or remove the old
text here instead of adding to it. History is in git; reasons are in `DECISIONS.md`.

Last update: 2026-10-03.

## What works now

Phase 0 (feasibility test). The test app, with the screen off:
- takes one photo (Camera1: open, warm up, focus once, photo, close);
- runs a speed test (about 3 minutes): Camera1 full photos at 4 sizes, JPEG from preview frames,
  10 s videos (15 fps; time-lapse at 5 and 1 frames per s), Camera2 full photos (Android 5+), and
  saves the camera facts.
Tested on the Samsung M12 only (`PHONES.md`). There is no schedule and no Camera2 capture path in the
real app yet; both are decided (`DECISIONS.md`, 2026-10-03) and come next.

## Key invariants

- Product rules (Java 8, minSdk 19, no AndroidX or other libraries, screen off, ROI square, time-named
  files, back camera only, camera path per phone): `AGENTS.md`, section "Hard constraints".
- Camera1 calls back on the thread that opened the camera. `Camera1Backend` opens it on its own
  HandlerThread; `SpeedTest` opens it on a plain thread without a message loop, so the callbacks go to
  the main thread. Never block the main thread while a camera test runs.
- Classes that use Camera2 (`Camera2Facts`, `Camera2StillTest`) are only reached behind
  `Build.VERSION.SDK_INT >= 21` checks; Android 4.4 must never load them.
- `CaptureService.running` is the only "busy" flag: one test at a time, started from the screen.
- Every test ends with one JSON line in `phase0/log.jsonl`; the screen shows the last line. Times in
  records are milliseconds from the start of the camera step (`SystemClock.elapsedRealtime`); wall
  clock time appears only in `time` fields and file names.
- `app/build.gradle` excludes the Kotlin standard library that the Android Gradle plugin adds; keep
  the exclusion when plugins change (check that the APK stays near 50 KB).

## Defaults (constants for now; no settings screen yet)

| Default | Value | Where |
|---|---|---|
| Start delay after the tap (time to switch the screen off) | 10 s | `CaptureService.START_DELAY_MS` |
| Wake lead, one-photo test (camera open to photo) | 5 s | `Camera1Backend.WAKE_LEAD_MS` |
| One-photo test camera settings | largest picture, smallest preview of the same shape, lowest frame rate, focus `auto` once (3 s timeout), shutter sound off where allowed, phone's JPEG quality | `Camera1Backend.openAndPreview` |
| Speed test | 5 photos per size, 3 s warm-up per restart, JPEG quality 90, ROI square 1024 px centred, 10 s videos with the preset bit rate scaled to the frame rate | constants at the top of `SpeedTest` and `Camera2StillTest` |
| Output folder | `Android/data/com.faunalapse.app/files/phase0/` (speed tests in `speed_<time>/`) | `CaptureService.outputDir` |

## File map

| File | Job |
|---|---|
| `app/src/main/java/com/faunalapse/app/MainActivity.java` | Test screen: one-photo and speed-test buttons; last log record; camera (and Android 13+ notification) permission; adb test hooks in debug builds |
| `app/src/main/java/com/faunalapse/app/CaptureService.java` | Foreground service of type camera + partial wake lock; runs the chosen test after the start delay; writes `log.jsonl` and Logcat (tag `FaunaLapse`) |
| `app/src/main/java/com/faunalapse/app/Camera1Backend.java` | One-photo test (timings, preview brightness); shared Camera1 size and frame-rate helpers |
| `app/src/main/java/com/faunalapse/app/SpeedTest.java` | Speed test script; `results.jsonl` and files in `speed_<time>/` |
| `app/src/main/java/com/faunalapse/app/Camera2StillTest.java` | Speed test part: Camera2 full photos (Android 5+) |
| `app/src/main/java/com/faunalapse/app/CameraFacts.java`, `Camera2Facts.java` | Camera facts for `facts.json` (Camera2 part Android 5+) |
| `tools/phase0_speed_report.py` | PC: copy the newest speed test from a phone, check videos with ffprobe, print a summary |

## Open problems and next steps

1. Schedule (`CapturePlan`, pure Java with unit tests): daily windows for N days or a run of Z hours;
   photo bursts or video clips with breaks; grid counted from each window start.
2. Session runner in the foreground service: follows the schedule with the screen off, camera closed
   in long breaks, Camera2 or Camera1 per phone, MediaRecorder clips, session folder and log.
3. Measure the phone's real photo speed at setup and suggest video when the asked step is too short.
4. Show the phone's date and time on the start screen (the Samsung clock was 14 min off on
   2026-10-02) and log it at session start.
5. Exposure: Camera2 photos are about one stop brighter than Camera1 (bright parts clip); meter on
   the ROI and test with white flowers. Camera1 EXIF exposure values are wrong on the Samsung M12.
6. Time-lapse video asked at 5 frames per s gave 3.7: ask for a little more (for example 5.5).
7. More test phones (Android 4.4 to 6, 7 to 9, the Xiaomi with Android 14) and a USB power meter for
   the phase 0 gate (power and heat).
8. Lint warnings left on purpose: `allowBackup` (decide when settings exist), `TargetApi` (the
   suggested `RequiresApi` needs AndroidX), `QUALITY_2160P` (harmless on Android 4.4).

## Pointers

- Rules, commands, git and writing style: `AGENTS.md`.
- Design decisions with reasons: `docs/DECISIONS.md`.
- Phone facts and measured speeds: `docs/PHONES.md`.
- Human introduction: `README.md`.
- History of a file: `git log --oneline -- <file>`.
