import json
import os

_PLOTTER_CACHE = None

try:
    import numpy as np
except Exception:
    np = None

try:
    import librosa
except Exception as e:
    librosa = None

try:
    from tflite_runtime.interpreter import Interpreter
except Exception:
    try:
        from tensorflow.lite.python.interpreter import Interpreter
    except Exception:
        Interpreter = None

try:
    from java import jclass
    JavaInterpreter = jclass("org.tensorflow.lite.Interpreter")
    JavaFile = jclass("java.io.File")
    JavaByteBuffer = jclass("java.nio.ByteBuffer")
    JavaByteOrder = jclass("java.nio.ByteOrder")
except Exception:
    JavaInterpreter = None
    JavaFile = None
    JavaByteBuffer = None
    JavaByteOrder = None

CLASS_LABELS = ["Angry", "High Stress", "Low Stress", "Neutral", "Soft"]

_SCALER_CACHE = {}
_INTERPRETER_CACHE = {}
_MODEL_SHAPE_CACHE = {}  # model_path -> {"input_size": int, "output_classes": int, "config": dict}

SR = 22050
N_FFT = 512
HOP_LENGTH = 512
FMIN = 133.33
FMAX = 6855.4976
N_MFCC = 13
N_MELS = 26


def _get_plotter():
    global _PLOTTER_CACHE
    if _PLOTTER_CACHE is not None:
        return _PLOTTER_CACHE
    try:
        os.environ.setdefault("MPLBACKEND", "Agg")
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        _PLOTTER_CACHE = plt
    except Exception:
        _PLOTTER_CACHE = False
    return _PLOTTER_CACHE if _PLOTTER_CACHE is not False else None


def _log_path(img_dir):
    return os.path.join(img_dir, "processing_log.txt")


def _reset_log(img_dir):
    _safe_mkdir(img_dir)
    with open(_log_path(img_dir), "w", encoding="utf-8") as f:
        f.write("StressDetection processing log\n")


def _append_log(img_dir, message):
    _safe_mkdir(img_dir)
    with open(_log_path(img_dir), "a", encoding="utf-8") as f:
        f.write(str(message).rstrip() + "\n")


def _safe_mkdir(path):
    os.makedirs(path, exist_ok=True)


def _load_scaler_values(path):
    if np is None:
        return None, None
    if not path or not os.path.exists(path):
        return None, None
    cached = _SCALER_CACHE.get(path)
    if cached is not None:
        return cached
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    mean = np.array(data.get("mean", []), dtype=np.float32)
    scale = np.array(data.get("scale", []), dtype=np.float32)
    _SCALER_CACHE[path] = (mean, scale)
    return mean, scale


def _extract_features_from_signal(y):
    if np is None:
        raise RuntimeError("numpy is not available")
    if librosa is None:
        raise RuntimeError("librosa is not available")

    if len(y) < N_FFT:
        y = np.pad(y, (0, N_FFT - len(y)))

    mfcc = librosa.feature.mfcc(
        y=y,
        sr=SR,
        n_mfcc=N_MFCC,
        n_fft=N_FFT,
        hop_length=HOP_LENGTH,
        fmin=FMIN,
        fmax=FMAX,
        n_mels=N_MELS,
        htk=True,
    )
    mfcc_mean = mfcc.mean(axis=1)

    zcr = librosa.feature.zero_crossing_rate(y, hop_length=HOP_LENGTH).mean()
    spectral_centroid = librosa.feature.spectral_centroid(
        y=y, sr=SR, n_fft=N_FFT, hop_length=HOP_LENGTH
    ).mean()

    features = np.concatenate([mfcc_mean, np.array([zcr, spectral_centroid], dtype=np.float32)])
    return features.astype(np.float32)


def _extract_features(audio_path):
    if librosa is None:
        raise RuntimeError("librosa is not available")
    y, _ = librosa.load(audio_path, sr=SR)
    return _extract_features_from_signal(y)


