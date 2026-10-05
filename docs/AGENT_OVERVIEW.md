# Agent overview: current state

A short snapshot for coding agents (humans may read it too), read at the start of every session; at
most 100 lines. Each entry says only what is true now and where it lives: replace or remove text when
it changes, never add history. Rules and reasons: `AGENTS.md`; history: git. Last update: 2026-10-05.

## What works now

- **Sessions** on a schedule, with the screen off: daily windows for N days or a run of Z hours;
  photo bursts (step, burst length, break) or video clips (clip length, 5, 10 or 15 fps, break); both
  save only the ROI square, upright; camera off in long breaks; Camera2 or Camera1 per phone; every
  event in `session.jsonl`, battery use at the end (`BatteryMeter`); heat pause (camera off), clean
  stop on low battery without charging or too little storage; a pause by the user (camera off, aim
  screen to adjust, goes on by itself after 10 min). Tested on the Samsung M12.
- **Main screen**: status card (state; while running: counts, last saved square or clip frame,
  warnings, phone checks, MB so far, Pause or Continue; folder, use, free space, memory card choice;
  Open the folder; Screen off; last session, battery, clock fold away); 1 Aim; 2 Field metadata
  (summary; on Change: phone model and ID, Camtrap DP setup fields, distance from a calibrated fixed
  focus until typed, custom fields of 7 types, site photos, position typed or from Get location with
  live satellites (GPS on until 10 m or 3 min), the Location switch); 3 Schedule (summary; settings on
  Change); 4 Phone for the field. A problem stops Aim and Start with a dialog to its card.
- **Aim screen**: camera view (at most 40 % of the screen height) in the session's camera setup; the
  square moves only from a finger inside it or within 24 dp of its edge; pinch or slider for its size
  (real and saved pixels live); focus and exposure follow it; focus fixed (Camera2, 20 cm, slider,
  Focus again) or auto; Done and Start stay at the bottom (paused session: red bar, Continue).

## Key invariants

- Product rules (Java 8, minSdk 24, no libraries, ROI square, schedule grid): `AGENTS.md`.
- Schedule maths (windows may cross midnight; days count from the first window not over) lives in
  `CapturePlan`, square maths in `RoiMath`: no Android classes, unit tests in `app/src/test/`. Every
  answer comes from the clock time passed in, never from counters.
- `SessionRunner` asks the plan for the burst at or after `max(now, end of the last burst handled)`,
  so a burst never runs twice; the plan starts one wake lead (5 s) after the tap. Grid times passed
  during a photo: `skipped_photo_times`; before a burst could start: `photo_times_missed_at_start`.
- Square position: shares of the full photo in sensor orientation; the portrait-only aim screen
  shows it turned by the sensor orientation; saved squares turn by that + the phone turn at Start.
- Camera1 calls back on the thread that opened it: sessions, `SpeedTest` and the aim screen open it on
  a plain thread, so callbacks go to the main thread (never block it); `Camera1Backend` has its own.
- `CaptureService.running` = a test or session runs; `sessionRunning` = a session. Stop = interrupt
  the session thread (an open clip is finished, `"stopped_early"`). Photos are cut on a saver thread
  (queue of 2; when full, the session thread saves).
- Pause: the burst ends after its photo (or the clip closes), the camera closes, then `userPaused`;
  after it the square and focus are read again, a cut burst goes on (`continues_after_pause`), and
  the camera waits for `AimActivity.cameraInUse`. Site photos: written by the camera app through
  `SitePhotos.Provider`, moved into `<session>/site_photos/` at the start, listed in `field`.
- Video square: camera -> `SquareRecorder` texture -> GPU -> encoder -> MP4; texture corners address
  the stored frame, within the SurfaceTexture transform (Camera2 adds the sensor turn, Camera1 not).
- Records: `time` = phone clock with time zone; durations in ms; every setting and field key always
  present (null where it does not apply), so tables from many sessions get the same columns. Field
  keys use Camtrap DP words where one fits (`setup_by`, `coordinate_uncertainty_m`, ...).
- `app/build.gradle` excludes the Kotlin library that the Android plugin adds (debug APK 254 KB).

## Defaults

