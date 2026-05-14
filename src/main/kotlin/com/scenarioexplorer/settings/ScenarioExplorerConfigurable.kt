package com.scenarioexplorer.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.*

class ScenarioExplorerConfigurable(private val project: Project) : Configurable {

    private var reportPathField: TextFieldWithBrowseButton? = null
    private var cucumberCommandField: JTextField? = null
    private var gaugeCommandField: JTextField? = null
    private var scanPathsField: JTextArea? = null
    private var buildBeforeRunCheckbox: JCheckBox? = null
    private var preRetryCommandField: JTextField? = null

    override fun getDisplayName(): String = "Scenario Explorer"

    override fun createComponent(): JComponent {
        reportPathField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                "Select Report Directory",
                "Choose the directory containing Cucumber/Gauge JSON reports",
                project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
            )
        }
        cucumberCommandField = JTextField()
        gaugeCommandField = JTextField()
        scanPathsField = JTextArea(4, 40).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        buildBeforeRunCheckbox = JCheckBox("Clean before test (mvn clean test)", true)
        preRetryCommandField = JTextField()

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Report path:", reportPathField!!)
            .addLabeledComponent("Cucumber run command (optional):", cucumberCommandField!!)
            .addLabeledComponent("Gauge run command (optional):", gaugeCommandField!!)
            .addLabeledComponent("Scan paths (one per line, empty = all):", JScrollPane(scanPathsField))
            .addComponent(buildBeforeRunCheckbox!!)
            .addLabeledComponent("Pre-retry command (optional):", preRetryCommandField!!)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = ScenarioExplorerSettings.getInstance(project)
        return reportPathField?.text != settings.state.reportPath ||
                cucumberCommandField?.text != settings.state.cucumberCommand ||
                gaugeCommandField?.text != settings.state.gaugeCommand ||
                scanPathsField?.text != settings.state.scanPaths ||
                buildBeforeRunCheckbox?.isSelected != settings.state.buildBeforeRun ||
                preRetryCommandField?.text != settings.state.preRetryCommand
    }

    override fun apply() {
        val settings = ScenarioExplorerSettings.getInstance(project)
        settings.loadState(
            ScenarioExplorerSettings.State(
                reportPath = reportPathField?.text ?: "",
                cucumberCommand = cucumberCommandField?.text ?: "",
                gaugeCommand = gaugeCommandField?.text ?: "",
                scanPaths = scanPathsField?.text ?: "",
                hiddenFiles = settings.state.hiddenFiles,
                buildBeforeRun = buildBeforeRunCheckbox?.isSelected ?: true,
                preRetryCommand = preRetryCommandField?.text ?: ""
            )
        )
    }

    override fun reset() {
        val settings = ScenarioExplorerSettings.getInstance(project)
        reportPathField?.text = settings.state.reportPath
        cucumberCommandField?.text = settings.state.cucumberCommand
        gaugeCommandField?.text = settings.state.gaugeCommand
        scanPathsField?.text = settings.state.scanPaths
        buildBeforeRunCheckbox?.isSelected = settings.state.buildBeforeRun
        preRetryCommandField?.text = settings.state.preRetryCommand
    }

    override fun disposeUIResources() {
        reportPathField = null
        cucumberCommandField = null
        gaugeCommandField = null
        scanPathsField = null
        buildBeforeRunCheckbox = null
        preRetryCommandField = null
    }
}
