package com.scenarioexplorer.runner

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Pipeline çalışma state'ini ve loglarını diske kaydeder/yükler.
 * IDE kapanıp açıldığında veya proje değiştirildiğinde son durumu geri yükler.
 */
object PipelineStateManager {

    private const val STATE_DIR = ".scenario-explorer-state"
    private const val STATE_FILE = "pipeline-state.json"
    private const val LOG_FILE = "pipeline-log.txt"
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

    fun saveState(project: Project, pipelineName: String, running: Boolean, items: List<RunItemState>) {
        try {
            val state = PipelineState(running, pipelineName, items, System.currentTimeMillis())
            File(stateDir(project), STATE_FILE).writeText(gson.toJson(state))
        } catch (_: Exception) {}
    }

    fun loadState(project: Project): PipelineState? {
        return try {
            val file = File(stateDir(project), STATE_FILE)
            if (!file.exists()) return null
            gson.fromJson(file.readText(), PipelineState::class.java)
        } catch (_: Exception) { null }
    }

    fun saveLog(project: Project, logText: String) {
        try {
            File(stateDir(project), LOG_FILE).writeText(logText)
        } catch (_: Exception) {}
    }

    fun loadLog(project: Project): String {
        return try {
            val file = File(stateDir(project), LOG_FILE)
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) { "" }
    }

    fun saveItemOutput(project: Project, index: Int, outputText: String) {
        try {
            File(stateDir(project), "$OUTPUT_PREFIX$index.txt").writeText(outputText)
        } catch (_: Exception) {}
    }

    fun loadItemOutput(project: Project, index: Int): String {
        return try {
            val file = File(stateDir(project), "$OUTPUT_PREFIX$index.txt")
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) { "" }
    }

    fun clearOutputs(project: Project) {
        try {
            val dir = stateDir(project)
            dir.listFiles()?.filter { it.name.startsWith(OUTPUT_PREFIX) }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun clearAll(project: Project) {
        try {
            stateDir(project).deleteRecursively()
        } catch (_: Exception) {}
    }
}
