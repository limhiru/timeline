"""Runtime test against an installed debug APK on a disposable phone emulator.

Usage: python3 tests/phone_adaptive_smoke.py emulator-5556
Requires adb on PATH (or ADB env). Injects test GPS/sensors, changes UI preference,
grants permissions and force-stops this app, but does not erase its stored data.
The real two-minute settle timer is tested; no debug-only cadence is substituted.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"
PACKAGE = "com.limhiru.timeline"
ACTIVITY = PACKAGE + "/com.example.timeline.MainActivity"


def run(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *args], text=True).strip()


def ui():
    run("shell", "uiautomator", "dump", "/sdcard/timeline-adaptive-ui.xml")
    return run("shell", "cat", "/sdcard/timeline-adaptive-ui.xml")


def node_for(value, attribute="content-desc"):
    root = ET.fromstring(ui())
    parents = {child: parent for parent in root.iter() for child in parent}
    nodes = [n for n in root.iter("node") if n.get(attribute) == value]
    assert nodes, f"Missing UI node: {value}"
    node = nodes[-1]  # Prefer bottom navigation over the preview.
    while node.get("clickable") != "true" and node.get("checkable") != "true" and node in parents:
        node = parents[node]
    return node


def tap(value, attribute="content-desc"):
    node = node_for(value, attribute)
    bounds = list(map(int, re.findall(r"\d+", node.get("bounds"))))
    run("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
    time.sleep(1)


def launch(home=False):
    args = ["shell", "am", "start", "-W", "-n", ACTIVITY]
    if home:
        args += ["--ez", "timeline_home", "true"]
    run(*args)
    time.sleep(2)


def samples():
    names = run("shell", "run-as", PACKAGE, "ls", "files/automatic-timeline").splitlines()
    tracks = []
    for name in names:
        if name.endswith(".json"):
            tracks += json.loads(run("shell", "run-as", PACKAGE, "cat", f"files/automatic-timeline/{name}"))
    return [p for track in tracks for p in track["points"]]


def gps_registration():
    # The current provider section, not the historical registration event log.
    text = run("shell", "dumpsys", "location").split("Historical Aggregate Location Provider Data:")[0]
    return text.split("gps provider:")[-1]


def alarm_block():
    text = run("shell", "dumpsys", "alarm")
    blocks = re.findall(r"(?ms)^    (?:ELAPSED|RTC)(?:_WAKEUP)? #.*?(?=^    (?:ELAPSED|RTC)(?:_WAKEUP)? #|^  \S|\Z)", text)
    return next((b for b in blocks if "TimelineAlarmReceiver" in b), "")


run("shell", "am", "force-stop", PACKAGE)
run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
run("shell", "wm", "dismiss-keyguard")
run("shell", "settings", "put", "system", "screen_off_timeout", "600000")
run("shell", "settings", "put", "secure", "location_mode", "3")
for permission in ["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS", "ACTIVITY_RECOGNITION"]:
    run("shell", "pm", "grant", PACKAGE, "android.permission." + permission)
run("emu", "sensor", "set", "acceleration", "0:0:9.81")
run("emu", "sensor", "set", "gyroscope", "0:0:0")
run("emu", "geo", "fix", "127", "37")
launch()
tap("Tinted", "text")
assert node_for("Tinted", "text").get("checked") == "true"
run("shell", "am", "force-stop", PACKAGE)
launch()
assert node_for("Tinted", "text").get("checked") == "true"
tap("홈")
assert "나의 타임라인" in ui()
print("PASS: appearance persisted; home icon opens daily timeline", flush=True)
tap("자동 타임라인 기록")
run("emu", "geo", "fix", "127", "37")
time.sleep(4)
initial = samples()
assert initial and node_for("자동 타임라인 기록").get("checked") == "true"
assert "AutomaticTimelineService" in run("shell", "dumpsys", "activity", "services", PACKAGE)
block = alarm_block()
assert re.search(r"requester=\+29m|origWhen=\+29m", block), block
assert PACKAGE not in gps_registration()
print("PASS: real checkpoint saved; GPS suspended; ~30-minute alarm requested", flush=True)

# Goldfish does not expose step/significant-motion sensors. Exercise real fallback.
for i in range(20):
    run("emu", "sensor", "set", "acceleration", f"{3 if i % 2 else -3}:0:9.81")
    time.sleep(.25)
run("emu", "sensor", "set", "acceleration", "0:0:9.81")
time.sleep(2)
assert "이동 중 · 10초 주기" in ui()
registration = gps_registration()
assert PACKAGE in registration and ("10s" in registration or "10000" in registration), registration
print("PASS: acceleration movement switches real GPS to 10 seconds", flush=True)
for i in range(3):
    run("emu", "geo", "fix", str(127.0 + (i + 1) * .00015), "37")
    time.sleep(11)
assert len(samples()) > len(initial)
run("shell", "input", "keyevent", "KEYCODE_HOME")
run("shell", "input", "keyevent", "KEYCODE_SLEEP")
time.sleep(125)
assert "AutomaticTimelineService" in run("shell", "dumpsys", "activity", "services", PACKAGE)
run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
run("shell", "wm", "dismiss-keyguard")
launch(home=True)
assert "기본 30분 · 이동 감지 대기" in ui()
print("PASS: background session settles back after real two-minute inactivity", flush=True)
tap("자동 타임라인 기록")
assert "AutomaticTimelineService" not in run("shell", "dumpsys", "activity", "services", PACKAGE)
assert not alarm_block()
persisted = samples()
run("shell", "am", "force-stop", PACKAGE)
launch(home=True)
assert samples() == persisted
assert node_for("자동 타임라인 기록").get("checked") == "false"
print("PASS: explicit stop cancels service/alarm; daily file survives; no secret restart", flush=True)
tap("운동 및 되돌아가기")
assert "워치에 위치 공유" in ui()
run("shell", "input", "swipe", "360", "970", "360", "330", "450")
time.sleep(1)
assert "기록 시작" in ui() and "되돌아가기" in ui()
print("PASS: separate workout/backtrack/watch-sharing page; ALL PASS", flush=True)