| Default | Value | Where |
|---|---|---|
| Session settings (mode photo, step 2 s, burst 60 s, clip 5 min, break 5 min, run 1 h, windows 05:00-21:00, 1 day, square 100 % of the short side in the middle, focus fixed at 20 cm, saved side 1280 px, video 5 fps) | reasons on the screen | `SessionSettings` fields, `strings.xml` hints |
| Video frame rate and bit rate | camera at the lowest fixed rate that is a multiple of it (Samsung: 15 for 5 fps); Camera2 sends every n-th frame to the recorder (repeating group), with Camera1 `FrameRates.Picker` keeps frames on the time grid; real time stamps; bit rate asked = the phone's best preset in bits per pixel, encoder's own rate control (best detail, owner's choice; Samsung 3.9 Mbit/s at 1280 px); key frame every 2 s | `FrameRates`, `Camera2Session.startClip`, `SquareRecorder` |
| Wake lead / autofocus lead / keep camera on if off for less than / shortest clip | 5 s / 3 s / 30 s / 3 s | `SessionRunner` constants |
| Stop after camera errors in a row; wake lock | 3; held for the whole session (plan end + 10 min) | `SessionRunner.MAX_ERRORS_IN_A_ROW`, `CaptureService.startSession` |
| Heat pause / resume; low battery stop; storage reserve; heat checks | 45 °C (a setting, 35 to 50) / 3 °C lower; 15 % when not charging; 200 MB; every 10 s in a burst, 30 s in a pause | `SessionSettings.heatLimitC`, `Guards`, `SessionRunner` |
| Pause by the user ends by itself; custom text / notes / list | 10 min (or at the session end); 100 / 5000 characters / 2 to 30 choices | `SessionRunner.PAUSE_LIMIT_MS`, `FieldInfo` |
| JPEG quality (saved square, Camera2 photo); camera while waiting | 90, 90; lowest frame-rate range, focus mode `auto` (lens moves only when asked) | `PhotoSaver`, `Camera2Session`, `Camera1Session` |
| Output | `Documents/FaunaLapse/<model>_<phone ID>_<start time>_<photo or video>/` on the phone or a memory card (user's choice; phone ID: 8 characters of `ANDROID_ID`; survives an uninstall; not in the Gallery). The app's own `Android/data/com.faunalapse.app/files/sessions/` (deleted with the app) on the phone with Android 10 and older without the storage permission, on a card with Android 7 to 10; tests in `Android/data/.../files/phase0/` | `Storage`, `CaptureService.outputDir` |

## File map (Java files in `app/src/main/java/com/faunalapse/app/`)

| File | Job |
|---|---|
| `MainActivity.java` | Main screen (status card with the last photo, Pause and Stop; the four cards; Start) and phase 0 test buttons; permissions; phone turn at Start and Continue; adb hooks in debug builds |
| `AimActivity.java`, `SquareView.java` | Aim screen: camera view (a visible preview for the session camera), the square (drag, pinch), focus auto or fixed |
| `CaptureService.java`, `Storage.java` | Foreground service of type camera + partial wake lock; starts the session thread or a test; writes `phase0/log.jsonl`. The places for sessions (phone, memory cards), the chosen one, paths as file apps show them |
| `SessionSettings.java` | Camera and schedule values: defaults, saving, checks, `CapturePlan` and the record line |
| `FieldInfo.java`, `CustomFields.java`, `GpsFix.java`, `SitePhotos.java` | Field metadata (saved apart from the settings; phone model and ID, folder names); the user's own fields on card 2; one GPS position (satellites live), then the GPS off; site photos with the camera app |
| `BatteryMeter.java` | Battery current every 10 s (camera open or closed), level at start and end; `battery` in `session_end` |
| `CapturePlan.java` | Schedule maths: periods, bursts, photo times, window parsing (unit tested) |
| `FrameRates.java` | Video below the camera's slowest rate: fixed camera rate, frames kept on a time grid (unit tested) |
| `Guards.java` | Heat pause, low battery and storage rules (unit tested) |
| `SessionRunner.java` | Follows the plan: camera on and off, bursts and clips, saver thread, `session.jsonl`, screen status |
| `SessionCamera.java` | Interface of a session camera; `Camera2Session.java` and `Camera1Session.java` implement it |
| `RoiMath.java`, `PhotoSaver.java` | Square position, saved size, screen and focus-area maps, upright turn, video crop and texture corners (unit tested); cutting, turning and saving the photo square |
| `SquareRecorder.java` | Video of the square: camera texture, GPU cut and turn, hardware H.264 encoder, MP4; encoder choice and bit rate |
| `SessionLog.java` | `session.jsonl` lines, time formats, file names |
| `PhoneCheck.java`, `HelpToggles.java`, `ScreenOff.java` | Phone settings for the field (read only; main screen and record); explanations behind the (i) of a label; Screen off: a device admin app with only the right to lock the screen (`lockNow`), granted once by the user |
| `Camera1Backend.java`, `SpeedTest.java`, `Camera2StillTest.java`, `CameraFacts.java`, `Camera2Facts.java` | Phase 0 tests (they start 10 s after the tap) and shared Camera1 size helpers |
| `tools/phase0_speed_report.py` | PC: copy the newest speed test from a phone and summarise it |

## Open problems and next steps

1. Battery per hour (for the README): 1 h photo and 1 h video sessions off the charger (the owner
   unplugs; `battery` in `session_end`). Then long breaks: alarms instead of the wake lock.
2. Camera2 photos are about one stop brighter than Camera1 (bright parts clip).
3. Camera1 video has no hardware zoom (a centred zoom could help small squares). Photos stay cut by
   the app (owner): the camera's own square sizes differ per phone (Samsung: 2992 and 1088 px only).
4. More test phones (three Android 7 and 8.1 phones at work, the Xiaomi): battery first, then the
   speed test (`sensor_pixels` above `jpeg_largest`: enlarged photos; `all_camera1_parameters`).
5. Lint warnings left on purpose: `allowBackup`, newer Gradle (update with the Android plugin).
6. Parked by the owner (copying to a computer, Camtrap DP export, GBIF and more): `docs/PARKED.md`.
