# Test phones and measured results

One section per phone. Numbers come from the phase 0 test app (screen off, phone on USB power, room
temperature, indoor light) unless stated otherwise. Raw data stays outside git. After a speed test on
a phone, `tools/phase0_speed_report.py` copies it to the PC and prints the summary used below.

## Samsung Galaxy M12 (SM-M127F), Android 13, 2021

Reference phone for phase 0 (cheap, about 5 years old). Tested 2026-10-02, two speed-test runs.

### Camera facts (back camera)

- Camera1: photos up to 4000x3000 (12 MP, 4:3); preview frames up to 1440x1080 (4:3) or 2400x1080
  (20:9: wider, but cuts the top and bottom of the view); camera frame rates 8 to 30 per s, slowest
  fixed rate 15; focus modes auto, continuous, infinity; one focus area and one metering area;
  exposure and white balance lock; shutter sound can be switched off.
- Camera2: hardware level "limited" (more than a Camera1 wrapper); freeform crop (the camera chip can
  cut out the square); manual focus possible, closest 10 cm; YUV frames up to 2576x1932.
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
| Time-lapse video 1080p, 5 frames per s asked | 3.7 frames per s (37 in 10 s): the recorder takes every 4th camera frame at 15 fps; ask a little more than 5 |
| Exposure, Camera1 vs Camera2 photo of the same scene | Camera2 about one stop brighter (bright labels clip to white); Camera1 darker. Exposure must be measured on the ROI either way |
| Battery temperature during 3 min of tests | 25 °C, no rise (phone on USB power) |

### What this means for 1 photo per second

The number of real pixels inside the square depends on how much of the view the square covers
(square side as a share of the view height):

| Path | Frame | Square covering the full height | Square covering half the height |
|---|---|---|---|
| Camera1 full photo | 4000x3000, but only one every 0.9 to 1.7 s | 3000 px (saved at 1024) | 1500 px (saved at 1024) |
| Camera1 preview frame | 1440x1080 | 1080 px (saved at 1024) | 540 px |
| Camera2 full photo | 4000x3000, 0.43 s each | 3000 px (saved at 1024) | 1500 px (saved at 1024) |
| Video 1080p (16:9 view, top and bottom cut) | 1920x1080 | 1080 px | 540 px |
