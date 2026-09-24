package com.scenarioexplorer.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JTabbedPane

class ScenarioExplorerFileEditor(project: Project, private val file: VirtualFile) :
    UserDataHolderBase(), FileEditor {

    private val dashboardPanel = DashboardPanel()
    private val stepsPanel = StepsPanel()
    private val pipelinePanel = PipelinePanel(project)
    private val errorsPanel = ErrorsPanel()
    private val scenarioPanel = ScenarioExplorerPanel(project).apply {
        onDataRefreshed = { files, reports, allReports ->
            dashboardPanel.update(files, reports, allReports)
            stepsPanel.update(files, reports)
            pipelinePanel.update(files, reports)
            val visibleNames = files.flatMap { it.scenarios }.map { it.name }.toSet()
            errorsPanel.update(reports.filterKeys { it in visibleNames })
        }
    }

    private val tabbedPane = JTabbedPane(JTabbedPane.TOP).apply {
        addTab("Dashboard", dashboardPanel)
        addTab("Scenarios", scenarioPanel)
        addTab("Steps", stepsPanel)
        addTab("Pipeline", pipelinePanel)
        addTab("Errors", errorsPanel)
    }

    init {
        stepsPanel.onNavigateToScenario = { scenarioName ->
            tabbedPane.selectedIndex = 1
            scenarioPanel.navigateToScenario(scenarioName)
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
    }

    override fun getComponent() = tabbedPane
    override fun getPreferredFocusedComponent() = tabbedPane
    override fun getName() = "Scenario Explorer"
    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file
    override fun dispose() {}
}
