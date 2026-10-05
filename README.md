# FaunaLapse

FaunaLapse turns old or cheap Android phones into time-lapse cameras that watch one spot, for example
insects visiting a flower. It takes short bursts of photos or short video clips with the screen off,
keeps only a square area that you choose, and closes the camera between bursts. This keeps the
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
- Inside a window: photo bursts (for example one photo every 2 s for 60 s, then a 5 minute break) or
  video clips (for example 5 minutes of video, then a 5 minute break) at 5, 10 or 15 frames per
  second; fewer frames give smaller files, enough to follow insects from frame to frame on a computer.
  Videos also keep only the square; where the phone allows it, the camera zooms onto the square in
  hardware, so a small square keeps its real detail.
- If a phone cannot take photos as fast as asked, the app suggests video instead.
- Photos and videos keep only a square region of interest that you drag over the subject and pinch to size,
  saved at any multiple of 32 pixels from 224 to 4096 (1280 by default, the input size of many
  insect detectors), never enlarged.
- Focus: fixed at one distance (20 cm to start, set while you watch the camera view), so wind, a
  passing insect or another flower cannot pull the focus away. Phones that cannot fix the focus
  (often the oldest ones) focus automatically at the start of each burst or clip, and the app says so.
- Screen off and camera closed between bursts, to save power and avoid heat.
- A phone check before Start: airplane mode, Wi-Fi, Bluetooth, location and battery limits, each
  with a button to its settings page, so an old phone used only as a camera wastes no energy.
- A pause while the phone is too warm, and a clean stop when the battery runs low or the storage
  is nearly full.
- Field metadata for each session: site, transect, plant (or sample ID), session ID, your own
  fields (text, number, date or time), and the phone model and ID (filled in by the app); one GPS
  position on a tap (the GPS switches off again when the position is within 10 m), or latitude,
  longitude and accuracy typed from another GPS device.
- A session log of every photo and event, ready for analysis in R or Python, with the battery use
  of the session.
- Files in `Documents/FaunaLapse` on the phone or on a memory card (SD card), one folder per session
  (phone model and ID, start time, photo or video): easy to copy to a computer over USB, and kept
  when the app is removed (on a card with Android 7 to 10, Android allows only the app's own folder,
  which goes with the app). The main screen shows how much space FaunaLapse uses and how much is
  free, opens the folder in Android's file app, and during a session shows what it saved so far and
  any phone setting that wastes energy (for example Wi-Fi left on).

## Using it in the field

1. **At home, once per phone:** install the app, open it and allow the camera (on Android 10 or
   older also the storage). Under *3 · Schedule* tap *Change settings* and set the schedule; it stays
   saved on the phone. Check that the phone time is right. With several phones, set them all up
   this way before you go out. *2 · Field metadata* shows each phone's model and ID (write the ID
   on the phone if you like): they start the session folder names, so the files do not mix on the
   computer. For a phone used only as a camera, follow
   *4 · Phone for the field*: tap *Allow* for the battery limits, turn on airplane mode, and open
   its (i) for the full checklist (factory reset, no account, unneeded apps off).
2. **In the field:** fix the phone on a tripod or a clamp, facing the flower. For sessions longer
   than a few hours, connect a power bank.
3. Open FaunaLapse, choose *Photos* or *Video*, tap *Aim the camera*, drag the square onto the
   flower (put your finger inside the square) and pinch it to size. The focus starts fixed at about
   20 cm: tap *Focus again* to focus once on the square and fix it there, or move the focus slider
   until the flower is sharp. Tap *Done*, fill in *2 · Field metadata* (site, transect, plant) and,
   if you want the position, tap *Get location* outdoors (Location must be on; turn it off again
   afterwards) or type latitude and longitude from another GPS device. Then tap *Start session*.
4. Tap *Screen off* on the status card: the screen switches off as with the power button, but
   without pressing the phone where it stands. The first time, Android asks you to allow it
   (FaunaLapse becomes a "device admin app" with the single right to lock the screen; remove it
   under *Last session, battery and clock* before you uninstall FaunaLapse). Or press the power
   button gently. The session goes on; the camera is off in the breaks.
5. To stop early: open the app, tap *Stop session*, then *Stop*.
6. **Afterwards:** connect the phone to a computer with a USB cable, choose *File transfer* on the
   phone and copy the session folders from `Internal storage/Documents/FaunaLapse/` (or the same
   folder on the SD card, if you chose it on the status card). Each folder
   holds the photos (or videos) and `session.jsonl`, the record of every photo and event with the
   field metadata and the battery use.

Tips: a daily window may cross midnight (22:00-04:00), and 00:00-24:00 records all day, for example
under the midnight sun. The session pauses while the battery is too warm (45 °C by default) and stops
cleanly when the battery falls to 15 % without charging. Some phones stop apps in the background to
save power with their own battery manager (Huawei, Xiaomi, Samsung): the checklist under the (i) of
*4 · Phone for the field* says where to allow FaunaLapse.

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
