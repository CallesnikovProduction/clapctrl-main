# Verification record

## 0.1.2 calibration activation and action feedback ·2026-10-16

**Overall: PARTIALLY VERIFIED.** The activation workflow and actual Windows screenshot action are verified; physical clap/snap accuracy in the user's room still requires fresh live examples.

- **Observed user state, read-only:** normal `%APPDATA%/clapctrl` contained settings but no saved calibration.json. Both enabled triggers selected SCREENSHOT. The running executable was0.1.1. This establishes absence of a persisted profile, not whether RAM collection was complete or validation had failed. User data and running processes were preserved.
- **Fixed workflow:** distinguish accepted unsaved samples from a saved profile. The default10 means10 of EACH class,30 total. A fixed banner on every page shows missing counts, explicit Save profile & enable actions, or Enable desktop actions when optional testing is active. A successful explicit save now activates automatic actions directly; rejected training retains the previous profile. Unsaved collections cannot silently arm as if saved. Unfinished samples remain RAM-only; users should finish/save in0.1.1 before quitting if they want to retain that work.
- **PASS Windows:** `./gradlew.bat test :app-desktop:createDistributable :app-desktop:packageMsi '-Pclapctrl.outputDir=out/action-fix-binaries' --no-daemon`; **114 tests: core71, platform15, desktop28; zero failures/errors/skips**. One initial run caught an obsolete validation-message expectation; the message was corrected and the entire desktop suite passed on rerun.
- **PASS Linux:** same current Kotlin sources, offline headless `test :app-desktop:createDistributable`; **114 tests, zero failures/errors/skips**. Extracted0.1.2 portable archive passed UI/window/hide/reopen smoke. Log: `out/linux-action-fix/logs/gradle-linux.log`. WSLg native screenshots remain unavailable; no physical Linux audio/action claim follows.
- **PASS rendered-control integration:**10 samples of one class do not create a profile; missing class counts remain visible. Global Save writes a profile and immediately dispatches a fresh synthetic SNAP and DOUBLE_CLAP through the configured fake backend at the unchanged0.75 threshold. Optional recognition testing suppresses effects, global Enable resumes dispatch, explicit Test action reaches its backend, capture remains open throughout. The test also checks profile JSON, activation state and one capture owner.
- **PASS dispatch feedback:** disabled trigger, low confidence and cooldown expose a reason without executing or weakening gates. The Audio engine shows last sound, waiting-for-second-clap/threshold rejection, and last action result. Controller and adapter now share the selected data directory, including screenshots.
- **PASS actual Windows OS action:** final Windows ZIP was extracted, and its own bundled executable ran `--screenshot-smoke` with a separate repository data directory. Controller Test action invoked the production Windows adapter/PowerShell screenshot backend and created a valid PNG: `out/action-fix-native-data/screenshots/`. `SCREENSHOT_SMOKE_PASS`, exit0. Log: `out/diagnostics/action-fix-native.stdout.log`. This verifies the actual screenshot side effect separately from synthetic-audio gesture integration; it is not a physical clap recording.
- **PASS artifacts:**0.1.2 Windows MSI/ZIP, Linux portable tar.gz and source archive under `packaging/dist`, SHA256 sidecars. Updated Windows image is `out/action-fix-binaries/main/app/ClapCtrl/ClapCtrl.exe`; the user's running0.1.1 image was not overwritten. No MSI installation, commit, push or destructive/live lock/mute/media/workspace test was performed.

The records below describe earlier versions and their original behavior, counts and artifacts.

## 0.1.1 microphone and calibration repair ·2026-10-16

**Overall: PARTIALLY VERIFIED.** The repaired input lifecycle and complete calibration/control flow pass automated and Windows microphone checks. Actual handclap/snap accuracy in the user's room remains unmeasured; synthetic PCM is used for deterministic recognition tests.

