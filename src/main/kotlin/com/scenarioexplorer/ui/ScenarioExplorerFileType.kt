package com.scenarioexplorer.ui

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object ScenarioExplorerFileType : FileType {
    private val pluginIcon: Icon by lazy {
        IconLoader.getIcon("/icons/enesReport.svg", ScenarioExplorerFileType::class.java)
    }

    override fun getName(): String = "ScenarioExplorer"
    override fun getDescription(): String = "Scenario Explorer"
    override fun getDefaultExtension(): String = ""
    override fun getIcon(): Icon = pluginIcon
    override fun isBinary(): Boolean = true
    override fun isReadOnly(): Boolean = true
}
