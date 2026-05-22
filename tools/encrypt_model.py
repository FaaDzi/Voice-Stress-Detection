#!/usr/bin/env python3
"""
Encrypt model.tflite -> model.enc for APK bundling.
Run this once before building: python tools/encrypt_model.py

Requirements: pip install cryptography
"""
import os
import sys

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    print("ERROR: Run `pip install cryptography` first.")
    sys.exit(1)

# Must match the KEY in ModelDecryptor.kt exactly
KEY = bytes([
    0x3A, 0xF1, 0x7C, 0xB8, 0x24, 0x9E, 0x56, 0xD0,
    0x8B, 0x4F, 0xC2, 0x71, 0xE3, 0x0D, 0xA6, 0x5C,
    0x19, 0x82, 0xBF, 0x43, 0x67, 0xD5, 0x1A, 0x9C,
    0xF4, 0x28, 0xEB, 0x60, 0x35, 0x7E, 0xB0, 0xC9,
])

ASSETS_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")


def encrypt_file(src_name: str, dst_name: str):
    src = os.path.join(ASSETS_DIR, src_name)
    dst = os.path.join(ASSETS_DIR, dst_name)
    if not os.path.exists(src):
        print(f"SKIP: {src_name} not found in assets/")
        return
    with open(src, "rb") as f:
        plaintext = f.read()
    nonce = os.urandom(12)
    ciphertext = AESGCM(KEY).encrypt(nonce, plaintext, None)
    with open(dst, "wb") as f:
        f.write(nonce + ciphertext)
    print(f"Encrypted {src_name} -> {dst_name}  ({len(plaintext):,} bytes plain, {len(nonce)+len(ciphertext):,} bytes encrypted)")


if __name__ == "__main__":
    encrypt_file("model.tflite", "model.enc")
    print("Done. You can now build the APK.")
