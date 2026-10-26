"""Package the root module without tests, local state or Windows line endings."""
from pathlib import Path
import sys
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

source = Path(__file__).resolve().parent
destination = Path(sys.argv[1])
destination.parent.mkdir(parents=True, exist_ok=True)
files = ("module.prop", "customize.sh", "post-fs-data.sh", "uninstall.sh", "identity.sh")
with ZipFile(destination, "w", compression=ZIP_DEFLATED) as archive:
    for name in files:
        entry = ZipInfo(name)
        entry.create_system = 3
        entry.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
        entry.compress_type = ZIP_DEFLATED
        archive.writestr(entry, (source / name).read_bytes().replace(b"\r\n", b"\n"))
with ZipFile(destination) as archive:
    assert archive.namelist() == list(files) and archive.testzip() is None
    assert all(b"\r\n" not in archive.read(name) for name in files)
print(destination)
