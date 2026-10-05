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
  power of each port (the `uhubctl` tool on the computer lists such hubs).
- The laptop's own USB ports and the small hub in use cannot switch the power off.
- The app logs the battery itself (`battery` in `session_end`). A hub that switches the power off
  and on again would therefore be enough: no cable data is needed during the test.

**Why parked:** it needs such a hub. Until then, the owner unplugs the phone for battery tests.
