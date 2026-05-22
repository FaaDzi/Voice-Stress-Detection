package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.myapplication.core.AppThemeManager
import com.example.myapplication.core.AppStorageManager
import com.example.myapplication.core.AppDialogs
import com.example.myapplication.core.DeviceCapabilityChecker
import com.example.myapplication.core.ModelCatalogStore
import com.example.myapplication.core.TaskForegroundService
import com.example.myapplication.core.setDebouncedClickListener
import coil.load
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProcessingActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ProcessingActivity"
        private const val LOG_REFRESH_INTERVAL_MS = 2500L
    }

    private lateinit var processButton: Button
    private lateinit var backButton: ImageButton
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var predictionPager: ViewPager2
    private lateinit var pieChartImage: ImageView
    private lateinit var summaryText: TextView
    private lateinit var comparisonSummaryText: TextView
    private lateinit var processingLogToggle: TextView
    private lateinit var processingLogScroll: ScrollView
    private lateinit var processingLogText: TextView
    private lateinit var loadingTitleText: TextView
    private lateinit var loadingDetailText: TextView
    private lateinit var loadingElapsedText: TextView
    private lateinit var loadingLogToggle: TextView
    private lateinit var loadingLogContainer: LinearLayout
    private lateinit var loadingLogScroll: ScrollView
    private lateinit var loadingLogText: TextView
    private lateinit var speakerFocusGroup: MaterialButtonToggleGroup
    private lateinit var speakerModeHint: TextView
    private lateinit var compareModelsButton: Button
    private lateinit var modelCatalogStore: ModelCatalogStore
    private val processingViewModel: ProcessingViewModel by viewModels()

    private var soundFolderPath: String? = null
    private var imgFolderPath: String? = null
    private var selectedTflitePath: String? = null
    private var selectedModelDisplayName: String? = null
    private var speakerFocus: String = "all"
    private var assetDirPath: String? = null
    private var appRootPath: String? = null
    private var sessionId: String? = null
    private var manualSegments: ArrayList<AudioSegment> = arrayListOf()
    private var primaryAudioName: String? = null
    private lateinit var storageManager: AppStorageManager
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var overlayStartedAtMs: Long = 0L
    private var lastOverlayMessage: String = ""
    private val overlayLogLines = mutableListOf<String>()
    private var cachedProcessingLogText: String = ""
    private var cachedProcessingLogModifiedAt: Long = -1L
    private var logRefreshJob: Job? = null
    private val overlayTicker = object : Runnable {
        override fun run() {
            if (loadingOverlay.visibility == View.VISIBLE && overlayStartedAtMs > 0L) {
                val elapsedSeconds = ((System.currentTimeMillis() - overlayStartedAtMs) / 1000L).coerceAtLeast(0L)
                val minutes = elapsedSeconds / 60L
                val seconds = elapsedSeconds % 60L
                loadingElapsedText.text = String.format("%02d:%02d", minutes, seconds)
                overlayHandler.postDelayed(this, 1000L)
            }
        }
    }
    private val overlayLogTicker = object : Runnable {
        override fun run() {
            if (loadingOverlay.visibility == View.VISIBLE) {
                val shouldRefreshLog = loadingLogContainer.visibility == View.VISIBLE ||
                    processingLogScroll.visibility == View.VISIBLE
                if (shouldRefreshLog) {
                    refreshProcessingLogCache {
                        renderOverlayLog()
                        if (processingLogScroll.visibility == View.VISIBLE) {
                            applyPersistentLogText()
                        }
                    }
                }
                overlayHandler.postDelayed(this, LOG_REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_processing)
        storageManager = AppStorageManager(this)

        processButton = findViewById(R.id.processButton)
        backButton = findViewById(R.id.backButton)
        backButton.setDebouncedClickListener { onBackPressedDispatcher.onBackPressed() }
        predictionPager = findViewById(R.id.predictionPager)
        pieChartImage = findViewById(R.id.pieChartImage)
        summaryText = findViewById(R.id.summaryText)
        comparisonSummaryText = findViewById(R.id.comparisonSummaryText)
        comparisonSummaryText.visibility = View.GONE
        processingLogToggle = findViewById(R.id.processingLogToggle)
        processingLogScroll = findViewById(R.id.processingLogScroll)
        processingLogText = findViewById(R.id.processingLogText)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingTitleText = findViewById(R.id.loadingTitleText)
        loadingDetailText = findViewById(R.id.loadingDetailText)
        loadingElapsedText = findViewById(R.id.loadingElapsedText)
        loadingLogToggle = findViewById(R.id.loadingLogToggle)
        loadingLogContainer = findViewById(R.id.loadingLogContainer)
        loadingLogScroll = findViewById(R.id.loadingLogScroll)
        loadingLogText = findViewById(R.id.loadingLogText)
        speakerFocusGroup = findViewById(R.id.speakerFocusGroup)
        speakerModeHint = findViewById(R.id.speakerModeHint)
        compareModelsButton = findViewById(R.id.compareModelsButton)
        modelCatalogStore = ModelCatalogStore(this, storageManager)
        processingLogToggle.setDebouncedClickListener { togglePersistentLog() }
        loadingLogToggle.setDebouncedClickListener { toggleOverlayLog() }

        soundFolderPath = intent.getStringExtra("SOUND_FOLDER_PATH")
        selectedTflitePath = intent.getStringExtra("TFLITE_SELECTED")
        selectedModelDisplayName = intent.getStringExtra("MODEL_DISPLAY_NAME")
        speakerFocus = (intent.getStringExtra("SPEAKER_FOCUS") ?: "all").lowercase()
        assetDirPath = intent.getStringExtra("ASSET_DIR")
        appRootPath = intent.getStringExtra("APP_ROOT_DIR")
        sessionId = intent.getStringExtra("SESSION_ID")
        @Suppress("DEPRECATION")
        manualSegments = (intent.getSerializableExtra("AUDIO_SEGMENTS") as? ArrayList<AudioSegment>) ?: arrayListOf()
        primaryAudioName = intent.getStringExtra("PRIMARY_AUDIO_NAME")
        speakerFocusGroup.check(focusToButtonId(speakerFocus))
        speakerFocusGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            speakerFocus = buttonIdToFocus(checkedId)
            updateSpeakerModeHint()
        }

        val resolvedSessionId = sessionId ?: storageManager.createSessionId()
        sessionId = resolvedSessionId
        imgFolderPath = storageManager.sessionImgDir(resolvedSessionId).absolutePath

        if (soundFolderPath.isNullOrEmpty() || selectedTflitePath.isNullOrEmpty()) {
            Toast.makeText(this, "Incomplete processing data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        processButton.setDebouncedClickListener {
            speakerFocus = buttonIdToFocus(speakerFocusGroup.checkedButtonId)
            Log.d(TAG, "Begin standard processing. focus=$speakerFocus session=$sessionId")
            Toast.makeText(this, "Processing audio files...", Toast.LENGTH_SHORT).show()
            startProcessing()
        }
        compareModelsButton.setDebouncedClickListener {
            speakerFocus = buttonIdToFocus(speakerFocusGroup.checkedButtonId)
            Log.d(TAG, "Begin model comparison. focus=$speakerFocus session=$sessionId")
            startModelComparison()
        }

        observeProcessingState()
        loadPredictionPages()
        loadPieChart()
        loadSummary()
        loadComparisonSummary()
        refreshProcessingLogCache(force = true) {
            renderOverlayLog()
            applyPersistentLogText()
        }
        updateManualSelectionNote()
        configurePredictionPagerScroll()
    }

    private fun startProcessing() {
        resetOverlayLog("Standard analysis")
        showOverlayMessage(
            title = "Preparing audio session",
            detail = "Checking the selected audio files before the model starts."
        )
        showLoading(true)
        TaskForegroundService.start(this, TaskForegroundService.MODE_PROCESSING)
        val assetsPath = assetDirPath ?: run {
            TaskForegroundService.stop(this)
            showLoading(false)
            Toast.makeText(this, "Asset directory is missing", Toast.LENGTH_SHORT).show()
            return
        }
        val modelPath = when {
            selectedTflitePath == null || selectedTflitePath == "default" -> File(assetsPath, "model.tflite").absolutePath
            else -> selectedTflitePath!!
        }
        val scalerPath = File(assetsPath, "scaler_values.json").absolutePath
        processingViewModel.startProcessing(
            ProcessingRequest(
                soundDir = File(soundFolderPath!!),
                imgDir = File(imgFolderPath!!),
                modelPath = modelPath,
                scalerPath = scalerPath,
                speakerFocus = speakerFocus
            )
        )
    }

    private fun observeProcessingState() {
        lifecycleScope.launch {
            processingViewModel.uiState.collect { state ->
                if (state.statusTitle.isNotBlank()) {
                    showOverlayMessage(state.statusTitle, state.statusDetail)
                }
                if (state.isProcessing || state.isComparing) {
                    showLoading(true)
                    return@collect
                }

                showLoading(false)
                if (state.errorMessage != null) {
                    TaskForegroundService.stop(this@ProcessingActivity)
                    Log.w(TAG, "Processing stopped with error: ${state.errorMessage}")
                    appendOverlayLogLine("Error: ${state.errorMessage}")
                    Toast.makeText(this@ProcessingActivity, state.errorMessage, Toast.LENGTH_SHORT).show()
                    refreshProcessingLogCache(force = true) {
                        renderOverlayLog()
                        applyPersistentLogText()
                    }
                    processingViewModel.resetCompletion()
                    return@collect
                }

                if (state.completed) {
                    TaskForegroundService.stop(this@ProcessingActivity)
                    Log.d(TAG, "Processing completed. processed=${state.processedCount} skipped=${state.skippedCount}")
                    Toast.makeText(
                        this@ProcessingActivity,
                        "Processing complete: ${state.processedCount} file(s) analyzed" +
                            if (state.skippedCount > 0) ", ${state.skippedCount} skipped" else "",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (speakerFocus != "all" && state.fallbackToAllCount > 0) {
                        Toast.makeText(
                            this@ProcessingActivity,
                            "${state.fallbackToAllCount} file(s) used full audio because speaker split was low confidence.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    state.warningMessage?.takeIf { it.isNotBlank() }?.let { warning ->
                        appendOverlayLogLine("Warning: $warning")
                        Toast.makeText(this@ProcessingActivity, warning, Toast.LENGTH_LONG).show()
                    }
                    withContext(Dispatchers.IO) { writeSessionMetadata() }
                    loadPredictionPages()
                    loadPieChart()
                    loadSummary()
                    refreshProcessingLogCache(force = true) {
                        renderOverlayLog()
                        applyPersistentLogText()
                    }
                    processingViewModel.resetCompletion()
                }

                if (!state.comparisonReport.isNullOrBlank()) {
                    TaskForegroundService.stop(this@ProcessingActivity)
                    Log.d(TAG, "Model comparison completed for session=$sessionId")
                    appendOverlayLogLine("Model comparison finished successfully.")
                    comparisonSummaryText.text = state.comparisonReport
                    comparisonSummaryText.visibility = View.VISIBLE
                    Toast.makeText(this@ProcessingActivity, "Model comparison complete", Toast.LENGTH_SHORT).show()
                    refreshProcessingLogCache(force = true) {
                        renderOverlayLog()
                        applyPersistentLogText()
                    }
                    processingViewModel.clearComparisonMessage()
                }
            }
        }
    }

    private fun startModelComparison() {
        resetOverlayLog("Saved model comparison")
        val models = modelCatalogStore.comparisonCandidates()
        if (models.size < 2) {
            Toast.makeText(this, "Add at least one custom model to compare", Toast.LENGTH_LONG).show()
            return
        }
        val readiness = DeviceCapabilityChecker.buildReport(this, storageManager)
        if (models.size >= 3 && !readiness.canHandleMaxComparison) {
            AppDialogs.builder(this)
                .setTitle("Heavy Comparison Warning")
                .setMessage(
                    readiness.summary + "\n\nThis comparison will run $models.size model(s) on the current session. It may take a long time on this device.\n\nContinue anyway?"
                )
                .setPositiveButton("Continue") { _, _ ->
                    runModelComparisonInternal(models)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        runModelComparisonInternal(models)
    }

    private fun runModelComparisonInternal(models: List<com.example.myapplication.core.StoredModel>) {
        val assetsPath = assetDirPath ?: run {
            Toast.makeText(this, "Asset directory is missing", Toast.LENGTH_SHORT).show()
            return
        }
        showOverlayMessage(
            title = "Preparing model comparison",
            detail = "Getting ${models.size} model(s) ready for the current audio session."
        )
        showLoading(true)
        TaskForegroundService.start(this, TaskForegroundService.MODE_PROCESSING)
        val scalerPath = File(assetsPath, "scaler_values.json").absolutePath
        val currentSessionId = sessionId ?: run {
            TaskForegroundService.stop(this)
            showLoading(false)
            Toast.makeText(this, "Session is missing", Toast.LENGTH_SHORT).show()
            return
        }
        processingViewModel.compareModels(
            request = ProcessingRequest(
                soundDir = File(soundFolderPath!!),
                imgDir = File(imgFolderPath!!),
                modelPath = "",
                scalerPath = scalerPath,
                speakerFocus = speakerFocus
            ),
            models = models,
            compareRootDir = storageManager.sessionCompareDir(currentSessionId),
            reportFile = storageManager.sessionCompareReportFile(currentSessionId)
        )
    }

    private fun loadPredictionPages() {
        val imgDir = File(imgFolderPath ?: return)
        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) {
                val list = imgDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".png") && !it.name.contains("pie_chart") }
                    ?.sortedBy { it.name }
                    ?.toMutableList()
                    ?: mutableListOf()
                val combined = File(imgDir, "all_bar_combined.png")
                if (combined.exists()) {
                    list.remove(combined)
                    list.add(combined)
                }
                list
            }
            predictionPager.adapter = ImagePagerAdapter(this@ProcessingActivity, images)
        }
    }

    private fun loadPieChart() {
        val pieFile = File(imgFolderPath, "pie_chart.png")
        if (pieFile.exists()) {
            pieChartImage.load(pieFile) {
                crossfade(true)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            if (overlayStartedAtMs == 0L) {
                overlayStartedAtMs = System.currentTimeMillis()
            }
            overlayHandler.removeCallbacks(overlayTicker)
            overlayHandler.removeCallbacks(overlayLogTicker)
            overlayHandler.post(overlayTicker)
            overlayHandler.post(overlayLogTicker)
            renderOverlayLog()
        } else {
            overlayHandler.removeCallbacks(overlayTicker)
            overlayHandler.removeCallbacks(overlayLogTicker)
            overlayStartedAtMs = 0L
            loadingElapsedText.text = "00:00"
        }
    }

    private fun showOverlayMessage(title: String, detail: String) {
        loadingTitleText.text = title
        loadingDetailText.text = if (detail.isBlank()) {
            "Working on the current analysis request."
        } else {
            detail
        }
        val merged = "$title|$detail"
        if (merged != lastOverlayMessage) {
            appendOverlayLogLine("$title - ${loadingDetailText.text}")
            lastOverlayMessage = merged
        }
    }

    private fun toggleOverlayLog() {
        val expanding = loadingLogContainer.visibility != View.VISIBLE
        loadingLogContainer.visibility = if (expanding) View.VISIBLE else View.GONE
        loadingLogToggle.text = if (expanding) "Hide Live Log" else "Show Live Log"
        if (expanding) {
            refreshProcessingLogCache(force = true) {
                renderOverlayLog()
            }
        }
    }

    private fun resetOverlayLog(modeLabel: String) {
        overlayLogLines.clear()
        lastOverlayMessage = ""
        loadingLogText.text = "Waiting for processing log..."
        loadingLogContainer.visibility = View.GONE
        loadingLogToggle.text = "Show Live Log"
        appendOverlayLogLine("Starting $modeLabel.")
    }

    private fun appendOverlayLogLine(message: String) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return
        if (overlayLogLines.lastOrNull()?.endsWith(trimmed) == true) return
        val elapsedSeconds = if (overlayStartedAtMs > 0L) {
            ((System.currentTimeMillis() - overlayStartedAtMs) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        val minutes = elapsedSeconds / 60L
        val seconds = elapsedSeconds % 60L
        overlayLogLines += "[${String.format("%02d:%02d", minutes, seconds)}] $trimmed"
        renderOverlayLog()
    }

    private fun renderOverlayLog() {
        val localLog = if (overlayLogLines.isNotEmpty()) {
            overlayLogLines.joinToString("\n")
        } else {
            ""
        }
        val fileLog = cachedProcessingLogText.takeIf { it.isNotBlank() }?.let {
            "--- Python Log ---\n$it"
        }.orEmpty()

        val finalText = buildString {
            if (localLog.isNotBlank()) {
                append(localLog)
            }
            if (fileLog.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(fileLog)
            }
            if (isEmpty()) {
                append("Waiting for processing log...")
            }
        }

        if (loadingLogText.text.toString() != finalText) {
            loadingLogText.text = finalText
            if (loadingLogContainer.visibility == View.VISIBLE) {
                loadingLogScroll.post { loadingLogScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun togglePersistentLog() {
        val expanding = processingLogScroll.visibility != View.VISIBLE
        processingLogScroll.visibility = if (expanding) View.VISIBLE else View.GONE
        processingLogToggle.text = if (expanding) "Hide" else "Show"
        if (expanding) {
            refreshProcessingLogCache(force = true) { applyPersistentLogText() }
        }
    }

    private fun applyPersistentLogText() {
        processingLogText.text = cachedProcessingLogText.ifBlank {
            "No log yet. Run analysis to see details here."
        }
        if (processingLogScroll.visibility == View.VISIBLE) {
            processingLogScroll.post { processingLogScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshProcessingLogCache(force: Boolean = false, onUpdated: (() -> Unit)? = null) {
        val imgDir = imgFolderPath?.let(::File) ?: return
        val logFile = File(imgDir, "processing_log.txt")
        if (!force && !logFile.exists() && cachedProcessingLogText.isBlank()) {
            onUpdated?.invoke()
            return
        }
        val modifiedAt = if (logFile.exists()) logFile.lastModified() else -1L
        if (!force && modifiedAt == cachedProcessingLogModifiedAt && logRefreshJob?.isActive != true) {
            onUpdated?.invoke()
            return
        }
        if (logRefreshJob?.isActive == true) return
        logRefreshJob = lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                if (logFile.exists()) {
                    logFile.readText().trim().ifBlank { "Processing log is empty for this session." }
                } else {
                    ""
                }
            }
            cachedProcessingLogModifiedAt = modifiedAt
            cachedProcessingLogText = text
            onUpdated?.invoke()
        }
    }

    private fun updateSpeakerModeHint() {
        speakerModeHint.text = when (speakerFocus) {
            "all"     -> "All: processes the full recording without separation."
            "patient" -> "Subject 1: focuses on one detected speaker."
            "doctor"  -> "Subject 2: focuses on the other detected speaker."
            else      -> ""
        }
    }

    private fun configurePredictionPagerScroll() {
        predictionPager.post {
            val pagerRecycler = predictionPager.getChildAt(0) ?: return@post
            var startX = 0f
            var startY = 0f
            pagerRecycler.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startY = event.y
                        predictionPager.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = kotlin.math.abs(event.x - startX)
                        val dy = kotlin.math.abs(event.y - startY)
                        predictionPager.parent?.requestDisallowInterceptTouchEvent(dx > dy)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        predictionPager.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        }
    }

    private fun focusToButtonId(focus: String): Int = when (focus) {
        "patient" -> R.id.focusPatientButton
        "doctor"  -> R.id.focusDoctorButton
        else      -> R.id.focusAllButton
    }

    private fun buttonIdToFocus(buttonId: Int): String = when (buttonId) {
        R.id.focusPatientButton -> "patient"
        R.id.focusDoctorButton  -> "doctor"
        else                    -> "all"
    }

    private fun loadSummary() {
        val summaryFile = File(imgFolderPath ?: return, "summary.json")
        if (!summaryFile.exists()) {
            summaryText.text = baseSummaryPrefix() + "Summary will appear after processing. If nothing appears, check processing_log.txt in this session's image folder."
            return
        }
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { JSONObject(summaryFile.readText()) }.getOrNull()
            }
            if (json == null) {
                summaryText.text = baseSummaryPrefix() + "Failed to read summary."
                return@launch
            }
            val total = json.optInt("total_files", 0)
            val top = json.optString("top_label", "-")
            val avg = json.optDouble("avg_confidence", 0.0)
            val counts = json.optJSONObject("counts")
            val lines = mutableListOf<String>()
            lines.add("Total files: $total")
            lines.add("Dominant label: $top")
            lines.add("Avg confidence: ${"%.2f".format(avg * 100)}%")
            if (counts != null) {
                lines.add("Counts:")
                for (key in counts.keys()) {
                    lines.add("- $key: ${counts.optInt(key, 0)}")
                }
            }
            summaryText.text = baseSummaryPrefix() + lines.joinToString("\n")
        }
    }

    private fun loadComparisonSummary() {
        val currentSessionId = sessionId ?: return
        val reportFile = storageManager.sessionCompareReportFile(currentSessionId)
        if (!reportFile.exists()) {
            comparisonSummaryText.text = "Model comparison will appear here."
            return
        }
        lifecycleScope.launch {
            val reportJson = withContext(Dispatchers.IO) {
                runCatching { JSONObject(reportFile.readText()) }.getOrNull()
            }
            comparisonSummaryText.text = if (reportJson != null) {
                reportJson.optString("summary_text", "Model comparison will appear here.")
            } else {
                "Failed to read model comparison."
            }
        }
    }

    private fun writeSessionMetadata() {
        val currentSessionId = sessionId ?: return
        val sessionDir = storageManager.sessionDir(currentSessionId)
        sessionDir.mkdirs()

        val soundDir = File(soundFolderPath ?: return)
        val summaryFile = File(imgFolderPath ?: return, "summary.json")
        val summaryJson = if (summaryFile.exists()) {
            try {
                JSONObject(summaryFile.readText())
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }

        val files = soundDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "wav" }
            ?.map { it.name }
            ?: emptyList()

        val meta = JSONObject().apply {
            put("session_id", currentSessionId)
            put(
                "created_at",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            )
            put("speaker_focus", speakerFocus)
            put("total_files", summaryJson.optInt("total_files", files.size))
            put("top_label", summaryJson.optString("top_label", "-"))
            put("avg_confidence", summaryJson.optDouble("avg_confidence", 0.0))
            put("sound_files", org.json.JSONArray(files))
            put("model_source", selectedTflitePath ?: "default")
            put("model_name", selectedModelDisplayName ?: "Default Model")
            put("python_source", "bundled")
            put("status", "completed")
            put("primary_audio_name", primaryAudioName ?: "")
            put(
                "manual_segments",
                org.json.JSONArray().apply {
                    manualSegments.forEach { segment ->
                        put(
                            JSONObject().apply {
                                put("subject_name", segment.subjectName)
                                put("start_ms", segment.startTimeMs)
                                put("end_ms", segment.endTimeMs)
                                put("source_file", segment.sourceFileName)
                            }
                        )
                    }
                }
            )
        }

        storageManager.sessionMetaFile(currentSessionId).writeText(meta.toString())
    }

    private fun updateManualSelectionNote() {
        if (manualSegments.isEmpty()) return
        val prefix = baseSummaryPrefix()
        if (!summaryText.text.startsWith(prefix)) {
            summaryText.text = prefix + summaryText.text
        }
    }

    private fun baseSummaryPrefix(): String {
        if (manualSegments.isEmpty()) return ""
        val subjects = manualSegments.map { it.subjectName }.distinct()
        val subjectSummary = subjects.joinToString(", ")
        return "Manual selection active for ${manualSegments.size} segment(s)"
            .plus(if (subjectSummary.isNotBlank()) " [$subjectSummary]" else "")
            .plus(
                if (!primaryAudioName.isNullOrBlank()) " from $primaryAudioName.\n\n" else ".\n\n"
            )
    }

    override fun onDestroy() {
        overlayHandler.removeCallbacks(overlayTicker)
        overlayHandler.removeCallbacks(overlayLogTicker)
        logRefreshJob?.cancel()
        super.onDestroy()
    }
}

class ImagePagerAdapter(private val context: Context, private val images: List<File>) :
    RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder>() {

    class ImageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val frame = FrameLayout(context)
        frame.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return ImageViewHolder(frame)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val file = images[position]
        holder.container.removeAllViews()

        val isCombined = file.name == "all_bar_combined.png"
        if (isCombined) {
            val scrollView = ScrollView(context)
            scrollView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            val imageView = ImageView(context)
            imageView.adjustViewBounds = true
            imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageView.load(file) {
                crossfade(true)
            }
            imageView.setOnLongClickListener {
                saveImageToGallery(file)
                Toast.makeText(context, "Image saved", Toast.LENGTH_SHORT).show()
                true
            }

            scrollView.addView(imageView)
            holder.container.addView(scrollView)
        } else {
            val imageView = ImageView(context)
            imageView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView.adjustViewBounds = true
            imageView.load(file) {
                crossfade(true)
            }
            imageView.setOnLongClickListener {
                saveImageToGallery(file)
                Toast.makeText(context, "Image saved", Toast.LENGTH_SHORT).show()
                true
            }
            holder.container.addView(imageView)
        }
    }

    override fun getItemCount(): Int = images.size

    private fun saveImageToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StressDetection")
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        }
    }
}
