# speaker_encoder.tflite — provenance

`app/src/main/assets/speaker_encoder.tflite` is a converted copy of the
pretrained GE2E speaker-encoder from
[resemble-ai/Resemblyzer](https://github.com/resemble-ai/Resemblyzer)
(`resemblyzer/pretrained.pt`, Apache-2.0 licensed).

It replaces the raw-MFCC cosine-similarity fingerprinting previously used in
`speaker_detect.py` with embeddings from a real speaker-ID model trained on
VoxCeleb + LibriSpeech, while keeping the existing energy-based VAD
segmentation and cosine-similarity clustering logic as-is.

## Why not run PyTorch or the `resemblyzer` pip package on-device

- `resemblyzer` pulls in `webrtcvad`, a C extension with no Android/Chaquopy
  build (and no prebuilt wheel we could substitute, unlike the
  `soundfile`/`numba` stub pattern used elsewhere in `vendor/`).
- Shipping PyTorch itself into the APK via Chaquopy would be enormous. The
  app already has a working TFLite inference path (see
  `stresstest.py::_get_interpreter_bundle`), so converting to TFLite reuses
  that.

## Conversion approach

1. Downloaded `voice_encoder.py`, `audio.py`, `hparams.py`, and
   `pretrained.pt` directly from the Resemblyzer GitHub repo (not via pip,
   to avoid the `webrtcvad` dependency).
2. Confirmed the architecture from `hparams.py`/`voice_encoder.py`:
   `LSTM(40, 256, num_layers=3, batch_first=True) -> Linear(256, 256) -> ReLU
   -> L2-normalize`, using the final layer's last hidden state (`hidden[-1]`),
   not the full output sequence.
3. **Did not** use an ONNX -> onnx2tf -> TFLite pipeline: onnx2tf's automatic
   graph surgery for the exported LSTM's `Expand` ops (for the zero initial
   hidden/cell state) failed to produce a valid Keras functional graph after
   3 auto-repair attempts.
4. Instead, rebuilt the same architecture directly as a Keras model (3
   stacked `LSTM` layers + `Dense` + `ReLU` + L2-normalize `Lambda`) and
   copied the PyTorch weights over directly:
   - PyTorch `nn.LSTM` and `tf.keras.layers.LSTM` use the same per-gate order
     in their stacked weight matrices (input, forget, cell, output).
   - `kernel = weight_ih.T`, `recurrent_kernel = weight_hh.T`,
     `bias = bias_ih + bias_hh` (Keras uses one combined bias; PyTorch keeps
     the input/hidden biases separate).
5. Converted the resulting Keras model to TFLite with
   `TFLiteConverter.from_keras_model(...)`. All ops used
   (`FULLY_CONNECTED`, `MUL`, `RESHAPE`, `RSQRT`, `STRIDED_SLICE`, `SUM`,
   `WHILE`) are native TFLite builtins — no Flex/`SELECT_TF_OPS` needed at
   runtime, though the app already depends on
   `tensorflow-lite-select-tf-ops` for other reasons.
6. **Verified numerically** at every stage against the real PyTorch model
   (not just shape-checked): the ported Keras model and the final `.tflite`
   file both matched the PyTorch reference output with cosine similarity
   ≈ 1.0 (differences at float32 rounding-noise level, ~1e-7) across
   multiple random inputs.

Input: fixed shape `(1, 160, 40)` — a 1.6s window of 40-channel mel-spectrogram
frames (25ms window / 10ms hop @ 16kHz, **not** log-mel — Resemblyzer's own
`wav_to_mel_spectrogram` explicitly uses a raw mel-spectrogram).
Output: `(1, 256)` — an L2-normalized speaker embedding.

## On-device usage (`speaker_detect.py`)

For each VAD-detected speech segment (or the calibration recording), the
segment's audio is:
1. Volume-normalized to -30 dBFS (increase-only), matching Resemblyzer's
   `normalize_volume`. The webrtcvad-based `trim_long_silences` step from the
   original preprocessing is skipped — our own energy-based VAD already
   segments speech, so this only affects internal micro-pauses, not
   correctness.
2. Converted to a mel-spectrogram, split into overlapping 1.6s windows
   (`compute_partial_slices`, same algorithm/rate as
   `VoiceEncoder.embed_utterance`), each run through the TFLite model.
3. The resulting per-window embeddings are averaged and L2-renormalized into
   one 256-dim embedding per segment.

That embedding replaces the old 40-dim raw-MFCC vector everywhere it was
used: matching against the stored "User 1" calibration fingerprint, and
cosine-similarity clustering unknown segments into "Subject 2/3/4".

## Reproducing the conversion

The one-time conversion scripts (PyTorch/TensorFlow/ONNX, never shipped in
the app) are not checked into this repo — they were run in a throwaway venv.
To redo it: fetch `pretrained.pt` + `voice_encoder.py` + `hparams.py` from
the Resemblyzer repo, rebuild the architecture in Keras, port weights by
transposing `weight_ih`/`weight_hh` and summing `bias_ih + bias_hh` per LSTM
layer, then `tf.lite.TFLiteConverter.from_keras_model(...)`. Always verify
numerically against the PyTorch reference before trusting the output.
