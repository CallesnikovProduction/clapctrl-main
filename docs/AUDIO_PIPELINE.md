# Audio pipeline

1. Java Sound enumerates target mixers without opening input. It tries48kHz,44.1kHz and16kHz, each mono then stereo, signed16-bit little-endian PCM. Stereo is downmixed and normalized to[-1,1).
2. Capture emits10ms frames with a monotonic sample-clock timestamp. Partial reads are assembled; buffer is about100ms. A4s stall watchdog closes a stalled line. Controller retries three times with a2s delay; user can select/refresh input and retry.
3. DSP handles arbitrary frame sizes and8–192kHz, uses adaptive RMS noise floor, peak/crest and spectral flux onset gates, hysteresis, quiet release and a refractory interval. Sustained events over180ms are rejected. Discontinuities reset temporal pairing.
4. A reusable radix-2 FFT measures Nyquist-normalized centroid/bandwidth, high-frequency energy ratio, zero crossing and spectral flux. Duration, RMS, peak, crest, attack and decay are derived from the event. No raw audio goes to disk.
5. Default clap/snap rules are conservative starting heuristics. They are NOT proven acoustically universal. Local calibration replaces them with standardized class centroids including a negative class and out-of-distribution rejection.
6. The temporal recognizer pairs distinct claps within the configured150–700ms default interval. Snaps dispatch individually. Per-trigger confidence and cooldown follow, then bounded action execution.

Scores are interpretable rule/distance scores, not calibrated probabilities. Ambient noise adaptation is not acoustic recognition. Quiet, distant or overlapping gestures may be missed; loud typing, clicks, music or desk taps may be confused. Evaluation must use independent recordings for the intended microphone and room.

The synthetic suite checks framing, normalization, energy, features, timing, false-positive suppression on generated signals and burst recovery. It does not prove real-world precision or recall. Use the WAV harness in [CALIBRATION](CALIBRATION.md) for that.