def _normalize_features(features, scaler_path, img_dir=None):
    mean, scale = _load_scaler_values(scaler_path)
    if mean is None or scale is None:
        return features
    if len(mean) != len(features):
        if img_dir:
            _append_log(img_dir, (
                "Note: scaler has {} entries but feature vector has {} — "
                "skipping normalization for this model.".format(len(mean), len(features))
            ))
        return features
    return (features - mean) / (scale + 1e-8)


def _get_interpreter_bundle(model_path):
    cached = _INTERPRETER_CACHE.get(model_path)
    if cached is not None:
        return cached

    if Interpreter is not None:
        interpreter = Interpreter(model_path=model_path)
        interpreter.allocate_tensors()
        bundle = {
            "engine": "python",
            "interpreter": interpreter,
            "input": interpreter.get_input_details()[0],
            "output": interpreter.get_output_details()[0],
        }
    elif JavaInterpreter is not None:
        interpreter = JavaInterpreter(JavaFile(model_path))
        interpreter.allocateTensors()
        output_tensor = interpreter.getOutputTensor(0)
        bundle = {
            "engine": "java",
            "interpreter": interpreter,
            "input_bytes": interpreter.getInputTensor(0).numBytes(),
            "output_bytes": output_tensor.numBytes(),
        }
    else:
        raise RuntimeError("No TFLite interpreter available")

    _INTERPRETER_CACHE[model_path] = bundle
    return bundle


def probe_model_input(model_path):
    """Read input/output shapes from a TFLite model and suggest a feature config.

    Returns a JSON string:
      {"input_size": N, "output_classes": M, "suggested_config": {...} | null, "note": "..."}
    """
    cached = _MODEL_SHAPE_CACHE.get(model_path)
    if cached is not None:
        return json.dumps(cached)

    if not os.path.exists(model_path):
        return json.dumps({"error": "Model file not found", "input_size": -1, "output_classes": -1})

    try:
        bundle = _get_interpreter_bundle(model_path)
    except Exception as e:
        return json.dumps({"error": str(e), "input_size": -1, "output_classes": -1})

    if bundle["engine"] == "python":
        input_shape = bundle["input"]["shape"]
        output_shape = bundle["output"]["shape"]
        input_size = int(input_shape[-1])
        output_classes = int(output_shape[-1])
    else:
        # Java backend: derive from byte counts
        input_size = bundle["input_bytes"] // 4
        output_classes = bundle["output_bytes"] // 4

    config = _infer_feature_config(input_size)
    result = {
        "input_size": input_size,
        "output_classes": output_classes,
        "suggested_config": config,
        "note": (
            "Auto-detected config: {}".format(config.get("label", "unknown"))
            if config else
            "Input size {} does not match any known feature pattern — normalization skipped".format(input_size)
        ),
    }
    _MODEL_SHAPE_CACHE[model_path] = result
    return json.dumps(result)


def _infer_feature_config(input_size):
    """Map an input feature count to the extraction parameters that would produce it."""
    # Priority order: most-specific match first
    # Pattern: n_mfcc means + ZCR + spectral_centroid → n_mfcc = input_size - 2
    if input_size >= 3:
        n_mfcc_candidate = input_size - 2
        if 1 <= n_mfcc_candidate <= 256:
            return {
                "label": "{}-MFCC + ZCR + centroid".format(n_mfcc_candidate),
                "n_mfcc": n_mfcc_candidate,
                "extras": ["zcr", "centroid"],
            }
    # Pattern: MFCC means only → n_mfcc = input_size
    if 1 <= input_size <= 256:
        return {
            "label": "{}-MFCC only".format(input_size),
            "n_mfcc": input_size,
            "extras": [],
        }
    return None


