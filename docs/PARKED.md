# Parked ideas

Ideas the owner put aside for now, with what was found, so the work is not lost. Read the entry
before reopening an idea; when one is built or dropped, remove it here.

## Copying sessions to a computer

**Wish:** a transfer mode that copies the sessions to a folder on a computer over USB, as fast as
pulling them from the computer, and tells which sessions were copied completely.

**What was found:**
- Over USB, "File transfer" (MTP) is the only way without extra software. It is slow with many small
  files, and the file explorer may skip files without a clear error.
- The phone cannot push files to a computer: the computer must fetch them. An app also cannot see
  what a computer read over MTP, so the app cannot tell which sessions were copied.
- `adb pull` (Google's free Android tools for computers) is fast and can be checked file by file,
  but it needs USB debugging, which is in the hidden Developer options (tap Build number 7 times),
  and one "Allow" tap per computer. Any allowed computer can then read the phone.
- rsync or scp over Wi-Fi would need network access in the app (against the rules) and is usually
  slower than USB.
- With a memory card (choose *SD card* on the status card), the card can go into a card reader on
  the computer: fast and without any software on the phone.

**Options studied:** copy by hand over MTP (as now); a Python script on the computer that copies
with adb, checks every file's size, copies again only what is missing and never deletes on the
phone (optionally with checksums); copying over Wi-Fi from the app.

**Why parked:** users should not need Developer options (owner).

## Heat drift of a fixed focus

**Question:** does the lens of a fixed-focus phone move a little as the phone warms up, so that
photos get less sharp during a long session?

**How to test:** a printed target at 20 cm, photos while the phone warms (for example video mode
in the sun), sharpness per photo compared. It differs per phone, so it needs each phone model.

**Options if it does:** nothing; a guarded autofocus that may move the lens only a little around
the set distance; a sharpness value per photo in the record.

**Why parked:** no calibration target yet; first see whether field photos show it.

## Copying settings between phones

**Wish:** set up one phone and copy its settings to the others: a settings file, later a QR code.

**Why parked:** not needed before the first release (owner).

## FaunaLapse on the lock screen during a session

**Idea:** after the screen was off, the power button shows FaunaLapse at once (as the camera app
does), without swiping or unlocking (`setShowWhenLocked`); Stop would still ask for the PIN on a
phone with one.

**Why parked:** the Screen off button solves the main problem (no press on the phone where it
stands); anyone who wakes the phone could then see and stop the session without unlocking.

## Battery tests without unplugging by hand

**Wish:** measure battery use per hour without the owner unplugging the phone each time.

**What was found:**
- `dumpsys battery unplug` only tells the apps that the phone is unplugged: the phone still
  charges, so the measured current is wrong.
- Stopping the charging itself needs root (not on our phones), or a USB hub that can switch the
  power of each port: the `uhubctl` tool (installed on the owner's computer) switches such hubs; its
  README lists hubs known to work.
- The laptop's own USB ports cannot switch the power. The small hub in use (1a40:0101) only switches
  the data: with its port "off" for 6 s, the phone still reported USB power the whole time.
- The app logs the battery itself (`battery` in `session_end`). A hub that switches the power off
  and on again would therefore be enough: no cable data is needed during the test.

**Why parked:** it needs such a hub. Until then, the owner unplugs the phone for battery tests.

## Camtrap DP export

**Wish:** turn the session records into a Camtrap DP data package (the camera trap data standard of
TDWG, https://camtrap-dp.tdwg.org/), to share and publish the data.

**What was found:**
- Version 1.0.2 (2025); 1.1 is being written, and insect and time-lapse terms are still proposals
  (InsectAI COST Action, datathon 2026). A package is `datapackage.json` plus three tables:
  deployments, media and observations.
- Card 2 already collects what cannot be found later; its record keys follow Camtrap DP words.
  Deployments: `deploymentID` = folder name; `latitude`, `longitude` (WGS 84);
  `coordinateUncertainty` = `coordinate_uncertainty_m` rounded up (whole m, at least 1);
  `deploymentStart` and `deploymentEnd` = times of `session_start` and `session_end` (no ms);
  `locationName` = `site`; `setupBy`, `cameraHeight`, `detectionDistance` (camera to target),
  `habitat`; `cameraID` = `phone_id`; `cameraModel` = maker-model; plant, transect, IDs and custom
  fields as `deploymentTags` (key:value). Media: one row per photo or clip (`time`, `file`).
- Study facts (project, people, licence, `captureMethod` timeLapse, `filePublic`) and the
  observations (`unclassified` until someone looks) are made on the computer.

**Options studied:** the phone writes the whole package; the phone writes deployments and media
only; a Python or R script on the computer builds one package per study from `session.jsonl`
(recommended: the standard is still changing, and the app stays small).

**Why parked:** not a priority now (owner). Build the script when the first studies need it.

## Publishing to GBIF

**Idea:** send data to GBIF (Global Biodiversity Information Facility) as it is collected.

**What was found:** GBIF holds occurrences (an organism at a place and time), via Darwin Core or a
Camtrap DP package. Time-lapse photos become occurrences only after detection (by AI or a person)
and identification checked by a taxonomist.

**Why parked:** nothing to publish before the photos are analysed (owner).

## Waking the screen with a tap

**Wish:** after Screen off, a tap wakes the screen, so the power button need not be pressed.

**What was found:** with the screen really off, the touch screen sleeps too, so no app gets a tap.
Some phones have their own "double tap to turn on screen" (Samsung M12: Settings, Advanced features,
Motions and gestures; off on the test phone): it keeps the touch screen listening (a little energy),
and rain or insects might wake the screen. A black screen kept on would take taps, but costs energy.

**Why parked:** the power button, pressed gently, or the phone's own setting is enough (owner).

## A phone ID that survives a factory reset

**What was found:** since Android 10 only phone makers' apps may read lasting hardware IDs (IMEI,
serial number); `ANDROID_ID` changes with a factory reset. A name written on the phone and typed as
a custom field (for example Device ID) does the job.

**Why parked:** not worth it (owner).