- **PASS build/tests Windows:** `./gradlew.bat test :app-desktop:createDistributable :app-desktop:packageMsi '-Pclapctrl.outputDir=out/repaired-binaries' --no-daemon`. **112 tests: core71, platform15, desktop26; zero failures/errors/skips.** Desktop includes actual rendered-control interaction with harmless platform fakes. Full-suite XML is preserved under `out/final-test-results/windows/` and `out/final-test-results/linux/`; later focused screenshot checks repeat one existing test rather than adding to the count.
- **PASS build/tests Linux:** the same current Kotlin sources in an isolated WSL Ubuntu copy, offline `test :app-desktop:createDistributable`; **112 tests, zero failures/errors/skips**, including the popup flow without DISPLAY/WAYLAND_DISPLAY. Log: `out/linux-repair/logs/gradle-linux.log`.
- **PASS actual opening:** three explicit3-second Java Sound captures reached firstPCM at119.9652,71.8106 and72.1255ms; each used one successful48kHz mono format attempt. Log: `out/diagnostics/mic-baseline-2026-10-16.log`. No native-provider optimization was claimed from these timings: the defect was unnecessary reconnection and misleading linear metering.
- **PASS final Windows native lifecycle:** first realPCM **96ms**,48kHz. Settings changes and calibration start/pause/resume/reset kept one continuous native capture; completed-session diagnostics remained empty until the intentional pause. Capture progressed while hidden, paused cleanly and resumed. All triggers were disabled. Log: `out/diagnostics/final-hardware.stdout.log`. This measures input readiness, not gesture/action latency.
- **PASS final extracted release hardware check:** after the dialog layout correction, the final ZIP was extracted again and its own executable repeated the real48kHz lifecycle checks, firstPCM **114ms**, exit0. Log: `out/diagnostics/release-hardware.stdout.log`.
- **PASS calibration controls:** real Compose buttons automatically open What was that sound? after an eligible transient; freeze the sample and counters despite further PCM; label Handclap/Finger snap/Other sounds; Discard; Undo; pause from the question; resume/reset with confirmation; enforce every class target; automatically pause at completion; validate/save a real JSON profile; recognize another synthetic snap at the unchanged0.75 threshold; suppress automatic OS effects during safe testing; explicit Test action reaches the fake backend.
- **PASS classification regressions:** exact informative prototypes no longer get compressed below the default threshold; ambiguous ties are rejected; cross-validation and independent WAV replay apply deployed confidence thresholds. A weak0.2 clap cannot consume a following0.9/0.9 pair. Recognition resets preserve ambient adaptation and exclude sound tails across mode boundaries.
- **PASS popup visual review:** a full Compose-rendered screenshot exposed waveform/title overlap in the Material dialog's baseline layout. One explicit content column fixes it; a geometric test now reserves100dp between the heading and caption for the waveform. Reviewed screenshot: `docs/images/calibration-review.png` (synthetic test input). Native Windows page captures are in `out/release-ui/`.
- **PASS extracted Windows ZIP launch:** the extracted executable with its bundled runtime passed four-page/minimum-size/hide/reopen smoke, microphone off, exit0. Log: `out/diagnostics/final-portable.stdout.log`.
- **PASS updated artifacts:** Windows0.1.1 MSI (read-only database ProductVersion0.1.1) and ZIP, Linux0.1.1 portable tar.gz with bundled runtimes. Windows image staged under `out/repaired-binaries/main/app/ClapCtrl/` to preserve the user's already running0.1.0 processes. No user-owned process was stopped and no user settings/profile were overwritten by checks. Test data directories are isolated under `out/`.
- **PASS Linux portable launch:** the extracted0.1.1 archive passed four-page/minimum-size/hide/reopen smoke with microphone off. WSLg still needs the rendering fallback and lacks native ScreencastHelper; native Linux screenshots remain unverified. Log: `out/linux-repair/logs/packaged-ui.log`.
- **NOT RUN:** MSI installation, physical clap/snap corpus/accuracy, microphone disconnect testing, native Arch/PipeWire/Hyprland and disruptive live lock/mute/media/workspace effects. The automated action tests deliberately use fakes; they do not establish those live OS side effects.

The following sections retain the historical0.1.0 baseline and its original artifacts/counts.

Dates:2026-10-15–16. Overall: **PARTIALLY VERIFIED**. Windows and Linux builds/tests and the actual Windows microphone lifecycle are verified; real gesture accuracy and native Arch/Hyprland deployment are separate outstanding gates. No commit/push, system install or autostart registration was performed. All project source changes, deliverable artifacts and retained test data are inside this repository.

## Windows: PASS

