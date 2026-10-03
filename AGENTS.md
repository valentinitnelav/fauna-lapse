# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex and others) and for human contributors working in
this repository. `CLAUDE.md` only imports this file.

## What FaunaLapse is

FaunaLapse turns old or cheap Android phones into time-lapse trail cameras for monitoring insects and
other animals at a fixed spot (for example, pollinators on a flower). It has no AI. It takes photos
(later also short videos) in timed bursts with the screen off, saves only a square region of interest
(ROI) that the user drags over the subject, and closes the camera between bursts, so the phone stays
cool and can run longer on a power bank. Every photo and event is logged in a session file, so visit
rates can be computed later.

Sister project: FaunaPulse (https://github.com/valentinitnelav/fauna-pulse), a Flutter app that runs AI
detection on newer phones. FaunaLapse re-uses its ideas (ROI square, burst schedule, thermal pause,
session log), re-written in Java, not copied.

The project owner is a pollination ecologist with statistics skills (R, Python), not a professional
mobile developer. In code comments and explanations, define mobile development terms in plain language
at first use. Write plain English: short sentences, no idioms.

Status (2026-10-03): scheduled photo and video sessions work with the screen off on the Samsung M12;
phase 0 tests on more phones are still to come (`docs/PHONES.md`). Current state and next steps:
`docs/AGENT_OVERVIEW.md`.

## Hard constraints (change only with the owner's approval)

- Android first. An iOS version, if any, comes later as a separate app.
- Java, language level 8. No Kotlin, no C/C++ (the heavy image work runs in the phone's camera hardware,
  so native code would not make the app faster or cooler).
- Oldest Android: 4.4 (minSdk 19). Compile and target the newest SDK (36).
- No AndroidX and no third-party libraries, only Android's own classes (new AndroidX releases need
  Android 6 or newer).
- Camera: back camera only. The camera interface is chosen per phone: Camera2 where its hardware
  level is "limited", "full" or "level 3" (much faster photos); the old Camera1 interface
  (`android.hardware.Camera`) on Android 4.4 and on "legacy" phones, because it works everywhere.
- No AI. No network access for now.
- Keep it small: aim for an APK under about 300 KB.
- Energy and heat come first: screen off during capture, no preview while capturing, camera closed
  between bursts, lowest frame rate while open, hardware JPEG and video encoders. Only the ROI crop
  runs on the processor.
- Photos: save only the ROI square. The saved side is at least 480 px (default 1024) and is never
  enlarged beyond the real pixels inside the ROI. Videos: full frame for now (cropped on the PC); a
  crop inside the camera (Camera2 "freeform" phones) may come later.
- Sessions follow a schedule (daily windows for some days, or a run of some hours; inside them photo
  bursts or video clips with breaks). Bursts and clips follow a fixed time grid counted from the start
  of each window, so the schedule never drifts.
- Photo file names use the time (`yyyyMMdd_HHmmss_SSS.jpg`), so they sort correctly across midnight.

## Code origin and licence

- All code is written from scratch for this repository. The owner chooses the licence before the first
  release.
- GPL projects (Open Camera, TimeLapseCam, Background-Timelapse, FreeDcam, Haven and others) may be read
  for ideas and behaviour. Never copy, translate or closely paraphrase their code.
- Code from permissive projects (MIT, Apache-2.0, Apple sample code) may be re-used only with credit, in
  `NOTICE.md` and in a comment next to the re-used code.
- When an idea comes from a paper, an app or a person, say so in the comment or doc. Credit the source
  of the idea and the implementer separately.

## Repository layout (update when it changes)

- `app/`: the Android app (Gradle project, package `com.faunalapse.app`).
- `tools/`: PC scripts in Python. Now: `phase0_speed_report.py` (copy the newest speed test from a
  phone and summarise it). Planned: copy sessions with `adb pull`, contact sheets, crop video bursts
  with ffmpeg.
- `docs/`: documentation for humans and agents (see "Where knowledge lives" below).

## Build and test