def _extract_features_adaptive(y, expected_size):
    """Extract features shaped to match expected_size, using _infer_feature_config."""
    if np is None or librosa is None:
        raise RuntimeError("numpy/librosa unavailable")

    config = _infer_feature_config(expected_size)
    if config is None:
        raise RuntimeError("Cannot infer feature config for input_size={}".format(expected_size))

    if len(y) < N_FFT:
        y = np.pad(y, (0, N_FFT - len(y)))

    n_mfcc = config["n_mfcc"]
    mfcc = librosa.feature.mfcc(
        y=y, sr=SR, n_mfcc=n_mfcc,
        n_fft=N_FFT, hop_length=HOP_LENGTH,
        fmin=FMIN, fmax=FMAX, n_mels=N_MELS, htk=True,
    )
    parts = [mfcc.mean(axis=1)]

    if "zcr" in config["extras"]:
        parts.append(np.array([librosa.feature.zero_crossing_rate(y, hop_length=HOP_LENGTH).mean()], dtype=np.float32))
    if "centroid" in config["extras"]:
        parts.append(np.array([librosa.feature.spectral_centroid(y=y, sr=SR, n_fft=N_FFT, hop_length=HOP_LENGTH).mean()], dtype=np.float32))

    return np.concatenate(parts).astype(np.float32)


def _predict_tflite(model_path, features):
    if np is None:
        raise RuntimeError("numpy is not available")

    bundle = _get_interpreter_bundle(model_path)
    interpreter = bundle["interpreter"]
    if bundle["engine"] == "python":
        input_details = bundle["input"]
        output_details = bundle["output"]

        input_data = np.expand_dims(features, axis=0).astype(np.float32)
        interpreter.set_tensor(input_details["index"], input_data)
        interpreter.invoke()
        output = interpreter.get_tensor(output_details["index"])[0].astype(np.float32)
    else:
        input_buffer = JavaByteBuffer.allocateDirect(bundle["input_bytes"])
        input_buffer.order(JavaByteOrder.nativeOrder())
        for value in np.asarray(features, dtype=np.float32):
            input_buffer.putFloat(float(value))
        input_buffer.rewind()

        output_buffer = JavaByteBuffer.allocateDirect(bundle["output_bytes"])
        output_buffer.order(JavaByteOrder.nativeOrder())
        interpreter.run(input_buffer, output_buffer)
        output_buffer.rewind()
        float_count = bundle["output_bytes"] // 4
        output = np.array([output_buffer.getFloat() for _ in range(float_count)], dtype=np.float32)

    # Ensure normalized probabilities.
    exp = np.exp(output - np.max(output))
    probs = exp / np.sum(exp)
    return probs


def _prepare_features(audio_path, scaler_path=None, speaker_focus="all", model_path=None, img_dir=None):
    if not os.path.exists(audio_path):
        return None

    focus_applied = False
    if speaker_focus in ("patient", "doctor") and librosa is not None:
        y, _ = librosa.load(audio_path, sr=SR)
        focused = _focus_audio_by_speaker(y, speaker_focus)
        y_for_features = focused if focused is not None else y
        focus_applied = focused is not None
    else:
        y_for_features = None

    # Determine expected input size from model (if provided) and extract adaptively
    expected_size = None
    if model_path and os.path.exists(model_path):
        shape_info = _MODEL_SHAPE_CACHE.get(model_path)
        if shape_info is None:
            try:
                probe_model_input(model_path)
                shape_info = _MODEL_SHAPE_CACHE.get(model_path)
            except Exception:
                pass
        if shape_info:
            expected_size = shape_info.get("input_size")

    try:
        if expected_size and expected_size > 0:
            if y_for_features is None:
                y_for_features, _ = librosa.load(audio_path, sr=SR)
            features = _extract_features_adaptive(y_for_features, expected_size)
        else:
            features = _extract_features_from_signal(y_for_features) if y_for_features is not None else _extract_features(audio_path)
    except Exception:
        features = _extract_features_from_signal(y_for_features) if y_for_features is not None else _extract_features(audio_path)

    features = _normalize_features(features, scaler_path, img_dir=img_dir)
    return {
        "audio_name": os.path.splitext(os.path.basename(audio_path))[0],
        "file": os.path.basename(audio_path),
        "features": features,
        "speaker_focus_applied": bool(focus_applied),
    }


