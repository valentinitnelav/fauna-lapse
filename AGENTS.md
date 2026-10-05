# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex and others) and human contributors. `CLAUDE.md`
only imports this file. At the start of a session, read this file and `docs/AGENT_OVERVIEW.md`.

## What FaunaLapse is

An Android app that turns old or cheap phones into time-lapse cameras for insects and other animals
at a fixed spot (for example pollinators on a flower), without AI. It records photo bursts or video
clips on a schedule with the screen off, saves only a square region of interest (ROI) of each photo,
closes the camera between bursts and logs every event, so visit rates can be computed later. Sister
app: FaunaPulse (https://github.com/valentinitnelav/fauna-pulse, Flutter, with AI); its ideas are
re-written in Java, never copied.

The owner is a pollination ecologist (R, Python), not a mobile developer: explain mobile terms in
plain language at first use, and write plain English with short sentences and no idioms.

## Hard constraints (change only with the owner's approval)

- Android only for now (iOS as a separate app, if ever). No AI. No network access; GPS only on tap.
- Java 8 language level; no Kotlin, no C/C++ (the image work runs in the camera hardware, so native
  code would not be faster or cooler). minSdk 24 (Android 7.0, the oldest phone we can test);
  compile and target SDK 36.
- In the app only Android's own classes: no AndroidX or other libraries (they add size and updates,
  not speed; CameraX would hide the Camera1 path that legacy phones need). JUnit for the PC tests is
  fine (it never goes into the APK). APK under about 300 KB.
- Back camera only. Camera interface per phone: Camera2 where its hardware level is "limited",
  "full" or "level 3" (Samsung M12: a 12 MP photo in 0.43 s, against 0.9 to 1.7 s with Camera1);
  Camera1 on "legacy" phones, where Camera2 only wraps Camera1.
- Focus: fixed at one distance where Camera2 allows (default 20 cm, set on the aim screen), else
  autofocus once per burst or clip. Exposure and white balance: automatic, metered on the ROI square.
- Energy and heat first: screen off, no visible preview, camera closed in long breaks, lowest frame
  rate while waiting, hardware JPEG and video encoders; the app only crops the square (GPU for video).
- Photos and videos: save only the ROI square, side any multiple of 32 from 224 to 4096 px (default
  1280, a detector input size), never enlarged beyond the real pixels in the ROI.
- Schedule: daily windows for some days, or a run of some hours; inside them photo bursts or video
  clips with breaks, on a fixed grid from each window start (no drift, same clock times every day).
- If the phone cannot reach the asked photo step, the app says so and suggests video or a longer step.
- File names use the time (`yyyyMMdd_HHmmss_SSS`), so they sort correctly across midnight; session
  folders start with the phone model and ID, so files from several phones never mix on one computer.
- Every setting gets a control on screen, a saved value, a reason for its default and a record line.

## Code origin and licence

- All code is written from scratch here; the owner chooses the licence before the first release.
- GPL projects (Open Camera, TimeLapseCam, Background-Timelapse, FreeDcam, Haven and others) may be
  read for ideas and behaviour. Never copy, translate or closely paraphrase their code.
- Code from permissive projects (MIT, Apache-2.0) may be re-used only with credit, in `NOTICE.md` and
  in a comment next to the code. When an idea comes from a paper, an app or a person, say so in the
  comment; credit the source of the idea and the implementer separately.

## Repository layout

- `app/`: the Android app (Gradle project, package `com.faunalapse.app`); unit tests in `app/src/test/`.
- `tools/`: PC scripts in Python (`phase0_speed_report.py`); `docs/`: see "Where knowledge lives".

## Build and test

Needs JDK 17+ and the Android SDK (platform 36), with its path in `local.properties`
(`sdk.dir=/path/to/Android/Sdk`, not in git). `gradlew` downloads Gradle itself.

```bash
./gradlew assembleDebug    # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test             # unit tests on the PC (JUnit)
./gradlew lintDebug        # Android code checks: app/build/reports/lint-results-debug.txt
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Debug builds only: start a test without tapping (it starts 10 s later)
adb shell am start -n com.faunalapse.app/.MainActivity --ez take_photo true
adb shell am start -n com.faunalapse.app/.MainActivity --ez speed_test true
adb shell am start -n com.faunalapse.app/.MainActivity --ez aim true    # the aim screen
# Add --ez camera1 true to any hook to use Camera1 on a Camera2 phone (until the app is closed)
# Start a session; any setting can be given (names as in SessionSettings), the rest stays as saved
adb shell am start -n com.faunalapse.app/.MainActivity --ez start_session true \
    --es mode photo --es step_s 1 --es burst_s 10 --es break_min 1 --es schedule run --es run_min 4
adb shell am start -n com.faunalapse.app/.MainActivity --ez stop_session true
# Guards, with the owner's OK: dumpsys battery set temp 460 | unplug | set level 10; then reset
```

Keep schedule and maths code in classes without Android code, and test it. On the phone: sessions in
`/sdcard/Documents/FaunaLapse/<model>_<phone ID>_<start time>_<photo or video>/`, tests in
`/sdcard/Android/data/com.faunalapse.app/files/phase0/`.

## Where knowledge lives (keep these files short)

- `AGENTS.md`: rules for every task (at most 120 lines).
- `docs/AGENT_OVERVIEW.md`: current state, defaults, file map, next steps (at most 100 lines).
- `docs/PHONES.md`: phone facts and measurements; read it only for camera or energy work.
- `README.md`: for humans. Git commit messages: the history (`git log --oneline -- <file>`).
- Upkeep: one home per fact. When something changes, replace or remove its text; never add history,
  dates of changes or "replaces X" notes. Merge or remove lines before passing a line limit. A reason
  goes in one clause next to its rule or in a code comment; longer reasoning in the commit message.

## Git

- Do not commit or push unless the owner asks. Never push to `main`, never force push, no
  destructive Git operations without explicit approval. Code changes go on the branch `develop`.
- Suggest a commit message after code changes: a first line of at most about 70 characters, an empty
  line, then what changed and why (this is the project history). No `Co-Authored-By` or other agent
  lines in commits or pull requests; the README says that coding agents help write this project.
- No private data in tracked files: email addresses, passwords, keys, keystores, phone serial
  numbers, personal whereabouts.
- No binaries in git (APKs go to GitHub releases; photos, videos and session data stay out). The one
  exception is the Gradle wrapper (`gradle/wrapper/gradle-wrapper.jar`), so anyone can build.

## Writing style

- Answers concise, clear and easy to understand. Less is more: the simplest code that keeps the core
  function and stays readable for humans.
- Avoid the em dash as punctuation; use parentheses (round brackets) instead.
- Text in the app: plain language, no jargon; the same unit and scale for a number everywhere. Live
  values and warnings stay visible; explanations open with the (i) of their label (`HelpToggles`).

## Working with test phones

- Ask the owner before running anything on a connected phone. It must be unlocked and on a charger,
  and nobody should use it during the check.
- Update with `adb install -r`; do not uninstall without the owner's agreement (it deletes the saved
  settings). Read only the app's own log lines and folders on the phone.
- A new default in the code does not change a value already saved on a phone: tell the owner which
  setting to change by hand.
