package com.example.myapplication.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredModel(
    val id: String,
    val name: String,
    val path: String,
    val isDefault: Boolean,
    val sha256: String?,
    val inputFeatureSize: Int?,
    val outputClassCount: Int?
)

class ModelCatalogStore(
    private val context: Context,
    private val storageManager: AppStorageManager
) {
    companion object {
        private const val PREFS_NAME = "model_catalog_store"
        private const val KEY_MODELS = "models"
        private const val KEY_SELECTED_ID = "selected_id"
        private const val DEFAULT_MODEL_ID = "default"
        private const val MAX_TOTAL_MODELS = 4
        private const val MAX_CUSTOM_MODELS = 3
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var cachedModels: List<StoredModel>? = null
    @Volatile private var cachedDefaultModel: StoredModel? = null
    @Volatile private var cachedDefaultModelKey: String? = null

    fun selectedModel(): StoredModel = selectedModelOrNull() ?: defaultModel()

    fun selectedModelOrNull(): StoredModel? {
        val selectedId = prefs.getString(KEY_SELECTED_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        return loadModels().firstOrNull { it.id == selectedId }
    }

    fun loadModels(): List<StoredModel> {
        cachedModels?.let { return it }
        val list = mutableListOf(defaultModel())
        val raw = prefs.getString(KEY_MODELS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    list += StoredModel(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        path = item.getString("path"),
                        isDefault = false,
                        sha256 = item.optString("sha256").takeIf { it.isNotBlank() },
                        inputFeatureSize = item.optInt("inputFeatureSize", -1).takeIf { it > 0 },
                        outputClassCount = item.optInt("outputClassCount", -1).takeIf { it > 0 }
                    )
                }
            } catch (_: Exception) {
            }
        }
        val result = list.filter { it.isDefault || File(it.path).exists() }
        cachedModels = result
        return result
    }

    fun canAddCustomModel(): Boolean = customModels().size < MAX_CUSTOM_MODELS

    fun customModels(): List<StoredModel> = loadModels().filterNot { it.isDefault }

    fun saveCustomModel(
        sourceName: String,
        sourceFile: File,
        validation: TfliteModelValidator.ValidationResult
    ): Result<StoredModel> {
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        if (!canAddCustomModel()) {
            return Result.failure(IllegalStateException("Maximum saved models reached. Remove one custom model first."))
        }

        val modelsDir = File(storageManager.assetDir(), "models").apply { mkdirs() }
        val nextId = nextCustomId()
        val target = File(modelsDir, "$nextId.tflite")
        sourceFile.copyTo(target, overwrite = true)

        val entry = StoredModel(
            id = nextId,
            name = sourceName.ifBlank { "Custom Model ${customModels().size + 1}" },
            path = target.absolutePath,
            isDefault = false,
            sha256 = validation.sha256,
            inputFeatureSize = validation.inputFeatureSize,
            outputClassCount = validation.outputClassCount
        )

        val updated = customModels().filterNot { it.id == entry.id } + entry
        persistCustomModels(updated)
        selectModel(entry.id)
        return Result.success(entry)
    }

    fun selectModel(modelId: String) {
        val validId = loadModels().firstOrNull { it.id == modelId }?.id ?: DEFAULT_MODEL_ID
        prefs.edit().putString(KEY_SELECTED_ID, validId).apply()
    }

    fun removeCustomModel(modelId: String): Boolean {
        if (modelId == DEFAULT_MODEL_ID) return false
        val current = customModels()
        val updated = current.filterNot { it.id == modelId }
        if (updated.size == current.size) return false
        persistCustomModels(updated)
        if ((prefs.getString(KEY_SELECTED_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID) == modelId) {
            selectModel(DEFAULT_MODEL_ID)
        }
        return true
    }

    fun currentModelInfo(): String {
        val model = selectedModel()
        val lines = mutableListOf<String>()
        lines += "Name: ${model.name}"
        lines += "Type: ${if (model.isDefault) "Default" else "Custom"}"
        lines += "Registered path: ${model.path}"
        if (!model.sha256.isNullOrBlank()) lines += "SHA-256: ${model.sha256}"
        if (model.inputFeatureSize != null) lines += "Input features: ${model.inputFeatureSize}"
        if (model.outputClassCount != null) lines += "Output classes: ${model.outputClassCount}"
        return lines.joinToString("\n")
    }

    fun comparisonCandidates(): List<StoredModel> = loadModels()

    private fun defaultModel(): StoredModel {
        val defaultPath = File(storageManager.assetDir(), "model.tflite")
        val key = "${defaultPath.absolutePath}:${defaultPath.lastModified()}"
        if (cachedDefaultModelKey == key) cachedDefaultModel?.let { return it }
        val validation = if (defaultPath.exists()) {
            runCatching { TfliteModelValidator.validate(defaultPath, null) }.getOrNull()
        } else {
            null
        }
        val model = StoredModel(
            id = DEFAULT_MODEL_ID,
            name = "Default Model",
            path = defaultPath.absolutePath,
            isDefault = true,
            sha256 = validation?.sha256,
            inputFeatureSize = validation?.inputFeatureSize,
            outputClassCount = validation?.outputClassCount
        )
        cachedDefaultModel = model
        cachedDefaultModelKey = key
        return model
    }

    private fun persistCustomModels(models: List<StoredModel>) {
        cachedModels = null
        val capped = models.take(MAX_TOTAL_MODELS - 1)
        val array = JSONArray()
        capped.forEach { model ->
            array.put(
                JSONObject().apply {
                    put("id", model.id)
                    put("name", model.name)
                    put("path", model.path)
                    put("sha256", model.sha256 ?: "")
                    put("inputFeatureSize", model.inputFeatureSize ?: -1)
                    put("outputClassCount", model.outputClassCount ?: -1)
                }
            )
        }
        prefs.edit().putString(KEY_MODELS, array.toString()).apply()
    }

    private fun nextCustomId(): String {
        val usedIds = customModels().map { it.id }.toSet()
        for (idx in 1..MAX_CUSTOM_MODELS) {
            val candidate = "custom_$idx"
            if (candidate !in usedIds) return candidate
        }
        return "custom_${System.currentTimeMillis()}"
    }
}