def _save_bar_chart(audio_name, probs, img_dir):
    plt = _get_plotter()
    if plt is None:
        return None
    out_path = os.path.join(img_dir, f"{audio_name}_bar.png")
    plt.figure(figsize=(8, 4))
    plt.bar(CLASS_LABELS, probs * 100)
    plt.ylim(0, 100)
    plt.ylabel("Probability (%)")
    plt.title(f"Prediction - {audio_name}")
    plt.xticks(rotation=20)
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()
    return out_path


def _segment_pitch(seg):
    if np is None or librosa is None:
        return np.nan
    if len(seg) < 512:
        return np.nan
    try:
        frame_length = 2048 if len(seg) >= 2048 else 1024
        if len(seg) < frame_length:
            return np.nan
        f0 = librosa.yin(seg, fmin=75.0, fmax=420.0, sr=SR, frame_length=frame_length)
        f0 = f0[np.isfinite(f0)]
        if len(f0) == 0:
            return np.nan
        return float(np.median(f0))
    except Exception:
        return np.nan


def _kmeans_two_clusters(x, max_iter=20):
    if np is None:
        return None
    if x is None or len(x) < 2:
        return None

    x = np.asarray(x, dtype=np.float32)
    if x.shape[0] < 2:
        return None

    means = x.mean(axis=0, keepdims=True)
    stds = x.std(axis=0, keepdims=True) + 1e-8
    x_norm = (x - means) / stds

    first = int(np.argmin(x_norm[:, 0]))
    second = int(np.argmax(x_norm[:, 0]))
    if first == second:
        return None
    centers = np.vstack([x_norm[first], x_norm[second]]).astype(np.float32)

    assign = np.zeros((x_norm.shape[0],), dtype=np.int32)
    for _ in range(max_iter):
        d0 = np.sum((x_norm - centers[0]) ** 2, axis=1)
        d1 = np.sum((x_norm - centers[1]) ** 2, axis=1)
        new_assign = (d1 < d0).astype(np.int32)

        if np.array_equal(new_assign, assign):
            break
        assign = new_assign

        for k in (0, 1):
            members = x_norm[assign == k]
            if len(members) == 0:
                return None
            centers[k] = members.mean(axis=0)

    if len(np.unique(assign)) < 2:
        return None
    return assign


def _focus_audio_by_speaker(y, speaker_focus):
    if np is None or librosa is None:
        return None
    if speaker_focus not in ("patient", "doctor", "auto"):
        return None
    effective_focus = "patient" if speaker_focus == "auto" else speaker_focus
    if y is None or len(y) < N_FFT:
        return None

    try:
        intervals = librosa.effects.split(y, top_db=30)
        if intervals is None or len(intervals) < 2:
            return None

        segments = []
        for start, end in intervals:
            seg = y[start:end]
            if len(seg) < int(0.30 * SR):
                continue
            rms = float(np.sqrt(np.mean(seg ** 2) + 1e-10))
            pitch = _segment_pitch(seg)
            if not np.isfinite(pitch):
                continue
            if rms < 1e-4:
                continue
            zcr = float(librosa.feature.zero_crossing_rate(seg, hop_length=HOP_LENGTH).mean())
            centroid = float(
                librosa.feature.spectral_centroid(
                    y=seg, sr=SR, n_fft=min(N_FFT, len(seg)), hop_length=HOP_LENGTH
                ).mean()
            )
            segments.append({
                "start": int(start),
                "end": int(end),
                "energy": rms,
                "pitch": float(pitch),
                "zcr": zcr,
                "centroid": centroid,
            })

        if len(segments) < 2:
            return None

        feats = np.array(
            [[s["energy"], s["pitch"], s["zcr"], s["centroid"]] for s in segments],
            dtype=np.float32,
        )
        assign = _kmeans_two_clusters(feats)
        if assign is None:
            return None

        # Confidence gate: if two clusters are too close, avoid forced speaker split.
        f_mean = feats.mean(axis=0, keepdims=True)
        f_std = feats.std(axis=0, keepdims=True) + 1e-8
        feats_norm = (feats - f_mean) / f_std
        c0 = feats_norm[assign == 0].mean(axis=0)
        c1 = feats_norm[assign == 1].mean(axis=0)
        separation = float(np.linalg.norm(c0 - c1))
        if not np.isfinite(separation) or separation < 1.25:
            return None

        e0 = float(np.median(feats[assign == 0, 0]))
        e1 = float(np.median(feats[assign == 1, 0]))
        p0 = float(np.median(feats[assign == 0, 1]))
        p1 = float(np.median(feats[assign == 1, 1]))

        e_mean = (e0 + e1) / 2.0
        e_std = abs(e0 - e1) / 2.0 + 1e-8
        p_mean = (p0 + p1) / 2.0
        p_std = abs(p0 - p1) / 2.0 + 1e-8

        patient_score_0 = (-(e0 - e_mean) / e_std) + ((p0 - p_mean) / p_std)
        patient_score_1 = (-(e1 - e_mean) / e_std) + ((p1 - p_mean) / p_std)
        patient_cluster = 0 if patient_score_0 >= patient_score_1 else 1
        target_cluster = patient_cluster if effective_focus == "patient" else 1 - patient_cluster

        selected = [y[s["start"]:s["end"]] for idx, s in enumerate(segments) if assign[idx] == target_cluster]
        if not selected:
            return None

        focused = np.concatenate(selected, axis=0)
        if len(focused) < N_FFT:
            return None
        return focused.astype(np.float32)
    except Exception:
        return None


