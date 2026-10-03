# FaunaLapse

FaunaLapse turns old or cheap Android phones into time-lapse cameras that watch one spot, for example
insects visiting a flower. It takes short bursts of photos (later also short videos) with the screen
off, keeps only a square area that you choose, and closes the camera between bursts. This keeps the
phone cool, so it can run for days on a power bank. Every photo is logged, so visit rates can be
computed later.

FaunaLapse has no artificial intelligence (AI). Its sister app,
[FaunaPulse](https://github.com/valentinitnelav/fauna-pulse), detects insects with AI on newer phones.

## Status

Early development, not ready for field use. Scheduled photo and video sessions with the screen off
work on the first test phone; the square is still fixed in the middle of the photo. Phase 0 tests on
more phones (speed, power, heat) are running. See [docs/PHONES.md](docs/PHONES.md) for the phones
tested so far.

## Planned features

- Android 7.0 or newer; old phones are welcome.
- Schedules: recording windows each day (for example 06:00 to 08:00 and 17:00 to 20:00) for several
  days, or a run of some hours.
- Inside a window: photo bursts (for example one photo every 2 s for 60 s, then a 4 minute break) or
  video clips (for example 5 minutes of video, then a 10 minute break).
- If a phone cannot take photos as fast as asked, the app suggests video instead.
- Photos keep only a square region of interest that you drag over the subject, at up to
  1024 x 1024 pixels, never enlarged.
- Screen off and camera closed between bursts, to save power and avoid heat.
- A session log of every photo and event, ready for analysis in R or Python.

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
