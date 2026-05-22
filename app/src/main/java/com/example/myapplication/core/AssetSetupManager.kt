package com.example.myapplication.core

import com.example.myapplication.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AssetSetupManager(
    private val storageManager: AppStorageManager,
    private val openAsset: (String) -> java.io.InputStream
) {
    data class ManifestAsset(
        val name: String,
        val localAsset: String?,
        val remotePath: String?,
        val sha256: String?,
        val required: Boolean
    )

    suspend fun runSetup(onProgress: (String, Int) -> Unit) = withContext(Dispatchers.IO) {
        storageManager.assetDir().mkdirs()
        storageManager.soundDir().mkdirs()
        storageManager.imgDir().mkdirs()

        val manifest = loadManifest()
        val total = manifest.size.coerceAtLeast(1)

        manifest.forEachIndexed { index, item ->
            val progress = (((index + 1).toFloat() / total) * 100).toInt().coerceIn(0, 100)
            onProgress("Preparing ${item.name}", progress)

            val target = File(storageManager.assetDir(), item.name)
            if (target.exists() && hashMatches(target, item.sha256)) return@forEachIndexed

            val downloaded = tryDownload(item, target)
            if (!downloaded) {
                if (!copyFromAssets(item, target) && item.required) {
                    throw IllegalStateException("Missing required asset: ${item.name}")
                }
            }

            if (target.exists() && !hashMatches(target, item.sha256)) {
                throw IllegalStateException("Checksum mismatch for ${item.name}")
            }
        }

        onProgress("Setup assets ready", 100)
        true
    }

    private fun loadManifest(): List<ManifestAsset> {
        val json = try {
            openAsset("asset_manifest.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            defaultManifestJson()
        }
        val root = JSONObject(json)
        val arr = root.optJSONArray("assets") ?: JSONArray()
        val list = mutableListOf<ManifestAsset>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                ManifestAsset(
                    name = obj.optString("name"),
                    localAsset = obj.optString("localAsset").ifBlank { null },
                    remotePath = obj.optString("remotePath").ifBlank { null },
                    sha256 = obj.optString("sha256").ifBlank { null },
                    required = obj.optBoolean("required", true)
                )
            )
        }
        return list
    }

    private fun defaultManifestJson(): String {
        return """
            {
              "assets": [
                {"name":"model.tflite","localAsset":"model.tflite","required":true},
                {"name":"scaler_values.json","localAsset":"scaler_values.json","required":true}
              ]
            }
        """.trimIndent()
    }

    private fun tryDownload(item: ManifestAsset, target: File): Boolean {
        val baseUrl = BuildConfig.ASSET_BASE_URL
        val remotePath = item.remotePath
        if (baseUrl.isBlank() || remotePath.isNullOrBlank()) return false
        if (item.sha256.isNullOrBlank()) return false

        return try {
            val fullUrl = baseUrl.trimEnd('/') + "/" + remotePath.trimStart('/')
            if (!fullUrl.startsWith("https://")) return false
            val url = URL(fullUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                requestMethod = "GET"
                doInput = true
            }

            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return false
            }

            val temp = File(target.parentFile, target.name + ".download")
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                }
            }
            connection.disconnect()
            if (!hashMatches(temp, item.sha256)) {
                temp.delete()
                return false
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copyFromAssets(item: ManifestAsset, target: File): Boolean {
        val localAsset = item.localAsset ?: return false
        return try {
            openAsset(localAsset).use { input ->
                if (localAsset.endsWith(".enc")) {
                    ModelDecryptor.decryptToFile(input, target)
                } else {
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hashMatches(file: File, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedSha256, ignoreCase = true)
    }
}
