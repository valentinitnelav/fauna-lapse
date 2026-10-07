# Installing FaunaLapse

FaunaLapse is not yet in the Google Play Store. You install it from an APK file (the install file of an
Android app), which you download from the project's GitHub page. It needs Android 7.0 or newer. One
APK works on every phone: the app has no code for a particular processor.

## 1. Get the APK on the phone

- On the phone, open the [list of releases](https://github.com/valentinitnelav/fauna-lapse/releases)
  and download `faunalapse-v<version>.apk` from the *Assets* of the newest release. Early versions
  are marked *Pre-release*: take the newest one in the list all the same.
- Or download it on a computer and copy it over USB (*File transfer*) into the phone's `Download`
  folder.

## 2. Install it

1. Tap the downloaded file: in the browser's downloads, or in the phone's file app (for example *My
   Files* on Samsung, *File Manager* on Xiaomi).
2. Android asks whether this app (the browser or the file app) may install apps. Tap *Settings*,
   turn on *Allow from this source*, and go back. On Android 7 the switch is called *Unknown
   sources* and is in the phone's settings under *Security* (or *Lock screen and security*); you
   can turn it off again after the install.
3. Tap *Install*. Google Play Protect may warn that it does not know the app (it does this for apps
   from outside Google Play): tap *More details*, then *Install anyway*. Xiaomi phones (MIUI or
   HyperOS) first check the file with their own security app and may show more warnings.

The same steps with pictures, on a Samsung phone, are in the install guide of the sister app
FaunaPulse ([Track A, step A2](https://github.com/valentinitnelav/fauna-pulse/blob/main/docs/INSTALL.md#a2-install-it)).

## 3. First start

1. Open FaunaLapse and allow the camera. On Android 13 or newer, Android asks at the same time
   whether FaunaLapse may show notifications: allow it, so the notification shows when a session
   runs.
2. On Android 10 or older, also allow the storage (photos, media and files). Sessions then go to
   `Documents/FaunaLapse`. Without it they stay in the app's own folder, which Android deletes
   together with the app.
3. The location is asked only when you first tap *Get location* (card *2 · Field metadata*). Choose
   *Precise*: an approximate location can be wrong by more than a kilometre.
4. Then follow *Using it in the field* in the [README](../README.md#using-it-in-the-field). For a
   phone used only as a camera, card *4 · Phone for the field* lists the phone settings to change.

## 4. Updates

- Download the newer APK and install it in the same way, over the installed app. **Do not uninstall
  FaunaLapse first:** uninstalling deletes the saved settings (schedule, field metadata, your own
  fields) and the app's own folder.
- An update keeps the settings, the sessions and the phone ID that starts the session folder names.
- Android installs an update only if it is signed with the same key as the installed app. All
  FaunaLapse releases on GitHub carry the same key. A version built on a computer for testing has
  another key: Android then says *App not installed* (or that the package conflicts with an
  existing one). To change between the two, copy the sessions to a computer, uninstall FaunaLapse,
  then install the other version. The phone ID changes too.
- The installed version is shown in the phone's settings, under *Apps*, *FaunaLapse*.

### Updates with Obtainium (optional)

[Obtainium](https://github.com/ImranR98/Obtainium) is a free app that installs apps from their
GitHub releases and tells you about new versions. In Obtainium, tap *Add*, paste
`https://github.com/valentinitnelav/fauna-lapse`, and turn on *Include prereleases* before you add
it: all FaunaLapse versions are pre-releases for now, and Obtainium skips those by default.

## 5. Uninstall

1. Copy the sessions to a computer first (README, *Using it in the field*, step 6). Sessions in
   `Documents/FaunaLapse` stay on the phone after the uninstall. Sessions in the app's own folder
   (`Android/data/com.faunalapse.app/`: on a memory card with Android 7 to 10, or without the
   storage permission on Android 10 or older) are deleted with the app.
2. If you allowed the *Screen off* button: in FaunaLapse, open *Last session, battery and clock* on
   the status card and tap *Remove*. Android does not uninstall an app that is still a "device admin
   app". (The same switch is in the phone's settings, under *Security*, *Device admin apps*; the
   name differs between phones.)
3. Uninstall FaunaLapse like any other app (touch and hold its icon, then *Uninstall*). Its saved
   settings go with it.

## Building FaunaLapse yourself

See *Build from source* in the [README](../README.md#build-from-source). Such a build is a test
version: it has another key than the releases (see *Updates*).

### Release builds (maintainers only)

Release APKs are signed with the project's release key. The key and its passwords stay outside the
repository, in `~/faunalapse-signing/` (or in a properties file named by the `FAUNALAPSE_SIGNING`
environment variable). Create the key once, in your own terminal:

```bash
bash tools/create_release_key.sh
```

Back up the key file in two places and keep the password in a password manager. If the key or its
password is lost, nobody can recover it, and every user must uninstall FaunaLapse (losing the
settings) to get newer versions. Then:

```bash
./gradlew test lintDebug assembleRelease
cp app/build/outputs/apk/release/app-release.apk faunalapse-v<version>.apk
~/Android/Sdk/build-tools/<version>/apksigner verify --print-certs faunalapse-v<version>.apk
```

Without the key, `assembleRelease` stops (`missing required property "storeFile"`); debug builds
and tests need no key.
