# FaunaLapse

FaunaLapse turns old or cheap Android phones into time-lapse cameras that watch one spot, for example
insects visiting a flower. It takes short bursts of photos or short video clips with the screen off,
keeps only a square area of each photo that you choose, and closes the camera between bursts. This keeps the
phone cool, so it can run for days on a power bank. Every photo is logged, so visit rates can be
computed later.

FaunaLapse has no artificial intelligence (AI). Its sister app,
[FaunaPulse](https://github.com/valentinitnelav/fauna-pulse), detects insects with AI on newer phones.

## Status

Early development: ready for test sessions, not yet for field studies. Scheduled photo and video
sessions with the screen off work on the first test phone. Setting up a phone takes a few taps: aim
the camera and place the square on a live camera view, check the schedule (saved on the phone),
start. Phase 0 tests on more phones (speed, power, heat) are running. See
[docs/PHONES.md](docs/PHONES.md) for the phones tested so far.

## Planned features

- Android 7.0 or newer; old phones are welcome.
- Schedules: recording windows each day (for example 06:00 to 08:00 and 17:00 to 20:00, also across
  midnight or all day under the midnight sun) for several days, or a run of some hours.
- Inside a window: photo bursts (for example one photo every 2 s for 60 s, then a 4 minute break) or
  video clips (for example 5 minutes of video, then a 10 minute break) at 5, 10 or 15 frames per
  second; fewer frames give smaller files, enough to follow insects from frame to frame on a computer.
- If a phone cannot take photos as fast as asked, the app suggests video instead.
- Photos keep only a square region of interest that you drag over the subject, at up to
  1024 x 1024 pixels, never enlarged.
- Screen off and camera closed between bursts, to save power and avoid heat.
- A pause while the phone is too warm, and a clean stop when the battery runs low or the storage
  is nearly full.
- A session log of every photo and event, ready for analysis in R or Python.
- Files in `Documents/FaunaLapse` on the phone, one folder per session: easy to copy to a computer
  over USB, and kept when the app is removed.

## Using it in the field

1. **At home, once per phone:** install the app, open it and allow the camera (on Android 10 or
   older also the storage). Under *2 · Schedule* tap *Change settings* and set the schedule; it stays
   saved on the phone. Check that the phone time is right. With several phones, set them all up
   this way before you go out, and give each a short phone name (for example P1, P2): it is added
   to the session folder names, so the files do not mix on the computer.
2. **In the field:** fix the phone on a tripod or a clamp, facing the flower. For sessions longer
   than a few hours, connect a power bank.
3. Open FaunaLapse, choose *Photos* or *Video*, tap *Aim the camera*, drag the square onto the
   flower and set its size. When the focus line says "sharp", tap *Start session here*.
4. Switch the screen off with the power button. The session goes on; the camera is off in the
   breaks.
5. To stop early: open the app, tap *Stop session*, then *Stop*.
6. **Afterwards:** connect the phone to a computer with a USB cable, choose *File transfer* on the
   phone and copy `Internal storage/Documents/FaunaLapse/<start time>/`. Each folder holds the photos
   (or videos) and `session.jsonl`, the record of every photo and event.

Tips: a daily window may cross midnight (22:00-04:00), and 00:00-24:00 records all day, for example
under the midnight sun. The session pauses while the battery is too warm (45 °C by default) and stops
cleanly when the battery falls to 15 % without charging. Some phones stop apps in the background to
save power: if a session ends too early, allow FaunaLapse to run in the background in the phone's
battery settings.

## Build from source

You need JDK 17 or newer and the Android SDK. Write the SDK path into a file `local.properties`
(`sdk.dir=/path/to/Android/Sdk`), then:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Documentation

- [docs/AGENT_OVERVIEW.md](docs/AGENT_OVERVIEW.md): current state, file map and next steps (written
  for coding agents, readable for humans).
- [docs/PHONES.md](docs/PHONES.md): test phones and measured results.
- [AGENTS.md](AGENTS.md): rules and commands for contributors, human or AI coding agents.

## How this project is made

The code is written with the help of AI coding agents (Claude Code and others), guided and reviewed by
the project owner.

## Licence

Not chosen yet.
