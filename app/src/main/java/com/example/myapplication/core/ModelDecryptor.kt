package com.example.myapplication.core

import java.io.File
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ModelDecryptor {
    // Must match tools/encrypt_model.py KEY exactly
    private val KEY = byteArrayOf(
        0x3A.toByte(), 0xF1.toByte(), 0x7C.toByte(), 0xB8.toByte(),
        0x24.toByte(), 0x9E.toByte(), 0x56.toByte(), 0xD0.toByte(),
        0x8B.toByte(), 0x4F.toByte(), 0xC2.toByte(), 0x71.toByte(),
        0xE3.toByte(), 0x0D.toByte(), 0xA6.toByte(), 0x5C.toByte(),
        0x19.toByte(), 0x82.toByte(), 0xBF.toByte(), 0x43.toByte(),
        0x67.toByte(), 0xD5.toByte(), 0x1A.toByte(), 0x9C.toByte(),
        0xF4.toByte(), 0x28.toByte(), 0xEB.toByte(), 0x60.toByte(),
        0x35.toByte(), 0x7E.toByte(), 0xB0.toByte(), 0xC9.toByte()
    )

    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    fun decryptToFile(encStream: InputStream, output: File) {
        val encBytes = encStream.readBytes()
        require(encBytes.size > IV_SIZE) { "Encrypted asset is too short" }
        val iv = encBytes.copyOfRange(0, IV_SIZE)
        val ciphertext = encBytes.copyOfRange(IV_SIZE, encBytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        output.writeBytes(cipher.doFinal(ciphertext))
    }
}
