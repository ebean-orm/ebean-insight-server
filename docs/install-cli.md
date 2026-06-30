# Installing the `insight` CLI

The `insight` CLI is a small GraalVM native binary that talks to the
[ebean-insight-server](install-server.md) `/v1` API — list apps and
environments, browse captured DB query plans, and request fresh plan captures.

For day-to-day usage (commands, flags, daemon mode, config persistence,
authentication, JSON output) see [`cli/README.md`](../cli/README.md).

> **Automating this for an AI agent?** See the non-interactive, idempotent
> install/upgrade recipe in
> [`install-cli-agents.md`](install-cli-agents.md).

---

## Where to get a binary

Per-OS native binaries are published as **GitHub Release assets** when a `v*`
tag is pushed (recommended), and as **workflow artifacts** for every run of
the [CLI native binaries](https://github.com/ebean-orm/ebean-insight-server/actions/workflows/cli-native.yml)
workflow (useful for unreleased commits).

| Asset name (in Release zip) | OS / arch | Binary inside the zip |
|---|---|---|
| `insight-linux-x64-v<version>.zip` | Linux x86\_64 | `insight` |
| `insight-macos-arm64-v<version>.zip` | macOS Apple Silicon | `insight` |
| `insight-windows-x64-v<version>.zip` | Windows x86\_64 | `insight.exe` |

> Not in the matrix today: macOS Intel (x86\_64), Linux arm64. Use
> [build from source](#build-from-source) for those targets.

### Download from the latest GitHub Release (recommended)

```bash
# macOS (Apple Silicon)
curl -L -o insight.zip \
  https://github.com/ebean-orm/ebean-insight-server/releases/latest/download/insight-macos-arm64-<version>.zip

# Linux (x86_64)
curl -L -o insight.zip \
  https://github.com/ebean-orm/ebean-insight-server/releases/latest/download/insight-linux-x64-<version>.zip

# Windows (PowerShell)
Invoke-WebRequest -OutFile insight.zip `
  https://github.com/ebean-orm/ebean-insight-server/releases/latest/download/insight-windows-x64-<version>.zip
```

Replace `<version>` with the tag (e.g. `v2.0`). You can browse all releases at
[Releases](https://github.com/ebean-orm/ebean-insight-server/releases).

### Verify checksums (recommended)

Each Release ships a `SHA256SUMS` file covering every CLI archive:

```bash
curl -LO https://github.com/ebean-orm/ebean-insight-server/releases/latest/download/SHA256SUMS
shasum -a 256 -c SHA256SUMS --ignore-missing
```

### Download via `gh` CLI

```bash
# Latest release zip for your platform
gh release download -R ebean-orm/ebean-insight-server \
  --pattern 'insight-macos-arm64-*.zip'
```

### Workflow artifacts (unreleased commits)

For an unreleased commit, the
[workflow runs](https://github.com/ebean-orm/ebean-insight-server/actions/workflows/cli-native.yml)
produce zipped artifacts named after each platform. Download via the GitHub
UI **Artifacts** section, or:

```bash
RUN_ID=$(gh run list -R ebean-orm/ebean-insight-server \
  -w cli-native.yml -s success --limit 1 --json databaseId -q '.[0].databaseId')

gh run download "$RUN_ID" -R ebean-orm/ebean-insight-server -n insight-macos-arm64
```

---

## macOS (Apple Silicon)

```bash
# 1. Download (see above) and unzip
unzip insight-macos-arm64.zip          # produces ./insight

# 2. Mark executable and remove the Gatekeeper quarantine flag
chmod +x insight
xattr -d com.apple.quarantine insight 2>/dev/null || true

# 3. Move onto your PATH
sudo mv insight /usr/local/bin/
# (no-sudo alternative: mv insight ~/.local/bin/ — ensure ~/.local/bin is on your PATH)

# 4. Verify
insight --version
```

Expected:

```
ebean-insight-cli <version>
commit: <git-sha>
built: <build-time>
```

> **macOS Intel (x86\_64):** not in the current build matrix.
> Build from source ([below](#build-from-source)) on an Intel Mac.
> Apple-Silicon binaries will not run under Rosetta (native-image targets
> the host arch).

---

## Linux (x86\_64)

```bash
unzip insight-linux-x64.zip
chmod +x insight
sudo mv insight /usr/local/bin/
insight --version
```

No-sudo alternative: `mv insight ~/.local/bin/`. If `~/.local/bin` isn't on your
`PATH` (common if the directory didn't already exist — check with
`which insight`), add it to your shell profile and reload:

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
exec $SHELL -l
```

Or install system-wide with `sudo mv insight /usr/local/bin/` instead.

> **Linux arm64:** not in the current build matrix; build from source.

---

## Windows (x86\_64)

```powershell
# 1. Download insight-windows-x64.zip from the workflow run, unzip to get insight.exe.

# 2. Move it to a directory on your PATH, e.g. %USERPROFILE%\bin
mkdir $env:USERPROFILE\bin -Force | Out-Null
Move-Item insight.exe $env:USERPROFILE\bin\

# 3. Add it to PATH for the current user (one-time)
$current = [Environment]::GetEnvironmentVariable("Path", "User")
[Environment]::SetEnvironmentVariable("Path", "$current;$env:USERPROFILE\bin", "User")

# 4. Open a new terminal and verify
insight --version
```

WSL2 users can also use the [Linux x86\_64 binary](#linux-x86_64).

---

## Build from source

For unsupported OS/arch combinations or local development. Requires a
**GraalVM JDK** on `JAVA_HOME`.

```bash
sdk install java 24-graal      # if using SDKMAN
sdk use java 24-graal

git clone https://github.com/ebean-orm/ebean-insight-server.git
cd ebean-insight-server
mvn -pl cli -am -Pnative -DskipTests package
./cli/target/insight --version
```

See [`cli/README.md`](../cli/README.md#running) for the JVM-mode runner that
skips the native build during development.

---

## First-run setup

Once `insight --version` works, point the CLI at your server.

### Via a static URL + OAuth2 login (recommended)

The `insight setup` command fetches the server's auth configuration automatically
so you only need to provide the URL:

```bash
insight setup https://<insight-host>   # sets url, fetches auth config, opens browser login
insight envs                           # smoke test
```

Or configure manually if your server doesn't expose `/api/cli-config`:

```bash
insight config set url       https://<insight-host>
insight config set auth-client-id  <public-app-client-id>
insight config set auth-domain     https://<pool>.auth.<region>.amazoncognito.com
insight config set auth-scope      openid
insight login                # opens browser; caches a bearer token
insight envs                 # smoke test
```

To set up multiple targets as profiles (e.g. prod and test):

```bash
insight setup https://insight-prod.example.com --profile prod
insight setup https://insight-test.example.com --profile test
insight config use prod           # activate prod
insight envs --profile test       # one-off against test without switching
```

`insight login` (run by `setup`) uses the OAuth2 Authorization-Code + PKCE flow and caches a
bearer token in `~/.insight/token.json`.  Subsequent commands load that token
and silently refresh it when it expires.  Re-run `insight login` if you are
ever prompted again.

See the [Authentication](../cli/README.md#authentication) section of the CLI
README for the full auth option reference.

### Via Kubernetes port-forward

If the server is only reachable inside the cluster (no Ingress), the CLI can
open a `kubectl port-forward` for you.  Persist your cluster target once:

```bash
insight config set namespace ebean-insight
insight config set service ebean-insight
insight config set context my-cluster      # optional
insight envs                                # smoke test
```

This requires the RBAC verbs documented in
[`install-server.md`](install-server.md#rbac-for-cli-users-port-forward-auth)
(`pods/portforward` in the target namespace).

For both modes, see [`cli/README.md`](../cli/README.md) for the full command
reference, daemon mode (`insight forward`), JSON output, and config
precedence rules.

---

## Upgrading

Download the new Release zip (or workflow artifact for unreleased commits)
and overwrite the binary on your `PATH`. `insight --version` shows the
installed version and git commit so you can confirm the upgrade.

---

## Uninstall

```bash
# macOS / Linux
sudo rm /usr/local/bin/insight   # or, if installed there: rm ~/.local/bin/insight
rm -rf ~/.insight                # removes persisted config + daemon advert

# Windows
Remove-Item $env:USERPROFILE\bin\insight.exe
Remove-Item -Recurse -Force $env:USERPROFILE\.insight
```
