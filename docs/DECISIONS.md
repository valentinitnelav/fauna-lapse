# Design decisions

One short entry per decision: what was decided, why, and who decided. Newest at the end. To change a
decision, add a new entry that names the one it replaces; do not rewrite old entries.

## 2026-10-01: new app with own code, in Java (owner)

Written from scratch in Java. GPL apps (Open Camera, TimeLapseCam and others) are read only for ideas;
no GPL code is copied, so the owner can choose the licence later. Code from permissive projects (MIT,
Apache-2.0) may be reused with credit. Java rather than Kotlin or C/C++: the heavy image work runs in the
camera hardware, so native code would not be faster, and Java needs no extra library.

## 2026-10-01: oldest Android is 4.4; Camera1 is the main camera path (owner)

minSdk 19 (Android 4.4). The old Camera1 interface works on every Android version; Camera2 offers more
only on better phones (manual focus) and is a later add-on. No AndroidX libraries, because their new
releases need Android 6 or newer.

## 2026-10-01: focus once per burst on Camera1 phones (owner)

Camera1 phones autofocus once on the square at the start of each burst, then keep that focus for the
burst. Phones with manual focus (Camera2, phase 4) keep one fixed distance and never autofocus.

## 2026-10-02: package name `com.faunalapse.app`

Same pattern as FaunaPulse (`com.faunapulse.app`). Must be registered for Android developer
verification before 2027.

## 2026-10-02: back camera only (owner)

Only the back camera is used for science. The front camera may become a fallback later, for an old
phone whose back camera is broken.

## 2026-10-02: phase 0 files in the app's own folder

Phase 0 test photos go to `Android/data/com.faunalapse.app/files/phase0/`: no storage permission is
needed. Android deletes this folder when the app is uninstalled, so phase 1 saves session photos to
`DCIM/FaunaLapse/<session>/` instead.

## 2026-10-02: build setup

Gradle wrapper committed (including `gradle-wrapper.jar`, as Gradle recommends), so anyone can build
with `./gradlew`. Android Gradle plugin 9 adds the Kotlin standard library to every app; it is
excluded in `app/build.gradle` (it would add 2.4 MB to a 35 KB app).

## 2026-10-02: documentation without an agent changelog (owner and agent)

No long agent changelog file (in FaunaPulse it grew to thousands of lines that nobody can read whole).
History lives in git commit messages. A short current-state file for coding agents,
`docs/AGENT_OVERVIEW.md`, is read at the start of every session and edited in place (only what is true
now). Decisions go here, measurements to `docs/PHONES.md`. `AGENTS.md` (read by most coding agents;
`CLAUDE.md` only points to it) holds the rules and commands; `README.md` is for humans. Commit
messages carry no agent co-author lines; the README says that coding agents help write the project.

## 2026-10-03: camera path chosen per phone (owner; replaces "Camera1 is the main camera path")

Camera2 where its hardware level is "limited", "full" or "level 3"; Camera1 on Android 4.4 and on
"legacy" phones (where Camera2 is only a wrapper around Camera1). Reason: on the Samsung M12 a 12 MP
photo takes 0.43 s through Camera2 but 0.9 to 1.7 s through Camera1 (`docs/PHONES.md`).

## 2026-10-03: schedules for photos and video (owner)

As in FaunaPulse, a session follows a schedule:
- when: one or more daily recording windows (for example 06:00 to 08:00 and 17:00 to 20:00) for a
  number of days, or simply a run of a number of hours from the start;
- inside a window: photo bursts (photo step, burst length, break between bursts) or video clips (clip
  length, break between clips); break 0 means continuous.
Bursts and clips follow a fixed time grid counted from the start of each window, so they never drift
and start at the same clock times every day. Multi-day runs assume the phone is on a power supply.

## 2026-10-03: suggest video when photos are too slow (owner)

If the phone cannot reach the asked photo step at the chosen size (measured on the phone), the app
says so and suggests video instead (15 fps, or time-lapse video at 1 frame per s) or a smaller picture.

## 2026-10-03: first session version: simple choices to replace later

- The square is centred, with its size as a share of the photo height, until the screen for dragging
  the square exists. Saved squares keep the sensor's orientation (turned 90° on most phones) until
  then.
- Session files go to the app's own folder (`files/sessions/<start time>/`) until DCIM output exists.
- The wake lock is held for the whole session (the phone stays awake between bursts). Fine on a power
  supply; alarms for long breaks come later.
- The schedule starts one wake lead (5 s) after the tap, so the first burst also has its warm-up.
- Video: full frame at 15 fps, the phone maker's bit rate scaled to 15 fps; one MP4 per clip.

## 2026-10-03: unit tests with JUnit, on the PC only

Schedule and square maths are plain Java classes tested with JUnit 4 (`./gradlew test`). JUnit is a
test-only dependency: it never goes into the app, so the "no libraries" rule for the app still holds.
