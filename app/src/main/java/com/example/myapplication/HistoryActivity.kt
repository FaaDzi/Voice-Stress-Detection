package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.core.AppStorageManager
import com.example.myapplication.core.AppThemeManager
import org.json.JSONObject
import java.io.File

data class HistorySession(
    val sessionId: String,
    val createdAt: String,
    val speakerFocus: String,
    val totalFiles: Int,
    val topLabel: String,
    val avgConfidence: Double,
    val soundDir: File,
    val imgDir: File
)

class HistoryActivity : AppCompatActivity() {
    private lateinit var backButton: ImageButton
    private lateinit var emptyView: TextView
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var storageManager: AppStorageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        storageManager = AppStorageManager(this)
        backButton = findViewById(R.id.backButton)
        emptyView = findViewById(R.id.emptyView)
        historyRecyclerView = findViewById(R.id.historyRecyclerView)

        backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val sessions = loadSessions()
        emptyView.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        historyRecyclerView.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
        historyRecyclerView.layoutManager = LinearLayoutManager(this)
        historyRecyclerView.adapter = HistorySessionAdapter(
            context = this,
            sessions = sessions,
            onOpen = { openSession(it) },
            onExport = { exportSessionGraphs(it) }
        )
    }

    private fun loadSessions(): List<HistorySession> {
        val runsDir = storageManager.runsDir()
        if (!runsDir.exists()) return emptyList()

        return runsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.mapNotNull { dir ->
                val metaFile = File(dir, "session.json")
                val meta = if (metaFile.exists()) {
                    try {
                        JSONObject(metaFile.readText())
                    } catch (_: Exception) {
                        JSONObject()
                    }
                } else {
                    JSONObject()
                }

                val soundDir = File(dir, "sound")
                val imgDir = File(dir, "img")
                if (!imgDir.exists()) return@mapNotNull null

                HistorySession(
                    sessionId = dir.name,
                    createdAt = meta.optString("created_at", dir.name.removePrefix("run-")),
                    speakerFocus = meta.optString("speaker_focus", "all"),
                    totalFiles = meta.optInt("total_files", soundDir.listFiles()?.size ?: 0),
                    topLabel = meta.optString("top_label", "-"),
                    avgConfidence = meta.optDouble("avg_confidence", 0.0),
                    soundDir = soundDir,
                    imgDir = imgDir
                )
            }
            ?: emptyList()
    }

    private fun openSession(session: HistorySession) {
        startActivity(
            Intent(this, ProcessingActivity::class.java).apply {
                putExtra("SOUND_FOLDER_PATH", session.soundDir.absolutePath)
                putExtra("TFLITE_SELECTED", "default")
                putExtra("SPEAKER_FOCUS", session.speakerFocus)
                putExtra("ASSET_DIR", storageManager.assetDir().absolutePath)
                putExtra("APP_ROOT_DIR", storageManager.appRootDir().absolutePath)
                putExtra("SESSION_ID", session.sessionId)
            }
        )
    }

    private fun exportSessionGraphs(session: HistorySession) {
        val pngFiles = session.imgDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "png" }
            ?: emptyList()

        if (pngFiles.isEmpty()) {
            Toast.makeText(this, "No graphs found for this session", Toast.LENGTH_SHORT).show()
            return
        }

        var saved = 0
        for (file in pngFiles) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/StressDetection/" + session.sessionId
                )
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                saved += 1
            }
        }

        Toast.makeText(this, "Exported $saved graph(s)", Toast.LENGTH_SHORT).show()
    }
}

private class HistorySessionAdapter(
    private val context: Context,
    private val sessions: List<HistorySession>,
    private val onOpen: (HistorySession) -> Unit,
    private val onExport: (HistorySession) -> Unit
) : RecyclerView.Adapter<HistorySessionAdapter.HistorySessionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistorySessionViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_history_session, parent, false)
        return HistorySessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistorySessionViewHolder, position: Int) {
        val session = sessions[position]
        holder.title.text = session.sessionId
        holder.subtitle.text = "${session.createdAt} • ${session.speakerFocus}"
        holder.summary.text =
            "Files: ${session.totalFiles}  |  Top label: ${session.topLabel}  |  Avg confidence: ${"%.2f".format(session.avgConfidence * 100)}%"
        holder.openButton.setOnClickListener { onOpen(session) }
        holder.exportButton.setOnClickListener { onExport(session) }
    }

    override fun getItemCount(): Int = sessions.size

    class HistorySessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sessionTitle)
        val subtitle: TextView = view.findViewById(R.id.sessionSubtitle)
        val summary: TextView = view.findViewById(R.id.sessionSummary)
        val openButton: View = view.findViewById(R.id.openButton)
        val exportButton: View = view.findViewById(R.id.exportButton)
    }
}
