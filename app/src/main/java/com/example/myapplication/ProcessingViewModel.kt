package com.example.myapplication

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.example.myapplication.core.StoredModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

data class ProcessingRequest(
    val soundDir: File,
    val imgDir: File,
    val modelPath: String,
    val scalerPath: String,
    val speakerFocus: String
)

data class ProcessingUiState(
    val isProcessing: Boolean = false,
    val completed: Boolean = false,
    val processedCount: Int = 0,
    val skippedCount: Int = 0,
    val fallbackToAllCount: Int = 0,
    val errorMessage: String? = null,
    val warningMessage: String? = null,
    val isComparing: Boolean = false,
    val comparisonReport: String? = null,
    val statusTitle: String = "",
    val statusDetail: String = ""
)

data class SpeakerAnalysisResult(
    val canSeparate: Boolean,
    val confidence: Float,
    val subject1Segments: Int,
    val subject2Segments: Int,
    val reason: String
)

private data class BatchProcessingResult(
    val status: String = "",
    val processedCount: Int = 0,
    val skippedCount: Int = 0,
    val fallbackToAllCount: Int = 0,
    val pieChartStatus: String = "",
    val barChartStatus: String = "",
    val summaryStatus: String = "",
    val reason: String? = null
)

data class DetectedSpeakerSegment(
    val startMs: Long,
    val endMs: Long,
    val speakerLabel: String,   // "User 1", "Subject 2", "Subject 3", "Subject 4"
    val confidence: Float       // 0.0–1.0
) : java.io.Serializable

sealed class SpeakerDetectionState {
    object Idle : SpeakerDetectionState()
    object Scanning : SpeakerDetectionState()
    data class Complete(val segments: List<DetectedSpeakerSegment>) : SpeakerDetectionState()
    data class Error(val message: String) : SpeakerDetectionState()
}

class ProcessingViewModel : ViewModel() {
    companion object {
        private const val TAG = "ProcessingViewModel"
    }

    private val _uiState = MutableStateFlow(ProcessingUiState())
    val uiState: StateFlow<ProcessingUiState> = _uiState.asStateFlow()

    private val _speakerAnalysis = MutableStateFlow<SpeakerAnalysisResult?>(null)
    val speakerAnalysis: StateFlow<SpeakerAnalysisResult?> = _speakerAnalysis.asStateFlow()

    private val _detectionState = MutableStateFlow<SpeakerDetectionState>(SpeakerDetectionState.Idle)
    val detectionState: StateFlow<SpeakerDetectionState> = _detectionState.asStateFlow()

