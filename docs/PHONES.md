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
  cut out the square); manual focus possible, closest 10 cm; YUV frames up to 2576x1932. Sensor
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
| **Video 5 / 10 fps** 1080p (camera fixed at 15 / 20 fps, every 3rd / 2nd frame kept), Camera2 and Camera1 | frames exactly 200 / 100 ms apart, real time stamps. Camera2: 4.8 / 6.9 Mbit/s although 2.8 / 5.7 were asked (the encoder ignores lower asks: 1.4 asked still gave 4.9; at 15 fps 8.6 as asked), so 5 fps needs about 55 % of the storage of 15. Camera1: 3.0 / 5.8 Mbit/s as asked; fixing the time stamps after a clip took 1.4 s for 40 MB (2 min) |
| Video 5 fps, 720p / 480p, Camera2 | 2.6 / 1.3 Mbit/s (about 1.2 / 0.6 GB per hour; 1080p 2.2 GB) |
| Automatic exposure in a 2 h photo session (8 bursts of 30 photos, indoor light) | brightness of the saved squares changed by at most 1.7 % from one photo to the next (2.7 % within a burst); Camera2 reports each photo's exposure: 30 ms, ISO 238, the same through a burst; no slower photos |
| Fixed focus (Camera2, autofocus off) | lens distances calibrated, closest 10 cm (10 diopters); autofocus on the monitor about 50 cm away reported 2.0 diopters (50 cm); the view follows the slider at once; bursts and clips with a fixed focus skip the autofocus (1.3 to 2.2 s) |
| Saved square 1280 vs 1024 px (cut from 3000 px, Camera2) | cut and save 0.39 vs 0.32 s on the saver thread, 296 vs 203 KB per photo; photo time unchanged (0.65 s) |
| Exposure, Camera1 vs Camera2 photo of the same scene | Camera2 about one stop brighter (bright labels clip to white); Camera1 darker. Exposure must be measured on the ROI either way |
| Battery temperature during 3 min of tests | 25 °C, no rise (phone on USB power) |

### Sessions (app version 0.0.1-phase0, Camera2, screen off, 2026-10-03)

| Session | Result |
|---|---|
| Photos: step 1 s, 10 s bursts, 1 min breaks, 3.6 min run | first burst had no warm-up and missed 3 photo times (fixed: the schedule now starts 5 s after the tap); the other bursts on time with 10 photos, 0 skipped; photo 0.61 to 0.68 s; square cut from 3000 px and saved at 1024 px in 0.23 s (on the saver thread), 172 KB; camera opened in 0.28 s 5 s before each burst, autofocus sharp in 1.7 s, camera off in each break |
| Video: 30 s clips at 1080p, 1 min breaks, 3.6 min run | 3 clips, each started 0 ms late; 15.00 fps and 8.5 Mbit/s measured, about 30 MB per 30 s; the first 0.4 s of each clip has no frames (encoder start) |
| Photos in a daily window (21:07 to 21:09): step 0.5 s, 20 s bursts, 30 s breaks | bursts at 21:07:00, 21:07:50 and 21:08:40 as planned; camera stayed on in the short breaks; each burst 20 photos and 20 skipped photo times (a photo takes 0.62 s); the app warned once: step too short, use video or a step of at least 1.0 s |
| Stop during a video clip | session ended within 1 s; the clip file was finished and playable (15 s), recorded with `stopped_early` |
| Aim screen and a moved square (50 %, top right), Camera2 and Camera1 | view 1440x1080 for photos (both interfaces), 1920x1080 for video; focus and exposure areas on the square work in both; focus sharp in 1.4 to 1.9 s; saved squares show the same area as the screen, upright with the phone upright or turned left side up. Camera1 session at step 1 s: photo 0.95 to 1.6 s, 3 of 10 photo times skipped and the step warning shown |

### What this means for 1 photo per second

The number of real pixels inside the square depends on how much of the view the square covers
(square side as a share of the view height):

| Path | Frame | Square covering the full height | Square covering half the height |
|---|---|---|---|
| Camera1 full photo | 4000x3000, but only one every 0.9 to 1.7 s | 3000 px (saved at 1024) | 1500 px (saved at 1024) |
| Camera1 preview frame | 1440x1080 | 1080 px (saved at 1024) | 540 px |
| Camera2 full photo | 4000x3000, 0.43 s each | 3000 px (saved at 1024) | 1500 px (saved at 1024) |
| Video 1080p (16:9 view, top and bottom cut) | 1920x1080 | 1080 px | 540 px |
