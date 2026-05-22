import sys


def main():
    print("=== CHECK MAIN DEPENDENCIES ===")
    print("-" * 30)

    missing = 0
    modules = [
        ("Numpy", "numpy"),
        ("Librosa", "librosa"),
        ("Matplotlib", "matplotlib"),
        ("Pandas", "pandas"),
    ]

    for name, mod in modules:
        try:
            parts = mod.split(".")
            module = __import__(parts[0])
            for sub in parts[1:]:
                module = getattr(module, sub)
            version = getattr(module, "__version__", "Unknown")
            print(f"[OK] {name}: {version}")
        except Exception:
            print(f"[MISSING] {name}")
            missing += 1

    try:
        from java import jclass
        jclass("org.tensorflow.lite.Interpreter")
        print("[OK] TensorFlow Lite Java bridge: Available")
    except Exception:
        print("[MISSING] TensorFlow Lite Java bridge")
        missing += 1

    print("-" * 30)
    if missing == 0:
        print("All dependencies are available.")
    else:
        print(f"{missing} module(s) missing.")

    sys.exit(0)
