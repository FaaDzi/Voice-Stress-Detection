package com.example.myapplication

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.core.AppDialogs
import com.example.myapplication.core.AppThemeManager
import com.example.myapplication.core.ThemeAnimator
import com.example.myapplication.core.AudioWavUtils
import com.example.myapplication.core.VoiceCalibrationStore
import com.example.myapplication.core.setDebouncedClickListener
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.RangeSlider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

class SelectionActivity : AppCompatActivity() {

    sealed class UiState {
        object Idle : UiState()
        object LoadingWaveform : UiState()
        object Scanning : UiState()
        data class Detected(val message: String) : UiState()
        object PreparingExport : UiState()
    }

    companion object {
        private const val TAG = "SelectionActivity"
        private const val STATE_SUBJECTS = "state_subjects"
        private const val STATE_SELECTED_SUBJECT_INDEX = "state_selected_subject_index"
        private const val STATE_TAGGED_SEGMENTS = "state_tagged_segments"
        private const val STATE_DURATION_MS = "state_duration_ms"
        private const val STATE_PLAYBACK_POSITION_MS = "state_playback_position_ms"
        private const val DEFAULT_SEGMENT_END_RATIO = 0.35f
        private const val FALLBACK_WAVEFORM_BAR_COUNT = 44
    }

    private lateinit var backButton: ImageButton
    private lateinit var nextButton: MaterialButton
    private lateinit var fileNameText: TextView
    private lateinit var timeText: TextView
    private lateinit var helperText: TextView
    private lateinit var selectionSummaryText: TextView
    private lateinit var waveformImage: ImageView
    private lateinit var savedSegmentsOverlay: FrameLayout
    private lateinit var playheadView: View
    private lateinit var segmentSlider: RangeSlider
    private lateinit var btnRewind: ImageButton
    private lateinit var btnPlayPause: FloatingActionButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnAddSpeaker: MaterialButton
    private lateinit var markSegmentButton: MaterialButton
    private lateinit var recyclerSpeakers: RecyclerView
    private lateinit var segmentsRecyclerView: RecyclerView

    private val processingViewModel: ProcessingViewModel by viewModels()
    private lateinit var detectionStatusCard: MaterialCardView
    private lateinit var detectionStatusText: TextView
    private lateinit var detectionProgressBar: ProgressBar
    private lateinit var btnSkipDetection: MaterialButton
    private lateinit var scannerOverlayView: com.example.myapplication.ui.ScannerOverlayView

    private lateinit var speakerAdapter: SpeakerChipAdapter
    private lateinit var segmentsAdapter: SegmentsAdapter

    private val taggedSegments = mutableListOf<AudioSegment>()
    private val subjects = mutableListOf("Subject 1", "Subject 2")
    private var selectedSubjectIndex = 0

    private var soundFolderPath: String? = null
    private var selectedTflitePath: String? = null
    private var selectedModelDisplayName: String? = null
    private var speakerFocus: String? = null
    private var assetDirPath: String? = null
    private var appRootPath: String? = null
    private var sessionId: String? = null

    private var primaryAudioFile: File? = null
    private var allAudioFiles: List<File> = emptyList()
    private var durationMs: Long = 0L
    private var mediaPlayer: MediaPlayer? = null
    private var isPreparingPlayer = false
    private var previewLoadJob: Job? = null
    private var prepareSelectionJob: Job? = null
    private var restoredPlaybackPositionMs: Long = 0L
    private val uiHandler = Handler(Looper.getMainLooper())

    private val playbackTicker = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            val currentMs = player.currentPosition.toLong()
            updateTimeText(currentPlaybackMs = currentMs)
            updatePlayhead(currentMs)
            if (player.isPlaying) {
                uiHandler.postDelayed(this, 100L)
            } else {
                updatePlayPauseIcon()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_selection)

