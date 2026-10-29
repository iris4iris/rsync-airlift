#!/usr/bin/env python3
"""Package the Linux x64 release on Linux or Windows; Python standard library only."""
import argparse
import gzip
import hashlib
import io
import os
from pathlib import Path
import re
import shutil
import struct
import tarfile
import time


def archive(entries, epoch):
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        directories = set()
        for name, content, mode in entries:
            # dpkg requires explicit directories before their children; unlike
            # tar extraction, package installation does not create them implicitly.
            parents = list(Path(name).parents)
            for parent in reversed(parents):
                directory = parent.as_posix()
                if directory == "." or directory in directories:
                    continue
                info = tarfile.TarInfo(directory + "/")
                info.type = tarfile.DIRTYPE
                info.mode, info.mtime = 0o755, epoch
                info.uid = info.gid = 0
                info.uname = info.gname = "root"
                tar.addfile(info)
                directories.add(directory)
            info = tarfile.TarInfo(name)
            info.size, info.mode, info.mtime = len(content), mode, epoch
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            tar.addfile(info, io.BytesIO(content))
    return gzip.compress(raw.getvalue(), compresslevel=9, mtime=0)


def changelog(version, maintainer, epoch):
    """Minimal Debian changelog (Policy 12.7); gzip without name or time keeps the package reproducible."""
    days = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    months = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    moment = time.gmtime(epoch)
    date = (f"{days[moment.tm_wday]}, {moment.tm_mday:02d} {months[moment.tm_mon - 1]} {moment.tm_year} "
            f"{moment.tm_hour:02d}:{moment.tm_min:02d}:{moment.tm_sec:02d} +0000")
    text = (f"rsync-airlift ({version}) stable; urgency=medium\n\n"
            f"  * Linux x86-64 Native build. See /usr/share/doc/rsync-airlift/README.md.\n\n"
            f" -- {maintainer}  {date}\n")
    return gzip.compress(text.encode("utf-8"), compresslevel=9, mtime=0)


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="0.1.0~snapshot", help="Debian package version")
    parser.add_argument("--binary", type=Path, default=root / "monitor/build/bin/linuxX64/releaseExecutable/rsync-airlift.kexe")
    parser.add_argument("--output", type=Path, default=root / "monitor/build/native-release")
    parser.add_argument("--maintainer", default="Rsync Airlift contributors")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9][A-Za-z0-9.+~\-]*", args.version):
        parser.error("invalid Debian version")
    if "\n" in args.maintainer or "\r" in args.maintainer:
        parser.error("invalid maintainer")
    binary = args.binary.read_bytes()
    if binary[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", binary, 18)[0] != 62:
        parser.error("expected a Linux x86-64 ELF executable")
    # Bounds checked against the current Kotlin/Native Linux toolchain. Fail closed
    # when a future toolchain needs newer symbol versions; update Depends then.
    versions = re.findall(rb"GLIBC_(\d+)\.(\d+)(?:\.(\d+))?", binary)
    if max(tuple(int(n or b"0") for n in v) for v in versions) > (2, 14, 0):
        parser.error("newer glibc required: review the package dependencies")
    epoch = int(os.environ.get("SOURCE_DATE_EPOCH", int(args.binary.stat().st_mtime)))
    # No copyright file: the project has not published a license, and one must not be invented here.
    data = [("usr/bin/rsync-airlift", binary, 0o755),
            ("usr/share/doc/rsync-airlift/README.md", (root / "README.md").read_bytes(), 0o644),
            ("usr/share/doc/rsync-airlift/NATIVE.md", (root / "docs/NATIVE.md").read_bytes(), 0o644),
            ("usr/share/doc/rsync-airlift/changelog.Debian.gz", changelog(args.version, args.maintainer, epoch), 0o644)]
    control = f"""Package: rsync-airlift
Version: {args.version}
Section: utils
Priority: optional
Architecture: amd64
Maintainer: {args.maintainer}
Installed-Size: {sum((len(content) + 1023) // 1024 for _, content, _ in data)}
Depends: libc6 (>= 2.14), libgcc-s1 | libgcc1 (>= 1:4.2), libcrypt1 | libc6 (<< 2.29), rsync, openssh-client
Suggests: sshpass
Homepage: https://github.com/iris4iris/rsync-airlift
Description: Parallel rsync dispatcher with an interactive terminal monitor
 Transfers file batches over SSH with adjustable workers, progress, statistics,
 error reporting and an attachable terminal interface. No Java runtime required.
""".encode()
    md5sums = "".join(f"{hashlib.md5(content).hexdigest()}  {name}\n" for name, content, _ in data).encode()
    parts = [("debian-binary", b"2.0\n"),
             ("control.tar.gz", archive([("control", control, 0o644), ("md5sums", md5sums, 0o644)], epoch)),
             ("data.tar.gz", archive(data, epoch))]
    args.output.mkdir(parents=True, exist_ok=True)
    deb = args.output / f"rsync-airlift_{args.version}_amd64.deb"
    with deb.open("wb") as stream:
        stream.write(b"!<arch>\n")
        for name, content in parts:
            header = f"{name + '/':<16}{epoch:<12}{0:<6}{0:<6}{'100644':<8}{len(content):<10}`\n".encode("ascii")
            assert len(header) == 60
            stream.write(header)
            stream.write(content)
            if len(content) % 2:
                stream.write(b"\n")
    standalone = args.output / "rsync-airlift-linux-x64"
    if not standalone.exists() or standalone.read_bytes() != binary:
        shutil.copyfile(args.binary, standalone)
    standalone.chmod(0o755)
    checksums = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in (standalone, deb))
    (args.output / "SHA256SUMS").write_text(checksums, encoding="ascii", newline="\n")
    for path in (standalone, deb):
        print(f"{path}: {path.stat().st_size:,} bytes ({path.stat().st_size / 1048576:.2f} MiB)")


if __name__ == "__main__":
    main()