def analyze_speakers(audio_path):
    if np is None or librosa is None:
        return json.dumps({"can_separate": False, "confidence": 0.0,
                           "reason": "Audio library unavailable"})
    try:
        y, _ = librosa.load(audio_path, sr=SR)
        if len(y) < N_FFT:
            return json.dumps({"can_separate": False, "confidence": 0.0,
                               "reason": "Audio too short"})
        intervals = librosa.effects.split(y, top_db=30)
        if intervals is None or len(intervals) < 2:
            return json.dumps({"can_separate": False, "confidence": 0.0,
                               "reason": "No distinct speech segments found"})
        segments = []
        for start, end in intervals:
            seg = y[start:end]
            if len(seg) < int(0.30 * SR):
                continue
            rms = float(np.sqrt(np.mean(seg ** 2) + 1e-10))
            pitch = _segment_pitch(seg)
            if not np.isfinite(pitch) or rms < 1e-4:
                continue
            zcr = float(librosa.feature.zero_crossing_rate(seg, hop_length=HOP_LENGTH).mean())
            centroid = float(librosa.feature.spectral_centroid(
                y=seg, sr=SR, n_fft=min(N_FFT, len(seg)), hop_length=HOP_LENGTH).mean())
            segments.append([rms, float(pitch), zcr, centroid])
        if len(segments) < 2:
            return json.dumps({"can_separate": False, "confidence": 0.0,
                               "reason": "Not enough speech segments found"})
        feats = np.array(segments, dtype=np.float32)
        assign = _kmeans_two_clusters(feats)
        if assign is None:
            return json.dumps({"can_separate": False, "confidence": 0.0,
                               "reason": "Could not find two distinct speaker groups"})
        f_mean = feats.mean(axis=0, keepdims=True)
        f_std = feats.std(axis=0, keepdims=True) + 1e-8
        feats_norm = (feats - f_mean) / f_std
        c0 = feats_norm[assign == 0].mean(axis=0)
        c1 = feats_norm[assign == 1].mean(axis=0)
        separation = float(np.linalg.norm(c0 - c1))
        can_separate = bool(np.isfinite(separation) and separation >= 1.25)
        count_0 = int(np.sum(assign == 0))
        count_1 = int(np.sum(assign == 1))
        reason = ("Two speakers detected" if can_separate
                  else "Speakers too similar (score {:.2f})".format(separation))
        return json.dumps({
            "can_separate": can_separate,
            "confidence": round(separation, 3),
            "subject1_segment_count": count_0,
            "subject2_segment_count": count_1,
            "total_segment_count": count_0 + count_1,
            "reason": reason,
        })
    except Exception as e:
        return json.dumps({"can_separate": False, "confidence": 0.0, "reason": str(e)})


