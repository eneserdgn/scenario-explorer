package com.scenarioexplorer.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ScenarioExplorerFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is ScenarioExplorerVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        ScenarioExplorerFileEditor(project, file)

    override fun getEditorTypeId(): String = "scenario-explorer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
