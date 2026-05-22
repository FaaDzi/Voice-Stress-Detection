package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.myapplication.core.AppThemeManager
import com.example.myapplication.core.AppStorageManager
import com.example.myapplication.core.AppDialogs
import com.example.myapplication.core.AssetSetupManager
import com.example.myapplication.core.DeviceCapabilityChecker
import com.example.myapplication.core.ModelCatalogStore
import com.example.myapplication.core.AudioWavUtils
import com.example.myapplication.core.SetupState
import com.example.myapplication.core.VoiceCalibrationStore
import com.example.myapplication.core.setDebouncedClickListener
import com.example.myapplication.core.StoredModel
import com.example.myapplication.core.TaskForegroundService
import com.example.myapplication.core.ThemeAnimator
import com.example.myapplication.core.TfliteModelValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_SAVE_LOCATION = 201
        private const val REQUEST_PERMISSIONS = 200
        private const val RECORDING_UI_UPDATE_INTERVAL_MS = 80L
        private const val STATE_SELECTED_AUDIO_URIS = "state_selected_audio_uris"
        private const val STATE_SELECTED_TFLITE_PATH = "state_selected_tflite_path"
        private const val STATE_SELECTED_MIC = "state_selected_mic"
        private const val STATE_SELECTED_SPEAKER_FOCUS = "state_selected_speaker_focus"
        private const val SAMPLE_RATE_HZ = 16000
        private const val PCM_16BIT_MAX = 32768.0
        private const val AMPLITUDE_BOOST = 1.2
        private const val MAX_IMPORT_FILE_BYTES = 128L * 1024L * 1024L
    }

    private lateinit var recordPauseButton: ImageButton
    private lateinit var loadAudioButton: Button
    private lateinit var proceedButton: Button
    private lateinit var gearButton: ImageButton
    private lateinit var selectedFilesView: TextView
    private lateinit var recordingTimer: TextView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var setupPanel: FrameLayout
    private lateinit var setupStatusText: TextView
    private lateinit var setupNowButton: Button
    private lateinit var patientIndicator: View
    private lateinit var doctorIndicator: View
    private lateinit var allIndicator: View
    private lateinit var rootLayout: View
    private lateinit var audioVisualizerContainer: View
    private lateinit var visBar1: View
    private lateinit var visBar2: View
    private lateinit var visBar3: View
    private lateinit var visBar4: View
    private lateinit var visBar5: View
    private lateinit var visBar6: View
    private lateinit var visBar7: View

    private lateinit var pickTfliteLauncher: ActivityResultLauncher<Intent>

    private var isRecording = false
    private var selectedTflitePath = "default"
    private var selectedMic = "internal"
    private var selectedSpeakerFocus = "all"

    private lateinit var tempRawPath: String
    private lateinit var audioRecord: AudioRecord
    private lateinit var buffer: ByteArray
    private var recordingJob: Job? = null
    private var setupJob: Job? = null

    private val selectedAudioUris = mutableListOf<Uri>()
    private lateinit var storageManager: AppStorageManager
    private lateinit var setupState: SetupState
    private lateinit var assetSetupManager: AssetSetupManager
    private lateinit var modelCatalogStore: ModelCatalogStore

    private val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val currentSetupVersion = 1
    @Volatile
    private var pythonRuntimeInitialized = false

    private val selectAudioLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                selectedAudioUris.clear()
                result.data?.let { data ->
                    if (data.clipData != null) {
                        for (i in 0 until data.clipData!!.itemCount) {
                            selectedAudioUris.add(data.clipData!!.getItemAt(i).uri)
                        }
                    } else {
                        data.data?.let { selectedAudioUris.add(it) }
                    }
                }
                updateSelectedFilesView()
                proceedButton.visibility = if (selectedAudioUris.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storageManager = AppStorageManager(this)
        setupState = SetupState(this)
        modelCatalogStore = ModelCatalogStore(this, storageManager)
        assetSetupManager = AssetSetupManager(
            storageManager = storageManager,
            openAsset = { assets.open(it) }
        )
        bindViews()
        applyThemeUi()
        restoreUiState(savedInstanceState)
        initializeStatusIndicators()
        ensureRuntimePermissions()
        registerFilePickers()
        setupListeners()
        updateSetupUi()
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.rootLayout)
        recordPauseButton = findViewById(R.id.recordPauseButton)
        loadAudioButton = findViewById(R.id.loadAudioButton)
        proceedButton = findViewById(R.id.proceedButton)
        gearButton = findViewById(R.id.gearButton)
        selectedFilesView = findViewById(R.id.selectedFilesView)
        recordingTimer = findViewById(R.id.recordingTimer)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        audioLevelBar = findViewById(R.id.audioLevelBar)
        setupPanel = findViewById(R.id.setupPanel)
        setupStatusText = findViewById(R.id.setupStatusText)
        setupNowButton = findViewById(R.id.setupNowButton)
        patientIndicator = findViewById(R.id.patientIndicator)
        doctorIndicator = findViewById(R.id.doctorIndicator)
        allIndicator = findViewById(R.id.allIndicator)
        audioVisualizerContainer = findViewById(R.id.audioVisualizerContainer)
        visBar1 = findViewById(R.id.visBar1)
        visBar2 = findViewById(R.id.visBar2)
        visBar3 = findViewById(R.id.visBar3)
        visBar4 = findViewById(R.id.visBar4)
        visBar5 = findViewById(R.id.visBar5)
        visBar6 = findViewById(R.id.visBar6)
        visBar7 = findViewById(R.id.visBar7)
    }

    private fun applyThemeUi() {
        when {
            AppThemeManager.isTheme2(this) -> {
                audioLevelBar.visibility = View.GONE
                audioVisualizerContainer.visibility = View.VISIBLE
                ThemeAnimator.startBackgroundAnimation(this, rootLayout)
            }
            else -> {
                audioLevelBar.visibility = View.VISIBLE
                audioVisualizerContainer.visibility = View.GONE
                ThemeAnimator.stopBackgroundAnimation()
            }
        }
    }

    private fun visualizerBars(): List<View> {
        return listOf(visBar1, visBar2, visBar3, visBar4, visBar5, visBar6, visBar7)
    }

    private fun initializeStatusIndicators() {
        findViewById<View>(R.id.tfliteStatus).setBackgroundResource(R.drawable.circle_green)
        if (selectedTflitePath == "default") {
            syncSelectedModelFromStore()
        } else {
            val selectedModel = modelCatalogStore.loadModels().firstOrNull { model ->
                (!model.isDefault && model.path == selectedTflitePath) || (model.isDefault && selectedTflitePath == "default")
            }
            if (selectedModel != null) {
                updateSelectedModelStatus(selectedModel)
            } else {
                syncSelectedModelFromStore()
            }
        }
        findViewById<View>(R.id.pythonStatus).setBackgroundResource(R.drawable.circle_green)
        findViewById<TextView>(R.id.pythonStatusText).text = "(bundled)"
        findViewById<TextView>(R.id.micStatusText).text =
            if (selectedMic == "external") "(External)" else "(Internal)"
        updateSpeakerFocusIndicators()
        updateSelectedFilesView()
        proceedButton.visibility = if (selectedAudioUris.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun ensureRuntimePermissions() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS)
        }
    }

    private fun registerFilePickers() {
        pickTfliteLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            if (!validateImport(uri, listOf("tflite"), MAX_IMPORT_FILE_BYTES)) {
                Toast.makeText(this, "Invalid model file", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (!modelCatalogStore.canAddCustomModel()) {
                Toast.makeText(this, "Maximum saved models reached. Remove one custom model first.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            loadingOverlay.visibility = View.VISIBLE
            lifecycleScope.launch {
                val validation = withContext(Dispatchers.IO) {
                    val target = File(storageManager.assetDir(), "import_candidate.tflite")
                    copyUriToFile(uri, target)
                    val reference = File(storageManager.assetDir(), "model.tflite")
                    val result = TfliteModelValidator.validate(target, reference)
                    Pair(target, result)
                }
                loadingOverlay.visibility = View.GONE
                val (target, result) = validation
                if (result.isValid) {
                    val defaultName = ((getFileName(uri) ?: "Custom Model").substringBeforeLast(".")).ifBlank { "Custom Model" }
                    saveValidatedModel(target, defaultName, result)
                    target.delete()
                    Toast.makeText(
                        this@MainActivity,
                        "Custom TFLite saved. Signature check passed.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    target.delete()
                    Toast.makeText(
                        this@MainActivity,
                        result.reason,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    }

    private fun setupListeners() {
        setupNowButton.setDebouncedClickListener { runFirstTimeSetup() }
        gearButton.setDebouncedClickListener { showSettingsDropdown() }
        recordPauseButton.setDebouncedClickListener { onRecordPauseClicked() }
        loadAudioButton.setDebouncedClickListener { openAudioPicker() }
        proceedButton.setDebouncedClickListener { proceedToProcessing() }
    }

    private fun onRecordPauseClicked() {
        if (isRecording) {
            stopRecording()
            startActivityForResult(createSaveRecordingIntent(), REQUEST_SAVE_LOCATION)
        } else {
            startRecording()
        }
    }

    private fun createSaveRecordingIntent(): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "recorded_audio.wav")
        }
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        selectAudioLauncher.launch(intent)
    }

    private fun proceedToProcessing() {
        if (selectedAudioUris.isEmpty()) {
            Toast.makeText(this, "Select audio files first", Toast.LENGTH_SHORT).show()
            return
        }
        loadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            val sessionId = storageManager.createSessionId()
            val soundFolderPath = withContext(Dispatchers.IO) {
                copyAllAudioToSoundFolder(sessionId)
            }
            loadingOverlay.visibility = View.GONE
            startActivity(createSelectionIntent(soundFolderPath, sessionId))
        }
    }

    private fun createSelectionIntent(soundFolderPath: String, sessionId: String): Intent {
        val selectedModel = modelCatalogStore.selectedModel()
        return Intent(this, SelectionActivity::class.java).apply {
            putExtra("SOUND_FOLDER_PATH", soundFolderPath)
            putExtra("TFLITE_SELECTED", selectedTflitePath)
            putExtra("MODEL_DISPLAY_NAME", selectedModel.name)
            putExtra("SPEAKER_FOCUS", selectedSpeakerFocus)
            putExtra("ASSET_DIR", storageManager.assetDir().absolutePath)
            putExtra("APP_ROOT_DIR", storageManager.appRootDir().absolutePath)
            putExtra("SESSION_ID", sessionId)
        }
    }

    private fun updateSetupUi() {
        val ready = setupState.isSetupComplete(currentSetupVersion)
        setupPanel.visibility = if (ready) View.GONE else View.VISIBLE
        setMainActionsEnabled(ready)
    }

    private fun setMainActionsEnabled(enabled: Boolean) {
        recordPauseButton.isEnabled = enabled
        loadAudioButton.isEnabled = enabled
        proceedButton.isEnabled = enabled
        gearButton.isEnabled = true
    }

    private fun runFirstTimeSetup() {
        setupNowButton.isEnabled = false
        setupStatusText.text = "Preparing assets and validating Python runtime..."
        loadingOverlay.visibility = View.VISIBLE

        setupJob?.cancel()
        setupJob = lifecycleScope.launch(Dispatchers.IO) {
            val setupResult = try {
                assetSetupManager.runSetup { message, progress ->
                    runOnUiThread { setupStatusText.text = "$message ($progress%)" }
                }
                runPythonValidationForSetup()
                SetupResult(success = true)
            } catch (e: Exception) {
                SetupResult(success = false, message = buildSetupErrorLog(e)).also {
                    writeSetupLog(it.message.orEmpty())
                }
            }

            withContext(Dispatchers.Main) {
                loadingOverlay.visibility = View.GONE
                setupNowButton.isEnabled = true
                if (setupResult.success) {
                    setupState.markSetupComplete(currentSetupVersion)
                    setupStatusText.text = "Setup complete. Storage: ${storageManager.appRootDir().absolutePath}"
                    Toast.makeText(this@MainActivity, "Setup completed", Toast.LENGTH_SHORT).show()
                    // After marking setup complete, offer calibration if not already done
                    if (!VoiceCalibrationStore.hasCalibration(this@MainActivity)) {
                        showCalibrationOfferDialog()
                    }
                } else {
                    setupStatusText.text = "Setup failed. Open Settings > View Setup Log for details."
                    Toast.makeText(this@MainActivity, "Setup failed", Toast.LENGTH_SHORT).show()
                }
                updateSetupUi()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
            return
        }

        val sampleRate = SAMPLE_RATE_HZ
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBufferSize <= 0) {
            Toast.makeText(this, "Audio config not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val source = if (selectedMic == "internal") MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.DEFAULT
        audioRecord = AudioRecord(source, sampleRate, channelConfig, encoding, minBufferSize)
        buffer = ByteArray(minBufferSize)

        val tempFile = File(storageManager.soundDir(), "temp_audio.raw")
        tempRawPath = tempFile.absolutePath

        TaskForegroundService.start(this, TaskForegroundService.MODE_RECORDING)
        audioRecord.startRecording()
        isRecording = true
        recordPauseButton.setImageResource(R.drawable.ic_pause)

        val startTimeMs = System.currentTimeMillis()
        var lastUiUpdateAt = 0L
        var lastDisplayedSecond = -1L
        recordingJob?.cancel()
        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            FileOutputStream(tempFile).use { output ->
                while (isRecording) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)

                        val amplitude = AudioWavUtils.maxAmplitude(buffer, read)
                        val progress = amplitudeToMeterLevel(amplitude)

                        val elapsed = System.currentTimeMillis() - startTimeMs
                        val elapsedSeconds = elapsed / 1000
                        val shouldRefreshVisualizer = (System.currentTimeMillis() - lastUiUpdateAt) >= RECORDING_UI_UPDATE_INTERVAL_MS
                        val shouldRefreshTimer = elapsedSeconds != lastDisplayedSecond

                        if (shouldRefreshVisualizer || shouldRefreshTimer) {
                            lastUiUpdateAt = System.currentTimeMillis()
                            lastDisplayedSecond = elapsedSeconds
                            val seconds = elapsedSeconds % 60
                            val minutes = elapsedSeconds / 60

                            withContext(Dispatchers.Main) {
                                if (shouldRefreshTimer) {
                                    recordingTimer.text = String.format("%02d:%02d", minutes, seconds)
                                }
                                if (AppThemeManager.isTheme2(this@MainActivity)) {
                                    ThemeAnimator.updateVisualizer(visualizerBars(), progress)
                                } else {
                                    audioLevelBar.progress = progress
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        try {
            audioRecord.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord.release()
        } catch (_: Exception) {
        }
        recordingJob?.cancel()
        recordingJob = null
        TaskForegroundService.stop(this)
        recordPauseButton.setImageResource(R.drawable.ic_mic)
    }

    private fun amplitudeToMeterLevel(amplitude: Int): Int {
        if (amplitude <= 0) return 0
        val normalized = (amplitude / PCM_16BIT_MAX).coerceIn(0.0, 1.0)
        val boosted = (sqrt(normalized) * AMPLITUDE_BOOST).coerceIn(0.0, 1.0)
        return (boosted * 100.0).toInt().coerceIn(0, 100)
    }

    private fun convertPcmToWavAndSave(pcmPath: String, wavUri: Uri) {
        loadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(wavUri)?.use { output ->
                        AudioWavUtils.writePcmAsWav(File(pcmPath), output)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            loadingOverlay.visibility = View.GONE
            if (success) {
                selectedAudioUris.clear()
                selectedAudioUris.add(wavUri)
                updateSelectedFilesView()
                proceedButton.visibility = View.VISIBLE
                Toast.makeText(this@MainActivity, "Recording saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Failed to save recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSettingsDropdown() {
        if (supportFragmentManager.findFragmentByTag("settings_bottom_sheet") != null) return
        SettingsBottomSheetFragment().apply {
            onActionSelected = { action ->
                when (action) {
                    SettingsBottomSheetFragment.Action.APP_THEME -> showThemeOptions()
                    SettingsBottomSheetFragment.Action.APP_GUIDE -> showAppGuideDialog()
                    SettingsBottomSheetFragment.Action.HISTORY -> startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
                    SettingsBottomSheetFragment.Action.DEVICE_CHECK -> showDeviceReadinessDialog()
                    SettingsBottomSheetFragment.Action.STORAGE_OPTIMIZER -> showStorageOptimizerDialog()
                    SettingsBottomSheetFragment.Action.SETUP_LOG -> showSetupLogDialog()
                    SettingsBottomSheetFragment.Action.TFLITE_MODEL -> showTfliteOptions()
                    SettingsBottomSheetFragment.Action.MICROPHONE -> showMicOptions()
                    SettingsBottomSheetFragment.Action.VOICE_TARGET -> showVoiceTargetOptions()
                    SettingsBottomSheetFragment.Action.STORAGE_LOCATION -> showStorageOptions()
                }
            }
        }.show(supportFragmentManager, "settings_bottom_sheet")
    }

    private fun showThemeOptions() {
        val options = arrayOf("Theme 1", "Theme 2")
        val checkedItem = if (AppThemeManager.currentTheme(this) == AppThemeManager.THEME2) 1 else 0

        AppDialogs.builder(this)
            .setTitle("App Theme")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val selectedTheme = if (which == 1) AppThemeManager.THEME2 else AppThemeManager.THEME1
                val currentTheme = AppThemeManager.currentTheme(this)
                if (selectedTheme != currentTheme) {
                    AppThemeManager.saveTheme(this, selectedTheme)
                    dialog.dismiss()
                    recreate()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTfliteOptions() {
        val options = arrayOf("Select Saved Model", "Import Custom Model", "Remove Custom Model", "Model Info")
        AppDialogs.builder(this)
            .setTitle("TFLite Model")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSavedModelChooser()
                    1 -> pickCustomTflite()
                    2 -> showRemoveModelDialog()
                    3 -> showCurrentModelInfo()
                }
            }
            .show()
    }

    private fun pickCustomTflite() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.fromFile(storageManager.assetDir()))
        }
        pickTfliteLauncher.launch(intent)
    }

    private fun showMicOptions() {
        val options = arrayOf("Internal", "External")
        AppDialogs.builder(this)
            .setTitle("Select Microphone")
            .setItems(options) { _, which ->
                selectedMic = if (which == 0) "internal" else "external"
                val label = if (selectedMic == "internal") "(Internal)" else "(External)"
                findViewById<TextView>(R.id.micStatusText).text = label
            }
            .show()
    }

    private fun showCalibrationOfferDialog() {
        AppDialogs.builder(this)
            .setTitle("Set Up Your Voice")
            .setMessage("Calibrate your voice so the app can automatically identify you in recordings.\n\nThis app is local-only — all voice data stays on your device and is never uploaded anywhere.")
            .setPositiveButton("Set Up Now") { _, _ ->
                showCalibrationDialog()
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    private fun showCalibrationDialog() {
        if (supportFragmentManager.findFragmentByTag(VoiceCalibrationDialog.TAG) != null) return
        VoiceCalibrationDialog.newInstance().also { dialog ->
            dialog.onCalibrationSaved = {
                Toast.makeText(this, "Voice calibration saved!", Toast.LENGTH_SHORT).show()
            }
        }.show(supportFragmentManager, VoiceCalibrationDialog.TAG)
    }

    private fun showVoiceTargetOptions() {
        val calibrated = VoiceCalibrationStore.hasCalibration(this)
        val allOptions = if (calibrated) {
            arrayOf("Set Speaker Focus", "Recalibrate My Voice", "Clear Voice Calibration")
        } else {
            arrayOf("Set Speaker Focus", "Calibrate My Voice")
        }
        AppDialogs.builder(this)
            .setTitle("Voice Target")
            .setItems(allOptions) { _, which ->
                when {
                    which == 0 -> showSpeakerFocusOptions()
                    allOptions[which] == "Calibrate My Voice" || allOptions[which] == "Recalibrate My Voice" -> showCalibrationDialog()
                    allOptions[which] == "Clear Voice Calibration" -> {
                        VoiceCalibrationStore.clearCalibration(this)
                        Toast.makeText(this, "Voice calibration cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showSpeakerFocusOptions() {
        val options = arrayOf("All", "User heuristic", "Doctor heuristic")
        val checkedItem = when (selectedSpeakerFocus) {
            "patient" -> 1
            "doctor" -> 2
            else -> 0
        }

        AppDialogs.builder(this)
            .setTitle("Voice Target")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                selectedSpeakerFocus = when (which) {
                    1 -> "patient"
                    2 -> "doctor"
                    else -> "all"
                }
                updateSpeakerFocusIndicators()
                Toast.makeText(this, "Speaker focus: ${options[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSpeakerFocusIndicators() {
        patientIndicator.setBackgroundResource(
            if (selectedSpeakerFocus == "patient") R.drawable.circle_patient else R.drawable.circle_gray
        )
        doctorIndicator.setBackgroundResource(
            if (selectedSpeakerFocus == "doctor") R.drawable.circle_doctor else R.drawable.circle_gray
        )
        allIndicator.setBackgroundResource(
            if (selectedSpeakerFocus == "all") R.drawable.circle_all else R.drawable.circle_gray
        )
    }

    private fun showStorageOptions() {
        val internal = storageManager.getPrimaryExternalDir()
        val removable = storageManager.getRemovableExternalDir()
        if (removable == null) {
            Toast.makeText(this, "No SD card storage detected", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            "Internal (${internal.name})",
            "SD Card (${removable.name})"
        )

        AppDialogs.builder(this)
            .setTitle("Choose Storage Location")
            .setItems(options) { _, which ->
                if (which == 0) {
                    storageManager.saveStorageRoot(internal)
                    Toast.makeText(this, "Using internal storage", Toast.LENGTH_SHORT).show()
                } else {
                    storageManager.saveStorageRoot(removable)
                    Toast.makeText(this, "Using SD card storage", Toast.LENGTH_SHORT).show()
                }
                setupStatusText.text = "Storage set to: ${storageManager.appRootDir().absolutePath}"
                if (setupState.isSetupComplete(currentSetupVersion)) {
                    Toast.makeText(this, "Re-run setup to copy assets into the new storage.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun showDeviceReadinessDialog() {
        val report = DeviceCapabilityChecker.buildReport(this, storageManager)
        AppDialogs.builder(this)
            .setTitle("Device Readiness")
            .setMessage(report.summary)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showStorageOptimizerDialog() {
        val appUsageMb = storageManager.appUsageBytes() / (1024.0 * 1024.0)
        val compareMb = storageManager.compareCacheBytes() / (1024.0 * 1024.0)
        val message = buildString {
            appendLine("Storage overview:")
            appendLine("App working data: ${"%.1f".format(appUsageMb)} MB")
            appendLine("Model comparison cache: ${"%.1f".format(compareMb)} MB")
            appendLine()
            append("Clearing compare cache removes saved comparison outputs only. Main session history stays intact.")
        }

        AppDialogs.builder(this)
            .setTitle("Storage Optimizer")
            .setMessage(message)
            .setPositiveButton("Clear Compare Cache") { _, _ ->
                val cleared = storageManager.clearCompareCaches()
                Toast.makeText(this, "Cleared comparison cache for $cleared session(s)", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAppGuideDialog() {
        val message = """
            Quick guide:

            1. Normal analysis runs the selected audio through the currently selected model only.
            2. Compare Saved Models runs the same audio through every saved model, up to 4 total models.
            3. Long WAV files and compare mode can take much longer and create extra charts/results for each model.
            4. For lower-end devices, prefer shorter sessions or single-model analysis.
            5. Use Storage Optimizer if comparison outputs start taking too much space.

            Notes:
            - Default model cannot be removed.
            - Custom models stay registered until you remove them from inside the app.
            - Removing a custom model from the app does not delete the original file from your device.
        """.trimIndent()

        AppDialogs.builder(this)
            .setTitle("App Guide")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSetupLogDialog() {
        val logFile = storageManager.setupLogFile()
        val message = if (logFile.exists()) logFile.readText() else "No setup log has been recorded yet."
        AppDialogs.builder(this)
            .setTitle("Setup Log")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun copyAllAudioToSoundFolder(sessionId: String): String {
        val folder = storageManager.sessionSoundDir(sessionId)
        folder.mkdirs()

        for (uri in selectedAudioUris) {
            val originalName = getFileName(uri) ?: "audio.wav"
            val safeName = sanitizeFilename(originalName)
            val target = safeFileInFolder(folder, safeName) ?: continue
            copyUriToFile(uri, target)
            val ext = target.extension.lowercase()
            if (ext == "mp3" || ext == "m4a" || ext == "aac") {
                val wavTarget = File(folder, target.nameWithoutExtension + ".wav")
                val converted = AudioWavUtils.convertToWav(target, wavTarget)
                if (converted) {
                    target.delete()
                } else {
                    Log.w("MainActivity", "Could not convert ${target.name} to WAV; leaving as-is")
                }
            }
        }
        return folder.absolutePath
    }

    private fun updateSelectedFilesView() {
        selectedFilesView.text = if (selectedAudioUris.isEmpty()) {
            "No files selected"
        } else {
            selectedAudioUris.joinToString("\n") { getFileName(it) ?: "Unknown" }
        }
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) return cursor.getString(index)
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    private fun allPermissionsGranted(): Boolean {
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        }
    }

    private fun sanitizeFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace("..", "_").replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.isBlank()) "audio.wav" else cleaned
    }

    private fun safeFileInFolder(folder: File, fileName: String): File? {
        val target = File(folder, fileName)
        return try {
            val folderPath = folder.canonicalPath + File.separator
            val targetPath = target.canonicalPath
            if (targetPath.startsWith(folderPath)) target else null
        } catch (_: Exception) {
            null
        }
    }

    private fun validateImport(uri: Uri, allowedExt: List<String>, maxBytes: Long): Boolean {
        val name = (getFileName(uri) ?: "").lowercase()
        val validExt = allowedExt.any { name.endsWith(".$it") }
        if (!validExt) return false

        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val len = afd.length
                len in 1..maxBytes
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SAVE_LOCATION && resultCode == RESULT_OK) {
            data?.data?.let { convertPcmToWavAndSave(tempRawPath, it) }
        }
    }

    override fun onDestroy() {
        setupJob?.cancel()
        if (isRecording) {
            stopRecording()
        } else {
            recordingJob?.cancel()
        }
        ThemeAnimator.stopBackgroundAnimation()
        super.onDestroy()
    }

    private fun restoreUiState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        selectedTflitePath = savedInstanceState.getString(STATE_SELECTED_TFLITE_PATH, selectedTflitePath)
        selectedMic = savedInstanceState.getString(STATE_SELECTED_MIC, selectedMic)
        selectedSpeakerFocus = savedInstanceState.getString(STATE_SELECTED_SPEAKER_FOCUS, selectedSpeakerFocus)
        val restoredUris = savedInstanceState.getStringArrayList(STATE_SELECTED_AUDIO_URIS).orEmpty()
        selectedAudioUris.clear()
        selectedAudioUris.addAll(restoredUris.map(Uri::parse))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            STATE_SELECTED_AUDIO_URIS,
            ArrayList(selectedAudioUris.map(Uri::toString))
        )
        outState.putString(STATE_SELECTED_TFLITE_PATH, selectedTflitePath)
        outState.putString(STATE_SELECTED_MIC, selectedMic)
        outState.putString(STATE_SELECTED_SPEAKER_FOCUS, selectedSpeakerFocus)
    }

    private fun syncSelectedModelFromStore() {
        val selectedModel = modelCatalogStore.selectedModel()
        selectedTflitePath = if (selectedModel.isDefault) "default" else selectedModel.path
        updateSelectedModelStatus(selectedModel)
    }

    private fun updateSelectedModelStatus(model: StoredModel) {
        val label = if (model.isDefault) "(default: ${model.name})" else "(custom: ${model.name})"
        findViewById<TextView>(R.id.tfliteStatusText).text = label
    }

    private fun saveValidatedModel(
        importedFile: File,
        suggestedName: String,
        validation: TfliteModelValidator.ValidationResult
    ) {
        val saved = modelCatalogStore.saveCustomModel(suggestedName, importedFile, validation)
        saved.onSuccess { model ->
            selectedTflitePath = model.path
            updateSelectedModelStatus(model)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "Unable to save model", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSavedModelChooser() {
        val models = modelCatalogStore.loadModels()
        val labels = models.map { model ->
            val type = if (model.isDefault) "Default" else "Custom"
            "$type - ${model.name}"
        }.toTypedArray()
        val checkedItem = models.indexOfFirst { model ->
            if (model.isDefault) selectedTflitePath == "default" else model.path == selectedTflitePath
        }.coerceAtLeast(0)

        AppDialogs.builder(this)
            .setTitle("Select Saved Model")
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                val chosen = models[which]
                modelCatalogStore.selectModel(chosen.id)
                selectedTflitePath = if (chosen.isDefault) "default" else chosen.path
                updateSelectedModelStatus(chosen)
                Toast.makeText(this, "Model selected: ${chosen.name}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoveModelDialog() {
        val customModels = modelCatalogStore.customModels()
        if (customModels.isEmpty()) {
            Toast.makeText(this, "No custom models to remove", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = customModels.map { it.name }.toTypedArray()
        AppDialogs.builder(this)
            .setTitle("Remove Custom Model")
            .setItems(labels) { _, which ->
                val model = customModels[which]
                val removed = modelCatalogStore.removeCustomModel(model.id)
                if (removed) {
                    syncSelectedModelFromStore()
                    Toast.makeText(this, "Removed from app catalog: ${model.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Unable to remove model", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showCurrentModelInfo() {
        AppDialogs.builder(this)
            .setTitle("Current Model Info")
            .setMessage(modelCatalogStore.currentModelInfo())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun runPythonValidationForSetup() {
        ensurePythonRuntimeStarted()
        val py = Python.getInstance()
        try {
            py.getModule("testpython").callAttr("main")
        } catch (e: PyException) {
            if (e.message?.contains("SystemExit: 0") == true) {
                return
            }
            throw e
        }
    }

    @Synchronized
    private fun ensurePythonRuntimeStarted() {
        if (pythonRuntimeInitialized || Python.isStarted()) {
            pythonRuntimeInitialized = true
            return
        }
        Python.start(AndroidPlatform(applicationContext))
        pythonRuntimeInitialized = true
    }

    private fun buildSetupErrorLog(error: Exception): String {
        return buildString {
            appendLine("Setup failed at: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
            appendLine("Error: ${error::class.java.simpleName}")
            appendLine("Message: ${error.message ?: "(no message)"}")
            appendLine("Hint: Use Logcat for the full technical trace if deeper debugging is needed.")
        }
    }

    private fun writeSetupLog(content: String) {
        val logFile = storageManager.setupLogFile()
        logFile.parentFile?.mkdirs()
        logFile.writeText(content)
    }

    private data class SetupResult(
        val success: Boolean,
        val message: String? = null
    )
}
