package com.scenarioexplorer.parser

import com.scenarioexplorer.model.*
import java.io.File

object CucumberParser {

    fun parse(file: File): ScenarioFile? {
        if (!file.exists()) return null
        val lines = file.readLines()
        var featureName = file.nameWithoutExtension
        var featureTags = listOf<String>()
        val scenarios = mutableListOf<Scenario>()
        var i = 0

        while (i < lines.size) {
            val trimmed = lines[i].trim()

            if (trimmed.startsWith("Feature:")) {
                featureName = trimmed.removePrefix("Feature:").trim()
                // Collect feature-level tags from lines above
                featureTags = collectTags(lines, i)
            }

            if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
                val scenarioName = trimmed
                    .removePrefix("Scenario Outline:")
                    .removePrefix("Scenario:")
                    .trim()
                val scenarioLine = i + 1
                val tags = collectTags(lines, i)
                val steps = mutableListOf<ScenarioStep>()
                i++

                while (i < lines.size) {
                    val stepLine = lines[i].trim()
                    if (stepLine.isEmpty() || stepLine.startsWith("Scenario") ||
                        stepLine.startsWith("Feature") || stepLine.startsWith("@") ||
                        stepLine.startsWith("Examples:")
                    ) break

                    val keyword = extractStepKeyword(stepLine)
                    if (keyword != null) {
                        val tableLines = mutableListOf<String>()
                        var j = i + 1
                        while (j < lines.size && lines[j].trim().startsWith("|")) {
                            tableLines.add(lines[j].trim())
                            j++
                        }
                        val dataTable = if (tableLines.isNotEmpty()) parseTable(tableLines) else null

                        steps.add(
                            ScenarioStep(
                                keyword = keyword,
                                text = stepLine.removePrefix(keyword).trim(),
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
                        file = file, line = scenarioLine, type = ScenarioType.CUCUMBER
                    )
                )
                continue
            }
            i++
        }

        return ScenarioFile(
            file = file, featureName = featureName, featureTags = featureTags,
            type = ScenarioType.CUCUMBER, scenarios = scenarios
        )
    }

    private fun collectTags(lines: List<String>, lineIndex: Int): List<String> {
        val tags = mutableListOf<String>()
        var j = lineIndex - 1
        while (j >= 0 && lines[j].trim().startsWith("@")) {
            tags.addAll(lines[j].trim().split("\\s+".toRegex()))
            j--
        }
        return tags
    }

    private fun extractStepKeyword(line: String): String? {
        val keywords = listOf("Given ", "When ", "Then ", "And ", "But ", "* ")
        return keywords.firstOrNull { line.startsWith(it) }
    }

    private fun parseTable(tableLines: List<String>): DataTable? {
        if (tableLines.isEmpty()) return null
        val parsed = tableLines.map { line ->
            line.trim().removePrefix("|").removeSuffix("|")
                .split("|").map { it.trim() }
        }
        return DataTable(headers = parsed.first(), rows = if (parsed.size > 1) parsed.drop(1) else emptyList())
    }
}