def run_model_per_file(audio_path, img_dir, model_path, scaler_path=None, speaker_focus="all"):
    _safe_mkdir(img_dir)

    if not os.path.exists(audio_path) or not os.path.exists(model_path):
        reason = "Audio file is missing" if not os.path.exists(audio_path) else "Model file is missing"
        _append_log(img_dir, f"Skipped {os.path.basename(audio_path)}: {reason}.")
        return json.dumps({"status": "skipped", "reason": reason})

    audio_name = os.path.splitext(os.path.basename(audio_path))[0]

    try:
        prepared = _prepare_features(audio_path, scaler_path, speaker_focus, model_path=model_path, img_dir=img_dir)
        if prepared is None:
            reason = "Feature preparation returned no usable data"
            _append_log(img_dir, f"Skipped {audio_name}: {reason}.")
            return json.dumps({"status": "skipped", "reason": reason, "file": os.path.basename(audio_path)})
        probs = _predict_tflite(model_path, prepared["features"])

        bar_chart_path = _save_bar_chart(audio_name, probs, img_dir)
        if bar_chart_path is None:
            _append_log(img_dir, f"Chart warning for {audio_name}: matplotlib is unavailable, so bar chart was not written.")

        payload = {
            "file": prepared["file"],
            "labels": CLASS_LABELS,
            "probs": probs.tolist(),
            "predicted": CLASS_LABELS[int(np.argmax(probs))],
            "speaker_focus_requested": speaker_focus,
            "speaker_focus_applied": prepared["speaker_focus_applied"],
        }
        with open(os.path.join(img_dir, f"{audio_name}_result.json"), "w", encoding="utf-8") as f:
            json.dump(payload, f)

        return json.dumps(
            {
                "status": "processed",
                "predicted": payload["predicted"],
                "speaker_focus_applied": payload["speaker_focus_applied"],
                "chart_written": bool(bar_chart_path),
            }
        )
    except Exception as e:
        reason = f"{type(e).__name__}: {e}"
        _append_log(img_dir, f"Skipped {audio_name}: {reason}")
        return json.dumps({"status": "skipped", "reason": reason, "file": os.path.basename(audio_path)})


def run_model_batch(sound_dir, img_dir, model_path, scaler_path=None, speaker_focus="all"):
    _safe_mkdir(img_dir)
    _reset_log(img_dir)
    if not os.path.isdir(sound_dir) or not os.path.exists(model_path):
        reason = "Sound directory or model path is missing"
        _append_log(img_dir, f"Batch aborted: {reason}.")
        return json.dumps({"status": "skipped", "reason": reason})

    wav_files = [
        os.path.join(sound_dir, name)
        for name in sorted(os.listdir(sound_dir))
        if name.lower().endswith(".wav")
    ]
    if not wav_files:
        reason = "No WAV files found"
        _append_log(img_dir, f"Batch aborted: {reason}.")
        return json.dumps({"status": "skipped", "reason": reason})

    fallback_to_all_count = 0
    processed_count = 0
    skipped_count = 0

    for audio_path in wav_files:
        result = run_model_per_file(audio_path, img_dir, model_path, scaler_path, speaker_focus)
        try:
            payload = json.loads(result)
        except Exception:
            payload = {"status": "skipped", "reason": "Unknown processing response"}

        if payload.get("status") != "processed":
            skipped_count += 1
            continue
        processed_count += 1
        try:
            if speaker_focus != "all" and not payload.get("speaker_focus_applied", True):
                fallback_to_all_count += 1
        except Exception:
            pass

    pie_result = generate_pie_chart(img_dir)
    bar_result = combine_bar_charts(img_dir)
    summary_result = generate_summary(img_dir)

    if processed_count == 0:
        _append_log(img_dir, "Batch finished with zero valid prediction results. No charts could be generated.")
    if pie_result == "NoPlotter" or bar_result == "NoPlotter":
        _append_log(img_dir, "Chart generation warning: matplotlib is unavailable in the current Python runtime.")
    elif pie_result == "NoData" or bar_result == "NoData":
        _append_log(img_dir, "Chart generation skipped because no prediction outputs were available.")
    else:
        _append_log(img_dir, f"Chart generation complete. Pie={pie_result}, Bars={bar_result}")

    if summary_result == "NoData":
        _append_log(img_dir, "Summary generation skipped because no prediction outputs were available.")
    else:
        _append_log(img_dir, f"Summary written to {summary_result}")

    return json.dumps(
        {
            "status": "completed",
            "processed_count": processed_count,
            "skipped_count": skipped_count,
            "fallback_to_all_count": fallback_to_all_count,
            "pie_chart_status": pie_result,
            "bar_chart_status": bar_result,
            "summary_status": summary_result,
            "log_path": _log_path(img_dir),
        }
    )


