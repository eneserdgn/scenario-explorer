package com.scenarioexplorer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.scenarioexplorer.ui.ScenarioExplorerVirtualFile
import javax.swing.JPanel

class ScenarioToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Minimal placeholder so the tool window has valid content
        val cf = ContentFactory.getInstance()
        toolWindow.contentManager.addContent(cf.createContent(JPanel(), "", false))

        // Listen for every future activation (e.g. after the user closes the editor tab)
        project.messageBus.connect().subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == "Enes Report") {
                        openEditorTab(project, toolWindow)
                    }
                }
            }
        )

        // Also open immediately for the current activation
        openEditorTab(project, toolWindow)
    }

    private fun openEditorTab(project: Project, toolWindow: ToolWindow) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val manager = FileEditorManager.getInstance(project)
            val existing = manager.openFiles.firstOrNull { it is ScenarioExplorerVirtualFile }
            val file = existing ?: ScenarioExplorerVirtualFile(project)
            manager.openFile(file, true)
            toolWindow.hide()
        }
    }
}
