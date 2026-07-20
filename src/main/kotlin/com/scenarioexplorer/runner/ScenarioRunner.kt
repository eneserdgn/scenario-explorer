package com.scenarioexplorer.runner

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.scenarioexplorer.model.Scenario
import com.scenarioexplorer.model.ScenarioType
import com.scenarioexplorer.settings.ScenarioExplorerSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Returned from run/runBatch so the caller can stop the process tree. */
class RunHandle {
    private val handlerRef = AtomicReference<OSProcessHandler?>(null)
    private val cancelled = AtomicBoolean(false)

    fun setHandler(h: OSProcessHandler?) { handlerRef.set(h) }
    fun isCancelled(): Boolean = cancelled.get()

    fun stop() {
        cancelled.set(true)
        killCurrent()
    }

    fun killCurrent() {
        val h = handlerRef.get() ?: return
        if (!h.isProcessTerminated) {
            h.process?.let { proc ->
                proc.descendants().forEach { it.destroyForcibly() }
                proc.destroyForcibly()
            }
            h.destroyProcess()
        }
    }
}

object ScenarioRunner {

    fun run(
        project: Project, scenario: Scenario,
        onOutput: (String) -> Unit, onFinished: (Int) -> Unit
    ): RunHandle? {
        val basePath = project.basePath ?: return null
        val settings = ScenarioExplorerSettings.getInstance(project).state
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val safeName = sanitizeFileName(scenario.name)
        val reportName = "${safeName}_$timestamp"
        val reportDir = resolveReportDir(basePath, settings.reportPath)

        val handle = RunHandle()

        // Check for custom command
        val customCmd = when (scenario.type) {
            ScenarioType.CUCUMBER -> settings.cucumberCommand.takeIf { it.isNotBlank() }
            ScenarioType.GAUGE -> settings.gaugeCommand.takeIf { it.isNotBlank() }
        }

        if (customCmd != null) {
            val commandLine = buildCustomCommand(basePath, customCmd, scenario)
            execute(commandLine, onOutput, onFinished, handle = handle)
            return handle
        }

        when (scenario.type) {
            ScenarioType.CUCUMBER -> {
                val tempTarget = createIsolatedTargetDir(basePath, "run")
                val commandLine = buildCucumberCommand(basePath, scenario, reportName, settings.buildBeforeRun, tempTarget, reportDir)
                execute(commandLine, onOutput, onFinished, tempTarget, handle)
            }
            ScenarioType.GAUGE -> {
                val commandLine = buildGaugeCommand(basePath, scenario, settings.buildBeforeRun)
                executeGauge(commandLine, basePath, reportName, reportDir, onOutput, onFinished, handle)
            }
        }
        return handle
    }

    fun runBatch(
        project: Project, scenarios: List<Scenario>,
        onOutput: (String) -> Unit, onFinished: (Int) -> Unit,
        sharedTargetDir: java.io.File? = null
    ): RunHandle? {
        val basePath = project.basePath ?: return null
        val settings = ScenarioExplorerSettings.getInstance(project).state
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val batchName = "BatchRun_$timestamp"
        val reportDir = resolveReportDir(basePath, settings.reportPath)
        val handle = RunHandle()

        val featureFiles = scenarios
            .filter { it.type == ScenarioType.CUCUMBER }
            .map { it.file.relativeTo(java.io.File(basePath)).path }
            .distinct().joinToString(",")

        val scenarioNames = scenarios
            .filter { it.type == ScenarioType.CUCUMBER }
            .joinToString("|") { escapeRegex(it.name) }

        if (scenarioNames.isNotEmpty()) {
            val isShared = sharedTargetDir != null
            val targetDir = sharedTargetDir ?: createIsolatedTargetDir(basePath, "batch")
            // Shared target: compile already done by pipeline; only run test phase
            val mvnGoals = if (isShared) {
                listOf("test")
            } else {
                if (settings.buildBeforeRun) listOf("compile", "test-compile", "test") else listOf("test")
            }
            val commandLine = GeneralCommandLine().apply {
                workDirectory = java.io.File(basePath)
                exePath = resolveMvnExecutable(basePath)
                addParameters(mvnGoals)
                addParameters(isolatedBuildParams(targetDir))
                addParameters(
                    "-Dcucumber.filter.tags=",
                    "-Dcucumber.filter.name=$scenarioNames",
                    "-Dcucumber.features=$featureFiles",
                    "-Dsurefire.reportsDirectory=${targetDir.absolutePath}/surefire-reports",
                    buildPluginParam(batchName, reportDir)
                )
            }
            // Don't delete shared target — pipeline manages its lifecycle
            execute(commandLine, onOutput, onFinished, if (isShared) null else targetDir, handle)
            return handle
        }

        // Gauge — run sequentially
        val gaugeScenarios = scenarios.filter { it.type == ScenarioType.GAUGE }
        if (gaugeScenarios.isEmpty()) {
            onOutput("No scenarios to run.\n")
            onFinished(0)
            return handle
        }

        val batchTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())

