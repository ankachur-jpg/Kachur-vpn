"""Exercise the installed TV APK using D-pad keys and public accessibility state."""
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.kachur.vpn.tv.experiment"
ACTIVITY = f"{PACKAGE}/com.kachur.vpn.TvHomeActivity"
SWITCHES = ("tv_variable_padding", "tv_route_apps", "tv_route_ips")


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=35)


def launch():
    resolved = adb("shell", "cmd", "package", "resolve-activity", "--brief",
                   "-a", "android.intent.action.MAIN",
                   "-c", "android.intent.category.LEANBACK_LAUNCHER", "-p", PACKAGE)
    assert ACTIVITY in resolved, resolved
    result = adb("shell", "am", "start", "-W", "-n", ACTIVITY)
    assert "Status: ok" in result, result


def focused_switch(name):
    for _ in range(18):
        adb("shell", "uiautomator", "dump", "/data/local/tmp/kachur-tv.xml")
        tree = ET.fromstring(adb("exec-out", "cat", "/data/local/tmp/kachur-tv.xml"))
        for node in tree.iter("node"):
            if node.get("resource-id") == f"{PACKAGE}:id/{name}" and node.get("focused") == "true":
                return node.get("checked") == "true"
        adb("shell", "input", "keyevent", "KEYCODE_DPAD_DOWN")
        time.sleep(0.2)
    raise AssertionError(f"D-pad could not reach {name}")


adb("install", "-r", sys.argv[1])
launch()
for name, initial in zip(SWITCHES, (False, True, True)):
    assert focused_switch(name) == initial, name
    adb("shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
    assert focused_switch(name) != initial, name
adb("shell", "am", "force-stop", PACKAGE)
launch()
for name, expected in zip(SWITCHES, (True, False, False)):
    assert focused_switch(name) == expected, name
