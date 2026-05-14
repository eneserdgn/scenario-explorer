package com.scenarioexplorer.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "ScenarioExplorerSettings",
    storages = [Storage("scenarioExplorer.xml")]
)
class ScenarioExplorerSettings : PersistentStateComponent<ScenarioExplorerSettings.State> {

    data class State(
        var reportPath: String = "",
        var cucumberCommand: String = "",
        var gaugeCommand: String = "",
        var scanPaths: String = "",
        var hiddenFiles: String = "",
        var hiddenScenarios: String = "",
        var buildBeforeRun: Boolean = true,
        var savedPipelines: String = "",  // JSON: list of {name, items:[{featurePath, scenarioName?}]}
        var preRetryCommand: String = ""  // Retry öncesi çalıştırılacak komut (ör: mvn exec:java -Dexec.mainClass=...)
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): ScenarioExplorerSettings =
            project.getService(ScenarioExplorerSettings::class.java)
    }
}