def run_model_comparison(sound_dir, compare_root_dir, scaler_path, models_json, speaker_focus="all"):
    _safe_mkdir(compare_root_dir)
    if not os.path.isdir(sound_dir):
        return json.dumps({"status": "skipped", "reason": "Sound directory not found"})

    wav_files = [
        os.path.join(sound_dir, name)
        for name in sorted(os.listdir(sound_dir))
        if name.lower().endswith(".wav")
    ]
    if not wav_files:
        return json.dumps({"status": "skipped", "reason": "No WAV files found"})

    try:
        models = json.loads(models_json)
    except Exception as e:
        return json.dumps({"status": "skipped", "reason": "Invalid models_json: {}".format(e)})
    if not models:
        return json.dumps({"status": "skipped", "reason": "Empty models list"})

    # Pre-extract features per audio file, adapting to each model's input shape per model loop below
    prepared_entries_raw = []
    for audio_path in wav_files:
        if librosa is not None:
            try:
                y, _ = librosa.load(audio_path, sr=SR)
            except Exception:
                y = None
        else:
            y = None
        prepared_entries_raw.append({"audio_path": audio_path, "y": y})

    if not prepared_entries_raw:
        return json.dumps({"status": "skipped", "reason": "No audio could be loaded"})

    results = []
    summary_lines = [f"Model comparison for {len(models)} saved model(s):"]

    for model in models:
        model_id = model.get("id")
        model_name = model.get("name", model_id or "Model")
        model_path = model.get("path")
        if not model_id or not model_path or not os.path.exists(model_path):
            continue

        model_dir = os.path.join(compare_root_dir, model_id)
        _safe_mkdir(model_dir)
        for existing in os.listdir(model_dir):
            existing_path = os.path.join(model_dir, existing)
            if os.path.isfile(existing_path):
                os.remove(existing_path)

        processed_count = 0
        fallback_to_all_count = 0
        for raw in prepared_entries_raw:
            prepared = _prepare_features(
                raw["audio_path"], scaler_path, speaker_focus,
                model_path=model_path, img_dir=model_dir
            )
            if prepared is None:
                continue
            if speaker_focus != "all" and not prepared["speaker_focus_applied"]:
                fallback_to_all_count += 1
            probs = _predict_tflite(model_path, prepared["features"])
            _save_bar_chart(prepared["audio_name"], probs, model_dir)
            payload = {
                "file": prepared["file"],
                "labels": CLASS_LABELS,
                "probs": probs.tolist(),
                "predicted": CLASS_LABELS[int(np.argmax(probs))],
                "speaker_focus_requested": speaker_focus,
                "speaker_focus_applied": prepared["speaker_focus_applied"],
            }
            with open(os.path.join(model_dir, f"{prepared['audio_name']}_result.json"), "w", encoding="utf-8") as f:
                json.dump(payload, f)
            processed_count += 1

        generate_pie_chart(model_dir)
        combine_bar_charts(model_dir)
        generate_summary(model_dir)

        summary_file = os.path.join(model_dir, "summary.json")
        summary_json = {}
        if os.path.exists(summary_file):
            with open(summary_file, "r", encoding="utf-8") as f:
                summary_json = json.load(f)

        top_label = summary_json.get("top_label", "-")
        avg_confidence = float(summary_json.get("avg_confidence", 0.0))
        total_files = int(summary_json.get("total_files", processed_count))
        results.append(
            {
                "id": model_id,
                "name": model_name,
                "path": model_path,
                "top_label": top_label,
                "avg_confidence": avg_confidence,
                "total_files": total_files,
                "fallback_to_all_count": fallback_to_all_count,
            }
        )
        summary_lines.append(
            f"- {model_name}: {top_label}, avg {avg_confidence * 100.0:.2f}%, files {total_files}, fallback {fallback_to_all_count}"
        )

    return json.dumps(
        {
            "results": results,
            "summary_text": "\n".join(summary_lines),
        }
    )


