package com.scenarioexplorer.parser

import com.scenarioexplorer.model.*
import java.io.File

object GaugeParser {

    fun parse(file: File): ScenarioFile? {
        if (!file.exists()) return null
        val lines = file.readLines()
        var specName = file.nameWithoutExtension
        val scenarios = mutableListOf<Scenario>()
        var i = 0

        while (i < lines.size) {
            val trimmed = lines[i].trim()

            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                specName = trimmed.removePrefix("# ").trim()
                i++
                continue
            }

            if (i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^=+$"))) {
                specName = trimmed
                i += 2
                continue
            }

            val isMarkdownScenario = trimmed.startsWith("## ")
            val isUnderlineScenario = i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^-+$"))

            if (isMarkdownScenario || (isUnderlineScenario && trimmed.isNotEmpty())) {
                val scenarioName = if (isMarkdownScenario) {
                    trimmed.removePrefix("## ").trim()
                } else trimmed
                val scenarioLine = i + 1
                i += if (isUnderlineScenario) 2 else 1

                val tags = mutableListOf<String>()
                val steps = mutableListOf<ScenarioStep>()

                while (i < lines.size) {
                    val stepLine = lines[i].trim()

                    if (stepLine.startsWith("## ") || stepLine.startsWith("# ") ||
                        (i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^[-=]+$")) && stepLine.isNotEmpty())
                    ) break

                    if (stepLine.startsWith("tags:")) {
                        tags.addAll(
                            stepLine.removePrefix("tags:").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        )
                        i++
                        continue
                    }

                    if (stepLine.startsWith("* ")) {
                        // Check for table after step
                        val tableLines = mutableListOf<String>()
                        var j = i + 1
                        while (j < lines.size && lines[j].trim().startsWith("|")) {
                            tableLines.add(lines[j].trim())
                            j++
                        }
                        val dataTable = if (tableLines.isNotEmpty()) parseTable(tableLines) else null

                        steps.add(
                            ScenarioStep(
                                keyword = "*",
                                text = stepLine.removePrefix("* ").trim(),
                                line = i + 1,
                                dataTable = dataTable
                            )
                        )
                        if (tableLines.isNotEmpty()) {
                            i = j
                            continue
                        }
                    }
                    i++
                }

                scenarios.add(
                    Scenario(
                        name = scenarioName, tags = tags, steps = steps,
                        file = file, line = scenarioLine, type = ScenarioType.GAUGE
                    )
                )
                continue
            }
            i++
        }

        return ScenarioFile(file = file, featureName = specName, type = ScenarioType.GAUGE, scenarios = scenarios)
    }

    private fun parseTable(tableLines: List<String>): DataTable? {
        if (tableLines.isEmpty()) return null
        val parsed = tableLines.map { line ->
            line.trim().removePrefix("|").removeSuffix("|")
                .split("|").map { it.trim() }
        }
        val headers = parsed.first()
        val rows = if (parsed.size > 1) parsed.drop(1) else emptyList()
        return DataTable(headers = headers, rows = rows)
    }
}
