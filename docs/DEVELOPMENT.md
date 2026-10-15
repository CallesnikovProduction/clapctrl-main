# Development

Install JDK25 and use the checked-in Gradle9.4.1 wrapper. Dependency versions are fixed in Gradle files; repositories are Maven Central, Google and Gradle Plugin Portal. No dynamic versions. The existing Apache-2.0 project license is retained.

```sh
./gradlew test :app-desktop:classes
./gradlew :app-desktop:run
./gradlew :app-desktop:createDistributable
./gradlew :app-desktop:packageMsi # Windows + WiX; host-native only
./gradlew :app-desktop:packageDeb # Debian builder
makepkg --dir packaging/arch # Arch builder, repository root
```

Windows PowerShell: `./gradlew.bat` instead of `./gradlew`. To keep test data local:
```powershell
$env:GRADLE_USER_HOME="$PWD\.gradle-user-home"
./gradlew.bat :app-desktop:run --args="--data-dir=$PWD\.local-data"
```

UI smoke mode captures only the actual app window, visits all four pages, resizes to minimum size, hides/reopens, then shuts down without opening the microphone:
```powershell
./gradlew.bat :app-desktop:run --args="--data-dir=$PWD\.local-data --ui-smoke=$PWD\out\ui"
```
It requires an interactive desktop and an unobscured foreground window for screenshot inspection. It verifies rendering/lifecycle, not physical gesture detection.

Microphone diagnostics enumerate inputs by default. Explicit short capture (no raw storage):
```sh
./gradlew :platform:audioDiagnostics --args='--capture-ms 1500'
./gradlew :platform:audioDiagnostics --args='--capture-ms 1500 device-id-from-list'
```
It reports frames, samples, negotiated rate and observed RMS/peak. Java Sound providers can report isRunning=false immediately after start; readiness is established by received PCM, while line closure and a stall watchdog detect failures.

Packaged hardware smoke mode requires a separate explicit data directory and `--ui-smoke`. It disables every desktop trigger in that test directory, explicitly opts into microphone capture, checks real frame progress while hidden, pause/release/resume, and exits:
```powershell
& .\app-desktop\build\compose\binaries\main\app\ClapCtrl\ClapCtrl.exe --data-dir=D:\GitHub\clapctrl-main\out\hardware-data --ui-smoke=D:\GitHub\clapctrl-main\out\hardware-ui --hardware-smoke
```
Do not point this diagnostic at your normal settings directory: it saves consent and disabled triggers there. Normal launches always require explicit Enable. Hardware diagnostics do not establish clap/snap accuracy or invoke desktop actions.

Test changes proportionally. Keep acoustic timing/features deterministic; test actions with injected command runners, never lock/mute the developer desktop as an automated unit test. Real-audio evaluation and target OS packaging are separate verification gates. See VERIFICATION.md for observed outcomes.
