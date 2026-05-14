package com.scenarioexplorer.ui

import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile

class ScenarioExplorerVirtualFile(val project: Project)
    : LightVirtualFile("Scenario Explorer", ScenarioExplorerFileType, "") {

    override fun isWritable(): Boolean = false
}
