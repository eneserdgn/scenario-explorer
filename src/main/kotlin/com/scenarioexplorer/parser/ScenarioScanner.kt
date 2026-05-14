package com.scenarioexplorer.parser

import com.intellij.openapi.project.Project
import com.scenarioexplorer.model.ScenarioFile
import com.scenarioexplorer.settings.ScenarioExplorerSettings
import java.io.File

object ScenarioScanner {

    private val EXCLUDED_DIRS = setOf(
        "target", "build", "node_modules", ".gradle", ".idea",
        "out", "bin", ".git", "dist", ".mvn"
    )

    /** Scan with all filters applied (scanPaths + hiddenFiles + hiddenScenarios) */
    fun scanProject(project: Project): List<ScenarioFile> {
        val settings = ScenarioExplorerSettings.getInstance(project)
        val all = scanWithScanPaths(project, settings)

        val hiddenSet = settings.state.hiddenFiles
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val hiddenScenarioSet = settings.state.hiddenScenarios
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val filtered = if (hiddenSet.isEmpty()) all
        else all.filter { it.file.path !in hiddenSet }

        return if (hiddenScenarioSet.isEmpty()) filtered
        else filtered.map { sf ->
            val visibleScenarios = sf.scenarios.filter { "${sf.file.path}::${it.name}" !in hiddenScenarioSet }
            sf.copy(scenarios = visibleScenarios)
        }.filter { it.scenarios.isNotEmpty() }
    }

    /** Scan all feature files (respects scanPaths but ignores hiddenFiles) */
    fun scanProjectUnfiltered(project: Project): List<ScenarioFile> {
        val settings = ScenarioExplorerSettings.getInstance(project)
        return scanWithScanPaths(project, settings)
    }

    private fun scanWithScanPaths(project: Project, settings: ScenarioExplorerSettings): List<ScenarioFile> {
        val basePath = project.basePath ?: return emptyList()
        val baseDir = File(basePath)
        val results = mutableListOf<ScenarioFile>()

        val scanPaths = settings.state.scanPaths
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val roots = if (scanPaths.isNotEmpty()) {
            scanPaths.map { path ->
                val f = File(path)
                if (f.isAbsolute) f else File(baseDir, path)
            }.filter { it.exists() }
        } else {
            listOf(baseDir)
        }

        for (root in roots) {
            root.walkTopDown()
                .onEnter { dir -> dir.name !in EXCLUDED_DIRS }
                .filter { it.isFile }
                .filter { it.extension in listOf("feature", "spec", "cspec") }
                .forEach { file ->
                    val parsed = when (file.extension) {
                        "feature" -> CucumberParser.parse(file)
                        "spec", "cspec" -> GaugeParser.parse(file)
                        else -> null
                    }
                    if (parsed != null && parsed.scenarios.isNotEmpty()) {
                        results.add(parsed)
                    }
                }
        }

        return results.sortedBy { it.file.path }
    }
}