Host: Windows11, ASUS13400, x86-64. Build JDK: JetBrains Runtime25.0.4.1; Gradle9.4.1; Kotlin/Compose compiler2.4.0; Compose1.10.3. Packaged runtime is bundled.

Command: `./gradlew.bat test :app-desktop:createDistributable :app-desktop:packageMsi --no-daemon` → exit0, BUILD SUCCESSFUL. Final XML reports: **95 tests, zero failures/errors/skips**:

| Suite | Count | Evidence |
|---|---:|---|
| Core DSP/framing/features/timing |41| Deterministic synthetic signals, rates/chunks, duplicates, noise, sustained bursts/recovery |
| Core calibration/configuration/WAV harness |20| Strict JSON, atomic preservation, held-out folds, metrics, profile validation, real WAV decoding of synthetic fixtures |
| Platform |13| Safe argv/capabilities, process timeout/cancellation/output bound, provider first-read readiness, cancellation waits native worker before restart |
| Controller/dispatcher |20| Consent, no automatic capture, persistence, live restart, PCM-to-action, calibration preview ownership/boundaries, rejected device change, profile retention after failed validation, confidence/cooldown/busy/cancellation |
| Compose interaction |1| Actual switch, action editor, save, Test action, history and section navigation with fake OS effects |

Reports: `core/build/reports/tests/test/`, `platform/build/reports/tests/test/`, `app-desktop/build/reports/tests/test/`. Synthetic tests are not real-world accuracy evidence. The seeded synthetic clap fixture scores0.707293: action wiring tests explicitly configure0.5 confidence; the production0.75 default remains conservative.

## Actual Windows runtime: PASS

- Real Java Sound enumeration: default device and three DirectSound input mixers, including USB PnP input and Virtual Audio Cable. Enumeration alone does not open input.
- Explicit diagnostic `:platform:audioDiagnostics --args='--capture-ms 1500'`: **143 frames,68,640 mono samples,48,000Hz**, maximum RMS0.0032427763 and peak0.0118408203. Audio was neither saved nor uploaded.
- Actual packaged executable with a separate test data directory, `--ui-smoke` and explicit `--hardware-smoke`: **HARDWARE_SMOKE_PASS**, exit0. Real PCM progressed while the window was hidden; pause stopped progress; resume reopened capture; shutdown completed. Desktop triggers were all disabled during this hardware check.
- Actual packaged UI smoke, microphone off: four pages, minimum window size, hide/reopen, exit0. Screenshots reviewed; low-contrast text was found and corrected, spacing compacted, scrollbar added. Actual image: `docs/images/dashboard.png`; all four pages and resized state: `out/ui/`.
- Hardware-run screen captures were obscured by another foreground window and were removed; they are not used as rendering evidence. The hardware assertions/log remain valid independently of screenshots.
- Final portable ZIP extracted under `out/portable-install-check/`. Its bundled `jvm.dll` and launcher exist; the **extracted** executable passed the same UI smoke and exited0. Log: `out/diagnostics/portable-ui.log`.
- No ClapCtrl process was left running after checks. No raw microphone files were created.

The first physical capture exposed an invalid immediate isRunning check. It was replaced with actual PCM readiness, isOpen and stall-watchdog checks, with a regression test. Further review found a worker cancellation/join race; capture now waits for native worker cleanup before allowing a new owner, with a deterministic latch regression. UI calibration tests also protect against stale gesture pairing, candidate replacement and bad profiles overwriting good ones.

## Windows artifacts: PASS creation, installation boundary explicit

- `packaging/dist/ClapCtrl-0.1.0.msi`: generated by jpackage/WiX. Read-only MSI database inspection confirmed ProductName=ClapCtrl, ProductVersion=0.1.0,261 files, bundled JVM and launcher.
- `packaging/dist/clapctrl-0.1.0-windows-x86_64.zip`: complete runtime image with SHA256 sidecar. Extraction and launch verified.
- `app-desktop/build/compose/binaries/main/app/ClapCtrl/ClapCtrl.exe`: ready-to-run application image.

**NOT RUN:** MSI installation/uninstallation into the Windows user profile/registry. The task was restricted to this repository; no system registration was made. This does not affect the independently verified portable distribution.

## Linux: PASS build, tests and portable launch

