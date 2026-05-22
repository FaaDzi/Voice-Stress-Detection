package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.core.VoiceCalibrationStore
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class VoiceCalibrationDialog : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "VoiceCalibrationDialog"
        fun newInstance() = VoiceCalibrationDialog()
    }

    private enum class CalibStep { INTRO, RECORDING, PROCESSING, DONE, ERROR }

    var onCalibrationSaved: (() -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentStep = CalibStep.INTRO

    // Views built programmatically
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var primaryButton: MaterialButton
    private lateinit var secondaryButton: MaterialButton

    private val SAMPLE_RATE = 16000
    private val RECORDING_DURATION_MS = 8000L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = { dp: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), ctx.resources.displayMetrics).toInt() }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }

        titleView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        root.addView(titleView)

        // Privacy notice
        val privacyView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#88AAAAAA"))
            gravity = Gravity.CENTER_HORIZONTAL
            text = "This app is local-only — all voice data stays on your device and is never uploaded anywhere."
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
        }
        root.addView(privacyView)

        messageView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(20) }
        }
        root.addView(messageView)

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(20) }
        }
        root.addView(progressBar)

        primaryButton = MaterialButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        root.addView(primaryButton)

        secondaryButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(secondaryButton)

        renderStep(CalibStep.INTRO)
        return root
    }

    private fun renderStep(step: CalibStep) {
        currentStep = step
        when (step) {
            CalibStep.INTRO -> {
                titleView.text = "Calibrate Your Voice"
                messageView.text = "Speak naturally for 8 seconds so the app learns to recognize your voice in future recordings."
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false

                primaryButton.text = "Start Recording"
                primaryButton.visibility = View.VISIBLE
                primaryButton.setOnClickListener { onStartRecordingClicked() }

                secondaryButton.text = "Not Now"
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.setOnClickListener { dismiss() }
            }

            CalibStep.RECORDING -> {
                titleView.text = "Recording..."
                messageView.text = "Please speak naturally. Recording will stop automatically."
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = 0

                primaryButton.visibility = View.GONE

                secondaryButton.text = "Cancel"
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.setOnClickListener { onCancelRecording() }
            }

            CalibStep.PROCESSING -> {
                titleView.text = "Analyzing Your Voice"
                messageView.text = "Analyzing your voice..."
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true

                primaryButton.visibility = View.GONE
                secondaryButton.visibility = View.GONE
            }

            CalibStep.DONE -> {
                titleView.text = "Voice Saved!"
                messageView.text = "Voice saved! The app will now recognize you automatically."
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false

                primaryButton.text = "OK"
                primaryButton.visibility = View.VISIBLE
                primaryButton.setOnClickListener {
                    onCalibrationSaved?.invoke()
                    dismiss()
                }

                secondaryButton.visibility = View.GONE
            }

            CalibStep.ERROR -> {
                titleView.text = "Calibration Failed"
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false

                primaryButton.text = "Try Again"
                primaryButton.visibility = View.VISIBLE
                primaryButton.setOnClickListener { renderStep(CalibStep.INTRO) }

                secondaryButton.text = "Close"
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.setOnClickListener { dismiss() }
            }
        }
    }

    private fun onStartRecordingClicked() {
        val ctx = requireContext()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(ctx, "Microphone permission is required for calibration", Toast.LENGTH_SHORT).show()
            renderStep(CalibStep.INTRO)
            return
        }
        startCalibrationRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startCalibrationRecording() {
        val ctx = requireContext()
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding)
        if (minBufferSize <= 0) {
            Toast.makeText(ctx, "Audio recording not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val tempFile = File(ctx.cacheDir, "calib_temp.raw")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                encoding,
                minBufferSize
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Failed to initialize microphone: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val record = audioRecord ?: return
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(ctx, "Microphone could not be initialized", Toast.LENGTH_SHORT).show()
            releaseAudioRecord()
            return
        }

        renderStep(CalibStep.RECORDING)

        recordingJob?.cancel()
        recordingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(minBufferSize)
            val startMs = System.currentTimeMillis()

            try {
                record.startRecording()
                FileOutputStream(tempFile).use { output ->
                    while (isActive) {
                        val elapsed = System.currentTimeMillis() - startMs
                        if (elapsed >= RECORDING_DURATION_MS) break

                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            output.write(buffer, 0, read)
                        }

                        val progress = ((elapsed.toFloat() / RECORDING_DURATION_MS) * 100).toInt().coerceIn(0, 100)
                        withContext(Dispatchers.Main) {
                            if (currentStep == CalibStep.RECORDING) {
                                progressBar.progress = progress
                                val remaining = ((RECORDING_DURATION_MS - elapsed) / 1000) + 1
                                messageView.text = "Recording... ${remaining}s remaining"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messageView.text = "Recording error: ${e.message}"
                    renderStep(CalibStep.ERROR)
                }
                return@launch
            } finally {
                try { record.stop() } catch (_: Exception) {}
            }

            // If we completed normally (not cancelled), proceed to processing
            if (isActive && currentStep == CalibStep.RECORDING) {
                withContext(Dispatchers.Main) {
                    renderStep(CalibStep.PROCESSING)
                }
                processRecording(tempFile.absolutePath)
            }
        }
    }

    private fun onCancelRecording() {
        recordingJob?.cancel()
        recordingJob = null
        releaseAudioRecord()
        renderStep(CalibStep.INTRO)
    }

    private suspend fun processRecording(rawPath: String) {
        val ctx = requireContext()
        val result = withContext(Dispatchers.IO) {
            try {
                val python = com.chaquo.python.Python.getInstance()
                val module = python.getModule("speaker_detect")
                val pyResult = module.callAttr("extract_user_fingerprint", rawPath, SAMPLE_RATE)
                val jsonStr = pyResult?.toString()
                if (jsonStr.isNullOrBlank()) {
                    Result.failure(Exception("Python returned an empty result"))
                } else {
                    VoiceCalibrationStore.saveFingerprint(ctx, jsonStr)
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        withContext(Dispatchers.Main) {
            if (result.isSuccess) {
                renderStep(CalibStep.DONE)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                messageView.text = "Failed to analyze voice: $errorMsg"
                renderStep(CalibStep.ERROR)
            }
        }
    }

    private fun releaseAudioRecord() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    override fun onDestroyView() {
        recordingJob?.cancel()
        recordingJob = null
        releaseAudioRecord()
        super.onDestroyView()
    }
}
