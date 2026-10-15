# Contributing

Use JDK25, run `./gradlew test :app-desktop:classes`, and describe the reproduced problem, resulting behavior and verification boundary. Follow AGENTS.md. Keep the core independent from Compose and OS commands. Add deterministic behavioral tests for meaningful DSP, lifecycle, persistence and command changes.

Do not add telemetry, cloud audio processing, dynamic dependency versions, hidden autostart or shell concatenation. Never claim synthetic signals establish acoustic accuracy. Audio fixtures must have a documented license/consent and separate train/validation roles. Report OS/JDK/audio backend for hardware findings. Retain Apache-2.0 licensing.