    fun detectSpeakers(wavFile: File, user1FingerprintJson: String?) {
        viewModelScope.launch {
            _detectionState.value = SpeakerDetectionState.Scanning
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("speaker_detect")
                    val resultJson = module.callAttr(
                        "detect_speakers",
                        wavFile.absolutePath,
                        user1FingerprintJson,
                        4
                    ).toString()
                    parseDetectionResult(resultJson)
                }
            }
            _detectionState.value = result.fold(
                onSuccess = { segments -> SpeakerDetectionState.Complete(segments) },
                onFailure = { e -> SpeakerDetectionState.Error(e.message ?: "Detection failed") }
            )
        }
    }

    fun resetDetection() {
        _detectionState.value = SpeakerDetectionState.Idle
    }

    private fun parseDetectionResult(json: String): List<DetectedSpeakerSegment> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            DetectedSpeakerSegment(
                startMs = obj.optLong("start_ms", 0L),
                endMs = obj.optLong("end_ms", 0L),
                speakerLabel = obj.optString("label", "Subject"),
                confidence = obj.optDouble("confidence", 0.5).toFloat()
            )
        }.filter { it.endMs > it.startMs }
    }

    fun analyzeSpeakers(firstAudioFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val raw = py.getModule("stresstest")
                    .callAttr("analyze_speakers", firstAudioFile.absolutePath)
                    ?.toString() ?: return@launch
                val json = JSONObject(raw)
                _speakerAnalysis.value = SpeakerAnalysisResult(
                    canSeparate      = json.optBoolean("can_separate", false),
                    confidence       = json.optDouble("confidence", 0.0).toFloat(),
                    subject1Segments = json.optInt("subject1_segment_count", 0),
                    subject2Segments = json.optInt("subject2_segment_count", 0),
                    reason           = json.optString("reason", "")
                )
            } catch (_: Exception) {
                _speakerAnalysis.value = SpeakerAnalysisResult(
                    canSeparate = false, confidence = 0f,
                    subject1Segments = 0, subject2Segments = 0,
                    reason = "Analysis unavailable"
                )
            }
        }
    }

    fun startProcessing(request: ProcessingRequest) {
        if (_uiState.value.isProcessing) return

        _uiState.value = ProcessingUiState(
            isProcessing = true,
            statusTitle = "Preparing audio session",
            statusDetail = "Scanning the selected WAV files and clearing old output."
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                request.imgDir.mkdirs()
                clearPreviousOutputs(request.imgDir)

                val wavFiles = request.soundDir.listFiles()?.filter { it.extension.lowercase() == "wav" } ?: emptyList()
                if (wavFiles.isEmpty()) {
                    _uiState.value = ProcessingUiState(errorMessage = "No WAV files found")
                    return@launch
                }

                Log.d(TAG, "Preparing Python processing for ${request.soundDir.absolutePath}")
                val py = Python.getInstance()
                val pyObj = py.getModule("stresstest")

                _uiState.value = ProcessingUiState(
                    isProcessing = true,
                    statusTitle = "Running audio analysis",
                    statusDetail = "Processing ${wavFiles.size} audio file(s) with the selected model. Long sessions can take a while."
                )
                Log.d(TAG, "Calling run_model_batch for ${wavFiles.size} WAV file(s)")

                val batchResult = if (request.speakerFocus == "all") {
                    pyObj.callAttr("run_model_batch", request.soundDir.absolutePath, request.imgDir.absolutePath, request.modelPath, request.scalerPath)
                } else {
                    pyObj.callAttr(
                        "run_model_batch",
                        request.soundDir.absolutePath,
                        request.imgDir.absolutePath,
                        request.modelPath,
                        request.scalerPath,
                        request.speakerFocus
                    )
                }

                _uiState.value = ProcessingUiState(
                    isProcessing = true,
                    statusTitle = "Finishing results",
                    statusDetail = "Reading the generated outputs and preparing charts."
                )

                val parsedResult = parseBatchResult(batchResult?.toString())
                if (parsedResult.status == "skipped") {
                    Log.w(TAG, "Processing skipped before predictions: ${parsedResult.reason}")
                    _uiState.value = ProcessingUiState(
                        errorMessage = parsedResult.reason ?: "Processing skipped before predictions were created"
                    )
                    return@launch
                }

                if (parsedResult.processedCount == 0) {
                    Log.w(TAG, "All files skipped. No predictions available for chart generation.")
                    _uiState.value = ProcessingUiState(
                        errorMessage = "All audio files were skipped. Check processing_log.txt in the session image folder for the reason."
                    )
                    return@launch
                }

                val warning = buildProcessingWarning(parsedResult)
                _uiState.value = ProcessingUiState(
                    completed = true,
                    processedCount = parsedResult.processedCount,
                    skippedCount = parsedResult.skippedCount,
                    fallbackToAllCount = parsedResult.fallbackToAllCount,
                    warningMessage = warning
                )
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed with an exception", e)
                _uiState.value = ProcessingUiState(errorMessage = "Processing failed")
            }
        }
    }

    fun resetCompletion() {
        _uiState.value = _uiState.value.copy(completed = false, errorMessage = null)
    }

    fun clearComparisonMessage() {
        _uiState.value = _uiState.value.copy(comparisonReport = null, errorMessage = null, isComparing = false)
    }

    private fun parseBatchResult(resultText: String?): BatchProcessingResult {
        if (resultText.isNullOrBlank() || resultText == "Skipped") {
            return BatchProcessingResult(status = "skipped", reason = "Processing returned no result")
        }
        return try {
            val json = JSONObject(resultText)
            BatchProcessingResult(
                status = json.optString("status", "completed"),
                processedCount = json.optInt("processed_count", 0),
                skippedCount = json.optInt("skipped_count", 0),
                fallbackToAllCount = json.optInt("fallback_to_all_count", 0),
                pieChartStatus = json.optString("pie_chart_status", ""),
                barChartStatus = json.optString("bar_chart_status", ""),
                summaryStatus = json.optString("summary_status", ""),
                reason = json.optString("reason").takeIf { it.isNotBlank() }
            )
        } catch (_: JSONException) {
            BatchProcessingResult(status = "skipped", reason = "Processing result could not be parsed")
        }
    }

    private fun buildProcessingWarning(result: BatchProcessingResult): String? {
        val warnings = mutableListOf<String>()
        if (result.pieChartStatus == "NoPlotter" || result.barChartStatus == "NoPlotter") {
            warnings += "Predictions finished, but matplotlib was unavailable so chart images were not created."
        } else if (
            result.processedCount > 0 &&
            (result.pieChartStatus == "NoData" || result.barChartStatus == "NoData")
        ) {
            warnings += "Predictions finished, but the chart step did not receive enough data to create images."
        }
        if (result.summaryStatus == "NoData") {
            warnings += "Summary output was not generated."
        }
        if (result.skippedCount > 0) {
            warnings += "${result.skippedCount} file(s) were skipped. See processing_log.txt for details."
        }
        return warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun clearPreviousOutputs(imgDir: File) {
        val generatedFiles = imgDir.listFiles() ?: return
        for (file in generatedFiles) {
            if (!file.isFile) continue
            val name = file.name
            if (
                name.endsWith("_result.json") ||
                name.endsWith("_bar.png") ||
                name == "pie_chart.png" ||
                name == "all_bar_combined.png" ||
                name == "summary.json" ||
                name == "summary.txt" ||
                name == "processing_log.txt"
            ) {
                file.delete()
            }
        }
    }

    fun compareModels(
        request: ProcessingRequest,
        models: List<StoredModel>,
        compareRootDir: File,
        reportFile: File
    ) {
        if (_uiState.value.isProcessing || _uiState.value.isComparing) return
        _uiState.value = ProcessingUiState(
            isComparing = true,
            statusTitle = "Preparing model comparison",
            statusDetail = "Collecting audio files and the saved models you selected."
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                compareRootDir.mkdirs()

                val wavFiles = request.soundDir.listFiles()?.filter { it.extension.lowercase() == "wav" } ?: emptyList()
                if (wavFiles.isEmpty()) {
                    _uiState.value = ProcessingUiState(errorMessage = "No WAV files found")
                    return@launch
                }

                Log.d(TAG, "Preparing model comparison for ${models.size} model(s)")
                val py = Python.getInstance()
                val pyObj = py.getModule("stresstest")

                _uiState.value = ProcessingUiState(
                    isComparing = true,
                    statusTitle = "Comparing saved models",
                    statusDetail = "Running ${models.size} model(s) across ${wavFiles.size} audio file(s). This is heavier than normal analysis."
                )

                val modelsJson = org.json.JSONArray().apply {
                    models.forEach { model ->
                        put(
                            JSONObject().apply {
                                put("id", model.id)
                                put("name", model.name)
                                put("path", model.path)
                            }
                        )
                    }
                }.toString()

                val rawResult = if (request.speakerFocus == "all") {
                    pyObj.callAttr(
                        "run_model_comparison",
                        request.soundDir.absolutePath,
                        compareRootDir.absolutePath,
                        request.scalerPath,
                        modelsJson
                    )
                } else {
                    pyObj.callAttr(
                        "run_model_comparison",
                        request.soundDir.absolutePath,
                        compareRootDir.absolutePath,
                        request.scalerPath,
                        modelsJson,
                        request.speakerFocus
                    )
                }

                val resultText = rawResult?.toString()
                if (resultText.isNullOrBlank() || resultText == "Skipped") {
                    Log.w(TAG, "Model comparison returned no usable result")
                    _uiState.value = ProcessingUiState(errorMessage = "Model comparison failed")
                    return@launch
                }

                _uiState.value = ProcessingUiState(
                    isComparing = true,
                    statusTitle = "Saving comparison report",
                    statusDetail = "Writing the summary so you can reopen it from history."
                )
                reportFile.writeText(resultText)
                val reportJson = JSONObject(resultText)
                _uiState.value = ProcessingUiState(
                    comparisonReport = reportJson.optString("summary_text", "Model comparison complete")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Model comparison failed with an exception", e)
                _uiState.value = ProcessingUiState(errorMessage = "Model comparison failed")
            }
        }
    }
}
