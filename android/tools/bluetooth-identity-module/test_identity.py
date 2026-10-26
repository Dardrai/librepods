"""Offline root-helper fault tests; replaces only its constant paths and Android commands."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

HERE = Path(__file__).resolve().parent
VENDOR = "bluetooth.device_id.vendor_id"
SOURCE = "bluetooth.device_id.vendor_id_source"
PRODUCT = "bluetooth.device_id.product_id"
VERSION = "bluetooth.device_id.version"

MOCK = r'''#!/usr/bin/python3
import json,os,sys,time
from pathlib import Path
p=Path(os.environ['IDENTITY_TEST_DB']);d=json.loads(p.read_text());name=Path(sys.argv[0]).name
if name=='getprop':
 if len(sys.argv)==1:
  for k,v in sorted(d.items()):print('['+k+']: ['+v+']')
 else:print(d.get(sys.argv[1],''))
 sys.exit(0)
if name=='setprop':
 key,value=sys.argv[1:3]
 fail=p.with_suffix('.fail-source')
 if key.endswith('vendor_id_source') and fail.exists():fail.unlink();sys.exit(1)
 d[key]=value
else:
 assert name=='resetprop' and sys.argv[1]=='--delete'
 key=sys.argv[2];d.pop(key,None)
p.with_suffix('.new').write_text(json.dumps(d));p.with_suffix('.new').replace(p)
with p.with_suffix('.events').open('a') as f:f.write(key+'\n')
if name=='setprop' and key.endswith('.vendor_id') and value=='76' and p.with_suffix('.slow').exists():
 p.with_suffix('.written').write_text('1');time.sleep(1)
'''


class Fixture:
    def __init__(self, values):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.db = self.root / "properties.json"
        self.db.write_text(json.dumps(values))
        self.boot = self.root / "boot"
        self.boot.write_text("aaaaaaaa-0000-0000-0000-000000000001\n")
        self.state = self.root / "data/adb/librepods_bluetooth_identity"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("getprop", "setprop", "resetprop"):
            file = self.bin / name
            file.write_text(MOCK)
            file.chmod(0o755)
        (self.bin / "id").write_text("#!/bin/sh\nprintf '0\\n'\n")
        (self.bin / "id").chmod(0o755)
        (self.bin / "stat").write_text("#!/bin/sh\nvalue=$(/usr/bin/stat \"$@\") || exit 1\nprintf '0:%s\\n' \"${value#*:}\"\n")
        (self.bin / "stat").chmod(0o755)
        ksu = self.root / "data/adb/ksu/bin"
        ksu.mkdir(parents=True)
        (ksu / "busybox").write_text('#!/bin/sh\nexec "$@"\n')
        (ksu / "busybox").chmod(0o755)
        (ksu / "resetprop").symlink_to(self.bin / "resetprop")
        self.script = self.root / "identity.sh"
        self.script.write_text((HERE / "identity.sh").read_text().replace("/data/adb", str(self.root / "data/adb"))
                               .replace("/proc/sys/kernel/random/boot_id", str(self.boot)))
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"], IDENTITY_TEST_DB=str(self.db))

    def values(self):
        return json.loads(self.db.read_text())

    def run(self, action, expected=0):
        result = subprocess.run(["sh", str(self.script), action], env=self.env, capture_output=True, text=True, timeout=6)
        assert result.returncode == expected, (action, result.returncode, result.stdout, result.stderr)
        return result.stdout

    def close(self):
        events = self.db.with_suffix(".events")
        if events.exists():
            assert set(events.read_text().splitlines()) <= {VENDOR, SOURCE}
        self.tmp.cleanup()


def main():
    original = {VENDOR: "224", SOURCE: "2", PRODUCT: "16650", VERSION: "0"}
    f = Fixture(original)
    try:
        assert f.run("status") == "version=1\nconfigured=0\nactive=0\n"
        assert "active=1" in f.run("enable")
        assert f.values() == dict(original, **{VENDOR: "76", SOURCE: "1"})
        f.run("enable")  # The second enable must not replace the original baseline.
        f.run("disable")
        assert f.values() == original
    finally: f.close()
    f = Fixture({PRODUCT: "16650", VERSION: "0"})
    try:
        f.run("enable"); f.run("disable")
        assert f.values() == {PRODUCT: "16650", VERSION: "0"}
    finally: f.close()
    spoofed = {"persist.test.note": "[bluetooth.device_id.vendor_id]: [76"}
    f = Fixture(spoofed)
    try:
        f.run("enable"); f.run("disable")
        assert f.values() == spoofed
    finally: f.close()
    f = Fixture(original)
    try:
        f.db.with_suffix(".fail-source").write_text("1")
        f.run("enable", expected=1)
        assert f.values() == original
        assert not (f.state / "pending").exists()
        assert "configured=0" in f.run("status")
    finally: f.close()
    f = Fixture(original)
    try:
        f.run("enable")
        f.boot.write_text("bbbbbbbb-0000-0000-0000-000000000002\n")
        updated = dict(original, **{VENDOR: "555", SOURCE: "1"})
        f.db.write_text(json.dumps(updated))
        f.run("boot"); f.run("disable")
        assert f.values() == updated
    finally: f.close()
    for killed in (False, True):
        f = Fixture(original)
        try:
            f.db.with_suffix(".slow").write_text("1")
            command = ["sh", str(f.script), "enable"] + ([] if killed else ["--stdin-lease"])
            process = subprocess.Popen(command, env=f.env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            deadline = time.monotonic() + 3
            while not f.db.with_suffix(".written").exists() and time.monotonic() < deadline: time.sleep(.01)
            assert f.db.with_suffix(".written").exists()
            if killed: process.kill()
            else: process.stdin.close()
            process.wait(timeout=4)
            if killed: f.run("status")  # Recover the interrupted journal with the next owner.
            assert f.values() == original
            assert not (f.state / "pending").exists()
            process.stdout.close(); process.stderr.close()
        finally: f.close()
    f = Fixture(original)
    try:
        f.run("enable")
        f.db.write_text(json.dumps(dict(original, **{VENDOR: "999"})))
        f.run("disable")
        assert f.values()[VENDOR] == "999"  # Never overwrite an external owner's configuration.
        assert "configured=0" in f.run("status")
    finally: f.close()
    for name in ("lock", "enabled.new", "baseline"):
        f = Fixture(original)
        try:
            f.run("status")
            external = f.root / "external"
            external.mkdir()
            sentinel = external / "boot"
            sentinel.write_text("retain")
            target = f.state / name
            if target.exists(): target.unlink()
            target.symlink_to(external if name == "baseline" else sentinel)
            f.run("enable", expected=1)
            assert sentinel.read_text() == "retain" and f.values() == original
        finally: f.close()
    print("Bluetooth identity helper: 11 restoration, OTA, interruption, ownership and symlink checks passed")


if __name__ == "__main__": main()
