package com.scenarioexplorer.report

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.scenarioexplorer.model.ReportEntry
import com.scenarioexplorer.model.StepReportEntry
import com.scenarioexplorer.model.StepStatus
import java.io.File
import java.text.SimpleDateFormat

object ReportReader {

    /**
     * Returns a map of scenario name -> list of report entries (multiple runs).
     * Sorted newest first by file lastModified.
     */
    fun readReports(reportPath: String): Map<String, List<ReportEntry>> {
        val file = File(reportPath)
        if (!file.exists()) return emptyMap()

        val allEntries = mutableMapOf<String, MutableList<ReportEntry>>()

        val jsonFiles = if (file.isDirectory) {
            file.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        } else listOf(file)

        // Sort files newest first
        val sorted = jsonFiles.sortedByDescending { it.lastModified() }

        for (jsonFile in sorted) {
            val entries = parseJsonReport(jsonFile)
            for ((name, entry) in entries) {
                allEntries.getOrPut(name) { mutableListOf() }.add(entry)
            }
        }

        return allEntries
    }

    /** Convenience: get only the latest report per scenario */
    fun readLatestReports(reportPath: String): Map<String, ReportEntry> {
        return readReports(reportPath).mapValues { it.value.first() }
    }

    private fun parseJsonReport(file: File): Map<String, ReportEntry> {
        return try {
            val content = file.readText()
            val element = JsonParser.parseString(content)
            val timestamp = formatTimestamp(file.lastModified())
            val sourceFile = file.absolutePath
            when {
                element.isJsonArray -> parseCucumberReport(element.asJsonArray, timestamp, sourceFile)
                element.isJsonObject -> parseGaugeReport(element.asJsonObject, timestamp, sourceFile)
                else -> emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseCucumberReport(array: JsonArray, timestamp: String, sourceFile: String): Map<String, ReportEntry> {
        val results = mutableMapOf<String, ReportEntry>()

        for (featureElement in array) {
            val feature = featureElement.asJsonObject
            val elements = feature.getAsJsonArray("elements") ?: continue

            for (scenarioElement in elements) {
                val scenario = scenarioElement.asJsonObject
                val name = scenario.get("name")?.asString ?: continue
                val steps = scenario.getAsJsonArray("steps") ?: JsonArray()

                // Also check "before" and "after" hooks for screenshots
                val beforeHooks = scenario.getAsJsonArray("before") ?: JsonArray()
                val afterHooks = scenario.getAsJsonArray("after") ?: JsonArray()

                val stepEntries = steps.map { stepEl ->
                    val step = stepEl.asJsonObject
                    val result = step.getAsJsonObject("result")

                    // Check step itself, then step's before/after hooks for screenshots
                    val screenshot = extractCucumberScreenshot(step)
                        ?: extractScreenshotFromHooks(step.getAsJsonArray("after") ?: JsonArray())
                        ?: extractScreenshotFromHooks(step.getAsJsonArray("before") ?: JsonArray())

                    // Step duration = step itself + its before/after hooks
                    val stepDuration = (result?.get("duration")?.asLong ?: 0L) +
                        hooksDuration(step.getAsJsonArray("before")) +
                        hooksDuration(step.getAsJsonArray("after"))

                    StepReportEntry(
                        text = step.get("name")?.asString ?: "",
                        status = parseStatus(result?.get("status")?.asString),
                        errorMessage = result?.get("error_message")?.asString,
                        duration = if (stepDuration > 0) stepDuration / 1_000_000 else null,
                        screenshotBase64 = screenshot
                    )
                }

                // Check after hooks for screenshots (common pattern: screenshot on failure)
                val hookScreenshot = extractScreenshotFromHooks(afterHooks)
                    ?: extractScreenshotFromHooks(beforeHooks)

                // If last failed step has no screenshot but hook has one, attach it
                val finalSteps = if (hookScreenshot != null) {
                    val lastFailedIdx = stepEntries.indexOfLast { it.status == StepStatus.FAILED }
                    if (lastFailedIdx >= 0 && stepEntries[lastFailedIdx].screenshotBase64 == null) {
                        stepEntries.toMutableList().also {
                            it[lastFailedIdx] = it[lastFailedIdx].copy(screenshotBase64 = hookScreenshot)
                        }
                    } else stepEntries
                } else stepEntries

                // A failing Before/After hook leaves every real step "skipped" and cucumber
                // never attaches its error to any step — surface it as its own synthetic entry
                // so the scenario shows Failed (not Not Run) and the error is actually visible.
                val allSteps = hookFailureEntries(beforeHooks, "Before Hook") +
                    finalSteps +
                    hookFailureEntries(afterHooks, "After Hook")

                // Total duration = all steps + scenario-level before/after hooks
                val scenarioHooksDuration = (hooksDuration(beforeHooks) + hooksDuration(afterHooks)) / 1_000_000
                val totalDuration = (finalSteps.mapNotNull { it.duration }.sum()) + scenarioHooksDuration

                results[name] = ReportEntry(
                    scenarioName = name,
                    status = deriveStatus(allSteps),
                    steps = allSteps,
                    duration = totalDuration,
                    timestamp = timestamp,
                    sourceFile = sourceFile
                )
            }
        }
        return results
    }

    /** Builds a synthetic step entry for each hook (before/after) whose own result is "failed". */
    private fun hookFailureEntries(hooks: JsonArray, label: String): List<StepReportEntry> {
        val entries = mutableListOf<StepReportEntry>()
        for (hookEl in hooks) {
            val hook = hookEl.asJsonObject
            val result = hook.getAsJsonObject("result") ?: continue
            if (result.get("status")?.asString != "failed") continue
            val duration = result.get("duration")?.asLong ?: 0L
            entries.add(
                StepReportEntry(
                    text = label,
                    status = StepStatus.FAILED,
                    errorMessage = result.get("error_message")?.asString,
                    duration = if (duration > 0) duration / 1_000_000 else null,
                    screenshotBase64 = extractCucumberScreenshot(hook)
                )
            )
        }
        return entries
    }

    private fun extractScreenshotFromHooks(hooks: JsonArray): String? {
        for (hookEl in hooks) {
            val hook = hookEl.asJsonObject
            val screenshot = extractCucumberScreenshot(hook)
            if (screenshot != null) return screenshot
        }
        return null
    }

    /** Sum durations (in nanoseconds) from a hooks array (before/after) */
    private fun hooksDuration(hooks: JsonArray?): Long {
        if (hooks == null) return 0L
        var total = 0L
        for (hookEl in hooks) {
            val result = hookEl.asJsonObject.getAsJsonObject("result")
            total += result?.get("duration")?.asLong ?: 0L
        }
        return total
    }

    private fun extractCucumberScreenshot(step: JsonObject): String? {
        val embeddings = step.getAsJsonArray("embeddings")
            ?: step.getAsJsonArray("attachments")
            ?: return null

        for (embed in embeddings) {
            val obj = embed.asJsonObject
            val mime = obj.get("mime_type")?.asString
                ?: obj.get("media")?.asJsonObject?.get("type")?.asString ?: ""
            if (mime.startsWith("image/")) {
                return obj.get("data")?.asString
            }
        }
        return null
    }

    private fun parseGaugeReport(obj: JsonObject, timestamp: String, sourceFile: String): Map<String, ReportEntry> {
        val results = mutableMapOf<String, ReportEntry>()
        val specResults = obj.getAsJsonArray("specResults") ?: return results

        // Resolve project root from spec fileName for screenshot path resolution
        val projectRoot = resolveGaugeProjectRoot(specResults, sourceFile)

        for (specEl in specResults) {
            val spec = specEl.asJsonObject
            val scenarios = spec.getAsJsonArray("scenarios") ?: continue

            for (scenarioEl in scenarios) {
                val scenario = scenarioEl.asJsonObject
                val name = scenario.get("scenarioHeading")?.asString ?: continue
                val executionTime = scenario.get("executionTime")?.asLong ?: 0L
                val executionStatus = scenario.get("executionStatus")?.asString ?: ""

                // Collect all steps: contexts + items + teardowns
                val stepEntries = mutableListOf<StepReportEntry>()

                val contexts = scenario.getAsJsonArray("contexts") ?: JsonArray()
                val items = scenario.getAsJsonArray("items") ?: JsonArray()
                val teardowns = scenario.getAsJsonArray("teardowns") ?: JsonArray()

                collectGaugeSteps(contexts, stepEntries, 0, projectRoot)
                collectGaugeSteps(items, stepEntries, 0, projectRoot)
                collectGaugeSteps(teardowns, stepEntries, 0, projectRoot)

                val status = when (executionStatus.lowercase()) {
                    "passed" -> StepStatus.PASSED
                    "failed" -> StepStatus.FAILED
                    "skipped", "notexecuted", "not executed" -> StepStatus.NOT_RUN
                    else -> deriveStatus(stepEntries)
                }

                results[name] = ReportEntry(
                    scenarioName = name,
                    status = status,
                    steps = stepEntries,
                    duration = executionTime,
                    timestamp = timestamp,
                    sourceFile = sourceFile
                )
            }
        }
        return results
    }

    private fun resolveGaugeProjectRoot(specResults: JsonArray, sourceFile: String): String? {
        // Try to get project root from spec fileName (e.g. /Users/x/project/specs/file.spec -> /Users/x/project)
        for (specEl in specResults) {
            val fileName = specEl.asJsonObject.get("fileName")?.asString ?: continue
            val specsIdx = fileName.indexOf("/specs/")
            if (specsIdx > 0) return fileName.substring(0, specsIdx)
        }
        // Fallback: report file is typically at <project>/reports/json-report/result.json or <project>/Reports/Gauge/Json/xxx.json
        val reportFile = File(sourceFile)
        var dir = reportFile.parentFile
        while (dir != null) {
            if (File(dir, "reports/html-report/images").exists()) return dir.absolutePath
            dir = dir.parentFile
        }
        return null
    }

    private fun collectGaugeSteps(items: JsonArray, result: MutableList<StepReportEntry>, depth: Int, projectRoot: String?) {
        for (itemEl in items) {
            val item = itemEl.asJsonObject
            val itemType = item.get("itemType")?.asString ?: ""

            when (itemType) {
                "step" -> {
                    result.add(parseGaugeStepItem(item, depth, projectRoot))
                }
                "concept" -> {
                    // Add concept heading as a parent step
                    val conceptStep = item.getAsJsonObject("conceptStep")
                    if (conceptStep != null) {
                        val conceptText = conceptStep.get("stepText")?.asString ?: "Concept"
                        val conceptResult = conceptStep.getAsJsonObject("result")
                        val conceptStatus = when (conceptResult?.get("status")?.asString?.lowercase()) {
                            "passed" -> StepStatus.PASSED
                            "failed" -> StepStatus.FAILED
                            else -> StepStatus.NOT_RUN
                        }
                        val conceptTime = conceptResult?.get("executionTime")?.asLong ?: 0L
                        val conceptScreenshot = extractGaugeScreenshot(conceptResult, projectRoot)
                        val conceptError = conceptResult?.get("errorMessage")?.asString?.takeIf { it.isNotEmpty() }
                        result.add(StepReportEntry(
                            text = conceptText,
                            status = conceptStatus,
                            errorMessage = conceptError,
                            duration = if (conceptTime > 0) conceptTime else null,
                            screenshotBase64 = conceptScreenshot,
                            depth = depth,
                            isConcept = true
                        ))
                    }
                    // Add concept's child items at deeper level
                    val conceptItems = item.getAsJsonArray("items") ?: JsonArray()
                    collectGaugeSteps(conceptItems, result, depth + 1, projectRoot)
                }
            }
        }
    }

    private fun parseGaugeStepItem(step: JsonObject, depth: Int, projectRoot: String?): StepReportEntry {
        val stepText = step.get("stepText")?.asString ?: ""
        val stepResult = step.getAsJsonObject("result")

        val status = when (stepResult?.get("status")?.asString?.lowercase()) {
            "passed" -> StepStatus.PASSED
            "failed" -> StepStatus.FAILED
            else -> StepStatus.NOT_RUN
        }

        val errorMessage = stepResult?.get("errorMessage")?.asString?.takeIf { it.isNotEmpty() }
        val executionTime = stepResult?.get("executionTime")?.asLong ?: 0L
        val screenshot = extractGaugeScreenshot(stepResult, projectRoot)

        return StepReportEntry(
            text = stepText,
            status = status,
            errorMessage = errorMessage,
            duration = if (executionTime > 0) executionTime else null,
            screenshotBase64 = screenshot,
            depth = depth
        )
    }

    private fun extractGaugeScreenshot(result: JsonObject?, projectRoot: String?): String? {
        if (result == null) return null
        // Base64 encoded screenshot
        val screenshot = result.get("screenshot")?.asString?.takeIf { it.isNotEmpty() }
        if (screenshot != null) return screenshot
        // Screenshot file name — resolve from html-report images directory
        val screenshotFile = result.get("ScreenshotFile")?.asString?.takeIf { it.isNotEmpty() }
        if (screenshotFile != null) {
            // Try absolute path first
            val absFile = File(screenshotFile)
            if (absFile.exists()) {
                return try { java.util.Base64.getEncoder().encodeToString(absFile.readBytes()) } catch (_: Exception) { null }
            }
            // Try relative to html-report images directory
            if (projectRoot != null) {
                val imgFile = File(projectRoot, "reports/html-report/images/$screenshotFile")
                if (imgFile.exists()) {
                    return try { java.util.Base64.getEncoder().encodeToString(imgFile.readBytes()) } catch (_: Exception) { null }
                }
            }
        }
        // Screenshots array
        val screenshots = result.getAsJsonArray("screenshots")
        if (screenshots != null && screenshots.size() > 0) {
            return screenshots[0].asString?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun deriveStatus(steps: List<StepReportEntry>): StepStatus = when {
        steps.any { it.status == StepStatus.FAILED } -> StepStatus.FAILED
        steps.all { it.status == StepStatus.PASSED } -> StepStatus.PASSED
        else -> StepStatus.NOT_RUN
    }

    private fun parseStatus(status: String?): StepStatus = when (status?.lowercase()) {
        "passed" -> StepStatus.PASSED
        "failed" -> StepStatus.FAILED
        "pending" -> StepStatus.PENDING
        "undefined" -> StepStatus.UNDEFINED
        else -> StepStatus.NOT_RUN
    }

    private fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm")
        return sdf.format(java.util.Date(millis))
    }
}
