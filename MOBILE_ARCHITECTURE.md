# Weyland Tavern Mobile (BapOS) — Architecture & Handoff Doc

This explains what the Android app actually is, how it works under the hood, and — most importantly for the backend/content team — **what changes on your side of the fence when a user is on mobile instead of desktop.**

If you've never seen this project before: this is a real native Android app that installs and runs Weyland Tavern **entirely on-device**, with no Termux, no manual terminal commands, and no second app. Download → tap Install → tap Launch → it opens in the phone's browser. That's the whole user-facing story.

---

## 1. The one-sentence version

**It's SillyTavern running as a real local web server on the phone, launched from inside a native Android launcher app ("BapOS") that also handles install/update/repair — the phone's browser is the actual UI, same as desktop.**

---

## 2. Why this was hard (skip if you don't care about history)

The obvious approach — embed Node via `nodejs-mobile` (a JNI wrapper around a custom-built libnode.so) — is what this project spent months on and eventually abandoned. That build was compiled with `--with-intl=none` (no ICU), which meant V8 couldn't parse Unicode property-escape regexes (`\p{Cc}`, etc.) — and SillyTavern's own module loader uses exactly that pattern during ESM parsing. Every attempt to boot crashed inside Node's own internals before a single line of our code ran, with no useful error surfaced (stdout was being swallowed too, so even the crash reason was invisible for a while).

