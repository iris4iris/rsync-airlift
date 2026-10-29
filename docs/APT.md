# APT repository on GitHub Pages

The prepared workflow publishes a signed Debian/Ubuntu **amd64** repository at
`https://iris4iris.github.io/rsync-airlift/`. This address becomes usable only
after the first successful Pages deployment. It requires no personal GitHub token.

## One-time maintainer setup

1. Push the workflow, tools and documentation to the repository's default branch.
2. Open **Settings → Pages → Build and deployment → Source → GitHub Actions**.
3. Open **Settings → Environments** and create `apt-signing`. Restrict deployment
   branches to the default branch. If available, add a required reviewer so that
   the signing job needs approval. Only run publication from trusted code on that
   branch. Keep the `github-pages` environment restricted to that branch too.
4. Generate a dedicated signing key on your Linux machine (not your SSH key):

   ```bash
   sudo apt install gnupg
   gpg --quick-generate-key 'Rsync Airlift APT signing' rsa3072 sign 0
   gpg --list-secret-keys --keyid-format long --fingerprint
   ```

   Choose a strong passphrase in the GPG prompt. Copy the full fingerprint of this
   new key (40 hexadecimal characters, without spaces) for the following commands.
   `0` means no automatic expiration; maintain this key and rotate it deliberately.

   ```bash
   umask 077
   gpg --armor --export-secret-keys YOUR_FULL_FINGERPRINT > airlift-apt-private.asc
   gpg --export YOUR_FULL_FINGERPRINT > airlift-apt-public.gpg
   ```

5. In environment **apt-signing**, add:

   | Kind | Name | Value |
   | --- | --- | --- |
   | Secret | `APT_SIGNING_KEY` | Entire `airlift-apt-private.asc`, including BEGIN/END lines |
   | Secret | `APT_SIGNING_PASSPHRASE` | The GPG key passphrase |
   | Variable | `APT_SIGNING_FINGERPRINT` | Full fingerprint, without spaces |

   Keep an offline backup of the private key and passphrase. Do not commit or
   send them in chat. The workflow exports only the public key to the website.
   Remove the temporary private export after saving the backup and GitHub secret.
   Publish the fingerprint alongside installation instructions as an independent
   reference; users can inspect it with `gpg --show-keys --with-fingerprint`.

## Publish a version

Open **Actions → Publish APT repository → Run workflow**, select the default branch
and enter a Debian version such as `0.1.0`. Use a higher version for each update
(`0.1.1`, `0.1.2`, ...); do not overwrite an already published version with changed
contents. `0.1.0~snapshot` sorts before `0.1.0`.

The workflow validates the committed Gradle wrapper JAR, builds the selected branch,
runs core/monitor Linux Native tests, builds the optimized executable, creates and
installs the `.deb`, and checks `--help`. It uploads the binary, `.deb` and SHA256SUMS as `native-release` artifacts.
Then it builds indexes, signs `InRelease` and `Release.gpg`, verifies both signatures
and checks `apt-get update` against the repository before deploying Pages.
Headless terminal tests skip checks requiring an interactive PTY.

The first deployment also creates/configures the `github-pages` environment as
needed by GitHub. If deployment is blocked, check its branch protection rules and
that Pages uses GitHub Actions as its source.

Every action in the workflow is pinned to a full commit SHA, with its release tag
in a comment. To update an action, resolve the new tag with
`git ls-remote https://github.com/OWNER/REPO refs/tags/TAG` (use the `^{}` line
for annotated tags) and change the SHA and the comment together.

`Release` deliberately has no `Valid-Until` field. Publication is manual, so an
expiry date would make `apt update` fail for every user whenever no release
happened within the validity window. The trade-off is that APT cannot detect a
replayed, outdated but correctly signed index.

This first implementation publishes the packages from the current workflow run;
it does not retain older package versions on Pages. Existing installations can
upgrade normally. Store/download workflow artifacts if older packages are needed.
It does not create a GitHub Release or a Git tag. It replaces the Pages site at
this repository URL, so do not use the same site for an unrelated website.

## User installation (after first publication)

```bash
sudo apt update
sudo apt install ca-certificates curl
sudo install -d -m 755 /etc/apt/keyrings
curl -fsSLo /tmp/rsync-airlift-archive-keyring.gpg \
  https://iris4iris.github.io/rsync-airlift/rsync-airlift-archive-keyring.gpg
# Optional inspection: compare with the maintainer's published fingerprint.
# gpg --show-keys --with-fingerprint /tmp/rsync-airlift-archive-keyring.gpg
sudo install -m 644 /tmp/rsync-airlift-archive-keyring.gpg \
  /etc/apt/keyrings/rsync-airlift.gpg
echo 'deb [arch=amd64 signed-by=/etc/apt/keyrings/rsync-airlift.gpg] https://iris4iris.github.io/rsync-airlift/ stable main' \
  | sudo tee /etc/apt/sources.list.d/rsync-airlift.list
sudo apt update
sudo apt install rsync-airlift
rsync-airlift --demo
```

For SSH password authentication also install `sshpass`; key authentication does
not require it. Java is not required. Subsequent updates use normal `apt upgrade`.
Never use `trusted=yes`, `apt-key`, or disable signature checking.

## Local repository generation on Linux

```bash
sudo apt install apt-utils gnupg
python tools/build_apt_repository.py \
  --packages monitor/build/native-release \
  --output build/apt-public \
  --sign-key YOUR_FULL_FINGERPRINT \
  --passphrase-file /path/to/private-passphrase-file
```

Use an empty output directory. The passphrase file must be private (`chmod 600`);
the GPG private key must already be imported. Outputs include `pool/`,
`dists/stable/`, signed indexes and the public keyring. Do not place private key
exports or passphrase files inside the output directory.

References: [GitHub Pages workflows](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages),
[Debian repository format](https://wiki.debian.org/DebianRepository/Format),
[APT Signed-By](https://manpages.debian.org/testing/apt/sources.list.5.en.html).
