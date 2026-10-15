# ADR0001: resident process, Java Sound, lightweight classification

Status: accepted,2026-10-15.

The engine must survive dashboard closure while keeping microphone status visible. Use one JVM process with a hidden Compose window and AWT system tray. A daemon/IPC split adds failure and packaging surfaces without improving this MVP's demonstrated reliability. When a tray is absent, keep a minimized window as an indicator.

Use Java Sound as the first capture backend: it is shipped with the JDK and avoids native dependency distribution. It negotiates multiple PCM formats and surfaces device failures. This does not establish PipeWire support on every Linux/JDK combination. Keep AudioCaptureService replaceable if target-system tests justify PortAudio or a PipeWire backend.

Use bounded onset segmentation, FFT features and a conservative heuristic before calibration; standardized nearest-centroid classification after local training. A heavyweight ML runtime and TarsosDSP were not necessary for this feature set. Handclaps and snaps overlap acoustically, so no universal accuracy claim follows. The acoustic classifier is replaceable independently from temporal double-clap recognition and platform actions.

Use JDK25 LTS, available on the development host, rather than require another JDK21 installation. The distributed application bundles its runtime. Users need no global Java installation.