Needs JDK 17 or newer and the Android SDK (platform 36). Put the SDK path in `local.properties`
(`sdk.dir=/path/to/Android/Sdk`); that file stays out of git. The Gradle wrapper (`gradlew`) downloads
Gradle itself on first use.

```bash
./gradlew assembleDebug    # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test             # unit tests on the PC (JUnit): app/src/test/
./gradlew lintDebug        # Android code checks: app/build/reports/lint-results-debug.txt
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Debug builds only: start a test without tapping (it starts 10 s later)
adb shell am start -n com.faunalapse.app/.MainActivity --ez take_photo true
adb shell am start -n com.faunalapse.app/.MainActivity --ez speed_test true
# Start a session; any setting can be given (names as in SessionSettings), the rest stays as saved
adb shell am start -n com.faunalapse.app/.MainActivity --ez start_session true \
    --es mode photo --es step_s 1 --es burst_s 10 --es break_min 1 --es schedule run --es run_h 0.06
adb shell am start -n com.faunalapse.app/.MainActivity --ez stop_session true
```

Unit tests cover the classes without Android code (`CapturePlan`, `RoiMath`); keep new schedule
and maths code in such classes and test it. On the phone, sessions are in
`/sdcard/Android/data/com.faunalapse.app/files/sessions/<start time>/` (photos or videos and
`session.jsonl`); phase 0 tests and `log.jsonl` are in `.../files/phase0/`.

## Where knowledge lives

Each fact has one home, so nothing needs to be read twice or kept in sync:

| Place | What | Update |
|---|---|---|
| `README.md` | For humans: what the app is, status, how to install and build | When the status or the use changes |
| `docs/AGENT_OVERVIEW.md` | For coding agents: current defaults, file map, key invariants, open problems, pointers | Edit in place whenever a change alters them; replace or remove old text, never add history |
| `docs/DECISIONS.md` | Each design decision with its reason and date | Add an entry when a decision is made or changed |
| `docs/PHONES.md` | Test phones and measured results (speed, heat, quirks) | Add results after each phone test |
| Git history | What changed in each commit and why | Through the commit message |
| Code comments | Why a line is written this way (non-obvious reasons, credits) | With the code |

Start of a session: read this file and `docs/AGENT_OVERVIEW.md`. Read `docs/DECISIONS.md` before changing the
design and `docs/PHONES.md` before camera or energy work. Use `git log` (for example
`git log --oneline -- <file>`) when you need the history of a file.

## Git

- Do not commit or push unless the owner asks for it in the conversation.
- Never push to `main`, never force push, no destructive Git operations without explicit approval.
- When you change code while on `main`, first switch to the branch `develop` (create it if missing) and
  leave the changes uncommitted.
- After code changes, suggest a commit message: a first line of at most about 70 characters that
  says what changed, an empty line, then a short body that says what and why (the body is the
  project history, so make it useful to a later reader).
- No `Co-Authored-By` or other agent lines in commit messages or pull requests. The README states that
  coding agents help write this project.
- Never put private or sensitive data in tracked files: email addresses, passwords, keys, signing
  keystores, phone serial numbers, personal whereabouts.
- No binaries in git (APKs, photos, videos, session data). APKs go to GitHub releases.

## Writing style

- Keep answers concise, clear and easy to understand.
- Less is more: the simplest code that keeps the core function and stays readable for humans.
- Avoid the em dash as punctuation; use parentheses (round brackets) instead.
- Text in the app: plain language, no jargon; give the reason for each default; show a number in the
  same unit and scale everywhere.
- Every new setting gets a control on screen, a saved value, and a line in the session record.

## Working with test phones

- Ask the owner before running anything on a connected phone. The phone must be unlocked and on a
  charger, and nobody should use it during the check.
- Do not uninstall the app from a test phone without the owner's agreement (it deletes the saved
  settings). Update with `adb install -r`.
- Read only the app's own log lines and the app's own folders on the phone. Do not browse other files.
- A new default in the code does not change a value already saved on a phone. Tell the owner which
  setting to change by hand.