Environment: Ubuntu26.04 LTS, WSL2 kernel6.18.33.2, x86-64; Temurin25.0.4.1; Gradle9.4.1. The official JDK archive was verified against SHA256 before extraction under `tools/linux/`. Source, home, temporary files and Gradle outputs were isolated under `out/linux-verification/` and `tools/linux/`; no system packages or user desktop configuration were changed.

- `test :app-desktop:createDistributable --no-daemon` → exit0, BUILD SUCCESSFUL. **95 tests, zero failures/errors/skips**: core61, platform13, desktop21. Original full-suite XML reports are preserved under `out/linux-verification/full-test-results/`; build log is `out/linux-verification/logs/gradle-linux.log`.
- Kotlin source hashes match the main checkout. A subsequent developer-task-only change sets the WAV evaluator working directory to the repository root; it does not change shipped application code.
- A separate `:app-desktop:test --tests dev.clapctrl.app.DashboardInteractionTest` run with DISPLAY and WAYLAND_DISPLAY unset also passed. This repeats one existing test; it is not counted as another unique test. Log: `out/linux-verification/logs/headless-ui.log`.
- `packaging/linux/build-portable.sh --package-only` produced the actual x86-64 Linux runtime archive and SHA256. The launcher is an ELF64 Linux executable; its direct dynamic dependencies resolve.
- The archive was extracted into `out/linux-verification/extracted-package/`. Its own launcher and bundled runtime passed the four-page/window-size/hide/reopen smoke in WSLg, microphone off, exit0. Log: `out/linux-verification/logs/packaged-ui.log`.
- WSLg could not create the initial OpenGL context, and the app continued through the rendering fallback. Native ScreencastHelper was unavailable; Robot captures are black, so **Linux visual screenshot QA remains unverified**. Windows screenshots and Linux semantic UI tests provide separate evidence; black captures are not treated as a successful visual review.
- Linux physical audio capture was **NOT RUN**: this WSL environment lacks libasound.so.2 and does not provide the target Arch/PipeWire microphone environment. The Arch recipe declares alsa-lib. No Arch/Hyprland hardware result is inferred from WSL builds.

Deliverable: `packaging/dist/clapctrl-0.1.0-linux-x86_64.tar.gz`, SHA256 `67e3bf4132d7648e6f0d309bd9a2b7c4ba1e6d67ebda3e3d91797a8936bbc0a3`.

## WAV evaluation command: PASS wiring, synthetic data only

`./gradlew.bat :core:evaluateAudio --args='out/evaluation-smoke/manifest.json' --no-daemon` completed successfully from the repository root. Three generated WAV fixtures exercised positive/negative replay and annotated signal latency. This checks the documented CLI, path resolution, WAV decoding and report generation; its synthetic metrics are **not** evidence of real-world recognition accuracy. The production default confidence was not lowered.

## Packaging/static checks: PASS

Shell syntax, Windows packaging-script AST, SVG/PNG/ICO, CI YAML parse, source-manifest exclusions, deterministic source archive and Arch prepare snapshot checks passed. CI workflow was authored, not executed on GitHub. The Arch recipe uses the installed checkout and `makepkg --dir packaging/arch`; it does not claim an existing remote release. Source archive excludes build/cache/data/history.

## Outstanding limits

- **NOT RUN:** representative real handclap/snap/negative corpus, acoustic precision/recall/FPR, real clap/snap confusion and gesture-to-action latency. Calibration UI and independent WAV harness are available for this gate.
- **NOT RUN:** physical mic disconnect/reconnect, arbitrary devices and long-duration monitoring/CPU/memory benchmark. Bounded recovery and resource ownership have automated tests; no invented performance numbers.
- **NOT RUN:** native Arch package build/install/uninstall, actual Hyprland actions, PipeWire capture/tray-provider matrix.
- **NOT RUN:** disruptive live Windows lock/mute/media/workspace actions. Adapters/capabilities and command runner have safe tests; actual OS action side effects remain a manual acceptance gate. Numbered Windows workspaces are unsupported.
- Optional feedback sounds/output selection, spectrogram, raw-recording retention/export, portal screenshot fallback and automatic autostart registration are not implemented. Manual opt-in startup template is provided.

Launch, calibration and safe command configuration: see README.md and CALIBRATION.md. Source references: SOURCES.md. These boundaries are part of the deliverable, not claims of full cross-platform production validation.
