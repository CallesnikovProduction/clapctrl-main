# ClapCtrl engineering context

Work within this repository. Preserve user changes and the Apache-2.0 license.
No commit/push, autostart registration, desktop lock or arbitrary action tests without an explicit task.

## Product and boundaries
Local JVM desktop utility: real microphone → bounded DSP → clap/snap classifier → temporal gestures → bounded action dispatch.
Exactly DOUBLE_CLAP and FINGER_SNAP. No voice recognition, cloud, telemetry, saved microphone audio or background startup by default.
One resident process owns capture; hiding the Compose window does not own or stop the engine. No tray: minimize/quit fallback.

## Modules
- `core`: models, PCM/DSP, classification/timing, validated atomic JSON, calibration/evaluation. No UI or process commands.
- `platform`: Java Sound capture + resource cancellation; Windows/Linux capabilities and safe argv process execution.
- `app-desktop`: controller, persistence orchestration, lifecycle, trigger cooldowns, bounded history, Compose UI/tray.

## Build
JDK 25 LTS, Gradle 9.4.1 wrapper, Kotlin/compiler 2.4.0, Compose 1.10.3.
`./gradlew test :app-desktop:classes`
`./gradlew :app-desktop:run`
`./gradlew :app-desktop:createDistributable` (host-native app with bundled runtime)
`./gradlew :app-desktop:packageMsi` (Windows + compatible WiX)
`makepkg --dir packaging/arch` (Arch only, repository root)
`./gradlew :platform:audioDiagnostics` (enumeration only; see docs for opt-in capture)
`./gradlew :core:evaluateAudio --args='manifest.json [profile.json] [report.json]'`
PowerShell uses `./gradlew.bat`. Agent checks must set GRADLE_USER_HOME to repository `.gradle-user-home` and app `--data-dir` to repository `.local-data` to keep all writes here.

## Invariants and limitations
Explicit microphone consent AND Enable required, including after restart. Never start from persisted consent alone.
Capture negotiation PCM16 mono/stereo48/44.1/16k, downmix; DSP10ms frames, Nyquist-normalized spectra, bounded180ms transients.
Detector is heuristic until calibrated; synthetic passes establish algorithm behavior, not real accuracy.
Calibration holds raw waveform transiently in memory, stores feature prototypes only, requires all3 classes and held-out validation.
Only apply profiles matching microphone ID + feature signature. Confidence is a score, not a proven probability.
Never use shell concatenation. Custom argv requires explicit approval. One dispatch at a time; no action backlog.
Keep tests harmless: use fakes for lock/media/mute/workspace, process fixture for timeouts, opt-in capture only.
Linux/PipeWire/Hyprland and Arch packages require Linux verification; do not infer success from Windows compilation.

## Status / next priorities
See `docs/VERIFICATION.md` for dated observed results and outstanding gates.
Verified:95 tests each on Windows and Ubuntu WSL, both portable runtime launches, Windows actual48kHz capture continuing while hidden plus pause/release/resume. Windows MSI built; no system installation. Linux screenshot capture was unavailable in WSLg; do not claim Linux visual QA.
Real-world audio corpus, target Linux runtime/device/package testing, and extended CPU/memory monitoring remain required before accuracy/performance claims.
