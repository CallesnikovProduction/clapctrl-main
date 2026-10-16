# Local calibration and evaluation

Open Audio input, select the microphone, then Enable monitoring and accept the microphone explanation. Wait for live levels and the first-PCM time. Open Calibration, choose5–200 examples per class (default10), and Start session. Desktop actions are suspended during collection.

Make ONE clap or finger snap. The app automatically captures the transient, freezes its waveform, and opens What was that sound? Choose Handclap, Finger snap, Other sounds, or Discard sample. No class needs to be selected beforehand. While this question is open, further sounds cannot replace the sample or silently add examples; input remains open for live metering. Each answer increases only the chosen class's counter and resumes collection after a350ms settling interval that excludes button noise. Undo removes the last accepted example.

Collect representative clicks, desk taps and typing transients as Other sounds. Label what you actually performed; Discard sample if unsure. Collection pauses automatically when every class reaches its target. Pause session is also available inside the review question. Pause/resume retains collected samples; Reset samples asks for confirmation and preserves the saved profile.

[Automatic review question](images/calibration-review.png) — rendered by the interaction test using synthetic audio; this illustrates the UI, not real acoustic accuracy.

Quiet ambient audio updates the adaptive noise floor but is not itself a transient example. Capture is memory-only. Each accepted example keeps a9-feature vector; profiles contain only class centroids/scales/counts and microphone association. Ten examples per class are a quick start; use more varied samples when validation or fresh-sound testing fails.

The default10 means10 handclaps +10 finger snaps +10 other sounds:30 accepted samples, not10 total. Samples remain in memory until a profile is saved; closing the app loses an unfinished collection. A fixed banner on every page shows missing class counts or Save profile & enable actions. Collection alone does not train or activate anything.

Save profile & enable actions becomes available only after ALL THREE class counters reach the target. It runs stratified cross-validation, computing normalization exclusively on each fold's training examples, and applies the current trigger confidence thresholds when scoring held-out predictions. It then trains and atomically saves the final profile. Success explicitly enables automatic actions; failure reports which classes failed and preserves the old profile. Undefined metrics show N/A. This feature-level estimate is not end-to-end action accuracy or a deployment guarantee.

After successful activation, perform TWO claps150–700ms apart by default or ONE finger snap. A single clap cannot run the double-clap action. The Audio engine shows the last sound, rejection threshold or wait-for-second-clap hint, plus the last action's result/path, disabled trigger or cooldown. Screenshot actions save a PNG to the application's data directory under screenshots; they do not copy it to the clipboard or open an editor.

Test recognition safely is optional. It suspends automatic actions and reports what would run. The fixed Enable desktop actions button exits this mode from any page. Unsaved accepted samples must first be completed/saved or explicitly reset; they cannot silently masquerade as an active profile. Test action on Triggers runs the chosen action directly, independently of acoustic recognition. Pause/reset collection keeps actions suspended until explicitly enabled. For0.1.1 only: after Validate & save profile, use Finish test · enable desktop actions;0.1.2 replaces that hidden extra step with explicit save-and-activate.

Profiles are versioned JSON with a pipeline signature. Import requires the selected microphone association to match. Export stores no waveform. A microphone change bypasses the old profile. No separate OS-specific profile is required; audio hardware, gain, room and distance matter instead.

The dashboard activates a newly trained profile only if every class reaches precision and recall of at least80%, and a false-positive rate of at most10%, in held-out folds. Failure reports metrics and preserves the previous profile. These conservative engineering gates are separate from real-world validation and do not establish end-to-end accuracy. Importing an external profile requires fresh evaluation by its user. Profiles from0.1.0 remain readable, but their earlier validation did not account for runtime confidence thresholds: revalidate with fresh sounds. Calibration blocks microphone changes until the session ends, freezes each preview until accept/reject, and resets temporal gesture state at session boundaries without closing the input.

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
Omit profile/report arguments to evaluate the default classifier. JSON and CSV manifests are accepted. The harness uses the production DSP on10ms frames, applies the default0.75 confidence threshold to positive predictions, counts missed onsets as NEGATIVE and reports recording-level confusion/metrics/candidate counts. If eventEndMs is annotated, it reports observed signal emission latency for correctly identified positives; without it latency is unknown. It does not benchmark OS scheduling or gesture-to-action latency. Synthetic fixtures are not a real-audio corpus.
