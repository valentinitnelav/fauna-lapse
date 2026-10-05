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

### Sessions (app version 0.0.1-phase0, Camera2 unless stated, screen off, 2026-10-03 and 04)

| Session | Result |
|---|---|
| Photos: step 1 s, 10 s bursts, 1 min breaks, 3.6 min run | first burst had no warm-up and missed 3 photo times (fixed: the schedule now starts 5 s after the tap); the other bursts on time with 10 photos, 0 skipped; photo 0.61 to 0.68 s; square cut from 3000 px and saved at 1024 px in 0.23 s (on the saver thread), 172 KB; camera opened in 0.28 s 5 s before each burst, autofocus sharp in 1.7 s, camera off in each break |
| Video: 5 min clips of the square (100 %, 1280 px, 5 fps), 1 min breaks (camera off), 21 min run | 4 clips, 0 errors, each started 0 ms late; 1498 frames per 5 min, 136 MB per clip (1.6 GB per hour); battery 25 °C (USB power). A 30 % square was saved at 896 px (900 real pixels, never enlarged) |
| Photos in a daily window (21:07 to 21:09): step 0.5 s, 20 s bursts, 30 s breaks | bursts at 21:07:00, 21:07:50 and 21:08:40 as planned; camera stayed on in the short breaks; each burst 20 photos and 20 skipped photo times (a photo takes 0.62 s); the app warned once: step too short, use video or a step of at least 1.0 s |
| Stop during a video clip | session ended within 1 s; the clip file was finished and playable (15 s), recorded with `stopped_early` |
| Photos on a 32 GB SD card against the phone storage (step 1 s, 30 s bursts, 2 min run, 73 % square from 3000 px saved at 1280 px, about 285 KB; 2026-10-05) | 60 of 60 photos, 0 skipped on both; save time (cut, turn, JPEG, write) median 378 ms on the card and 322 ms on the phone, 90 % below 415 and 331 ms, slowest 1484 and 378 ms; photos started 3 ms late (median) on both. A card is fast enough for 1 photo per second |
| Aim screen and a moved square (50 %, top right), Camera2 and Camera1 | view 1440x1080 (both interfaces, photos and video); focus and exposure areas on the square work in both; focus sharp in 1.4 to 1.9 s; saved squares show the same area as the screen, upright with the phone upright or turned left side up. Camera1 session at step 1 s: photo 0.95 to 1.6 s, 3 of 10 photo times skipped and the step warning shown |

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

### Sessions (Camera2, screen off, MIUI battery limits on)

| Session | Result |
|---|---|
| Photos: step 2 s, 30 s bursts, 1 min breaks, 12 min run, 55 % square (1650 of 3000 real pixels) saved at 1280 px, fixed focus; screen off with the Screen off button | 8 bursts, 120 of 120 photos, 0 skipped, 0 errors (6 bursts with the screen off); photo 0.60 to 0.93 s (median 0.71), cut and save median 0.21 s, about 280 KB per photo; photos started 1 to 6 ms late; camera opened in 0.45 to 0.70 s, 4 s before each burst |
| Video: 2 min clips of a 55 % square (5 fps), 1 min breaks, 15 min run; screen off with the power button; a pause after 33 s of clip 2, the square moved to the middle, Continue | 6 clips, 0 errors; 598 frames per 2 min clip, started 1 ms late; the paused clip went on until its planned end. Off-centre square: no hardware zoom (crop only in the centre), 792 real pixels saved at 768 px, 23 MB per 2 min; in the middle the camera zoomed (crop 2203x1652): 1438 real pixels saved at 1280 px, 55 MB per 2 min (1.6 GB per hour) |
