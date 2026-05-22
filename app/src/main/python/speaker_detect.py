import json

try:
    import numpy as np
except Exception:
    np = None

try:
    import librosa
except Exception:
    librosa = None


def extract_user_fingerprint(raw_pcm_path: str, sample_rate: int = 16000) -> str:
    """
    Reads raw 16-bit PCM file, extracts mean MFCC vector (40 coefficients),
    returns as JSON string: {"mfcc": [float, float, ...], "sample_rate": int}
    """
    if np is None:
        raise RuntimeError("numpy is not available")
    if librosa is None:
        raise RuntimeError("librosa is not available")

    with open(raw_pcm_path, "rb") as f:
        data = f.read()

    audio = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    mfcc = librosa.feature.mfcc(y=audio, sr=sample_rate, n_mfcc=40)
    fingerprint = np.mean(mfcc, axis=1).tolist()
    return json.dumps({"mfcc": fingerprint, "sample_rate": sample_rate})


def detect_speakers(wav_path: str, user1_fingerprint_json: str = None, max_speakers: int = 4) -> str:
    """
    Segments the audio by speaker. Returns JSON string:
    [{"start_ms": int, "end_ms": int, "label": str, "confidence": float}, ...]

    Labels: "User 1" for matched user, "Subject 2"/"Subject 3"/"Subject 4" for clustered unknowns
    """
    try:
        if np is None:
            raise RuntimeError("numpy is not available")
        if librosa is None:
            raise RuntimeError("librosa is not available")

        # 1. Load audio
        audio, sr = librosa.load(wav_path, sr=16000, mono=True)

        # 2. Voice Activity Detection (energy-based)
        frame_length = 512
        hop_length = 256
        rms = librosa.feature.rms(y=audio, frame_length=frame_length, hop_length=hop_length)[0]

        threshold = 0.1 * float(np.max(rms)) if np.max(rms) > 0 else 0.0
        speech_frames = rms > threshold

        # Convert frame indices to time in seconds
        frame_times = librosa.frames_to_time(
            np.arange(len(speech_frames)), sr=sr, hop_length=hop_length
        )

        # Build raw speech intervals from frame-level mask
        raw_intervals = []
        in_speech = False
        seg_start = 0.0
        for i, is_speech in enumerate(speech_frames):
            if is_speech and not in_speech:
                seg_start = float(frame_times[i])
                in_speech = True
            elif not is_speech and in_speech:
                raw_intervals.append((seg_start, float(frame_times[i])))
                in_speech = False
        if in_speech:
            raw_intervals.append((seg_start, float(frame_times[-1])))

        # Merge adjacent regions (gap < 0.3 s) and discard segments < 0.5 s
        merge_gap = 0.3
        min_duration = 0.5
        merged = []
        for start, end in raw_intervals:
            if merged and (start - merged[-1][1]) < merge_gap:
                merged[-1] = (merged[-1][0], end)
            else:
                merged.append((start, end))
        speech_segments = [(s, e) for s, e in merged if (e - s) >= min_duration]

        if not speech_segments:
            return json.dumps([])

        # 3. Extract MFCC feature vector for each speech segment
        def _segment_mfcc(start_sec, end_sec):
            start_sample = int(start_sec * sr)
            end_sample = int(end_sec * sr)
            seg = audio[start_sample:end_sample]
            if len(seg) < frame_length:
                seg = np.pad(seg, (0, frame_length - len(seg)))
            mfcc = librosa.feature.mfcc(y=seg, sr=sr, n_mfcc=40)
            return np.mean(mfcc, axis=1)  # shape: (40,)

        def _cosine_similarity(a, b):
            denom = (np.linalg.norm(a) * np.linalg.norm(b))
            if denom < 1e-10:
                return 0.0
            return float(np.dot(a, b) / denom)

        segment_features = []
        for start, end in speech_segments:
            feat = _segment_mfcc(start, end)
            segment_features.append(feat)

        # 4. Match User 1 if fingerprint provided
        user1_mfcc = None
        if user1_fingerprint_json is not None:
            try:
                fp_data = json.loads(user1_fingerprint_json)
                user1_mfcc = np.array(fp_data["mfcc"], dtype=np.float32)
            except Exception:
                user1_mfcc = None

        USER1_THRESHOLD = 0.75
        labels = [None] * len(speech_segments)
        confidences = [0.5] * len(speech_segments)

        if user1_mfcc is not None:
            for i, feat in enumerate(segment_features):
                sim = _cosine_similarity(feat, user1_mfcc)
                if sim > USER1_THRESHOLD:
                    labels[i] = "User 1"
                    confidences[i] = float(sim)

        # 5. Cluster unknowns
        CLUSTER_THRESHOLD = 0.70
        subject_names = ["Subject 2", "Subject 3", "Subject 4"]
        # cluster_centers[k] = running mean feature vector for that subject cluster
        cluster_centers = []   # list of np arrays
        cluster_counts = []    # list of int for computing running mean

        for i, feat in enumerate(segment_features):
            if labels[i] is not None:
                continue  # already labelled as User 1

            best_cluster = -1
            best_sim = -1.0
            for k, center in enumerate(cluster_centers):
                sim = _cosine_similarity(feat, center)
                if sim > best_sim:
                    best_sim = sim
                    best_cluster = k

            if best_cluster >= 0 and best_sim > CLUSTER_THRESHOLD:
                # Assign to existing cluster and update running mean
                k = best_cluster
                n = cluster_counts[k]
                cluster_centers[k] = (cluster_centers[k] * n + feat) / (n + 1)
                cluster_counts[k] = n + 1
                labels[i] = subject_names[k]
                confidences[i] = float(best_sim)
            else:
                # New cluster — only if we haven't hit max_speakers
                # max_speakers counts User 1 as one slot; subjects fill the rest
                total_clusters_used = 1 + len(cluster_centers)  # 1 for User 1 slot
                if total_clusters_used < max_speakers:
                    cluster_centers.append(feat.copy().astype(np.float32))
                    cluster_counts.append(1)
                    subject_idx = len(cluster_centers) - 1
                    labels[i] = subject_names[subject_idx]
                    confidences[i] = 1.0
                else:
                    # Assign to the closest existing cluster regardless of threshold
                    if cluster_centers:
                        k = best_cluster if best_cluster >= 0 else 0
                        labels[i] = subject_names[k]
                        confidences[i] = max(0.0, float(best_sim))
                    else:
                        labels[i] = "Subject 2"
                        confidences[i] = 0.5

        # 6. Build result list
        result_list = []
        for i, (start, end) in enumerate(speech_segments):
            result_list.append({
                "start_ms": int(start * 1000),
                "end_ms": int(end * 1000),
                "label": labels[i] if labels[i] is not None else "Subject 2",
                "confidence": round(confidences[i], 4),
            })

        result_list.sort(key=lambda x: x["start_ms"])
        return json.dumps(result_list)

    except Exception:
        return json.dumps([])


if __name__ == "__main__":
    print("speaker_detect module loaded")