def generate_pie_chart(img_dir):
    plt = _get_plotter()
    if plt is None:
        return "NoPlotter"
    result_files = [
        os.path.join(img_dir, x)
        for x in os.listdir(img_dir)
        if x.endswith("_result.json")
    ]

    if not result_files:
        return "NoData"

    counts = {label: 0 for label in CLASS_LABELS}
    for rf in result_files:
        with open(rf, "r", encoding="utf-8") as f:
            data = json.load(f)
        pred = data.get("predicted")
        if pred in counts:
            counts[pred] += 1

    labels = [k for k, v in counts.items() if v > 0]
    values = [counts[k] for k in labels]
    if not values:
        return "NoData"

    out_path = os.path.join(img_dir, "pie_chart.png")
    plt.figure(figsize=(6, 6))
    plt.pie(values, labels=labels, autopct="%1.1f%%", startangle=90)
    plt.title("Prediction Distribution")
    plt.tight_layout()
    plt.savefig(out_path)
    plt.close()
    return out_path


def combine_bar_charts(img_dir):
    plt = _get_plotter()
    if plt is None:
        return "NoPlotter"
    bar_files = sorted(
        [os.path.join(img_dir, x) for x in os.listdir(img_dir) if x.endswith("_bar.png")]
    )
    if not bar_files:
        return "NoData"

    images = [plt.imread(p) for p in bar_files]
    fig, axes = plt.subplots(len(images), 1, figsize=(10, 4 * len(images)))
    if len(images) == 1:
        axes = [axes]

    for ax, img, path in zip(axes, images, bar_files):
        ax.imshow(img)
        ax.axis("off")
        ax.set_title(os.path.basename(path))

    plt.tight_layout()
    out_path = os.path.join(img_dir, "all_bar_combined.png")
    plt.savefig(out_path)
    plt.close()
    return out_path


def generate_summary(img_dir):
    result_files = [
        os.path.join(img_dir, x)
        for x in os.listdir(img_dir)
        if x.endswith("_result.json")
    ]
    if not result_files:
        return "NoData"

    counts = {label: 0 for label in CLASS_LABELS}
    confidences = []

    for rf in result_files:
        with open(rf, "r", encoding="utf-8") as f:
            data = json.load(f)
        pred = data.get("predicted")
        probs = data.get("probs", [])
        if pred in counts:
            counts[pred] += 1
        if probs:
            confidences.append(float(max(probs)))

    total = len(result_files)
    avg_conf = (sum(confidences) / len(confidences)) if confidences else 0.0
    top_label = max(counts.items(), key=lambda x: x[1])[0]

    summary = {
        "total_files": total,
        "top_label": top_label,
        "avg_confidence": avg_conf,
        "counts": counts,
    }

    out_json = os.path.join(img_dir, "summary.json")
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(summary, f)

    out_txt = os.path.join(img_dir, "summary.txt")
    with open(out_txt, "w", encoding="utf-8") as f:
        f.write(f"Total files: {total}\n")
        f.write(f"Dominant label: {top_label}\n")
        f.write(f"Average confidence: {avg_conf:.4f}\n")
        for label in CLASS_LABELS:
            f.write(f"{label}: {counts.get(label, 0)}\n")

    return out_json
