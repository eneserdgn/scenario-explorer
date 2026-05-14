package com.scenarioexplorer.model

import java.io.File

enum class ScenarioType { CUCUMBER, GAUGE }

enum class StepStatus { PASSED, FAILED, SKIPPED, PENDING, UNDEFINED, NOT_RUN }

data class DataTable(
    val headers: List<String>,
    val rows: List<List<String>>
)

data class ScenarioStep(
    val keyword: String,
    val text: String,
    val line: Int,
    val status: StepStatus = StepStatus.NOT_RUN,
    val errorMessage: String? = null,
    val duration: Long? = null,
    val dataTable: DataTable? = null,
    val screenshotBase64: String? = null
)

data class Scenario(
    val name: String,
    val tags: List<String> = emptyList(),
    val steps: List<ScenarioStep> = emptyList(),
    val file: File,
    val line: Int,
    val type: ScenarioType,
    val status: StepStatus = StepStatus.NOT_RUN,
    val duration: Long? = null
)

data class ScenarioFile(
    val file: File,
    val featureName: String,
    val featureTags: List<String> = emptyList(),
    val type: ScenarioType,
    val scenarios: List<Scenario>
)

data class ReportEntry(
    val scenarioName: String,
    val status: StepStatus,
    val steps: List<StepReportEntry> = emptyList(),
    val duration: Long? = null,
    val timestamp: String? = null,
    val sourceFile: String? = null
)

data class StepReportEntry(
    val text: String,
    val status: StepStatus,
    val errorMessage: String? = null,
    val duration: Long? = null,
    val screenshotBase64: String? = null,
    val depth: Int = 0,
    val isConcept: Boolean = false
)
