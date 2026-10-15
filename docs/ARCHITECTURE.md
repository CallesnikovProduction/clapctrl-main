# Architecture

ClapCtrl is one resident JVM process. `core` depends on Kotlin/coroutines/serialization; `platform` depends on core; `app-desktop` depends on both. The core never launches a process or refers to Compose. Capture and platform actions implement explicit core interfaces and can be replaced by fakes.

The controller owns a supervisor scope and lifecycle mutex. Start negotiates audio on an IO worker, then processes each 10ms frame synchronously: hardware input is the only bounded buffer, with no growing frame channel. UI metering is sampled at25Hz. Hiding the window removes dashboard composition; capture remains independent. Pause closes the line before joining capture; shutdown closes capture and cancels all owned jobs. A data-directory file lock prevents two instances opening the same engine.

Actions have a mutex with immediate rejection when busy, confidence/enable gates and per-trigger monotonic cooldown. Failed attempts also consume cooldown. Command execution has its own concurrency bound, output limit and timeout. Manual tests bypass recognition and trigger enable state intentionally. Activity is memory-only, newest200 entries.

Configuration updates are serialized, validated and atomically persisted before UI state changes. Corrupt settings produce an error; existing files are preserved until the user explicitly saves a replacement. Monitoring never auto-starts from stored consent. Profiles are applied only to their microphone ID; reselecting another input falls back to the uncalibrated classifier.

See [ADR0001](adr/0001-resident-engine.md), [audio pipeline](AUDIO_PIPELINE.md), [platform support](PLATFORM_SUPPORT.md).
