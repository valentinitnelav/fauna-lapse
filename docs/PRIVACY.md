# FaunaLapse privacy policy

This policy covers the FaunaLapse Android app (`com.faunalapse.app`), from GitHub or Google Play.

## In short

FaunaLapse collects nothing about you and sends nothing anywhere. The app has no internet access
at all: Android does not let it use the network, because it does not ask for that permission. It has
no account, no advertising and no analytics or tracking code. Everything it records stays on your
phone until you copy or share it yourself.

## What the app saves on your phone

| What | Where | Contents |
|---|---|---|
| Sessions | `Documents/FaunaLapse/` on the phone or a memory card (your choice) | the photos or video clips of the square you chose; `session.jsonl`, the record of the session (times, settings, phone model, the phone ID below, battery level and temperature, and the field metadata); the site photos you took for the session; zip files made by *Pack* |
| Field metadata and settings | the app's private storage | what you typed on card 2 (for example site, plant, your name as *Set up by*, notes), the position from *Get location*, and the schedule and camera settings |

- **Phone ID:** 8 characters of Android's ID for this app on this phone (`ANDROID_ID`). It starts
  the session folder names, so files from several phones do not mix on one computer. On Android 8
  and newer each app gets its own value, so other apps cannot match it; a factory reset changes it.
- **Position:** only when you tap *Get location*, the app turns on the GPS for one position and then
  turns it off (after reaching your *Stop at* value, or after 3 minutes). It never tracks you. You can
  also type a position or leave it empty.
- **Backup:** FaunaLapse does not allow Android's cloud backup, so nothing goes to a Google account.
- **Deleting:** uninstalling FaunaLapse deletes its settings and its own folder
  (`Android/data/com.faunalapse.app/`). Sessions in `Documents/FaunaLapse` stay until you delete
  them, for example with the phone's file app.

## Permissions, and why each one is needed

| Permission | Why |
|---|---|
| Camera | The core function: photos and video of the square you chose |
| Location (precise and approximate) | Only for *Get location* (see above); optional |
| Notifications | The notification Android requires while a session runs or *Pack* works |
| Foreground service (camera, data sync) | Keeps a session running with the screen off, and lets *Pack* finish when you leave the app |
| Keep the phone awake (wake lock) | So the phone does not sleep through the times a photo or clip is due |
| Storage (Android 10 and older only) | To save sessions in `Documents/FaunaLapse` |
| Device admin app, only the right to lock the screen (optional) | Only if you allow the *Screen off* button: it switches the screen off at once. It cannot read, change or erase anything. Remove it under *Last session, battery and clock* |

FaunaLapse can open Android's battery settings, where you decide whether the app may run without
battery limits. It does not ask for that exemption itself.

## Sharing

FaunaLapse never sends data to the developer or to anyone else. Files leave the phone only when you
copy them (for example over USB) or share them with another app yourself.

*Support FaunaLapse* on the main screen opens other apps: *Share* opens Android's share window with
a link to FaunaLapse; *Report a problem* opens a GitHub page in your browser, with the FaunaLapse
version, the phone model and the Android version filled in. You see all of it before you send it;
what you send is then handled by GitHub under its own privacy policy.

## Children

FaunaLapse is a research tool for adults; it does not address children.

## Changes and contact

Changes to this policy appear in this file, with the history of the file on GitHub. Questions:
open an issue at https://github.com/valentinitnelav/fauna-lapse/issues.
