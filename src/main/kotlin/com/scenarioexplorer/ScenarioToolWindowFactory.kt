package com.scenarioexplorer

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.scenarioexplorer.ui.DashboardPanel
import com.scenarioexplorer.ui.ErrorsPanel
import com.scenarioexplorer.ui.PipelinePanel
import com.scenarioexplorer.ui.ScenarioExplorerPanel
import javax.swing.JTabbedPane

/**
 * Builds the "Enes Report" tool window content once per project. Unlike an editor tab, this
 * content is never disposed when the user hides/closes the tool window — only when the project
 * itself closes — so a running pipeline (and its Stop button) survives the panel being hidden.
 */
class ScenarioToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val dashboardPanel = DashboardPanel()
        val pipelinePanel = PipelinePanel(project)
        val errorsPanel = ErrorsPanel()
        val scenarioPanel = ScenarioExplorerPanel(project).apply {
            onDataRefreshed = { files, reports, allReports ->
                dashboardPanel.update(files, reports, allReports)
                pipelinePanel.update(files, reports)
                val visibleNames = files.flatMap { it.scenarios }.map { it.name }.toSet()
                errorsPanel.update(reports.filterKeys { it in visibleNames })
            }
        }

        val tabbedPane = JTabbedPane(JTabbedPane.TOP).apply {
            addTab("Dashboard", dashboardPanel)
            addTab("Scenarios", scenarioPanel)
            addTab("Pipeline", pipelinePanel)
            addTab("Errors", errorsPanel)
        }

        errorsPanel.onNavigateToScenario = { scenarioName ->
            tabbedPane.selectedIndex = 1
            scenarioPanel.navigateToScenario(scenarioName)
        }

        // A finished/stopped pipeline run wrote new reports — re-read them so counts everywhere catch up
        pipelinePanel.onRunFinished = { scenarioPanel.refresh() }

        errorsPanel.onAddToPipeline = { scenarioNames ->
            pipelinePanel.addScenarioNamesToPipeline(scenarioNames)
        }

        errorsPanel.onCountUpdated = { unique, total ->
            val idx = tabbedPane.indexOfComponent(errorsPanel)
            if (idx >= 0) {
                tabbedPane.setTitleAt(idx, if (unique > 0) "Errors ($unique / $total)" else "Errors")
            }
        }

        val content = ContentFactory.getInstance().createContent(tabbedPane, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