        Thread {
            var lastExitCode = 0
            for ((idx, scenario) in gaugeScenarios.withIndex()) {
                if (handle.isCancelled()) break
                val latch = CountDownLatch(1)
                val cmd = buildGaugeCommand(basePath, scenario, settings.buildBeforeRun)
                val reportName = "GaugeBatch_${batchTimestamp}_${idx + 1}"
                try {
                    val handler = OSProcessHandler(cmd)
                    handle.setHandler(handler)
                    handler.addProcessListener(object : ProcessAdapter() {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            onOutput(event.text)
                        }
                        override fun processTerminated(event: ProcessEvent) {
                            lastExitCode = event.exitCode
                            copyGaugeReport(basePath, reportName, reportDir)
                            latch.countDown()
                        }
                    })
                    handler.startNotify()
                } catch (e: Exception) {
                    onOutput("Error: ${e.message}\n")
                    latch.countDown()
                }
                latch.await()
            }
            onFinished(lastExitCode)
        }.start()

        return handle
    }

    private fun resolveReportDir(basePath: String, reportPath: String): String {
        if (reportPath.isNotBlank()) {
            val f = java.io.File(reportPath)
            return if (f.isAbsolute) reportPath else java.io.File(basePath, reportPath).absolutePath
        }
        return java.io.File(basePath, "Reports").absolutePath
    }

    private fun resolveMvnExecutable(basePath: String): String {
        val isWindows = com.intellij.openapi.util.SystemInfo.isWindows
        return when {
            isWindows && java.io.File(basePath, "mvnw.cmd").exists() -> "mvnw.cmd"
            !isWindows && java.io.File(basePath, "mvnw").exists() -> "./mvnw"
            isWindows -> "mvn.cmd"
            else -> "mvn"
        }
    }

    fun createTempTargetDir(): java.io.File {
        return java.nio.file.Files.createTempDirectory("scenario-explorer-target-").toFile().apply {
            deleteOnExit()
        }
    }

    /**
     * Proje içinde izole bir build directory oluşturur.
     * Her koşum tipi (run, batch, pipeline) kendi target klasörünü kullanır,
     * böylece paralel koşumlarda birbirlerinin class dosyalarını ezmezler.
     */
    fun createIsolatedTargetDir(basePath: String, prefix: String): java.io.File {
        val buildRoot = java.io.File(basePath, ".scenario-explorer-builds")
        buildRoot.mkdirs()
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(java.util.Date())
        val dir = java.io.File(buildRoot, "${prefix}_$timestamp")
        dir.mkdirs()
        return dir
    }

    private fun buildCucumberCommand(
        basePath: String, scenario: Scenario, reportName: String, buildBeforeRun: Boolean, tempTarget: java.io.File, reportDir: String
    ): GeneralCommandLine {
        val relativePath = scenario.file.relativeTo(java.io.File(basePath)).path
        val mvnGoals = if (buildBeforeRun) listOf("compile", "test-compile", "test") else listOf("test")
        return GeneralCommandLine().apply {
            workDirectory = java.io.File(basePath)
            exePath = resolveMvnExecutable(basePath)
            addParameters(mvnGoals)
            addParameters(isolatedBuildParams(tempTarget))
            addParameters(
                "-Dcucumber.filter.tags=",
                "-Dcucumber.filter.name=${escapeRegex(scenario.name)}",
                "-Dcucumber.features=$relativePath",
                "-Dsurefire.reportsDirectory=${tempTarget.absolutePath}/surefire-reports",
                buildPluginParam(reportName, reportDir)
            )
        }
    }

    private fun buildCustomCommand(basePath: String, command: String, scenario: Scenario): GeneralCommandLine {
        val relativePath = scenario.file.relativeTo(java.io.File(basePath)).path
        val expanded = command
            .replace("\${scenarioName}", scenario.name)
            .replace("\${featureFile}", relativePath)
            .replace("\${scenarioLine}", scenario.line.toString())

        val parts = expanded.trim().split("\\s+".toRegex())
        return GeneralCommandLine().apply {
            workDirectory = java.io.File(basePath)
            exePath = parts.first()
            if (parts.size > 1) addParameters(parts.drop(1))
        }
    }

    /** Returns the three Maven properties that fully isolate a build into [dir]. */
    fun isolatedBuildParams(dir: java.io.File): List<String> = listOf(
        "-Dproject.build.directory=${dir.absolutePath}",
        "-Dproject.build.outputDirectory=${dir.absolutePath}/classes",
        "-Dproject.build.testOutputDirectory=${dir.absolutePath}/test-classes"
    )

    private fun buildPluginParam(reportName: String, reportDir: String): String {
        return "-Dcucumber.plugin=" + listOf(
            "pretty",
            "json:$reportDir/$reportName.json"
        ).joinToString(",")
    }

    private fun buildGaugeCommand(basePath: String, scenario: Scenario, buildBeforeRun: Boolean): GeneralCommandLine {
        val relativePath = scenario.file.relativeTo(java.io.File(basePath)).path
        val specsDir = "$relativePath:${scenario.line}"
        val gaugeTarget = createIsolatedTargetDir(basePath, "gauge")
        val mvnGoals = if (buildBeforeRun) listOf("compile", "test-compile", "gauge:execute") else listOf("gauge:execute")
        return GeneralCommandLine().apply {
            workDirectory = java.io.File(basePath)
            exePath = resolveMvnExecutable(basePath)
            addParameters(mvnGoals)
            addParameter("-DspecsDir=$specsDir")
            addParameters(isolatedBuildParams(gaugeTarget))
        }
    }

    private fun copyGaugeReport(basePath: String, reportName: String, reportDir: String) {
        try {
            val source = java.io.File(basePath, "reports/json-report/result.json")
            if (!source.exists()) return
            val destDir = java.io.File(reportDir)
            destDir.mkdirs()
            val dest = java.io.File(destDir, "$reportName.json")
            source.copyTo(dest, overwrite = true)
        } catch (_: Exception) {}
    }

    private fun executeGauge(
        commandLine: GeneralCommandLine,
        basePath: String,
        reportName: String,
        reportDir: String,
        onOutput: (String) -> Unit,
        onFinished: (Int) -> Unit,
        handle: RunHandle
    ) {
        try {
            val handler = OSProcessHandler(commandLine)
            handle.setHandler(handler)
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    onOutput(event.text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    copyGaugeReport(basePath, reportName, reportDir)
                    onFinished(event.exitCode)
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            onOutput("Error starting process: ${e.message}\n")
            onFinished(-1)
        }
    }

    private fun execute(
        commandLine: GeneralCommandLine,
        onOutput: (String) -> Unit,
        onFinished: (Int) -> Unit,
        tempTarget: java.io.File? = null,
        handle: RunHandle
    ) {
        try {
            val handler = OSProcessHandler(commandLine)
            handle.setHandler(handler)
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    onOutput(event.text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    tempTarget?.let { dir ->
                        try { dir.deleteRecursively() } catch (_: Exception) {}
                    }
                    onFinished(event.exitCode)
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            tempTarget?.let { dir ->
                try { dir.deleteRecursively() } catch (_: Exception) {}
            }
            onOutput("Error starting process: ${e.message}\n")
            onFinished(-1)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(80)
    }

    private fun escapeRegex(name: String): String {
        val specialChars = setOf('.', '+', '*', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\')
        return buildString {
            for (c in name) {
                if (c in specialChars) append('\\')
                append(c)
            }
        }
    }
}