        bindViews()
        readExtras()
        setupToolbar()
        setupSpeakerRecycler()
        setupSegmentsRecycler()
        setupSlider()
        setupButtons()
        restoreState(savedInstanceState)
        loadPrimaryAudio()
        refreshSubjectUi()
        observeDetectionState()
    }

    private fun bindViews() {
        backButton = findViewById(R.id.backButton)
        nextButton = findViewById(R.id.nextButton)
        fileNameText = findViewById(R.id.fileNameText)
        timeText = findViewById(R.id.timeText)
        helperText = findViewById(R.id.helperText)
        selectionSummaryText = findViewById(R.id.selectionSummaryText)
        waveformImage = findViewById(R.id.waveformImage)
        savedSegmentsOverlay = findViewById(R.id.savedSegmentsOverlay)
        playheadView = findViewById(R.id.playheadView)
        segmentSlider = findViewById(R.id.segmentSlider)
        btnRewind = findViewById(R.id.btnRewind)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward = findViewById(R.id.btnForward)
        btnAddSpeaker = findViewById(R.id.btnAddSpeaker)
        markSegmentButton = findViewById(R.id.markSegmentButton)
        recyclerSpeakers = findViewById(R.id.recyclerSpeakers)
        segmentsRecyclerView = findViewById(R.id.segmentsRecyclerView)
        detectionStatusCard = findViewById(R.id.detectionStatusCard)
        detectionStatusText = findViewById(R.id.detectionStatusText)
        detectionProgressBar = findViewById(R.id.detectionProgressBar)
        btnSkipDetection = findViewById(R.id.btnSkipDetection)
        scannerOverlayView = findViewById(R.id.scannerOverlayView)
    }

    private fun readExtras() {
        soundFolderPath = intent.getStringExtra("SOUND_FOLDER_PATH")
        selectedTflitePath = intent.getStringExtra("TFLITE_SELECTED")
        selectedModelDisplayName = intent.getStringExtra("MODEL_DISPLAY_NAME")
        speakerFocus = intent.getStringExtra("SPEAKER_FOCUS")
        assetDirPath = intent.getStringExtra("ASSET_DIR")
        appRootPath = intent.getStringExtra("APP_ROOT_DIR")
        sessionId = intent.getStringExtra("SESSION_ID")
    }

    private fun setupToolbar() {
        findViewById<Toolbar>(R.id.topToolbar).title = ""
        backButton.setDebouncedClickListener { onBackPressedDispatcher.onBackPressed() }
        nextButton.setDebouncedClickListener { continueToProcessing() }
    }

    private fun setupSpeakerRecycler() {
        speakerAdapter = SpeakerChipAdapter(
            speakers = subjects.toMutableList(),
            onClick = { index ->
                selectedSubjectIndex = index
                refreshSubjectUi()
            },
            onLongClick = { subjectName ->
                showSubjectActions(subjectName)
            }
        )
        recyclerSpeakers.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerSpeakers.adapter = speakerAdapter
    }

    private fun setupSegmentsRecycler() {
        segmentsAdapter = SegmentsAdapter(taggedSegments) { position ->
            if (position in taggedSegments.indices) {
                taggedSegments.removeAt(position)
                segmentsAdapter.replaceAll(taggedSegments)
                updateSelectionSummary()
                renderSavedSegments()
            }
        }
        segmentsRecyclerView.layoutManager = LinearLayoutManager(this)
        segmentsRecyclerView.adapter = segmentsAdapter
    }

    private fun setupSlider() {
        segmentSlider.addOnChangeListener { _, _, _ ->
            updateTimeText(currentPlaybackMs = mediaPlayer?.currentPosition?.toLong() ?: 0L)
            updateSelectionSummary()
        }
    }

    private fun setupButtons() {
        btnPlayPause.setDebouncedClickListener(250L) { togglePlayback() }
        btnRewind.setDebouncedClickListener(250L) { seekBy(-5_000L) }
        btnForward.setDebouncedClickListener(250L) { seekBy(5_000L) }
        btnAddSpeaker.setDebouncedClickListener { promptForSubject() }
        markSegmentButton.setDebouncedClickListener { addTaggedSegment(selectedSubjectName()) }
        btnSkipDetection.setDebouncedClickListener {
            processingViewModel.resetDetection()
            scannerOverlayView.stopScanning()
            applyUiState(UiState.Idle)
        }
    }

    private fun loadPrimaryAudio() {
        val soundDir = soundFolderPath?.let(::File)
        if (soundDir == null || !soundDir.exists()) {
            Toast.makeText(this, "Audio session is missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        applyUiState(UiState.LoadingWaveform)
        lifecycleScope.launch {
            val wavFiles = withContext(Dispatchers.IO) {
                soundDir.listFiles()
                    ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
                    ?.sortedBy { it.name.lowercase() }
                    .orEmpty()
            }
            allAudioFiles = wavFiles
            primaryAudioFile = wavFiles.firstOrNull()
            val audioFile = primaryAudioFile
            if (audioFile == null) {
                applyUiState(UiState.Idle)
                Toast.makeText(this@SelectionActivity, "No WAV audio found for selection", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            fileNameText.text = audioFile.name
            Log.d(TAG, "Loaded primary audio for selection: ${audioFile.name}")
            startPreviewPreparation(audioFile)
        }
    }

    private fun startPreviewPreparation(audioFile: File) {
        previewLoadJob?.cancel()
        previewLoadJob = lifecycleScope.launch {
            val preview = withContext(Dispatchers.IO) {
                val currentContext = coroutineContext
                val duration = readDurationWithRetriever(audioFile).coerceAtLeast(1L)
                val bitmap = AudioWavUtils.createWaveformBitmap(
                    file = audioFile,
                    width = resources.displayMetrics.widthPixels - resources.displayMetrics.density.times(72).toInt(),
                    height = resources.displayMetrics.density.times(150).toInt(),
                    shouldContinue = { currentContext.isActive }
                ) ?: createFallbackWaveformBitmap()
                duration to bitmap
            }
            durationMs = preview.first
            (waveformImage.drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }?.recycle()
            waveformImage.setImageBitmap(preview.second)
            updateSliderBounds()
            updateTimeText(currentPlaybackMs = 0L)
            waveformImage.post {
                updatePlayhead(0L)
                renderSavedSegments()
            }
            updateSelectionSummary()
            triggerAutoDetection()
        }
    }

    private fun readDurationWithRetriever(audioFile: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
        } catch (_: Exception) {
            1L
        } finally {
            retriever.release()
        }
    }

    private fun updateSliderBounds() {
        segmentSlider.valueFrom = 0f
        segmentSlider.valueTo = durationMs.toFloat().coerceAtLeast(1f)
        val end = min(durationMs.toFloat(), max(1_000f, durationMs * DEFAULT_SEGMENT_END_RATIO))
        segmentSlider.values = listOf(0f, end)
    }

    private fun createFallbackWaveformBitmap(): Bitmap {
        val width = max(waveformImage.width, resources.displayMetrics.widthPixels - 120)
        val height = max(waveformImage.height, 300)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#12000000") }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6606B6D4") }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val barCount = FALLBACK_WAVEFORM_BAR_COUNT
        val step = width / barCount.toFloat()
        for (i in 0 until barCount) {
            val centerY = height / 2f
            val barHeight = (24 + ((i * 19) % 72)).toFloat()
            val x = (i * step) + step / 2f
            canvas.drawRoundRect(x - 4f, centerY - barHeight, x + 4f, centerY + barHeight, 6f, 6f, barPaint)
        }
        return bitmap
    }

    private fun togglePlayback() {
        val player = mediaPlayer
        if (player == null) {
            prepareMediaPlayerAsync(autoPlay = true)
            return
        }
        if (player.isPlaying) {
            player.pause()
            uiHandler.removeCallbacks(playbackTicker)
        } else {
            player.start()
            uiHandler.post(playbackTicker)
        }
        updatePlayPauseIcon()
    }

    private fun seekBy(deltaMs: Long) {
        val player = mediaPlayer ?: return
        val next = (player.currentPosition.toLong() + deltaMs).coerceIn(0L, durationMs)
        player.seekTo(next.toInt())
        updateTimeText(currentPlaybackMs = next)
        updatePlayhead(next)
    }

    private fun prepareMediaPlayerAsync(autoPlay: Boolean) {
        if (isPreparingPlayer) return
        val audioFile = primaryAudioFile ?: return
        isPreparingPlayer = true
        btnPlayPause.isEnabled = false

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(audioFile.absolutePath)
                setOnPreparedListener { player ->
                    isPreparingPlayer = false
                    btnPlayPause.isEnabled = true
                    durationMs = max(player.duration.toLong(), durationMs).coerceAtLeast(1L)
                    if (restoredPlaybackPositionMs > 0L) {
                        player.seekTo(restoredPlaybackPositionMs.coerceAtMost(durationMs).toInt())
                    }
                    updateTimeText(currentPlaybackMs = player.currentPosition.toLong())
                    if (autoPlay) {
                        player.start()
                        uiHandler.post(playbackTicker)
                    }
                    updatePlayPauseIcon()
                }
                setOnCompletionListener {
                    updatePlayPauseIcon()
                    updatePlayhead(durationMs)
                    updateTimeText(currentPlaybackMs = durationMs)
                }
                setOnErrorListener { _, what, extra ->
                    isPreparingPlayer = false
                    btnPlayPause.isEnabled = true
                    Log.w(TAG, "MediaPlayer error. what=$what extra=$extra")
                    Toast.makeText(this@SelectionActivity, "Playback is not available for this file", Toast.LENGTH_SHORT).show()
                    true
                }
                prepareAsync()
            } catch (e: Exception) {
                isPreparingPlayer = false
                btnPlayPause.isEnabled = true
                Log.w(TAG, "MediaPlayer could not prepare audio preview", e)
                Toast.makeText(this@SelectionActivity, "Playback is not available for this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePlayPauseIcon() {
        val icon = if (mediaPlayer?.isPlaying == true) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        btnPlayPause.setImageResource(icon)
    }

    private fun updateTimeText(currentPlaybackMs: Long) {
        val values = segmentSlider.values
        val start = values.getOrNull(0)?.toLong() ?: 0L
        val end = values.getOrNull(1)?.toLong() ?: durationMs
        val playbackText = AudioSegment.formatMillis(currentPlaybackMs)
        val selectionText = "${AudioSegment.formatMillis(start)} - ${AudioSegment.formatMillis(end)}"
        timeText.text = "$playbackText / ${AudioSegment.formatMillis(durationMs)}  •  $selectionText"
    }

    private fun updatePlayhead(currentPlaybackMs: Long) {
        val width = waveformImage.width.takeIf { it > 0 } ?: return
        val ratio = if (durationMs <= 0) 0f else currentPlaybackMs.toFloat() / durationMs.toFloat()
        val x = width * ratio
        playheadView.translationX = x - (playheadView.width / 2f)
    }

    private fun addTaggedSegment(subjectName: String) {
        val audioFile = primaryAudioFile ?: return
        val values = segmentSlider.values
        if (values.size < 2) return
        val start = values[0].toLong().coerceAtLeast(0L)
        val end = values[1].toLong().coerceAtMost(durationMs)
        if (end <= start) {
            Toast.makeText(this, "Please select a valid range first", Toast.LENGTH_SHORT).show()
            return
        }
        val segment = AudioSegment(
            startTimeMs = start,
            endTimeMs = end,
            subjectName = subjectName,
            sourceFileName = audioFile.name
        )
        taggedSegments.add(segment)
        taggedSegments.sortBy { it.startTimeMs }
        segmentsAdapter.replaceAll(taggedSegments)
        updateSelectionSummary()
        renderSavedSegments()
        Log.d(TAG, "Marked segment ${segment.label()} as $subjectName")
        Toast.makeText(this, "Saved segment for $subjectName", Toast.LENGTH_SHORT).show()
    }

    private fun refreshSubjectUi() {
        if (subjects.isEmpty()) {
            subjects += "Subject 1"
        }
        selectedSubjectIndex = selectedSubjectIndex.coerceIn(0, subjects.lastIndex)
        speakerAdapter.updateSubjects(subjects, selectedSubjectIndex)
        markSegmentButton.text = "Save segment for ${selectedSubjectName()}"
        updateSelectionSummary()
    }

    private fun updateSelectionSummary() {
        val selectedRange = segmentSlider.values
        val start = selectedRange.getOrNull(0)?.toLong() ?: 0L
        val end = selectedRange.getOrNull(1)?.toLong() ?: 0L
        selectionSummaryText.text = buildString {
            append("Selected subject: ${selectedSubjectName()}\n")
            append("Current range: ${AudioSegment.formatMillis(start)} - ${AudioSegment.formatMillis(end)}")
            if (taggedSegments.isNotEmpty()) {
                append("\nSaved segments: ${taggedSegments.size}")
            }
        }
    }

    private fun showSubjectActions(subjectName: String) {
        val options = if (subjects.size > 1) arrayOf("Rename", "Remove") else arrayOf("Rename")
        AppDialogs.builder(this)
            .setTitle(subjectName)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Rename" -> promptForSubject(existingName = subjectName)
                    "Remove" -> removeSubject(subjectName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForSubject(existingName: String? = null) {
        val defaultName = existingName ?: "Subject ${subjects.size + 1}"
        val editText = EditText(this).apply {
            setText(defaultName)
            setSelection(text.length)
        }
        AppDialogs.builder(this)
            .setTitle(if (existingName == null) "Add Subject" else "Rename Subject")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                if (existingName == null) {
                    subjects.add(name)
                    selectedSubjectIndex = subjects.lastIndex
                } else {
                    val index = subjects.indexOf(existingName)
                    if (index >= 0) {
                        subjects[index] = name
                        taggedSegments.replaceAll { segment ->
                            if (segment.subjectName == existingName) segment.copy(subjectName = name) else segment
                        }
                        segmentsAdapter.replaceAll(taggedSegments)
                        renderSavedSegments()
                        if (selectedSubjectIndex == index) {
                            selectedSubjectIndex = index
                        }
                    }
                }
                refreshSubjectUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeSubject(subjectName: String) {
        if (subjects.size <= 1) {
            Toast.makeText(this, "Keep at least one subject in the list", Toast.LENGTH_SHORT).show()
            return
        }
        val taggedForSubject = taggedSegments.count { it.subjectName == subjectName }
        val message = if (taggedForSubject > 0) {
            "Remove $subjectName and delete its $taggedForSubject saved segment(s)?"
        } else {
            "Remove $subjectName from the list?"
        }
        AppDialogs.builder(this)
            .setTitle("Remove Subject")
            .setMessage(message)
            .setPositiveButton("Remove") { _, _ ->
                val removedIndex = subjects.indexOf(subjectName)
                subjects.remove(subjectName)
                taggedSegments.removeAll { it.subjectName == subjectName }
                segmentsAdapter.replaceAll(taggedSegments)
                renderSavedSegments()
                if (selectedSubjectIndex >= subjects.size) {
                    selectedSubjectIndex = subjects.lastIndex
                } else if (selectedSubjectIndex == removedIndex) {
                    selectedSubjectIndex = min(selectedSubjectIndex, subjects.lastIndex)
                }
                refreshSubjectUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun continueToProcessing() {
        if (prepareSelectionJob?.isActive == true) return

        if (taggedSegments.isEmpty()) {
            AppDialogs.builder(this)
                .setTitle("Process Full Audio?")
                .setMessage("No segments have been marked yet. Continue and process the full audio session instead?")
                .setPositiveButton("Continue") { _, _ ->
                    startActivity(buildProcessingIntent(soundFolderPath))
                }
                .setNegativeButton("Stay Here", null)
                .show()
            return
        }

        prepareToContinue()
    }

    private fun buildProcessingIntent(nextSoundFolderPath: String?): Intent {
        return Intent(this, ProcessingActivity::class.java).apply {
            putExtra("SOUND_FOLDER_PATH", nextSoundFolderPath)
            putExtra("TFLITE_SELECTED", selectedTflitePath)
            putExtra("MODEL_DISPLAY_NAME", selectedModelDisplayName)
            putExtra("SPEAKER_FOCUS", speakerFocus)
            putExtra("ASSET_DIR", assetDirPath)
            putExtra("APP_ROOT_DIR", appRootPath)
            putExtra("SESSION_ID", sessionId)
            putExtra("AUDIO_SEGMENTS", ArrayList(taggedSegments))
            putExtra("PRIMARY_AUDIO_NAME", primaryAudioFile?.name)
        }
    }

    private suspend fun buildPreparedSelectionFolder(): String? {
        val originalDir = soundFolderPath?.let(::File) ?: return null
        val previewFile = primaryAudioFile ?: return originalDir.absolutePath
        val selectionDir = File(originalDir.parentFile ?: originalDir, "selected_sound").apply {
            deleteRecursively()
            mkdirs()
        }

        for (file in allAudioFiles) {
            coroutineContext.ensureActive()
            if (file != previewFile) {
                file.copyTo(File(selectionDir, file.name), overwrite = true)
                continue
            }

            val sourceSegments = taggedSegments.filter { it.sourceFileName == file.name }
            if (sourceSegments.isEmpty()) {
                file.copyTo(File(selectionDir, file.name), overwrite = true)
                continue
            }

            var createdAny = false
            sourceSegments.forEachIndexed { index, segment ->
                coroutineContext.ensureActive()
                val currentContext = coroutineContext
                val safeSubject = segment.subjectName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(selectionDir, "${index + 1}_${safeSubject}_${file.name}")
                val clipped = AudioWavUtils.extractSegmentToWav(
                    file,
                    target,
                    segment.startTimeMs,
                    segment.endTimeMs,
                    shouldContinue = { currentContext.isActive }
                )
                if (clipped) {
                    createdAny = true
                }
            }
            if (!createdAny) {
                file.copyTo(File(selectionDir, file.name), overwrite = true)
            }
        }

        return selectionDir.absolutePath
    }

    private fun prepareToContinue() {
        applyUiState(UiState.PreparingExport)
        prepareSelectionJob?.cancel()
        prepareSelectionJob = lifecycleScope.launch {
            val nextSoundFolderPath = withContext(Dispatchers.IO) {
                buildPreparedSelectionFolder()
            } ?: soundFolderPath
            applyUiState(UiState.Idle)
            startActivity(buildProcessingIntent(nextSoundFolderPath))
        }
    }

    private fun selectedSubjectName(): String = subjects.getOrElse(selectedSubjectIndex) { "Subject 1" }

    private fun baseHelperText(): String {
        return buildString {
            append("Tap a subject chip to make it active. Long-press a chip to rename or remove it. ")
            if (allAudioFiles.size > 1) {
                append("Only the first WAV is previewed here; the other files stay in the session.")
            }
        }
    }

    private fun applyUiState(state: UiState) {
        val isLoading = state == UiState.LoadingWaveform
        val isPreparing = state == UiState.PreparingExport
        btnPlayPause.isEnabled = !isLoading
        btnRewind.isEnabled = !isLoading
        btnForward.isEnabled = !isLoading
        nextButton.isEnabled = !isLoading && !isPreparing
        markSegmentButton.isEnabled = !isPreparing
        btnAddSpeaker.isEnabled = !isPreparing

        when (state) {
            UiState.LoadingWaveform -> {
                helperText.text = "Loading waveform preview. Long audio files may take a moment."
                detectionStatusCard.visibility = View.GONE
            }
            UiState.Scanning -> {
                helperText.text = baseHelperText()
                detectionStatusCard.visibility = View.VISIBLE
                detectionProgressBar.visibility = View.VISIBLE
                btnSkipDetection.visibility = View.VISIBLE
                detectionStatusText.text = "Scanning for speakers..."
            }
            is UiState.Detected -> {
                helperText.text = baseHelperText()
                detectionStatusCard.visibility = View.VISIBLE
                detectionProgressBar.visibility = View.GONE
                btnSkipDetection.visibility = View.GONE
                detectionStatusText.text = state.message
            }
            UiState.Idle, UiState.PreparingExport -> {
                helperText.text = if (isPreparing)
                    "Preparing clipped audio segments for analysis..."
                else
                    baseHelperText()
                detectionStatusCard.visibility = View.GONE
            }
        }
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        val restoredSubjects = savedInstanceState.getStringArrayList(STATE_SUBJECTS)
        if (!restoredSubjects.isNullOrEmpty()) {
            subjects.clear()
            subjects.addAll(restoredSubjects)
        }

        @Suppress("DEPRECATION")
        val restoredSegments = savedInstanceState.getSerializable(STATE_TAGGED_SEGMENTS) as? ArrayList<AudioSegment>
        if (!restoredSegments.isNullOrEmpty()) {
            taggedSegments.clear()
            taggedSegments.addAll(restoredSegments)
            segmentsAdapter.replaceAll(taggedSegments)
        }

        selectedSubjectIndex = savedInstanceState.getInt(STATE_SELECTED_SUBJECT_INDEX, selectedSubjectIndex)
        durationMs = savedInstanceState.getLong(STATE_DURATION_MS, durationMs)
        restoredPlaybackPositionMs = savedInstanceState.getLong(STATE_PLAYBACK_POSITION_MS, 0L)
    }

    private fun renderSavedSegments() {
        if (!::savedSegmentsOverlay.isInitialized) return
        val overlayWidth = savedSegmentsOverlay.width.takeIf { it > 0 } ?: return
        val overlayHeight = savedSegmentsOverlay.height.takeIf { it > 0 } ?: return
        if (durationMs <= 0L) return

        savedSegmentsOverlay.removeAllViews()
        taggedSegments.forEach { segment ->
            val color = SpeakerChipAdapter.colorForSubject(segment.subjectName)
            val startRatio = (segment.startTimeMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val endRatio = (segment.endTimeMs.toFloat() / durationMs.toFloat()).coerceIn(startRatio, 1f)
            val left = (overlayWidth * startRatio).toInt()
            val right = max(left + 4, (overlayWidth * endRatio).toInt())

            val band = View(this).apply {
                background = ContextCompat.getDrawable(this@SelectionActivity, R.drawable.bg_rounded_surface)?.mutate()
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(color, 64)
                )
                layoutParams = FrameLayout.LayoutParams(
                    (right - left).coerceAtLeast(4),
                    overlayHeight
                ).apply {
                    leftMargin = left
                }
            }
            savedSegmentsOverlay.addView(band)

            val edge = View(this).apply {
                setBackgroundColor(color)
                layoutParams = FrameLayout.LayoutParams(3, overlayHeight).apply {
                    leftMargin = left
                }
            }
            savedSegmentsOverlay.addView(edge)
        }
    }

    private fun triggerAutoDetection() {
        val wavFile = primaryAudioFile ?: return
        val fingerprint = VoiceCalibrationStore.loadFingerprint(this)
        applyUiState(UiState.Scanning)
        scannerOverlayView.startScanning(if (AppThemeManager.isTheme2(this)) 2 else 1)
        processingViewModel.detectSpeakers(wavFile, fingerprint)
    }

    private fun observeDetectionState() {
        lifecycleScope.launch {
            processingViewModel.detectionState.collect { state ->
                when (state) {
                    is SpeakerDetectionState.Idle -> {
                        scannerOverlayView.stopScanning()
                        applyUiState(UiState.Idle)
                    }
                    is SpeakerDetectionState.Scanning -> {
                        // Scanner already running from triggerAutoDetection
                    }
                    is SpeakerDetectionState.Complete -> {
                        scannerOverlayView.stopScanning()
                        val segments = state.segments
                        if (segments.isEmpty()) {
                            applyUiState(UiState.Detected("No speakers detected — add segments manually below"))
                        } else {
                            populateFromDetectedSegments(segments)
                            val count = segments.map { it.speakerLabel }.distinct().size
                            val label = if (count == 1) "speaker" else "speakers"
                            applyUiState(UiState.Detected("$count $label detected — review and tap Next when ready"))
                        }
                    }
                    is SpeakerDetectionState.Error -> {
                        scannerOverlayView.stopScanning()
                        applyUiState(UiState.Idle)
                        Log.d(TAG, "Speaker detection unavailable: ${state.message}")
                    }
                }
            }
        }
    }

    private fun populateFromDetectedSegments(detected: List<DetectedSpeakerSegment>) {
        val audioFile = primaryAudioFile ?: return

        // Build ordered unique speaker labels
        val orderedLabels = detected.map { it.speakerLabel }.distinct()

        // Merge with existing subjects list: replace defaults only if we have real detections
        if (orderedLabels.isNotEmpty()) {
            subjects.clear()
            subjects.addAll(orderedLabels)
            selectedSubjectIndex = 0
        }

        // Convert detected segments to AudioSegments
        taggedSegments.clear()
        detected.forEach { seg ->
            taggedSegments.add(
                AudioSegment(
                    startTimeMs = seg.startMs,
                    endTimeMs = seg.endMs,
                    subjectName = seg.speakerLabel,
                    sourceFileName = audioFile.name
                )
            )
        }
        taggedSegments.sortBy { it.startTimeMs }

        // Refresh all UI
        segmentsAdapter.replaceAll(taggedSegments)
        speakerAdapter.updateSubjects(subjects, selectedSubjectIndex)
        markSegmentButton.text = "Save segment for ${selectedSubjectName()}"
        updateSelectionSummary()

        // Animate segments appearing on waveform
        savedSegmentsOverlay.post {
            renderSavedSegments()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SUBJECTS, ArrayList(subjects))
        outState.putInt(STATE_SELECTED_SUBJECT_INDEX, selectedSubjectIndex)
        outState.putSerializable(STATE_TAGGED_SEGMENTS, ArrayList(taggedSegments))
        outState.putLong(STATE_DURATION_MS, durationMs)
        outState.putLong(STATE_PLAYBACK_POSITION_MS, mediaPlayer?.currentPosition?.toLong() ?: restoredPlaybackPositionMs)
    }

    override fun onPause() {
        super.onPause()
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            updatePlayPauseIcon()
        }
        restoredPlaybackPositionMs = mediaPlayer?.currentPosition?.toLong() ?: restoredPlaybackPositionMs
        uiHandler.removeCallbacks(playbackTicker)
    }

    override fun onDestroy() {
        previewLoadJob?.cancel()
        prepareSelectionJob?.cancel()
        uiHandler.removeCallbacks(playbackTicker)
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}