**The fix that actually worked:** stop embedding a custom Node build entirely. Instead, ship a **real, full-ICU Node 24 LTS executable** (sourced from Termux's official prebuilt aarch64 package — the same trusted binary that already runs when a user does the manual Termux route today) and run it as a genuine child process via Android's `ProcessBuilder`. No JNI, no embedded V8, no custom Node compile.

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────────┐
│  MainActivity  (UI process)                              │
│  - WebView showing bapos.html (the "BapOS" UI shell)      │
│  - JS ↔ Kotlin bridge (WeyTavBridge)                       │
│  - Handles: install, update, repair, launch/stop, logs     │
└───────────────────────────┬───────────────────────────────┘
                             │ starts (separate process)
                             ▼
┌─────────────────────────────────────────────────────────┐
│  NodeService  (":node" process — foreground service)      │
│  - ProcessBuilder execs libnode_bin.so directly            │
│  - stdout/stderr piped straight to a log file              │
│  - Watchdog thread logs the exit code if Node dies          │
└───────────────────────────┬───────────────────────────────┘
                             │ runs
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Real Node.js 24 (Termux-built, full ICU)                  │
│  → bootstrap.js → SillyTavern's server.js                  │
│  → listens on 127.0.0.1:8000                                │
└───────────────────────────┬───────────────────────────────┘
                             │ opened by
                             ▼
                  Phone's default browser
                  (same UI users get on desktop)
```

### The trick that makes this legal on modern Android

Android forbids executing binaries from most app-writable directories ("W^X" — write XOR execute), *except* one: `applicationInfo.nativeLibraryDir`. That's the directory Android's installer extracts `jniLibs/` into, and it's the one place the OS guarantees is both installed-and-verified *and* executable on every version since API 29.

So the Node executable is packaged as a fake native library — literally named `libnode_bin.so` and dropped in `jniLibs/arm64-v8a/` alongside its real shared-library dependencies (ICU, OpenSSL, zlib, c-ares, sqlite3, libc++). Android's installer treats it exactly like any other `.so`, extracts it to that executable directory, and `NodeService` just runs it with `ProcessBuilder`. Android has no idea — and doesn't care — that it's a whole language runtime rather than a JNI library.

### Where the runtime actually comes from

`tools/fetch_runtime.py` (in the repo) downloads the official Termux `.deb` packages for `nodejs-lts` and its full dependency chain directly from `packages.termux.dev`, unpacks them, and does one nontrivial fix: Termux's binaries reference **versioned** shared library names (`libssl.so.3`, `libicuuc.so.78`) via both the ELF `DT_NEEDED` table *and* the symbol-versioning (`verneed`) table — but Android's `jniLibs` packaging only accepts files named exactly `lib*.so`. The script uses `LIEF` to rewrite every reference to the unversioned name so the linker resolves correctly at runtime. This script is the reproducible source of truth for the runtime — re-run it to bump Node versions.

**Nobody on a user's phone ever touches Termux, sees Termux, or installs Termux.** It's purely where we source the binary at build time, on our own machine.

---

## 4. What ships inside the APK

| Component | Size (approx) | Purpose |
|---|---|---|
| `libnode_bin.so` + runtime libs (ICU, OpenSSL, etc.) | ~88 MB | The actual Node.js runtime |
| `assets/node_modules.zip` | ~98 MB | SillyTavern's `node_modules`, pre-resolved at build time |
| `assets/bapos.html` | ~55 KB | The entire launcher UI (single-file HTML/CSS/JS) |
| `assets/bootstrap.js` | ~4 KB | Boot shim that runs before `server.js` |
| `assets/cacert.pem` | ~185 KB | CA bundle for outbound HTTPS |
| Kotlin app code | small | MainActivity, NodeService |

**SillyTavern itself is *not* bundled** — it's downloaded fresh from GitHub (`Shirubaurufu/WeylandTavern`, `release` branch, ~700 MB) on first install, same source as desktop. Only its `node_modules` dependency tree is pre-bundled in the APK, because `npm install` isn't a realistic thing to run on a phone.

**⚠️ This is the single most important thing for the backend/content team to internalize:** *content* changes (characters, prompts, UI, most `src/` code) pushed to the `release` branch reach mobile users automatically via the in-app Update button, same as desktop. But **any change to `package.json` / dependencies does not** — `node_modules.zip` is frozen at whatever it was when the APK was built. A new dependency, a version bump, anything `npm install`-shaped requires **a new APK release**, not just a git push. There's currently no tooling to detect this mismatch automatically — if you add a dependency and don't tell mobile, the app will silently run against stale `node_modules` until someone rebuilds the APK.

---

## 5. The install / update / repair flows

All three share one Kotlin function (`performInstall`) with different flags:

- **Install** (first run): download the release-branch zip, extract it, extract the bundled `node_modules.zip`, save the current commit SHA.
- **Update**: same, but skips re-extracting `node_modules` unless forced. Just overlays new files on top of old ones — nothing gets deleted.
- **Repair**: the one that matters most for support load. Unlike Update, this does a **true clean-slate reset** — deletes everything under `SillyTavern/` *except* `data/` (chats, characters, personas, settings), then re-extracts fresh. This is the mobile equivalent of the desktop repair tool's `git reset --hard`, since a zip-overlay alone can't remove stray/corrupted files. It also:
  - Takes an automatic backup of `data/` before touching anything (defense in depth, not user-restorable via UI — just a safety net)
  - Verifies `server.js` exists and `node_modules` isn't suspiciously empty afterward
  - Optionally re-downloads all installed characters fresh via the same `/api/weyland/*` endpoints the desktop repair tool uses (spins up a real temporary server on loopback to do this)
  - Is gated: the button is disabled until the app has confirmed the server has successfully launched at least once, so it can't be misused as a confused substitute for "install"

Update-availability detection polls the GitHub commits API for the `release` branch and compares SHAs — same mechanism as desktop's git-based check, just via REST instead of `git fetch`.

---

## 6. Security posture (things a security-minded backend person would ask about)

- **Cleartext HTTP is restricted to loopback only** (`127.0.0.1`/`localhost`) via Android's network security config — everything else the app talks to (GitHub, update checks) is HTTPS-only by policy, not just by convention.
- **The WebView cannot navigate anywhere except the bundled `bapos.html` asset.** A custom `WebViewClient` blocks any URL that isn't `file:///android_asset/...`, and `allowFileAccess` is off. The JS↔Kotlin bridge (which can install/delete/read logs) is not reachable from arbitrary web content.
- **`android:allowBackup="false"`** — chat data can't be pulled off the device via `adb backup`.
- **AGPL-3.0 compliance is built in.** Weyland Tavern is a modified SillyTavern fork, and AGPL's network-use clause means anyone the app serves over a network (even loopback-to-browser) is entitled to the corresponding source. The app has an About screen linking to the Tavern repo, upstream SillyTavern, the license text itself, and third-party licenses for the bundled runtime (Node, ICU, OpenSSL). *This app's own source repo link is currently a stub pending the repo actually being pushed public.*
- **Release builds are properly signed** with a dedicated keystore (not the shared Android debug key). ⚠️ If that keystore or its password is ever lost, the app can never be updated under its current identity again — everyone would need to uninstall and reinstall fresh. It is *not* stored in git.

---

## 7. Advantages

- **Zero technical barrier.** Download → Install → Open → Launch. No Termux, no terminal, no second app, no copy-pasted shell commands. This was the entire point — the old flow was losing users who saw "install a terminal emulator" and left for Character.AI instead.
- **Real Node, not a compromise.** Full ICU, current LTS version, actively-maintained upstream binary. Not a custom fork nobody else uses.
- **Same server code as desktop.** SillyTavern itself isn't forked or modified for mobile — it's the identical `release` branch. Feature parity is automatic for anything that isn't platform-specific.
- **Self-service repair.** Users experiencing a broken update can fix it themselves from Settings without filing a support ticket — same safety guarantees as the desktop repair tool (chats/characters untouched), now with backup + verification + character re-fetch built in.
- **Debuggability was a deliberate priority.** Every layer — install, boot, TLS, the Node process itself — logs to a single file readable and copyable from within the app. If a server crashes, the exact stack trace is one tap away, not lost to a silently-swallowed stdout stream (which was a real problem in the abandoned nodejs-mobile approach).

## 8. Disadvantages / limitations

- **arm64-v8a only.** No x86_64 build currently, so it won't run on an Android emulator or (extremely rare) x86 Android devices. Covers effectively every real phone from ~2016 onward, but testing has to happen on real hardware.
- **`node_modules` is frozen per APK build**, as covered above — dependency changes need a new release, not just a git push.
- **First install is heavy**: ~700 MB download (SillyTavern) + unpacking the bundled ~98 MB `node_modules.zip`. Wi-Fi is strongly recommended and the app says so.
- **Android can still kill the background service** despite the foreground-service + battery-exemption request, especially on aggressive OEM battery managers (Samsung, Xiaomi, etc.). The app requests the exemption but can't force it — some users will need to grant it manually if they notice random disconnects.
- **One irreversible transition already happened**: moving from the debug-signed alpha to a properly release-signed build forces one uninstall (and one chat-data wipe) for anyone who installed the debug build first. This is a one-time Android platform limitation, not a bug — but it needs to be communicated clearly whenever it happens for real users.
- **Update checks depend on GitHub's anonymous API rate limit.** If it's exhausted, update detection silently no-ops until the next successful check — not a hard failure, but something to know about if "the update button isn't showing up" ever comes up in support.
- **Repair's file-level reset is bluntly scoped**: it deletes *everything* under `SillyTavern/` except `data/`, with no concept of "which files are actually ours." Desktop's `git reset --hard` only resets tracked files and leaves anything a user manually dropped in alone; mobile's repair has no such nuance. Not expected to matter in practice (nothing but the app's own files should live there), but worth knowing.
- **No Play Store presence (yet).** This is a sideloaded APK. That's fine for the current alpha/testing phase and is explicitly allowed by Play policy if it's ever pursued (the app doesn't download or execute new code at runtime — everything's bundled at build time — which is the key requirement), but it's not there today.

---

## 9. Feature checklist (what's actually built)

- ✅ One-APK install with a full boot-theater / install-theater UI ("BapOS", cat-demon mascot "Bap")
- ✅ First-run orientation screen (Kressa-voiced) explaining what the app does before installing anything
- ✅ Launch / Stop server, with a persistent foreground notification while running
- ✅ Auto-detect and offer in-app updates (GitHub SHA polling)
- ✅ Self-service Repair (backup → clean reset → reinstall deps → verify → optional character re-fetch)
- ✅ Reinstall (full wipe) and Delete Install, both behind explicit confirmation
- ✅ In-app log viewer + one-tap log copy for support triage
- ✅ Battery-optimization exemption request flow
- ✅ Settings: sound toggle, version display, About/licenses screen
- ✅ AGPL-3.0 compliant open-source disclosure screen
- ✅ Loopback-restricted cleartext policy, locked-down WebView, no-backup flag
- ✅ Properly signed release build pipeline (separate from debug alpha)
- 🚧 App's own source repo not yet public (link is a stub)
- 🚧 No automated way to detect "node_modules.zip is stale relative to package.json" — currently a manual process

---

## 10. What backend/content team members specifically need to know

1. **Pushing to the `release` branch reaches mobile users automatically**, same as desktop — no mobile-specific deploy step for ordinary content/code changes.
2. **Dependency changes (`package.json`) do not reach mobile automatically.** Flag any dependency change so the mobile APK can be rebuilt with a refreshed `node_modules.zip`. This is the one place desktop and mobile genuinely diverge in the deploy story.
3. **Anything that assumes a real OS shell/terminal is present will break on mobile.** We already found and fixed one instance of this: SillyTavern's post-boot browser auto-launch calls `xdg-open`, which doesn't exist in Android's app sandbox — it was silently killing the server on every boot until `bootstrap.js` was told to pass `--browserLaunchEnabled false` and let BapOS handle opening the browser itself instead. If you add anything else that shells out to a platform-specific command, it's worth asking whether mobile needs a similar override.
4. **The mobile app talks to the same `/api/weyland/*` endpoints** desktop's repair tool uses (character re-download, manifest rebuild) — no new backend surface was needed for mobile-specific features.
5. **Support requests will come with a copy-pasted log file**, not a screen recording of a terminal. The log format is whatever Node/SillyTavern prints to stdout/stderr plus a handful of `[bootstrap]`/`[SERVICE]` prefixed diagnostic lines — nothing backend-specific to learn there.

---

*Questions about anything in this doc — ping whoever's been driving the mobile build. Everything above reflects the actual current state of the code, not a plan or aspiration.*
