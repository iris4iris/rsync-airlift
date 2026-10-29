# Linux Native dispatcher and terminal monitor

The Linux x86-64 build supports interactive transfers, `--demo`, `--attach`,
`--serve` and `--serve-demo` without a JVM. `rsync` and OpenSSH remain external
tools; password authentication also requires `sshpass`. Swing remains JVM-only.
The Native terminal backend uses ANSI and POSIX termios, without ncurses.
Interactive modes require a terminal/PTY (SSH: `ssh -t`).

## Build

```bash
./gradlew -PenableLinuxNative=true :monitor:linkDebugExecutableLinuxX64 :monitor:linkDebugTestLinuxX64
```

Files:

- `monitor/build/bin/linuxX64/debugExecutable/rsync-airlift.kexe`
- `monitor/build/bin/linuxX64/debugTest/test.kexe`

Compilation/linking can be performed on Windows; the ELF executables must run on
Linux x86-64. Building with Gradle still requires a JDK; executing the produced
binary does not. Core's 41 Native tests passed on the user's Linux server.
The monitor's headless socket/filesystem/signal checks passed on Linux; the Native demo service was also verified through the JVM monitor. The new
Native terminal backend was subsequently verified on Linux, including a real SSH/rsync transfer (982 files, 113.9 GiB, zero failures).

## First test

Copy both files to Linux (rename the test binary if core's test.kexe is still there):

```bash
chmod +x rsync-airlift.kexe airlift-monitor-tests.kexe
./airlift-monitor-tests.kexe
./rsync-airlift.kexe --demo --endpoint ./airlift-native.connection
```

Demo transfers are simulated, with one deliberate error to exercise error display.
`Q` during transfer detaches the panel; the service remains available. In an
ordinary interactive run, Q after completion exits the program. A `--serve`
service stays alive, and `--attach` never shuts down the dispatcher. Reattach:

```bash
./rsync-airlift.kexe --attach ./airlift-native.connection
```

The terminal uses an alternate screen and restores its saved modes, cursor and
mouse state on exit. Only changed cells are emitted after startup/resize, so
unchanged frames emit no terminal output. Arrow keys, F2/Enter, Tab, paging,
Home/End, worker clicks and mouse wheel map to the shared monitor commands.
Bracketed paste is ignored rather than executed as control keys. Terminal
lifecycle tests need a PTY; headless test runs skip those three checks.

For a headless demo use `--serve-demo`.
Ctrl+C, SIGTERM or SIGHUP (terminal hangup, SSH logout) stops it and removes its
connection file. When SIGHUP is ignored at launch (`nohup`), it stays ignored, as
on the JVM. The connection file
contains a token, is created exclusively with owner-only permissions (0600), and
must not be committed or shared publicly.

## Real transfer

```bash
./rsync-airlift.kexe --serve --workers 8 \
  --rsync "-avH --partial --inplace" \
  user@host:/source/ /destination/
```

From/To confirmation and SSH password prompts require an interactive terminal.
Use `--yes` only to skip direction confirmation for an unattended launch; SSH
still requires working keys or SSHPASS. This is the same preparation policy as
the JVM CLI. Batch size and file-list/mask rules are shared.

To inspect the dispatcher with the existing JVM UI on the same server:

```bash
java -jar rsync-airlift.jar --attach ./airlift-native.connection
```

Native and JVM monitors can both attach to the same dispatcher. The connection protocol remains version 3 and the endpoint format remains Java
properties compatible. Sockets bind to 127.0.0.1 only. Native socket cancellation
closes connections; blocking signal handlers do no Kotlin work: a POSIX handler
sets a flag and a coroutine performs shutdown.

## Processes, signals and terminal

- Children start through `vfork` and `execve` in a C shim, so a large dispatcher
  heap is not copied for every rsync job. Signals are blocked around the call; the
  child resets caught signals and SIGPIPE to their defaults and starts with an
  empty signal mask.
- Children inherit only their standard descriptors. Anything else open in the
  dispatcher, including descriptors from libraries or the launching shell, is
  marked close-on-exec in the child (`close_range` on Linux 5.11+, otherwise a
  bounded sweep).
- The executable ignores SIGPIPE: a closed stdout pipe (`| head`, a dead log
  reader) no longer kills the dispatcher and orphans its SSH masters.
- Pipes and monitor sockets wait for readiness in one shared `poll()` thread, so an
  idle dispatcher or monitor service does not wake up periodically. A transient
  accept failure, such as running out of descriptors, is retried with a short
  back-off; the service keeps accepting monitors afterwards.
- Terminal output never blocks transfers. If the terminal stops reading (a stalled
  SSH connection or a sleeping laptop), output is queued and new frames are skipped
  until it drains. A hangup or terminal error only ends the panel; the transfers
  continue as a detached service, just as when the panel is closed with Q.
- SSH ControlMaster sockets live in `$TMPDIR`. An empty or relative `TMPDIR`, or one
  too long for the socket path limit (108 bytes), falls back to `/tmp`.

For logout survival use tmux or a service. Final database consistency still
requires stopping writers or a consistent snapshot; live MyISAM pre-copy is not
a consistent backup.

## Optimized release and Debian package

Build the optimized executable (no JVM or JAR build):

```bash
./gradlew -PenableLinuxNative=true :monitor:linkReleaseExecutableLinuxX64
python tools/package_native.py
```

On Windows use `./gradlew.bat` and `python` (or `py -3`). Packaging uses only
Python's standard library and can run on Windows too. It creates a Debian ar
container with gzip-compressed control/data tar members, root ownership, and
executable permissions for `/usr/bin/rsync-airlift`. The documentation directory
contains `README.md`, `NATIVE.md` and a minimal `changelog.Debian.gz`. It has no
`copyright` file because the project has not published a license yet; add one
when it does.

Output in `monitor/build/native-release/`:

- `rsync-airlift-linux-x64`: standalone Linux executable.
- `rsync-airlift_0.1.0~snapshot_amd64.deb`: Debian/Ubuntu amd64 package.
- `SHA256SUMS`: checksums for both files.

The package version defaults to `0.1.0~snapshot`, matching the current development
version. For a numbered release, pass `--version 0.1.0`; publishing/tagging is a
separate operation. `--maintainer "Name <email>"` overrides the default project
maintainer name. `SOURCE_DATE_EPOCH` controls archive entry timestamps.

Copy the `.deb` to a Debian/Ubuntu amd64 machine and install:

```bash
sudo apt install ./rsync-airlift_0.1.0~snapshot_amd64.deb
rsync-airlift --demo
```

The package declares `libc6 (>= 2.14)`, `libgcc-s1 | libgcc1 (>= 1:4.2)`,
`libcrypt1 | libc6 (<< 2.29)`, `rsync` and `openssh-client` dependencies.
The alternatives account for older distributions using libgcc1 and shipping
libcrypt.so.1 within libc6; newer distributions use separate packages. `sshpass` is optional: install it for password
authentication. Runtime compatibility on each distribution still needs verification. The ELF also uses the system's
standard glibc libraries; it is not a fully static binary or an Alpine/musl build.
The symbol requirement was inspected with `readelf`; packaging refuses newer
glibc symbol requirements until the dependency declaration is reviewed.

The first optimized build measured 3,155,768 bytes (3.01 MiB), versus 8,645,632
bytes (8.25 MiB) for debug. Package size varies with the included documentation.
Package structure/checksums were verified on Windows; installation and execution
of this release package must be checked on Linux.
