# Troubleshooting

- **No microphones / cannot open:** grant OS microphone access to desktop apps; select a specific device, Refresh inputs, then Enable. Close programs using exclusive audio mode. Java Sound format negotiation errors are shown; retries stop after three attempts. On PipeWire verify ALSA compatibility and the JDK's mixer enumeration.
- **Flat meter:** monitoring may be paused, wrong input selected, or OS input muted. The meter has no simulated data. Check Audio input diagnostics and opt-in audio CLI.
- **False positives / missed gestures:** start with low sensitivity and high confidence, move nearer the microphone, collect varied calibration examples and negative sounds. Adjust double-clap interval. Finger snaps and claps overlap; a fixed classifier cannot guarantee reliable distinction in all rooms.
- **Action unavailable:** inspect the capability message; install the relevant user-session tool or choose an approved custom executable. Wayland does not use X11 screenshot APIs. Numbered Windows workspace switching is unavailable.
- **Action error / timeout:** inspect Activity. Commands run with argv, without shell expansion. Paths/arguments containing spaces are one field/line and do not need quotes. A shell script requires an explicitly approved shell executable and argument list.
- **Window disappeared:** reopen from tray. If tray is unsupported, Minimize keeps an indicator. Quit fully releases input. An instance lock prevents a second engine for the same data directory.
- **Corrupt config/profile:** the app reports errors and preserves the file. Copy it for diagnosis, then explicitly save repaired settings/import a valid profile. Future schemas are rejected.
- **Profile not used after switching input:** profiles are tied to microphone ID. Reselect the original input or calibrate the new one. Importing another microphone's profile is rejected.
- **Windows packaging:** createDistributable uses jlink/jpackage and needs a full JDK25; MSI additionally needs compatible WiX available to jpackage. Installers must be built on Windows. Native Linux packages must be built on Linux.

No telemetry, cloud connection or continuous raw-audio log exists. Recent activity is held in memory only. Screenshot actions intentionally write PNGs under the application data directory.
