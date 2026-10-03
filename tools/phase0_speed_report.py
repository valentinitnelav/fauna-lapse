#!/usr/bin/env python3
"""Phase 0: copy the newest speed test from a phone and print a short summary.

Usage:
    python3 tools/phase0_speed_report.py --out <folder> [-s <adb serial>]

Needs adb and ffprobe (part of ffmpeg) on the PC. The speed test folder (photos, videos,
results.jsonl, facts.json) is copied into <folder>; keep that folder outside the git repository.
"""
import argparse
import json
import subprocess
from pathlib import Path

PHONE_DIR = "/sdcard/Android/data/com.faunalapse.app/files/phase0"


def adb(serial, *args):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def probe(video):
    """Size, real frame count, duration and frames per second of a video, from ffprobe."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets",
         "-show_entries", "stream=width,height,nb_read_packets,duration", "-of", "json", str(video)],
        check=True, capture_output=True, text=True).stdout
    s = json.loads(out)["streams"][0]
    frames, seconds = int(s["nb_read_packets"]), float(s["duration"])
    return f'{s["width"]}x{s["height"]}, {frames} frames in {seconds:.1f} s = {frames / seconds:.1f} fps'


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="folder for the copy (outside the repository)")
    ap.add_argument("-s", "--serial", help="adb serial of the phone, if more than one is connected")
    a = ap.parse_args()

    newest = adb(a.serial, "shell", f"ls -d {PHONE_DIR}/speed_*").split()[-1]
    out = Path(a.out).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    adb(a.serial, "pull", newest, str(out))
    local = out / Path(newest).name

    facts = json.loads((local / "facts.json").read_text())
    print(f'{facts["phone"]}, Android {facts["android"]}, folder {local}')
    for line in (local / "results.jsonl").read_text().splitlines():
        r = json.loads(line)
        head = f'{r["test"]:8} {r["size"]:>10}'
        if "error" in r:
            print(f'{head}  ERROR: {r["error"]}')
        elif r["test"] == "still":
            print(f'{head}  one photo every {r["mean_cycle_ms"] / 1000:.2f} s (cycles {r["cycle_ms"]} ms)')
        elif r["test"] == "preview":
            print(f'{head}  camera {r["camera_frames_per_s"]} frames/s; at 1 photo/s: whole frame '
                  f'{sum(r["every_1s_whole_ms"]) // 10} ms, {r["roi_side"]} px square '
                  f'{sum(r["every_1s_roi_ms"]) // 10} ms, app CPU {r["every_1s_app_cpu_percent"]} %; '
                  f'fastest {r["fast_roi_saved_per_s"]} squares/s')
        elif r["test"] == "camera2_still":
            print(f'{head}  Camera2, at 1 photo/s: photo {sum(r["every_1s_photo_ms"]) // 10} ms, '
                  f'save {sum(r["every_1s_save_ms"]) // 10} ms; fastest {r["fast_saved_per_s"]} photos/s')
        elif r["test"] == "video":
            asked = f'{r["requested_fps"]} fps'
            if "time_lapse_frames_per_s" in r:
                asked += f', time-lapse {r["time_lapse_frames_per_s"]} frames/s'
            frames = probe(local / r["file"])
            print(f'{head}  asked {asked}: {frames}, {r["kb"] // 1024} MB in {r["recorded_ms"] / 1000:.1f} s')


if __name__ == "__main__":
    main()
