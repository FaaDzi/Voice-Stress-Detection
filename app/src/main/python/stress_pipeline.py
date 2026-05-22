import math


def _parse_csv_floats(csv_text):
    if csv_text is None:
        return []
    text = str(csv_text).strip()
    if not text:
        return []
    return [float(x) for x in text.split(",") if x != ""]


def _to_csv(values):
    return ",".join(str(v) for v in values)


def normalize_csv(features_csv, mean_csv, std_csv):
    features = _parse_csv_floats(features_csv)
    mean = _parse_csv_floats(mean_csv)
    std = _parse_csv_floats(std_csv)

    normalized = []
    for i, value in enumerate(features):
        m = mean[i] if i < len(mean) else 0.0
        s = std[i] if i < len(std) else 1.0
        normalized.append((value - m) / (s + 1e-8))
    return _to_csv(normalized)


def softmax_csv(logits_csv):
    logits = _parse_csv_floats(logits_csv)
    if not logits:
        return ""
    max_logit = max(logits)
    exp_values = [math.exp(x - max_logit) for x in logits]
    total = sum(exp_values) or 1.0
    probs = [x / total for x in exp_values]
    return _to_csv(probs)
