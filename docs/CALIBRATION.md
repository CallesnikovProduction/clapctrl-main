# Local calibration and evaluation

Open Audio input, select the microphone, then Enable monitoring and accept the microphone explanation. Open Calibration, choose5–200 examples per class (default30), and Start session. Desktop actions are suspended during collection.

Perform one clap, leave a quiet gap, inspect the waveform/features, and Accept clap or Reject. Repeat with snaps. Label representative clicks, desk taps and typing transients NEGATIVE; use a varied sample set. Quiet ambient audio updates the adaptive noise floor but is not itself a transient example. Capture is memory-only. Each accepted example keeps a9-feature vector; profiles contain only class centroids/scales/counts and microphone association.

Validate & save profile runs stratified cross-validation, computing normalization exclusively on each fold's training examples, then trains the final profile on all accepted examples. Precision, recall, false-positive rate and confusion use held-out examples. Undefined metrics show N/A. This feature-level estimate is not end-to-end action accuracy or a deployment guarantee. Gather a fresh recording set to evaluate actual onsets and missed events.

Profiles are versioned JSON with a pipeline signature. Import requires the selected microphone association to match. Export stores no waveform. A microphone change bypasses the old profile. No separate OS-specific profile is required; audio hardware, gain, room and distance matter instead.

The dashboard activates a newly trained profile only if every class reaches precision and recall of at least80%, and a false-positive rate of at most10%, in held-out folds. Failure reports metrics and preserves the previous profile. These conservative engineering gates are separate from real-world validation and do not establish end-to-end accuracy. Importing an external profile requires fresh evaluation by its user. Calibration blocks microphone changes until the session ends, freezes each preview until accept/reject, and resets temporal gesture state at session boundaries.

## Independent WAV recordings
Record your own PCM WAV files with one gesture per positive file, quiet lead-in and trailing silence (at least250ms). Include separate negative files containing realistic confusing sounds. Never evaluate only training recordings. Create a manifest:

```json
[
  {"file":"audio/clap-01.wav","label":"CLAP","eventEndMs":820},
  {"file":"audio/snap-01.wav","label":"SNAP"},
  {"file":"audio/keyboard-01.wav","label":"NEGATIVE"}
]
```

Paths resolve relative to the manifest. Run:
```sh
./gradlew :core:evaluateAudio --args='dataset/manifest.json calibration.json report.json'
```
Omit profile/report arguments to evaluate the default classifier. JSON and CSV manifests are accepted. The harness uses the production DSP on10ms frames, counts missed onsets as NEGATIVE and reports recording-level confusion/metrics/candidate counts. If eventEndMs is annotated, it reports observed signal emission latency for correctly identified positives; without it latency is unknown. It does not benchmark OS scheduling or gesture-to-action latency. Synthetic fixtures are not a real-audio corpus.
