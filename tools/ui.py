"""adb UI helper: ui.py SERIAL (texts | tap TEXT | type TEXT | wait TEXT [seconds] | key CODE)."""
import os, re, subprocess, sys, time

os.environ["MSYS_NO_PATHCONV"] = "1"
serial = sys.argv[1]


def adb(*args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def nodes():
    xml = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    out = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group()
        t = re.search(r' text="([^"]*)"', n)
        d = re.search(r' content-desc="([^"]*)"', n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        label = (t.group(1) if t else "") or (d.group(1) if d else "")
        if b:
            x0, y0, x1, y1 = map(int, b.groups())
            out.append((label.replace("&#10;", " ").replace("&amp;", "&"), (x0 + x1) // 2, (y0 + y1) // 2, "EditText" in n))
    return out


def find(text):
    seen = nodes()
    for exact in (True, False):
        for label, x, y, _ in seen:
            if (label.lower() == text.lower()) if exact else (text.lower() in label.lower()):
                return x, y
    return None


cmd = sys.argv[2]
if cmd == "texts":
    for label, x, y, edit in nodes():
        if label or edit:
            print(f"{x:5d} {y:5d} {'[edit] ' if edit else ''}{label}")
elif cmd == "tap":
    at = find(sys.argv[3])
    if not at:
        sys.exit(f"not on screen: {sys.argv[3]}")
    adb("shell", "input", "tap", str(at[0]), str(at[1]))
elif cmd == "edit":  # tap the Nth text field (0-based) and type
    fields = [n for n in nodes() if n[3]]
    f = fields[int(sys.argv[3])]
    adb("shell", "input", "tap", str(f[1]), str(f[2]))
    time.sleep(0.4)
    adb("shell", "input", "text", sys.argv[4])
elif cmd == "wait":
    limit = time.time() + float(sys.argv[4] if len(sys.argv) > 4 else 30)
    while time.time() < limit:
        if find(sys.argv[3]):
            sys.exit(0)
        time.sleep(1)
    sys.exit(f"never appeared: {sys.argv[3]}")
elif cmd == "key":
    adb("shell", "input", "keyevent", sys.argv[3])
