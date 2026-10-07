# Test phones and measured results

Read this file only for camera or energy work. One section per phone; new measurements replace the
old ones (no history of runs). Numbers come from the phase 0 test app (screen off, phone on USB power,
indoor light) unless stated otherwise. Raw data stays outside git; after a speed test,
`tools/phase0_speed_report.py` copies it to the PC and prints the summary used below.

## Samsung Galaxy M12 (SM-M127F), Android 13, 2021

Reference phone for phase 0 (cheap, about 5 years old). Tested 2026-10-02, two speed-test runs.

### Camera facts (back camera)

- Camera1: photos up to 4000x3000 (12 MP, 4:3); preview frames up to 1440x1080 (4:3) or 2400x1080
  (20:9: wider, but cuts the top and bottom of the view); camera frame rates 8 to 30 per s, slowest
  fixed rate 15; focus modes auto, continuous, infinity; one focus area and one metering area;
  exposure and white balance lock; shutter sound can be switched off.
- Camera2: hardware level "limited" (more than a Camera1 wrapper); freeform crop (the camera chip can
  zoom onto the square anywhere, up to 8 times); manual focus possible, closest 10 cm; stream frames up
  to 2576x1932 (4:3) at 30 per s, also 1088x1088; square photos only 2992x2992 and 1088x1088. H.264 encoder
  (OMX.Exynos.AVC.Encoder) takes any size up to 8192x8192. Sensor
  4000x3000, the same as the largest photo (not enlarged in software). A second back camera (id 2,
  2576x1932) exists; the app uses id 0.
- Video presets: 1080p, 720p and 480p at 30 fps (17, 12 and 3.4 Mbit/s).
- EXIF exposure values in Camera1 photos are fixed (always 1/33 s, ISO 308): do not trust them.

### Results

