# Installing the `insight` CLI — guide for AI agents

A non-interactive, copy-pasteable recipe for an **AI coding agent** (or any
automation) to install or upgrade the `insight` CLI from the latest GitHub
Release. For the human-oriented walkthrough (browser download, per-OS detail,
build-from-source) see [`install-cli.md`](install-cli.md); for day-to-day usage
see [`cli/README.md`](../cli/README.md).

The CLI is a single GraalVM native binary published per-OS as a GitHub Release
asset. This guide assumes the [`gh`](https://cli.github.com/) CLI is available
and authenticated (`gh auth status`). A `curl`-only fallback is included.

> **Sandbox caveat:** some agent runtimes block executing freshly-downloaded
> native binaries (the same restriction that often blocks `curl`/`node`). If
> `insight --version` returns *"Permission denied"* inside the agent sandbox,
> the install is still valid — verify with `file` / `codesign` (below) and run
> the binary from a normal user terminal.

---

## 0. Repo / asset facts

- Repo: `ebean-orm/ebean-insight-server`
- The latest stable release is tagged `vX.Y[-RCn]` and marked **Latest** (RCs
  are published as full releases, not GitHub "pre-releases", so `latest`
  resolves to them).
- Release assets (one zip per platform) + a `SHA256SUMS` covering the CLI zips:

  | Platform (`uname -s`/`-m`) | Asset zip | Binary inside |
  |---|---|---|
  | `Darwin` / `arm64` | `insight-macos-arm64-<tag>.zip` | `insight` |
  | `Linux` / `x86_64` | `insight-linux-x64-<tag>.zip` | `insight` |
  | Windows / x64 | `insight-windows-x64-<tag>.zip` | `insight.exe` |

  Not in the matrix: macOS Intel, Linux arm64 — build from source
  ([`install-cli.md`](install-cli.md#build-from-source)).

---

## 1. Decide whether an install/upgrade is needed (idempotent)

```bash
REPO=ebean-orm/ebean-insight-server
LATEST=$(gh release view -R "$REPO" --json tagName -q .tagName)   # e.g. v2.0-RC12

# Installed version, if any. The banner is "ebean-insight-cli <tag> ...".
CURRENT=$(insight --version 2>/dev/null | awk 'NR==1{print $2}') || true

echo "installed=${CURRENT:-none} latest=$LATEST"
[ "$CURRENT" = "${LATEST#v}" ] || [ "$CURRENT" = "$LATEST" ] \
  && echo "up to date — nothing to do" \
  || echo "install/upgrade needed"
```

If the binary can't be executed in your sandbox, fall back to comparing the
release publish date against the installed file's mtime, or just reinstall
(the steps below are safe to repeat).

---

## 2. Detect platform → asset name

```bash
case "$(uname -s)/$(uname -m)" in
  Darwin/arm64)        ASSET="insight-macos-arm64-${LATEST}.zip" ;;
  Linux/x86_64|Linux/amd64) ASSET="insight-linux-x64-${LATEST}.zip" ;;
  *) echo "no prebuilt binary for $(uname -s)/$(uname -m) — build from source" >&2; exit 1 ;;
esac
echo "asset=$ASSET"
```

---

## 3. Download + verify checksum

```bash
TMP=$(mktemp -d); cd "$TMP"

# gh resolves the latest release; download the platform zip + checksums
gh release download "$LATEST" -R "$REPO" --pattern "$ASSET" --pattern SHA256SUMS

# Verify (must print "<asset>: OK")
grep " $ASSET\$" SHA256SUMS | shasum -a 256 -c -
```

`curl`-only fallback (no `gh`):

```bash
BASE="https://github.com/$REPO/releases/download/$LATEST"
curl -fL -o "$ASSET" "$BASE/$ASSET"
curl -fL -o SHA256SUMS "$BASE/SHA256SUMS"
grep " $ASSET\$" SHA256SUMS | shasum -a 256 -c -
```

> Never install if the checksum line does not print `OK`.

---

## 4. Unzip, de-quarantine, install onto PATH

Prefer a **no-sudo** install into `~/.local/bin` (create it and ensure it's on
`PATH`). The zip contains a top-level folder `insight-<platform>-<tag>/`.

```bash
unzip -oq "$ASSET"
BIN=$(find . -type f -name insight -perm -u+x | head -1)   # the extracted binary

chmod +x "$BIN"
# macOS only: strip the Gatekeeper quarantine flag (no-op elsewhere)
xattr -d com.apple.quarantine "$BIN" 2>/dev/null || true

mkdir -p "$HOME/.local/bin"
# Back up any existing install so you can roll back
[ -f "$HOME/.local/bin/insight" ] && cp "$HOME/.local/bin/insight" "$HOME/.local/bin/insight.bak-$CURRENT"
mv "$BIN" "$HOME/.local/bin/insight"

cd - >/dev/null && rm -rf "$TMP"
```

Ensure `~/.local/bin` is on `PATH` (add to the shell profile if `which insight`
finds nothing):

```bash
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *)
  echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc ;;   # or ~/.bashrc
esac
```

A system-wide alternative is `sudo mv "$BIN" /usr/local/bin/insight`.

---

## 5. Verify the install

```bash
# Preferred: run it (prints version, commit, build time)
insight --version

# Sandbox fallback when execution is blocked — confirm it's a valid binary:
file "$HOME/.local/bin/insight"          # -> Mach-O 64-bit executable arm64 (macOS)
xattr "$HOME/.local/bin/insight"         # -> (no com.apple.quarantine)
codesign -dv "$HOME/.local/bin/insight" 2>&1 | head -2   # macOS, ad-hoc signed
```

Expected `--version` output:

```
ebean-insight-cli <tag>
commit: <git-sha>
built: <build-time>
```

---

## 6. First-run configuration (optional)

Point the CLI at a server once and every command picks it up
(see [`cli/README.md`](../cli/README.md) for the full reference).

**Static URL + OAuth2 (recommended):**

```bash
insight config set url            https://<insight-host>
insight config set auth-client-id <public-app-client-id>
insight config set auth-domain    https://<pool>.auth.<region>.amazoncognito.com
insight config set auth-scope     openid
insight login                     # opens browser; caches a bearer token
insight envs                      # smoke test
```

**Kubernetes port-forward (cluster-internal servers):**

```bash
insight config set namespace <namespace>
insight config set service   <service>
insight config set context   <kube-context>   # optional
insight envs                                   # smoke test
```

**Multiple targets (profiles):** if you need to switch between environments
(e.g. prod vs test), create named profiles so each target keeps its own
settings and token:

```bash
insight config set --profile prod url            https://prod.example.com
insight config set --profile prod auth-client-id <prod-client-id>
insight config set --profile prod auth-domain    https://prod.auth.<region>.amazoncognito.com
insight config set --profile prod auth-scope     openid

insight config use prod
insight login          # caches ~/.insight/token-prod.json

insight config use test && insight login   # repeat for each profile
```

Use `insight config profiles` to list available profiles and
`insight config use --none` to go back to the base config.

For the EROAD deployment specifics (the server runs only in `dev-core`,
filtering by `--env dev|test`, the `forward` daemon, capture timing) follow the
internal `insight-cli` skill / runbook rather than this generic guide.

---

## Rollback

```bash
mv "$HOME/.local/bin/insight.bak-$CURRENT" "$HOME/.local/bin/insight"
```

## Upgrading later

Re-run this guide from step 1 — it is idempotent and backs up the previous
binary on each install.
