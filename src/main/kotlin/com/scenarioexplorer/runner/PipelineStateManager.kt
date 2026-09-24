package com.scenarioexplorer.runner

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Pipeline çalışma state'ini ve loglarını diske kaydeder/yükler.
 * IDE kapanıp açıldığında veya proje değiştirildiğinde son durumu geri yükler.
 *
 * Birden fazla pipeline aynı anda koşabildiği için her pipeline kendi [key]'i altında saklanır.
 */
object PipelineStateManager {

    private const val STATE_DIR = ".scenario-explorer-state"
    private const val STATE_PREFIX = "pipeline-state-"
    private const val LOG_PREFIX = "pipeline-log-"
    private const val OUTPUT_PREFIX = "output-"

    private val gson = Gson()

    data class RunItemState(
        val featurePath: String,
        val featureName: String,
        val scenarioNames: List<String>,
        val status: String,
        val duration: Long,
        val scenarioCount: Int
    )

    data class PipelineState(
        val running: Boolean,
        val pipelineName: String,
        val items: List<RunItemState>,
        val timestamp: Long
    )

    private fun stateDir(project: Project): File {
        val dir = File(project.basePath ?: return File(System.getProperty("java.io.tmpdir")), STATE_DIR)
        dir.mkdirs()
        return dir
    }

    fun saveState(project: Project, key: String, pipelineName: String, running: Boolean, items: List<RunItemState>) {
        try {
            val state = PipelineState(running, pipelineName, items, System.currentTimeMillis())
            File(stateDir(project), "$STATE_PREFIX$key.json").writeText(gson.toJson(state))
        } catch (_: Exception) {}
    }

    /** All saved pipeline states as (key, state) pairs. */
    fun listStates(project: Project): List<Pair<String, PipelineState>> {
        return try {
            stateDir(project).listFiles { f -> f.name.startsWith(STATE_PREFIX) && f.name.endsWith(".json") }
                ?.mapNotNull { f ->
                    val key = f.name.removePrefix(STATE_PREFIX).removeSuffix(".json")
                    val state = try { gson.fromJson(f.readText(), PipelineState::class.java) } catch (_: Exception) { null }
                    if (state == null) null else key to state
                } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun saveLog(project: Project, key: String, logText: String) {
        try {
            File(stateDir(project), "$LOG_PREFIX$key.txt").writeText(logText)
        } catch (_: Exception) {}
    }

    fun loadLog(project: Project, key: String): String {
        return try {
            val file = File(stateDir(project), "$LOG_PREFIX$key.txt")
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) { "" }
    }

    fun saveItemOutput(project: Project, key: String, index: Int, outputText: String) {
        try {
            File(stateDir(project), "$OUTPUT_PREFIX$key-$index.txt").writeText(outputText)
        } catch (_: Exception) {}
    }

    fun loadItemOutput(project: Project, key: String, index: Int): String {
        return try {
            val file = File(stateDir(project), "$OUTPUT_PREFIX$key-$index.txt")
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) { "" }
    }

    /** Removes everything saved for one pipeline (state, log, item outputs). */
    fun clear(project: Project, key: String) {
        try {
            stateDir(project).listFiles()?.filter {
                it.name == "$STATE_PREFIX$key.json" || it.name == "$LOG_PREFIX$key.txt" ||
                    (it.name.startsWith("$OUTPUT_PREFIX$key-") && it.name.endsWith(".txt"))
            }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun clearAll(project: Project) {
        try {
            stateDir(project).deleteRecursively()
        } catch (_: Exception) {}
    }
}
