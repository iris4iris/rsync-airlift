# Rsync Airlift

### The power of rsync. Multiplied.

## Contents

- [Install with APT](#install-with-apt)
- [Why Airlift?](#why-airlift)
- [Born from a production migration](#born-from-a-production-migration)
- [A small core, a clear view](#a-small-core-a-clear-view)
- [What parallelism changed in practice](#what-parallelism-changed-in-practice)
- [Built around rsync](#built-around-rsync)
- [Build and deployment](#build-and-deployment)
- [Quick start](#quick-start)
- [SSH authentication](#ssh-authentication)
- [File selection and destination paths](#file-selection-and-destination-paths)
- [Scheduling, batching and directory collapse](#scheduling-batching-and-directory-collapse)
- [Rsync options](#rsync-options)
- [Terminal monitor](#terminal-monitor)
- [Background dispatch and attaching](#background-dispatch-and-attaching)
- [Midnight Commander](#midnight-commander)
- [Core library](#core-library)
- [Logs, resuming and consistency](#logs-resuming-and-consistency)
- [Validation](#validation)
- [Linux Native](#linux-native)

**Rsync is brilliant. Give it a crew.**

Rsync can reconcile enormous files while transferring only the parts that changed.
That makes it an excellent tool for moving large datasets, repeating synchronization
passes, and preparing a server migration while most of the service remains online.

But a directory full of large files also contains an opportunity: independent files
can be processed at the same time. When one rsync process leaves CPU, storage, or
network capacity available, several processes can put more of that capacity to work.

Rsync Airlift coordinates that work. It runs the original rsync through a pool of
SSH ControlMaster connections, feeds files to available workers, and shows the
operation in an interactive terminal monitor inspired by `htop`.

You still choose the files, endpoints, and rsync options. Airlift manages the queue,
the workers, and the view of what is happening.

## Install with APT

For **Debian/Ubuntu on amd64 (x86-64)**. No Java runtime required.
Add the signed repository once and install:

```bash
sudo apt update &&
sudo apt install -y ca-certificates curl &&
sudo install -d -m 755 /etc/apt/keyrings &&
curl -fsSLo /tmp/rsync-airlift-archive-keyring.gpg \
  https://iris4iris.github.io/rsync-airlift/rsync-airlift-archive-keyring.gpg &&
sudo install -m 644 /tmp/rsync-airlift-archive-keyring.gpg \
  /etc/apt/keyrings/rsync-airlift.gpg &&
echo 'deb [arch=amd64 signed-by=/etc/apt/keyrings/rsync-airlift.gpg] https://iris4iris.github.io/rsync-airlift/ stable main' \
  | sudo tee /etc/apt/sources.list.d/rsync-airlift.list &&
sudo apt update &&
sudo apt install -y rsync-airlift
```

Installation does not start a transfer. Future updates arrive through normal
`sudo apt update` and `sudo apt upgrade`. For SSH password authentication,
also install `sshpass`; SSH keys do not require it.
Installation and demo startup have been verified on Ubuntu 18.04 amd64.
See [APT repository setup](docs/APT.md) for maintainer instructions.

## Why Airlift?

Parallel rsync is an established approach. Airlift brings visibility, live control,
and file scheduling together in one terminal workflow.

- **See the whole operation.** An `htop`-inspired dashboard shows each worker's
  job progress, size, speed, and ETA alongside the queue and errors. Overall
  statistics distinguish matched data from newly transferred data, with elapsed
  time and an average processing rate at completion. Select a worker to inspect
  its current file and error details.
- **Adjust the run as it happens.** Increase or decrease concurrency from the
  keyboard, pause the assignment of new files while active transfers finish,
  and resume the queue when ready. Detach the monitor and attach again to the
  running dispatcher. Each attempt has a raw rsync log; after an interruption,
  rerun the command and let rsync reconcile the destination using the options
  you supplied.
- **Put available resources to work.** The CLI collects file sizes before
  transfer and schedules the largest files first. A shared queue feeds each
  available worker its next job. Small files are grouped into batches with a
  default minimum load of 5 MiB, amortizing local and remote rsync startup across
  many files. Each worker also reuses its SSH ControlMaster connection across jobs.
  This can reduce waiting on workloads with independent
  files and spare CPU, storage, or network capacity; it does not guarantee a
  speedup when those resources are already saturated.

The goal is to make parallel synchronization easy to watch and control while
keeping rsync's behavior and your choice of copy options at the center.
Each file is processed by one worker; splitting a single file across CPU cores
is not implemented. Run logs are available, but a persistent job history and
dispatcher checkpoints are not implemented either.

![Rsync Airlift terminal monitor showing parallel file synchronization in progress](docs/images/airlift-in-process.png)

*Parallel synchronization in progress: per-worker progress, file sizes, speeds,
and estimated completion times. Displayed rates measure logical processing, not network throughput.*

## Born from a production migration

Airlift began with a real migration: approximately **1.16 TB of MyISAM database
files**, a changing production service, and a limited window for downtime.

The approach was to copy ahead of the move, repeat synchronization passes to reduce
the remaining differences, then stop MySQL and perform the final pass against
stable files before switching production to the destination.

The preliminary copies were only preparation. A copy of a running MyISAM database
is **not an application-consistent backup**. The final synchronization required
stopping the database so its files would stop changing.

A small Kotlin dispatcher gave multiple rsync processes their own SSH connections
and a shared queue of files. As soon as a worker finished, it took the next file.
Large files went first, reducing the chance of ending with one enormous file while
every other worker sat idle.

The migration used **16 workers**, reached **up to 900 Mbit/s on a 1 Gbit/s link**,
and finished with **12 minutes of downtime**.

That does not mean a terabyte crossed the network in twelve minutes. Most of the
data had already been copied. The downtime covered the final reconciliation and
the production switch. Reducing that last stretch of work was the point.

## A small core, a clear view

The original dispatcher did its job. The next question was how to see what all
those workers were doing without following a pile of separate logs.

That became the Airlift monitor: individual progress bars, file sizes, speeds,
estimated completion times, queue and error views, and a details panel for the
selected worker. You can change concurrency while a run is active, pause the
assignment of new files, or detach the panel and attach again later.

![Airlift workers arranged in two columns, showing progress bars, checksum preparation, failed connections, and selected worker details](docs/images/airlift-workers.png)

*A larger worker pool during an experiment: per-worker progress, visible failures,
and details for the selected worker. Columns are added when the available height fills up.*

The overall bar separates data that did not need to be sent from newly transferred
file data and failures. `Matched` includes unchanged files that rsync skipped;
it does not imply that every byte was checksum-verified. `Processed` and the
displayed speeds describe logical file processing, not measured network traffic.

The project has two modules, currently targeting JVM:

- **`core`** accepts a prepared list of files, owns the queue, and manages worker
  connections. Applications can supply their own selection and ordering.
- **`monitor`** provides the command-line launcher, file discovery, size collection,
  size-based scheduling and batching, an attachable monitoring service, and the Lanterna TUI.

This separation keeps the library useful beyond the migration that inspired it.
The input can be database files, archives, media, or any other list of files.
Application-specific selection belongs to the caller; Airlift handles dispatch.

## What parallelism changed in practice

In subsequent experiments on a dataset of approximately **127.6 GiB across 1,003
files**, a one-worker run took **6:34**. A six-worker run took **1:29** — an observed
**4.43× reduction in elapsed time**. Further runs included **2:09 with six workers**
and **1:35 with twelve**. Before batching, the fastest observed run finished in **1:14 with eleven
workers**, with **359.5 MiB of new file data** and **no failed files**. Compared
with the one-worker run, that is **5.32× faster**, or **81.2% less elapsed time**.

After introducing small-file batching, a run with **four workers** completed in
**1:14**, with **303.3 MiB of new file data**. A subsequent run with **eight workers**
finished in **50 seconds**, with **318.4 MiB of new file data**, all **1,003 files**
completed, and **no failures**. That is an observed **7.88× speedup** over the
original one-worker run, or **87.3% less elapsed time**.

One worker handled **458 files in a single rsync invocation**. Previously, each
file required starting rsync on both the local and remote machines and negotiating
a new rsync protocol session, even though the SSH connection was already reused.
Batching amortizes that startup and protocol overhead across the file list. Large
files still occupy their own jobs in the descending-size queue, while small files
share invocations near the end of the run.

These were practical migration experiments, not a controlled benchmark. The amount
of changed data differed between runs, and filesystem caching may have influenced
the results. They demonstrate the benefit on this workload, not a universal speedup
or a promise that adding workers always helps.

Parallelism is most useful when there are several substantial files and spare
resources to process them. If the network or storage is already saturated, more
workers may add contention. Each file is still handled by one rsync process:
Airlift does not split a single file between workers.

For a final synchronization, the useful measure is often the time between stopping
writers and bringing production back. On this workload, making independent files
progress together shortened that wait considerably.

## Built around rsync

Airlift keeps rsync responsible for its delta-transfer algorithm and file-copying
behavior. Copy options are supplied through `--rsync`; the dispatcher adds the
transport and reporting arguments it needs to manage individual jobs.

It also leaves consistency to the application. For databases, virtual machines,
and other actively changing datasets, arrange a consistent snapshot or a coordinated
shutdown before treating the result as authoritative. A successful file transfer
alone cannot establish that consistency.

The aim is straightforward: keep available workers busy, make the operation easy
to understand, and reduce the time spent waiting for synchronization to finish.

**Move terabytes. Keep downtime short.**

*Built with Kotlin. Powered by rsync. Inspired by a real production migration.*

## Build and deployment

Requires **Java 21**. The project uses Kotlin **2.1.21** and the Gradle wrapper.
Build the two component JARs and collect their dependencies without an archive:

```sh
./gradlew :core:test :monitor:test :monitor:jarBundle
```

On Windows, use `gradlew.bat`. Deploy the contents of `monitor/build/jar-bundle/`:

```text
rsync-airlift.jar          # executable monitor/CLI
rsync-airlift-core.jar     # core library; no standalone main
lib/                      # Kotlin, Lanterna, JLine and other runtime dependencies
```

The monitor manifest links the adjacent core JAR and the JARs in `lib/` using a
relative `Class-Path`. Keep this layout; no Gradle or manual classpath is needed
on the destination machine. Rebuild with `:monitor:jarBundle` without `clean`:
unchanged tasks are `UP-TO-DATE`. Deploy changed component JARs; update `lib/`
when external dependencies change.

Other build targets:

| Target | Output |
| --- | --- |
| `:core:jar` | `core/build/libs/rsync-airlift-core.jar` |
| `:monitor:jar` | `monitor/build/libs/rsync-airlift.jar` |
| `:monitor:jarBundleZip` | Portable ZIP under `monitor/build/distributions/` |
| `:monitor:installDist` | Launch scripts and libraries under `monitor/build/install/rsync-airlift-monitor/` |
| `build` | Tests and distributions, including archives |

Builds, tests and the terminal/demo UI work on Windows. Real transfers target
**Linux or WSL with a Linux JDK**, with OpenSSH and rsync on both machines.
Remote discovery also requires a POSIX `sh` and GNU `find` on the source host; the login
shell may be bash, zsh, fish or tcsh.
Password authentication requires `sshpass` on the machine running Airlift.

## Quick start

Run these commands from the deployed bundle directory:

```sh
java -jar rsync-airlift.jar --help
java -jar rsync-airlift.jar --demo

# Download and open the terminal monitor.
java -jar rsync-airlift.jar --workers 8 --rsync "-av --partial" \
  user@source-server:/srv/source/ /srv/destination/

# Upload by swapping the endpoints.
java -jar rsync-airlift.jar --workers 8 --rsync "-av --partial" \
  /srv/source/ user@destination-server:/srv/destination/
```

The last positional argument is always the destination:
`[options] SOURCE [SOURCE ...] DESTINATION`. Sources must all be local for upload,
or remote on the same SSH host for download. Local-to-local, remote-to-remote,
and rsync-daemon endpoints are not supported. Remote paths must be absolute;
bracket IPv6 addresses, for example `user@[2001:db8::1]:/srv/source/`.

Real transfers display **FROM** and **TO** before SSH authentication, discovery
or worker startup. Only `y` or `yes` confirms; Enter or EOF cancels.
`--yes` explicitly skips this confirmation for automation. It does not bypass
SSH authentication or host-key verification. The core library, demo and attach
modes do not ask for transfer confirmation.

### Options and modes

| Argument | Behavior/default |
| --- | --- |
| `--workers N` | Initial target concurrency, 1–256; 16 for real transfers, 4 for demo |
| `--rsync "OPTIONS"` | Copy options passed to rsync; no archive/partial/bandwidth policy is injected |
| `--ssh "OPTIONS"` | Extra OpenSSH client options, such as a cipher; see [Rsync options](#rsync-options) |
| `--attempts N` | rsync attempts per job, 1–10; 1 |
| `--batch-min-size SIZE` | Minimum accumulated job size; `5MiB` |
| `--files FILE` | UTF-8 relative file list; one path per line (a BOM and leading `./` are accepted) |
| `--null` | Read the list as NUL-separated paths instead |
| `--port N` | SSH port; 22 |
| `--identity FILE` | SSH private-key path |
| `--endpoint FILE` | Monitor connection file; `airlift-monitor.connection` in the working directory for real runs |
| `--yes` | Skip transfer-direction confirmation |
| `--run` | Transfer and open TUI; implied by positional endpoints |
| `--serve` | Transfer without opening TUI; same CLI confirmation/authentication rules apply |
| `--attach FILE` | Open TUI for an existing dispatcher; accepts only optional `--swing` |
| `--demo` / `--serve-demo` | Simulate transfers with/without TUI; no SSH or real copying |
| `--swing` | Explicit graphical terminal window; otherwise use the real terminal |
| `--mc` | Optional compatibility flag; MC path recognition is automatic |

Value options accept `--name VALUE` or `--name=VALUE`. `--` ends option parsing.
An empty invocation prints help and starts nothing. Demo uses 48 simulated files,
includes one failure, and gives each run a temporary endpoint by default.

## SSH authentication

Worker connections open in waves of at most five (`AirliftConfig.connectConcurrency`).
Every first connection attempt in a wave must finish before the next wave starts;
connected workers can already transfer files. Workers added during a run use the
same ordering. Retries and reconnects never run more than five handshakes at once
either. This keeps startup below OpenSSH's default `MaxStartups 10` but does not
guarantee acceptance by every server.

Authentication is prepared once before opening the monitor:

1. If `SSHPASS` exists in the environment, use `sshpass -e` with `BatchMode=no`.
2. Otherwise try SSH keys or `ssh-agent` with `BatchMode=yes`.
3. If authentication is denied and a console is available, ask for a password
   once, without echo, even if the rejected password came from `SSHPASS`.
   The entered password overrides inherited `SSHPASS` for this run.
   Reuse it through the environment of `sshpass` processes
   for discovery and worker connections. A rejected password ends startup.

The prompted password is not written to files, command arguments, or logs, and
is not exported to the parent shell. Noninteractive launches need working keys,
an agent, or `SSHPASS` supplied in advance. `--swing` does not provide a separate
graphical password prompt: the fallback password input still requires a console.

Unknown host keys can be reviewed and accepted through OpenSSH's interactive
fingerprint prompt. Changed host keys remain an error; Airlift does not silently
replace them. For a headless run, establish the trusted host key beforehand.

Each worker owns an independent SSH ControlMaster and reuses it across jobs.

A worker retries a failed connection with backoff (1 s, 2 s, ...) and gives up
after 3 consecutive failures (`AirliftConfig.connectAttempts`). After a failed rsync
attempt the worker asks its ControlMaster whether it is still alive (`ssh -O check`).
If the connection is gone, for example after a network outage longer than the
90 s keepalive window, the job returns to the front of the queue instead of being
marked failed, and the worker reconnects. A completed job resets the failure count.
Without this check, a worker with a dead master would fail every remaining job
within milliseconds. Connection problems appear in the worker and error views.

rsync attempts per job use `AirliftConfig.maxAttempts` (CLI `--attempts`, default 1).

## File selection and destination paths

Without `--files`, the launcher discovers regular files and symlinks under the
source directory, or selects a single file or mask. It does not follow directory
symlinks during discovery. It collects source sizes before starting workers;
those sizes describe the initial inventory, not an atomic filesystem snapshot.

```sh
# A quoted mask is expanded by Airlift.
java -jar rsync-airlift.jar --rsync "-av --partial" \
  'user@host:/srv/source/*.bin' /srv/destination/

# A shell-expanded local list is also supported.
java -jar rsync-airlift.jar --rsync "-av --partial" \
  /srv/source/*.bin user@host:/srv/destination/
```

`*` and `?` match within one path component, including names that start with a dot;
`**` crosses directories; `[abc]`, `[a-z]` and `[!abc]` match one character. A backslash
escapes the next character (`\*`, `\?`, `\[`, `\\`). A mask ending in `/` selects every
file below the matching directories, for example `'user@host:/srv/*/data/'`. Quote masks
when Airlift should expand them.

A path that exists is always used literally, so names containing `[`, `?` or `*`, such as
`Photos [2020]/`, need no escaping. A mask or source that selects no files stops
preparation with an error; a mask that also matches directories prints a warning,
because masks select files.

A source that is a symlink to a directory is followed only when written with a trailing
slash (`link/`), as in rsync; without it the link itself is copied.

Names that are not valid UTF-8 cannot be passed in rsync's file list: discovery skips
them with a warning that lists the escaped paths (`\xFF`), and their directories are never
collapsed. Entries that disappear during discovery are ignored.

With `--files` and no mask, only the listed paths are selected. With a mask and
`--files`, the two selections are combined without duplicates. List paths are
relative to the source directory, or the directory before the first wildcard.
Blank entries are ignored; missing listed files abort preparation.

Destination mapping:

| Source/selection | Result under `/backup/` |
| --- | --- |
| Single directory root `/data/`, list entry `one/a.bin` | `/backup/one/a.bin` |
| Single file `/data/one/a.bin` | `/backup/a.bin` |
| Multiple directories `/data/one` and `/data/two` | `/backup/one/...` and `/backup/two/...` |
| Multiple directories `/data/one/` and `/data/two/` | Their contents directly under `/backup/` |
| Multiple sources including `.`, `..` or `/data/one/.` | Contents directly under `/backup/`, as with a trailing slash |

A single directory is treated as a root and copies its contents, with or without
a trailing slash. The directory-name distinction in the table applies to multiple
sources. For multiple roots, `--files` entries are searched relative to each root
and must exist in at least one. Repeated source files are deduplicated; different
sources mapping to the same destination path cause a preparation error.

## Scheduling, batching and directory collapse

The launcher starts with descending file sizes (ties by path). When it reaches
files smaller than `--batch-min-size`, it sorts the remaining tail by source root, then
path, so files from the same source share batches.
It fills jobs until their accumulated size reaches the threshold or more, the
queue ends, or the source root changes. A large file naturally fills a job on its
own in the descending-size prefix. The final job may be smaller; there is no
file-count limit, so zero-length files can share one invocation.

The default is **5MiB = 5,242,880 bytes**. Units are case-insensitive: decimal
`KB`/`MB`/`GB`, binary `KiB`/`MiB`/`GiB`, or an integer byte count without a suffix.
For example, `--batch-min-size 5MB` means 5,000,000 bytes.

Each job uses one rsync invocation, fed by NUL-separated stdin with
`--from0 --files-from=-`; no temporary file list is needed. Paths containing
spaces, newlines or leading dashes are not interpolated into shell commands.

If a job owns all files below a directory, its list can be shortened to a recursive
directory entry. Parents may be collapsed too, and a job can mix directories with
individual files. For example, 17,000 files may become one `.` entry **if they all
belong to that job**. Jobs are not enlarged just to achieve directory collapse.

Coverage is checked against the complete **initial** inventory, including files
not selected by a mask/list. Files assigned to another job prevent collapse.
Unselected empty directories and special filesystem entries also block parent
collapse. Airlift performs no per-job directory rescan or additional SSH check;
rsync handles the subsequent traversal. Collapsed jobs add `--recursive`.

Collapse is disabled for `--delete*`, `--del`, `--remove-source-files`/`--remove-sent-files`,
`--copy-links`, `--copy-dirlinks`, `--copy-unsafe-links`, `--keep-dirlinks`, the short
link-following flags `-L`, `-k`, `-K`, `--no-recursive`, and filter or file-system options
(`--exclude*`, `--include*`, `--filter`/`-f`, `-F`, `-C`/`--cvs-exclude`,
`-x`/`--one-file-system`), because recursion could then copy, delete or skip files other
than the listed ones. Original file names and sizes remain in the job ledger.
New files found by rsync in a collapsed directory can be copied; files removed
since startup may no longer be copied. Displayed counts/totals describe the
initial selection. Keep directory membership stable when that exact selection
must be preserved.

`Done` counts files and advances when a whole job succeeds. A failed batch has
unconfirmed individual outcomes: all its files are conservatively counted as
failed even if some were copied. A retry reruns the whole batch; rsync reconciles
the destination according to the supplied options. Pause and concurrency reduction
take effect between jobs, without interrupting active transfers.

rsync exit code 24 ("some files vanished before they could be transferred") completes
the job with a warning, shown in the error view, instead of failing it. When a live
dataset is pre-copied, temporary files routinely disappear between discovery and
transfer, and one vanished file would otherwise mark a whole batch as failed. Other
partial-transfer codes, such as 23, still fail the job.

## Rsync options

```sh
java -jar rsync-airlift.jar --workers 4 \
  --rsync "-avH --partial --inplace --bwlimit=90000" \
  user@host:/srv/source/ /srv/destination/
```

The string is tokenized with single/double quoting and escapes, not evaluated by
a shell. No variable, command-substitution or wildcard expansion takes place in
`--rsync`. Supply the copy policy yourself: Airlift does not inject `-a`,
`--partial`, `--partial-dir`, `--inplace`, or bandwidth limits.

Airlift appends its relative-path, file-list, SSH transport and telemetry arguments,
including a filename output format and `--no-human-readable`. With the latter, `-h` in
`--rsync` cannot hide progress or `--stats` byte counts from the monitor; it only
changes rsync's own number formatting, which Airlift does not display.

Some options are reserved inside `--rsync` and rejected:
- `-e`/`--rsh`, including `-e` inside a short-option cluster such as `-ave ssh`;
- `--files-from`;
- options that would read Airlift's file list from standard input: `--exclude-from=-`,
  `--include-from=-`, and `--filter`/`-f` merge rules that read `-`;
- an option terminator `--`, and any argument that is not an option or an option's value,
  since it would become an extra rsync source.

Use Airlift's `--port`, `--identity`, `--ssh` and `--files` for those purposes. Write
option values as `--option=value` when in doubt. Other options are forwarded, not
comprehensively validated.

Extra OpenSSH client options go in `--ssh`, for example a faster cipher on a
CPU-bound link: `--ssh "-c aes128-gcm@openssh.com -o Compression=no"`. They apply to
every SSH command Airlift runs, after Airlift's own options; OpenSSH keeps the first
value of an option, so Airlift's transport settings always take precedence. Options
that would break the worker ControlMasters are rejected: `-M`, `-S`, `-O`, `-N`, `-f`,
`-n`, `-T`, `-t`, `-W`, `-w`, forwardings (`-L`, `-R`, `-D`), `-G`, `-Q`, `-V`, `-s`,
`-p` and `-i` (use `--port` and `--identity`), as well as `-o ControlMaster`,
`ControlPath`, `ControlPersist`, `RemoteCommand`, `RequestTTY`, `SessionType`,
`StdinNull`, `ForkAfterAuthentication` and forwarding or tunnel options.

`--bwlimit` is **per rsync process**, not global. `-H` can preserve hard links
within a job, but not across separate jobs. Symlink copying follows the options
you provide; discovery itself does not follow directory symlinks.
`--inplace` updates the destination directly and conflicts with `--partial-dir`.

## Terminal monitor

The default UI uses a real terminal, including on Windows. Use `--swing` explicitly
for a graphical terminal window. For SSH, allocate a PTY with `ssh -t`; no X11
forwarding is needed. Prefer `java -jar` or the installed launcher over Gradle
`:monitor:run`, which can redirect or interleave terminal I/O.

The monitor builds terminal-independent `MonitorFrame` text runs using its own
`MonitorColor` palette and `MonitorEvent` input types. `MonitorRenderer`, worker
layouts and selection logic do not import Lanterna. The JVM
`LanternaMonitorTerminal` adapter handles keyboard/mouse conversion, display-column
clipping, terminal resize and buffered COMPLETE/DELTA refreshes. Lanterna remains
the current backend for both console and optional Swing windows; this separation
prepares another backend without changing the transfer engine or wire protocol.

The monitor refreshes about every 200 ms and needs at least **64 × 16** characters.
Worker panels fill vertically before adding columns, are at most **88** characters
wide, and page when necessary. The overall bar has at most **100** interior cells.

| Input | Action |
| --- | --- |
| `1` / `2` / `3`, Tab | Workers / queue / errors |
| Up/Down, PgUp/PgDn, Home/End | Select/navigate entries |
| Left/Right | Move between worker columns |
| F2, Enter | Toggle details; displayed when height is at least 22 |
| `P` | Pause/resume assignment of new jobs |
| `+` / `=` | Increase concurrency by one (at most 256), or cancel one pending retirement |
| `-` | First dismiss one FAILED worker row; otherwise decrease concurrency, minimum one |
| Click worker/list row or tab | Select an entry or switch view |
| Mouse wheel over the list | Move selection by one entry |
| `Q` | Detach while running; after completion, exit an owned interactive run. An attached panel only detaches |

Dismissed FAILED rows are hidden in that panel session; their errors remain in
Errors. They can reappear after reattaching. STOPPED workers are hidden. A subdued
orange background on the last live rows indicates how many retirements are pending;
the first workers to finish actually retire. During a run, `Workers` shows the live
count; after completion it shows the last configured target.

Keys and mouse input apply immediately; the panel fetches a new snapshot about every
200 ms. The selection stays on the same job or worker when the list changes. Command
errors appear above the shortcut line and clear after the next successful command or
about 5 seconds.

Mouse reporting can prevent ordinary text selection. In terminals supporting it,
hold Shift while selecting to bypass application mouse capture. Fonts, palette,
zoom and padding belong to the terminal application. In Swing only, use
`AIRLIFT_FONT` and `AIRLIFT_FONT_SIZE` (12–40, default 18); the default font prefers
Consolas and falls back to installed monospace fonts.

### Progress and details

The overall bar reserves the full size of active jobs: green shows processed data
awaiting final statistics, and orange shows their remaining work. Queued work is
blank. Confirmed matched data stays cyan; confirmed new data is green and failed
jobs are red. The Matched/New counters include only confirmed statistics.

Errors (`3`) lists failed jobs, then SSH worker problems, then warnings of successful
jobs (for example rsync code 24, source files vanished) in yellow. Warnings are never
counted as failures: the summary adds `Warnings N`, DETAILS shows `Warning:`, and a run
with warnings but no failures ends as FINISHED WITH WARNINGS. A reconnecting worker's
message is shown as a warning.

A worker at 100% can still be waiting for rsync to finish. Its bar shows a quiet
`finishing` label and DETAILS shows `FINISHING`; ETA is blank. The task remains
running until the process exits, then becomes successful or displays its error.
This label indicates waiting for completion, not a detected internal rsync phase.

Worker size, rate and percentage refer to the whole job. While a running job has
zero reported bytes and no positive rate, a quiet `computing checksums` or
`checksums` label replaces the empty bar. This is a preparation indicator, not
confirmation of rsync's precise internal phase.

Details show the filename reported by rsync, job size and state, speed, attempt
and errors. Status, size, speed and attempt share a single compact line;
Matched/New statistics remain in the overall summary rather than DETAILS. Multi-file jobs also show the file count, current file's index
in the submitted list, and rsync's `sent` counter. The index is **not** a processed
count: rsync can reorder and skip files. `sent` counts transferred files, excluding
quick-check skips. Successful batches display `Processed N/N` instead of the last
reported index. Single-file details include the attempt number.

Overall statistics:

- **Processed** is logical work, not network traffic. The displayed byte count
  includes successful job sizes and reported progress of running jobs.
- **Matched** includes rsync's matched blocks and successfully skipped/inferred
  bytes that did not need to be sent. **New** is rsync's literal-data counter.
  These are not compressed wire-byte measurements or proof of a checksum audit.
  Filters and dry runs can also avoid transfer; interpret the figures according
  to your rsync options.
- Percentages beside Matched/New use the already processed byte count, not the
  full dataset. Their sum may be below 100% while final statistics are pending.
- The overall bar counts successful and finally failed jobs at full weight, so
  completed work with errors can still reach 100%. Colors: cyan matched, green
  new or processed work awaiting a final breakdown, red failed, yellow the
  remaining work of active jobs. Queued work stays blank. The colored extent
  therefore includes active reservations and may exceed the processed percentage.
  Without usable byte totals, progress falls back to job weights.
- Exact Matched/New statistics arrive at the end of each rsync attempt. Completed
  worker bars split by those statistics; live bars show ordinary logical progress.
- Live speed uses fresh rates; readings expire after three seconds. The previous
  complete aggregate is retained for up to three seconds during job handovers.
  ETA uses available rates when sizes are known, the queue is not paused, and no
  files have failed. Otherwise ETA is unknown.
- At completion, overall speed is recorded processed bytes divided by dispatcher
  elapsed time, and ETA disappears. A successful worker's final speed is its job
  size divided by processing time, including retries/backoff but excluding queue
  wait and initial SSH connection. Final worker ETA is blank.

Progress output is parsed on both CR and LF. Traditional rsync speed units use
1024 scaling. For newer progress records with a byte-count `B` suffix and
configurable scaling, byte progress is retained but live rate is left unknown.

## Background dispatch and attaching

`--serve` runs the dispatcher without a panel. It still belongs to the monitor
CLI, so unattended use requires `--yes` plus noninteractive SSH credentials:

```sh
nohup java -jar rsync-airlift.jar --serve --yes --workers 8 \
  --rsync "-av --partial" --endpoint "$HOME/airlift.connection" \
  user@host:/srv/source/ /srv/destination/ > airlift.log 2>&1 < /dev/null &

# In another terminal on the dispatcher machine:
java -jar rsync-airlift.jar --attach "$HOME/airlift.connection"
```

For an interactive launch that should survive SSH logout, use tmux or a service.
Without tmux, logout (SIGHUP) stops the dispatcher cleanly, like Ctrl+C or SIGTERM:
transfers are cancelled and the connection file is removed. A `nohup` launch keeps
ignoring SIGHUP. `Q` alone does not protect the hosting process from logout. The server remains
alive after transfer completion so results can be inspected; **Ctrl+C stops it**.
For an ordinary interactive run, Q after completion closes both the panel and its
service. Q during transfer only detaches; a separately attached panel never stops
the dispatcher.
The CLI is not currently a one-shot command that exits at the end of copying.

The monitor service binds to **127.0.0.1** on an ephemeral port. Its connection
file contains the port, a 256-bit token and endpoint descriptions. It is created
exclusively, without following links, and is readable only by its owner: mode 0600
on POSIX, and an ACL for the current user on Windows (best effort; file systems
without ACLs keep default permissions). Treat it as a credential and keep it out of Git. To attach remotely,
run the attach command through SSH on the dispatcher host, with a PTY.

Connection files are never overwritten. If one exists, attach to that dispatcher
or choose a different `--endpoint`. After an unclean termination, remove a stale
file only after confirming that its dispatcher is no longer running. Normal
shutdown removes the connection file. The protocol is version **4**; update
dispatcher and attached monitors together when the protocol changes.

If the dispatcher becomes unreachable, an attached panel keeps the last data, shows
`Connection lost, retrying...` and exits with the error after 10 seconds without a
successful refresh.

## Midnight Commander

MC shell-link paths are recognized automatically:
`/sh://user@host/home/data` becomes `user@host:/home/data`.
`--mc` is accepted but not required. Use `--port` for a nonstandard SSH port rather
than embedding it in the MC shell-link host. Directory names containing `[`, `?` or `*`
are used literally.

Add to `~/.config/mc/menu`, adjusting the absolute JAR path:

```text
A Rsync Airlift - synchronize panels
    java -jar /opt/rsync-airlift/rsync-airlift.jar --rsync "-av --partial" %d %D
```

`%d` is the **active panel** directory (source); `%D` is the **other panel** directory
(destination), not fixed left/right positions. Review FROM/TO before confirming.
See the [MC manual](https://source.midnight-commander.org/man/mc.html) for macro
substitution and user menus. Airlift makes its own SSH connections and does not
reuse MC's authenticated shell link. Ctrl+O hides/restores MC's panels to inspect
terminal output.

## Core library

`rsync-airlift-core.jar` is a library, without TUI dependencies or a standalone
entry point. It accepts prepared jobs and preserves their order; it does not
discover files, sort, create batches automatically, or ask confirmation/password
questions. The monitor CLI provides those preparation and interactive features.

`AirliftDispatcher`, its configuration, task states and snapshots live in
`commonMain`. The single dispatcher uses an owned `SupervisorJob`, a coroutine
`Mutex`, and suspend controls: `start()`, `snapshot()`, `pause()`, `resume()`,
`setWorkerCount()`, `awaitCompletion()` and `close()`. There is no JVM dispatcher
wrapper or `CompletableFuture` API. Paths in the shared models use
`kotlinx.io.files.Path`.

`SshSessionFactory` and the ControlMaster/rsync session live in `commonMain` too.
`TransferSessionFactory` remains injectable for alternative transports. SSH uses
`ProcessLauncher` rather than Java process APIs; authentication, file operations,
clocks and absolute-path normalization are supplied through platform interfaces.
Default implementations are selected by `expect`/`actual` factories.
No transfer or resource cleanup runs under the dispatcher's state mutex. Closing
cancels the run and unblocks sessions; `awaitCompletion()` waits for worker cleanup.

The process API consists of `ProcessSpec`, `ProcessLauncher`, `ChildProcess` and
suspend byte pipes. A specification carries literal arguments, environment
changes, working directory and pipe/file/inherited redirects. `awaitExit()`
suspends; its timed overload returns null on timeout without killing the child.
Owners always call `close()` to terminate/reap the child and release its pipes.
Cancelling pipe I/O aborts the child so a blocked OS read/write can be released.
Environment values are excluded from specification descriptions.

`JvmProcessLauncher` implements startup and blocking pipe I/O on its own elastic view of
`Dispatchers.IO` and waits through Java's `Process.onExit()`. A blocked pipe read holds a
thread until the child writes, so with many workers the 64-thread `Dispatchers.IO`
limit could park every thread in a read while the writes feeding those children
waited in the queue; the separate pool removes that deadlock. Its cleanup is idempotent, including
concurrent close calls. The common SSH session streams input and output
concurrently to avoid deadlocks on large file lists; it parses CR/LF records even
when UTF-8 characters span input chunks. It does not create threads or use Java
streams itself.

`SshAuthentication` also lives in `commonMain`: key/password selection, password
replacement and process-environment preparation share one synchronization lock.
All password reads and writes use that lock; no volatile field or JVM annotation
is needed. Only the environment lookup, synchronous lock implementation and
Java `ProcessBuilder` interop stay in JVM adapters. Password buffers are copied
on input and cleared on replacement/shutdown; child-process descriptions redact
environment values.

The monitor uses the same suspend API and shared models directly. `runBlocking`
is used only at JVM CLI/thread entry points, not in a dispatcher compatibility
layer. The command-line workflow and monitor wire protocol remain unchanged.

`DefaultSshFiles` is shared too: directory creation, diagnostic text reads and
buffered binary logs call `FileContent`/`kotlinx-io` directly, without Java stream
round-trips. `FileContent` serializes filesystem entry points with a shared
platform lock; independent streams do not hold that lock while transferring data.
Only atomic private temporary-directory creation and no-follow recursive cleanup
remain narrow platform operations. kotlinx-io 0.7.0 does not expose equivalent
primitives with those guarantees, so they are not replaced by a check-then-create
or a symlink-following walk. Cancellation during log/directory creation retains
ownership long enough to release the newly created resource.

File content I/O uses `kotlinx-io` 0.7.0 behind a small portable implementation.
The JVM adapters retain the existing `java.nio.file.Path` and stream APIs, atomic
temporary-file creation, owner-only connection-file creation, and inventory scans
without following symbolic links. The connection-file format is unchanged.
Both core and monitor use Kotlin Multiplatform source sets with JVM and opt-in Linux Native targets. Java process, socket
and terminal APIs are still used on JVM. The `kotlinx-io` filesystem API is experimental.

```kotlin
import iris.tools.airlift.*
import kotlinx.io.files.Path
import kotlinx.coroutines.runBlocking

val config = AirliftConfig(
    host = "user@source-server",
    remoteRoot = "/srv/source",
    localRoot = Path("/srv/destination"),
    workers = 8,
    maxAttempts = 2,
    rsyncOptions = "-av --partial",
)
val jobs = listOf(
    TransferTask("large.bin", size = 100_000_000),
    TransferTask(
        path = "small/a.txt",
        size = 2_000,
        files = listOf("small/a.txt", "small/b.txt"),
    ),
)

runBlocking {
    val dispatcher = AirliftDispatcher(config, jobs)
    try {
        dispatcher.start()
        val result = dispatcher.awaitCompletion()
        check(result.failed == 0 && !result.cancelled)
        // Another coroutine may call snapshot(), pause(), resume(), or setWorkerCount(4).
    } finally {
        dispatcher.close()
    }
}
```

Direction defaults to DOWNLOAD; UPLOAD reverses the configured roots. `path` and
`files` are relative POSIX paths without traversal; batch `size` is the total
source size. An empty `files` list means one file at `path`; `fileTotal` defaults to
the list size and lets a monitor copy of a job carry the count without the list.
A job that rsync finishes with code 24 succeeds with `TaskState.warning` set. A custom
`TransferSession` can implement `isConnected()` so that a job failed by a dropped
connection is requeued and the worker reconnects; the default reports a live
connection. Task IDs must be
unique (default: `path`); callers must avoid overlapping destinations, including
symlink aliases. `sourceRoot` can override a job's source root. `entries` can
provide a shortened file/directory list while `files` retains the accounting list;
the caller is responsible for establishing full directory coverage.

Closing the dispatcher cancels pending work and terminates its child processes.
To integrate a monitor, wrap it in `DispatcherMonitorSource`, expose
`MonitorServer(source, endpoint)`, and/or run `AirliftMonitor(source).run()` from a coroutine.
The library adapters do not own the dispatcher; close their resources separately.

### Multiplatform source layout

`core` applies Kotlin Multiplatform 2.1.21 with a JVM target and Java 21.
`monitor` also applies Kotlin Multiplatform. Both modules have an opt-in `linuxX64()` target (`-PenableLinuxNative=true`).

| Source set | Contents |
| --- | --- |
| `commonMain` | Dispatcher/models, SSH ControlMaster session, process/pipe interfaces, rsync command builder/parsers, file primitives |
| `jvmMain` | ProcessBuilder-based launcher, environment/lock bridges, filesystem/time/path adapters and actual factories |
| `commonTest` | Dispatcher lifecycle with virtual time and fake transports, model/parser tests |
| `jvmTest` | Parallel execution on JVM, blocking-session cancellation, filesystem and platform tests |

Monitor path parameters, including connection endpoints and file manifests, use
`kotlinx.io.files.Path`. NIO conversion is confined to JVM filesystem/process
boundaries (atomic private-file creation, no-follow inventory and redirection).
Endpoint path construction is shared; obtaining an absolute path uses a small
platform function and does not require the destination to exist.

File preparation and SSH preflight use suspend APIs. Preflight executes through
common process contracts, captures a bounded diagnostic tail without temporary
log files, and inherits terminal I/O only for interactive host-key confirmation.
That trust-only command bypasses sshpass and removes SSHPASS from its environment.

File preparation uses a suspend API. SSH inventory runs through the shared
`ProcessLauncher`/`ChildProcess` contracts; NUL/UTF-8 parsing and manifest reading
live in common code. Stderr is drained concurrently with a bounded diagnostic
tail, and cancellation closes/reaps the child. JVM supplies the process launcher,
I/O dispatcher and local no-follow inventory implementation.

The application workflow and demo transfer sessions live in common code.
Service lifetime uses coroutine completion/cancellation; the JVM entry point only
bridges main/runBlocking, stderr and process exit. JVM adapters provide the
socket service/client, shutdown-hook registration and temporary directories.

The monitor client/server and protocol-4 codec live in common code. The server
serves each connection in its own coroutine, at most 8 at a time, and closes further
connections immediately. A request (version, token, command) must arrive within
5 seconds and the response must complete within 30 seconds.
JVM adapters provide loopback sockets with connect/read timeouts and cancellation
cleanup; token generation and protected connection-file persistence are separate
platform functions. Protocol 4 keeps the data-stream framing and properties file
format but sends each batch's file count instead of its file list; protocol-3
monitors receive a clear "Monitor protocol changed" message.

Monitor source sets:

| Source set | Contents |
| --- | --- |
| `commonMain` | Monitor source contract, model, renderer, worker grid, progress/rate calculations, frame/color/event types, terminal loop, CLI parsing, file selection/batching/directory collapse, confirmation and SSH preflight policies |
| `jvmMain` | Lanterna adapter, native console/Swing setup, CLI entry point, socket service/client, process execution, filesystem inventory, console/Swing prompts and actual platform factories |
| `commonTest` | Portable formatting, progress labels, breakdown and rendering/input checks |
| `jvmTest` | Virtual-terminal rendering, transport, CLI and platform integration tests |

`monitor:jvmJar` and `monitor:jvmTest` build and test the JVM variant;
`:monitor:jar`, `:monitor:test`, `:monitor:run`, `:monitor:installDist` and
`:monitor:jarBundle` remain available. The Linux target provides a Native executable; the ANSI terminal backend has been verified on Linux with real SSH/rsync transfers.

The core JVM artifact is built by `:core:jvmJar`; tests run with `:core:jvmTest`.
`:core:jar` and `:core:test` remain compatibility aliases. The deployed filenames,
manifest classpaths and monitor connection protocol are unchanged. The Linux target is opt-in and provides POSIX process/filesystem/system adapters, loopback sockets and an ANSI terminal backend. JVM adapters remain separate.

## Logs, resuming and consistency

Each run creates a unique directory under `AirliftConfig.logDirectory` (CLI default:
`airlift-logs/` in the working directory). Each job attempt has one raw rsync log.
Logs are available on disk. Failed transfers show a bounded diagnostic from the
original rsync/SSH output in DETAILS, followed by the exit code. Full raw output
and log paths are not shown in the panel.

The queue and results are in memory: there is no persistent checkpoint or job-history
database. After an interruption, rerun the selection and let rsync reconcile the
destination. Partial-data reuse and destination replacement depend on the options
you supplied. Default rsync quick checks use size and mtime; this is not a checksum
audit or a guarantee of application consistency.

For databases, VM images and other live datasets, arrange a consistent snapshot
or a coordinated shutdown for the authoritative final pass. In particular,
pre-copying a running MyISAM database is not a consistent backup.

## Validation

Tests cover scheduling, batch accounting, directory-coverage decisions, SSH command
selection and prompt flows, virtual-terminal rendering, mouse decoding, controls,
and the localhost attach protocol. Real SSH/rsync transfer experiments are described
above; automated unit tests do not replace an integration test on your target hosts.

## Linux Native

**A standalone Linux tool, around 3 MiB. No JVM required.**

The optimized Linux x86-64 executable includes both the dispatcher and interactive
terminal monitor. Install it from our signed [APT repository](#install-with-apt),
or deploy the executable directly. `rsync` and OpenSSH remain external tools;
password authentication also requires `sshpass`.

Verified in practice:

- A real SSH/rsync run completed **982 files / 113.9 GiB with zero failures**.
- APT installation and demo startup succeeded on **Ubuntu 18.04 amd64**.
- The first optimized executable measured **3,155,768 bytes (3.01 MiB)**,
  compared with **8.25 MiB** for debug. Its Debian package is about **0.91 MiB**.
- GitHub Actions runs Linux Native tests, installs the package, checks startup
  and verifies the signed APT repository before publication. Checks requiring
  an interactive PTY are skipped in headless test runs.

Build the optimized Linux x64 executable:

```bash
./gradlew -PenableLinuxNative=true :monitor:linkReleaseExecutableLinuxX64
```

Output: `monitor/build/bin/linuxX64/releaseExecutable/rsync-airlift.kexe`.
Building requires a JDK; running the resulting executable does not.
Linux core process and system adapters are implemented and its Native test executable
can be linked. Linux monitor socket, console, inventory and application adapters are
implemented. A Native ANSI terminal backend supports demo, attach and interactive
transfers and has been verified on Linux with real SSH/rsync transfers. If the terminal
stops reading or disconnects, only the panel closes; transfers continue as a detached
service that `--attach` can reopen. See [Linux Native](docs/NATIVE.md)
for build, packaging and usage instructions. Default JVM builds remain available.
Compilation and linking work on Windows too; the resulting ELF runs on Linux x86-64.

Both modules' Native tests can be built and run on Linux:

```bash
./gradlew -PenableLinuxNative=true :core:linuxX64Test :monitor:linuxX64Test
```

The test executable can also be cross-linked on Windows with
`:core:linkDebugTestLinuxX64`, then copied to Linux and executed directly:
`core/build/bin/linuxX64/debugTest/test.kexe`. Native compilation and linking have
been checked; All 41 core Native tests passed on the Linux server; monitor Native service and TUI execution have also been verified on Linux.

`-PenableWindowsNative=true` exposes a `mingwX64()` target in both modules for
future Windows Native work. Only common metadata can currently be checked for
that target; Windows process/system/monitor actual implementations are not yet
provided. POSIX implementations are confined to `linuxX64Main`.

The Native terminal backend renders shared frames as ANSI deltas and uses POSIX
termios for raw input and restoration. It adds no ncurses dependency. Native
`--demo` and `--attach FILE` are available alongside headless `--serve` modes;
`--swing` remains JVM-only. See [Native usage](docs/NATIVE.md).