| Test | Result |
|---|---|
| Wake: camera open / first preview frame / exposure stable | 0.05 to 0.2 s / 0.8 to 1.1 s / about 1.4 s after open |
| Autofocus once | 0.9 to 2.5 s, sharp |
| **Full photos, Camera1** 4000x3000, one after another | one every 0.9 to 1.7 s (the photo itself takes either 0.6 or 1.2 s, then 0.25 s to restart the preview) |
| Full photos, Camera1, 1440x1080 | one every 0.8 to 1.5 s: smaller is hardly faster |
| **Full photos, Camera2** 4000x3000 | 0.43 s per photo (very steady), save 0.03 s; one per second easily; up to 2.1 per s one at a time |
| **Preview frames, Camera1**, 1440x1080, one per second | whole frame to JPEG 66 ms, 1024 px square 38 ms; app uses 27 % of one processor core |
| Preview frames, 1440x1080, as fast as possible | 24 squares (1024 px) per s, one core fully used; camera gives 25.5 frames per s |
| Preview frames, 2400x1080 | whole frame 107 ms, square 42 ms; 20 squares per s |
| **Video 15 fps** 1080p / 720p / 480p | works, 15.0 fps measured; 9 / 7 / 2 MB per 10 s |
| Video 5 fps asked directly | fails ("start failed"): the camera cannot run slower than 8 fps |
| **Time-lapse video** 1080p, 1 frame per s | works: 10 frames in 10 s, 1 MB; plays at 15 fps |
| **Square video**, Camera2, 5 fps, 1280 px from a 2576x1932 stream (camera at 15 fps, every 3rd frame sent to the recorder) | frames exactly 200 ms apart (298 in 60 s), real time stamps; clip stops in 50 ms; the camera used the asked crop; the video square matches the photo square (correlation 0.999; Camera1 1.0). 3.9 Mbit/s (1.75 GB per hour) although 2.2 was asked: the encoder's own rate control keeps a quality floor; constant bit rate (CBR) follows the ask (2.4 or 1.2 Mbit/s), with slightly smoother detail on a still scene |
| Square video, processor use during a clip (screen off) | of one core: camera service 28 %, image kernel threads about 20 %, camera server 9 %, FaunaLapse 12 % (its graphics thread 2 %), encoder 2.5 %; the idle phone about 6 % |
| Square video, Camera1 (1440x1080 preview frames, no hardware zoom) | a 50 % square has 540 real pixels, saved at 512; 1.0 Mbit/s although 0.4 was asked |
| Automatic exposure in a 2 h photo session (8 bursts of 30 photos, indoor light) | brightness of the saved squares changed by at most 1.7 % from one photo to the next (2.7 % within a burst); Camera2 reports each photo's exposure: 30 ms, ISO 238, the same through a burst; no slower photos |
| Fixed focus (Camera2, autofocus off) | lens distances calibrated, closest 10 cm (10 diopters); autofocus on the monitor about 50 cm away reported 2.0 diopters (50 cm); the view follows the slider at once; bursts and clips with a fixed focus skip the autofocus (1.3 to 2.2 s) |
| Saved square 1280 vs 1024 px (cut from 3000 px, Camera2) | cut and save 0.39 vs 0.32 s on the saver thread, 296 vs 203 KB per photo; photo time unchanged (0.65 s) |
| Photo burst, processor use (step 2 s, 73 % square: 2190 real pixels saved at 1280; screen off; the phone's Power saving mode was on) | of one core: FaunaLapse 28 %, of which the square cut 20 % (0.43 to 0.46 s per photo on the saver thread); camera service 24 %, camera server 8 %; whole phone 111 %; photo 0.62 to 0.64 s. By the phone's power profile (one core at full speed 96 mA, camera 164 mA) the cut is about 5 % of a burst's energy |
| Exposure, Camera1 vs Camera2 photo of the same scene | Camera2 about one stop brighter (bright labels clip to white); Camera1 darker. Exposure must be measured on the ROI either way |
| Battery temperature during 3 min of tests | 25 °C, no rise (phone on USB power) |
| Pack a photo session into a zip (`SessionZip`; 1280 px squares of about 145 KB) | about 19 ms per photo: write 3.3 (photos read 8 ahead on 4 threads; 15 when each was opened twice), check 0.8, delete 14 (in one call to Android's file index; one by one 16); 300 photos about 6 s, 5000 about 1.5 min. On the FAT32 memory card 23 ms per photo. Copy over USB *File transfer* to a Linux laptop (2026-10-06): the folder of 301 photos (43 MB) 13.8 s, its zip 1.5 s; the extracted zip equals the folder |
| Pack 4019 photos of 2976 px (1.33 MB, 5.4 GB) on the FAT32 card (2026-10-06) | zips of 3,998,870,872 and 1,437,547,521 bytes, no Zip64; about 1.3 min per GB (write 20 MB/s, check about 80 MB/s, delete 7 ms per photo); the 4.0 GB zip copied over USB *File transfer* in 150 s (27 MB/s); all 4022 files (2 site photos) identical. App force-stopped 1 s after zip 1: the next Pack deleted the photos in it and made `_part2`. As a foreground service with Home pressed and the screen off: priority (`oom_score_adj`) 200 throughout, 0.94 GB in 110 s, all 1692 files identical |

### Sessions (Camera2 unless stated, screen off, 2026-10-03 to 06)

| Session | Result |
|---|---|
| Photos: step 1 s, 10 s bursts, 1 min breaks, 3.6 min run | first burst had no warm-up and missed 3 photo times (fixed: the schedule now starts 5 s after the tap); the other bursts on time with 10 photos, 0 skipped; photo 0.61 to 0.68 s; square cut from 3000 px and saved at 1024 px in 0.23 s (on the saver thread), 172 KB; camera opened in 0.28 s 5 s before each burst, autofocus sharp in 1.7 s, camera off in each break |
| Video: 5 min clips of the square (100 %, 1280 px, 5 fps), 1 min breaks (camera off), 21 min run | 4 clips, 0 errors, each started 0 ms late; 1498 frames per 5 min, 136 MB per clip (1.6 GB per hour); battery 25 °C (USB power). A 30 % square was saved at 896 px (900 real pixels, never enlarged) |
| Photos in a daily window (21:07 to 21:09): step 0.5 s, 20 s bursts, 30 s breaks | bursts at 21:07:00, 21:07:50 and 21:08:40 as planned; camera stayed on in the short breaks; each burst 20 photos and 20 skipped photo times (a photo takes 0.62 s); the app warned once: step too short, use video or a step of at least 1.0 s |
| Stop during a video clip | session ended within 1 s; the clip file was finished and playable (15 s), recorded with `stopped_early` |
| Photos on a 32 GB SD card against the phone storage (step 1 s, 30 s bursts, 2 min run, 73 % square from 3000 px saved at 1280 px, about 285 KB; 2026-10-05) | 60 of 60 photos, 0 skipped on both; save time (cut, turn, JPEG, write) median 378 ms on the card and 322 ms on the phone, 90 % below 415 and 331 ms, slowest 1484 and 378 ms; photos started 3 ms late (median) on both. A card is fast enough for 1 photo per second |
| Photos at the largest square (2976 of 3000 px), step 1 s, one 83 min burst, on the FAT32 card, charging (2026-10-06) | 4019 photos, 604 photo times skipped: cutting and saving one square takes 1.1 s on the card; 1.33 MB per photo (a desk), 3.9 GB per hour (4 GB after about 61 min); 0 errors, 25 to 29 °C |
| Aim screen and a moved square (50 %, top right), Camera2 and Camera1 | view 1440x1080 (both interfaces, photos and video); focus and exposure areas on the square work in both; focus sharp in 1.4 to 1.9 s; saved squares show the same area as the screen, upright with the phone upright or turned left side up. Camera1 session at step 1 s: photo 0.95 to 1.6 s, 3 of 10 photo times skipped and the step warning shown |
| Battery, 1 h off the charger, default settings, two runs each (2026-10-06, at night in a dim room; airplane mode, but Wi-Fi on for adb, which was disconnected during each hour; 63 % square saved at 1280 px) | Photos (a photo every 2 s for 60 s, 5 min breaks; the session ends after the last burst, at 55 min): 300 of 300 photos, 0 skipped, 3 ms late; camera open 194 and 189 mA, closed 39 and 52 mA: 65 and 73 mAh (1.4 to 1.6 % of the 5000 mAh battery per hour; the level fell by 0 and 1 %). Video (5 min clips at 5 fps, 5 min breaks): 6 of 6 clips, 1498 frames each, 0 to 3 ms late; open 256 and 262 mA, closed 55 mA: 154 and 157 mAh (3.1 to 3.4 % per hour; the level fell by 3 %); 136 MB per clip, the same in every clip. Battery 22 to 28 °C |

### What this means for 1 photo per second

The number of real pixels inside the square depends on how much of the view the square covers
(square side as a share of the view height):

| Path | Frame | Square covering the full height | Square covering half the height |
|---|---|---|---|
| Camera1 full photo | 4000x3000, but only one every 0.9 to 1.7 s | 3000 px (saved at 1024) | 1500 px (saved at 1024) |
| Camera1 preview frame | 1440x1080 | 1080 px (saved at 1024) | 540 px |
| Camera2 full photo | 4000x3000, 0.43 s each | 3000 px (saved at 1024) | 1500 px (saved at 1024) |
| Square video, Camera2 (hardware zoom onto the square) | 2576x1932 stream | 1932 px (saved at 1280) | 1500 px (saved at 1280) |
| Square video, Camera1 | 1440x1080 preview frames | 1080 px | 540 px |

## Xiaomi Redmi Note 9S, Android 12 (MIUI 14), 2020

A mid-range phone (Snapdragon 720G), tested like a new user would use it. Tested 2026-10-05, two
speed-test runs and sessions, on the charger.

### Camera facts (back camera)

- Camera2: hardware level "level 3"; the camera chip crops only in the centre; manual focus,
  closest 10 cm, lens distances "approximate"; sensor 4000x3000. Android lists the 4000x3000 photo
  size apart, as a "high resolution" size (15 per s, below the 20 of the usual list, which stops at
  3840x2160, 16:9); the app uses such sizes when they run at 10 or more per s.
- Before the camera is allowed, Android hides the lens range (the app then cannot tell whether the
  focus can be fixed).
- Camera1: photos up to 4000x3000; camera frame rates 8 to 30 per s, slowest fixed rate 15.
- Video presets: 2160p, 1080p, 720p and 480p at 30 fps (42, 20, 14 and 2 Mbit/s).
- MIUI: installs over USB need *Install via USB* in Developer options and a tap on the phone each
  time. Allowing the Screen off button shows MIUI's own red "Danger" page with rights FaunaLapse
  does not ask for (Android's page before it lists only "Lock the screen"). A double tap did not
  wake the screen (owner), although Android's own double-tap setting reads "on".

### Results

| Test | Result |
|---|---|
| **Full photos, Camera2** 4000x3000 | 0.52 to 0.67 s per photo, save 0.02 to 0.03 s; up to 1.8 per s one at a time; 1.6 MB per full JPEG |
| Full photos, Camera1 4000x3000 / 1280x960 | one every 2.1 s / 1.9 s (photo 1.3 to 1.6 s, preview restart 0.5 to 0.6 s): smaller is not faster |
| Preview frames, Camera1, 1920x1440, one per second | whole frame to JPEG 41 to 52 ms, 1024 px square 11 to 15 ms; 16 % of one core; as fast as possible 30 squares per s (40 %) |
| Video 15 fps 1080p / 720p / 480p | works; 11.8 / 8.3 / 1.5 MB per 10 s |
| Time-lapse video 1080p, 5 and 1 frames per s | works; 2.9 and 0.9 MB per 10 s |
| Autofocus once | 1.9 to 2.1 s, sharp |
| Pack a photo session into a zip (1280 px squares of about 120 KB) | about 13 ms per photo: write 4.3, check 0.5, delete 5.7 (in one call to Android's file index; one by one 17.6) |
| Pack 4952 photos of 2976 px (0.93 MB, 4.4 GB) (2026-10-06) | zips of 3,999,632,472 and 369,738,616 bytes, no Zip64; 147 s (write 51 s, 86 MB/s; check 17 s; delete 73 s, one by one, as the index spelled the folder `documents`); all 4954 files identical. Force-stopped at 2.4 GB written: the folder stayed whole, the next Pack started over. With FaunaLapse in the background (Home, screen off), Android's low-memory killer closed it after 20 s (critical memory pressure while writing); in front with the screen off it finished. As a foreground service (Home, screen off): 1.3 GB in 31 s, all 1800 files identical; under the same pressure the killer closed other apps (priority 995 to 999), not FaunaLapse |
| Session files missing from a folder listing (2026-10-06) | Android 11 to 13 index a file an app writes as "pending" until a media scan (idle and charging, or on demand); the app still lists and opens its own pending files. But the index sometimes stores the folder spelled "documents/..." instead of "Documents/..." (the last 120 of 301 photos of a 1 h session; later all 4954 files of a session): a listing then misses those files, while each one still opens by its name, and a computer over USB sees them all. Pack takes the files the record names by their names |

### Sessions (Camera2, screen off, MIUI battery limits on)

| Session | Result |
|---|---|
| Photos: step 2 s, 30 s bursts, 1 min breaks, 12 min run, 55 % square (1650 of 3000 real pixels) saved at 1280 px, fixed focus; screen off with the Screen off button | 8 bursts, 120 of 120 photos, 0 skipped, 0 errors (6 bursts with the screen off); photo 0.60 to 0.93 s (median 0.71), cut and save median 0.21 s, about 280 KB per photo; photos started 1 to 6 ms late; camera opened in 0.45 to 0.70 s, 4 s before each burst |
| Video: 2 min clips of a 55 % square (5 fps), 1 min breaks, 15 min run; screen off with the power button; a pause after 33 s of clip 2, the square moved to the middle, Continue | 6 clips, 0 errors; 598 frames per 2 min clip, started 1 ms late; the paused clip went on until its planned end. Off-centre square: no hardware zoom (crop only in the centre), 792 real pixels saved at 768 px, 23 MB per 2 min; in the middle the camera zoomed (crop 2203x1652): 1438 real pixels saved at 1280 px, 55 MB per 2 min (1.6 GB per hour) |
| Photos at the largest square (2976 of 3000 px), step 1 s, one 83 min burst, charging (2026-10-06) | 4952 photos, 0 skipped, cut and save 0.41 to 0.45 s, 0.93 MB per photo (a desk), 3.2 GB per hour (4 GB after about 76 min) |
| Battery, 1 h off the charger, default settings (2026-10-06, as for the Samsung M12; 100 % square; FaunaLapse without battery limits) | Photos: 300 of 300 photos, 0 skipped, 4 ms late; camera open 272 mA, closed 18.5 mA: 65 mAh in 55 min (1.3 % of the 5020 mAh battery per hour; the level fell by 1 %). Video: 6 of 6 clips, 1498 frames each, 0 to 2 ms late, 111 MB per clip; open 268 mA, closed 19 mA: 145 mAh (2.9 % per hour), but the level fell by 6 % (also in a first run). Android's own counts since unplugging differ the same way (its power model 609 mAh, the level 914 to 965 mAh; learned capacity 5077 mAh): the level falls up to 2 times faster than the current says. Readings in the breaks 6 to 190 mA, mostly 10 to 30 mA (the phone reports microamperes). Battery 23 to 26 °C |

## Xiaomi 11T Pro, Android 14 (MIUI), 2021

| Test | Result |
|---|---|
| Photos at the largest square (2976 of 3000 px), step 1 s, 10 min, charging; then Pack with FaunaLapse in the background and the screen off (2026-10-06) | 600 of 600 photos, 0 skipped, cut and save 0.27 s, 1.29 MB per photo; Pack: 817 MB in 9 s (write 2.0, check 3.2, delete 3.5); all 601 files identical. As a foreground service (Home, screen off): Android allowed it (type data sync), priority 50, 1.9 GB in 19 s, all 1801 files identical |

## Further battery tests

### Used phones, test 2026-10-06

1. Samsung Galaxy M12 (SM-M127F), Android 13, 2021
2. Xiaomi Redmi Note 9S, Android 12 (MIUI 14), 2020

Settings and conditions:

- one hour of photos and one hour of video each, plus repeats;
- photos every 2 s for 60 s with 5-minute breaks; 
- video in 5-minute clips at 5 frames per second with 5-minute breaks;
- Both phones were off the charger, in airplane mode with Wi-Fi on, with location off, at room temperature (overnight);
- `adb` disconnected during each test hour.

| Per hour of session  | 1. Samsung Galaxy M12 | 2. Xiaomi Redmi Note 9S |
|---|---|---|
| Photos: camera open 11 of 55 min; two runs | 71 and 80 mA on average; ~1.5 % of the battery | 71 mA; ~1.3 % |
| Video: camera open 31 of 55 min; two runs | 167 and 171 mA; ~3.3 % of the battery | 157 mA; ~3.1 %, but the level fell 6 % |
| Camera open | 189 to 262 mA | 268 to 272 mA |
| Camera closed (breaks) | 39 to 55 mA | 19 mA |

**Rough estimates**: 

A session stops itself when the battery reaches 15 % level. A full 5000 mAh battery would then last about 2 days of photo sessions or about 1 day of video with these settings, running without stop. Daily windows stretch this over more days. Heat, cold or an older battery in the field may shorten it.

**Possible biases**: 

- Wi-Fi was on, which costs a little within this test setup.
- Some phones report the current in milliamperes (the Samsung) and others in microamperes (the Redmi), and the app guesses the unit and tries to adjust. The unit is decided once per session from the largest reading.
- Xiaomi phone - in video mode, its battery level fell about twice as fast as the measured current says (6 % instead of about 3 % per hour). Android's own counters show the same gap since unplugging (609 mAh by its power model, about 940 mAh by the level). Android counts the battery at a full 5077 mAh, so it is not worn out. On the Samsung, the current and the level agree. Next proposed step - a long test from full battery down to the 15 % stop. But this will be specific to these phones' battery conditions.

**Behaviour:** no bugs in the sessions

- Photos: 1,320 in total, none skipped, none late by more than 13 ms, no errors.
- Video: 24 clips, each with exactly 1498 frames, started 0 to 3 ms late.
- Temperature: 22 to 28 °C, no heat pause.
- Tested in room overnight with artificial light on: the automatic exposure handled it.
- Length: a "1 h" run ends after 55 min, because the next burst would start at 60 min. This is how the schedule works, not a bug.


