package com.scenarioexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.scenarioexplorer.model.*
import com.scenarioexplorer.runner.ScenarioRunner
import java.awt.*
import java.util.Base64
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class ScenarioDetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val titleLabel = JBLabel("Select a scenario").apply {
        font = font.deriveFont(Font.BOLD, 16f)
        border = JBUI.Borders.empty(12, 16, 4, 16)
    }
    private val tagsLabel = JBLabel().apply {
        border = JBUI.Borders.empty(0, 16, 8, 16)
    }
    private val runButton = JButton("Run", AllIcons.Actions.Execute).apply {
        isEnabled = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val openFileButton = JButton("Open File", AllIcons.Actions.OpenNewTab).apply {
        isEnabled = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val stepsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    private val reportTabs = JTabbedPane(JTabbedPane.TOP).apply {
        border = JBUI.Borders.empty()
    }

    private val contentPanel = JPanel(CardLayout()).apply {
        add(JBScrollPane(stepsPanel).apply { border = JBUI.Borders.empty(4) }, "steps")
        add(reportTabs, "reports")
    }

    // Empty state
    private val emptyStatePanel = JPanel(GridBagLayout()).apply {
        isOpaque = false
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(AllIcons.General.Information).apply {
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(12))
            add(JBLabel("Bir senaryo seçin").apply {
                font = font.deriveFont(Font.BOLD, 14f)
                foreground = UIUtil.getLabelDisabledForeground()
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(4))
            add(JBLabel("Sol panelden bir senaryo seçerek detaylarını görüntüleyin").apply {
                foreground = UIUtil.getLabelDisabledForeground()
                font = font.deriveFont(12f)
                alignmentX = Component.CENTER_ALIGNMENT
            })
        })
    }

    private val mainCardLayout = CardLayout()
    private val mainSwitcher = JPanel(mainCardLayout).apply {
        add(emptyStatePanel, "empty")
        add(JPanel(BorderLayout()).also { it.name = "detail" }, "detail")
    }

    private var currentScenario: Scenario? = null
    private var currentReports: List<ReportEntry> = emptyList()
    var onRunScenario: ((Scenario) -> Unit)? = null

    fun getCurrentScenario(): Scenario? = currentScenario

    init {
        background = UIConstants.surfaceBackground()

        // Header card
        val headerCard = RoundedPanel(UIConstants.CARD_ARC).apply {
            layout = BorderLayout()
            background = UIConstants.cardBackground()
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(UIConstants.CARD_ARC, UIConstants.subtleBorder()),
                JBUI.Borders.empty(4)
            )

            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(titleLabel, BorderLayout.NORTH)
                add(tagsLabel, BorderLayout.CENTER)
                val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(0, 12, 8, 12)
                    add(runButton)
                    add(openFileButton)
                }
                add(buttonPanel, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }

        val detailContent = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(headerCard, BorderLayout.NORTH)
            add(contentPanel, BorderLayout.CENTER)
        }

        // Replace the detail panel in mainSwitcher
        mainSwitcher.remove(1)
        mainSwitcher.add(detailContent, "detail")

        add(mainSwitcher, BorderLayout.CENTER)
        mainCardLayout.show(mainSwitcher, "empty")

        runButton.addActionListener { currentScenario?.let { runScenario(it) } }
        openFileButton.addActionListener { currentScenario?.let { openFile(it) } }
    }

    fun showScenario(scenario: Scenario, reports: List<ReportEntry> = emptyList()) {
        currentScenario = scenario
        val sorted = reports.sortedWith(compareBy<ReportEntry> {
            when (it.status) {
                StepStatus.PASSED -> 0
                StepStatus.FAILED -> 1
                StepStatus.SKIPPED -> 2
                else -> 3
            }
        })
        currentReports = sorted

        // Title with status badge
        val statusEmoji = when {
            sorted.isNotEmpty() -> when (sorted.first().status) {
                StepStatus.PASSED -> "✅"
                StepStatus.FAILED -> "❌"
                StepStatus.SKIPPED -> "⏭"
                else -> "⬜"
            }
            else -> ""
        }
        titleLabel.text = "$statusEmoji ${scenario.name}  [${scenario.type}]"

        val tagText = if (scenario.tags.isNotEmpty()) {
            scenario.tags.joinToString("  ") { "🏷 $it" }
        } else ""
        tagsLabel.text = tagText
        tagsLabel.foreground = UIUtil.getLabelDisabledForeground()

        runButton.isEnabled = true
        openFileButton.isEnabled = true

        val cl = contentPanel.layout as CardLayout

        if (currentReports.isNotEmpty()) {
            reportTabs.removeAll()
            for ((index, report) in currentReports.withIndex()) {
                val tabPanel = buildReportPanel(scenario, report)
                val tabTitle = report.timestamp ?: "Report"
                val statusIcon = when (report.status) {
                    StepStatus.PASSED -> "✓"
                    StepStatus.FAILED -> "✗"
                    StepStatus.SKIPPED -> "⊘"
                    else -> "?"
                }
                val displayTitle = "$statusIcon $tabTitle"
                reportTabs.addTab(displayTitle, tabPanel)

                val tabIndex = reportTabs.tabCount - 1
                reportTabs.setToolTipTextAt(tabIndex, report.sourceFile ?: "Unknown source")

                val tabColor = when (report.status) {
                    StepStatus.PASSED -> UIConstants.GREEN
                    StepStatus.FAILED -> UIConstants.RED
                    StepStatus.SKIPPED -> UIConstants.YELLOW
                    else -> UIUtil.getLabelForeground()
                }
                val tabLabel = JBLabel(displayTitle).apply {
                    foreground = tabColor
                    font = if (index == 0) font.deriveFont(Font.BOLD) else font
                }
                reportTabs.setTabComponentAt(tabIndex, tabLabel)
            }
            cl.show(contentPanel, "reports")
        } else {
            stepsPanel.removeAll()
            for (step in scenario.steps) {
                stepsPanel.add(createStepRow(step))
                if (step.dataTable != null) stepsPanel.add(createDataTableView(step.dataTable))
            }
            stepsPanel.add(Box.createVerticalGlue())
            stepsPanel.revalidate()
            stepsPanel.repaint()
            cl.show(contentPanel, "steps")
        }

        mainCardLayout.show(mainSwitcher, "detail")
    }

    private fun buildReportPanel(scenario: Scenario, report: ReportEntry): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        // Summary bar as a card
        val summaryText = buildString {
            append("Status: ${report.status}")
            if (report.duration != null && report.duration > 0) {
                append("  │  Duration: ${formatDuration(report.duration)}")
            }
            append("  │  Steps: ${report.steps.size}")
            val passed = report.steps.count { it.status == StepStatus.PASSED }
            val failed = report.steps.count { it.status == StepStatus.FAILED }
            append("  (✓$passed  ✗$failed)")
        }
        val summaryCard = RoundedPanel(8).apply {
            layout = BorderLayout()
            background = when (report.status) {
                StepStatus.PASSED -> UIConstants.GREEN.let { Color(it.red, it.green, it.blue, 20) }
                StepStatus.FAILED -> UIConstants.RED.let { Color(it.red, it.green, it.blue, 20) }
                else -> UIConstants.cardBackground()
            }
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(8, when (report.status) {
                    StepStatus.PASSED -> UIConstants.GREEN.let { Color(it.red, it.green, it.blue, 60) }
                    StepStatus.FAILED -> UIConstants.RED.let { Color(it.red, it.green, it.blue, 60) }
                    else -> UIConstants.subtleBorder()
                }),
                JBUI.Borders.empty(8, 12)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 44)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(summaryText).apply {
                foreground = when (report.status) {
                    StepStatus.PASSED -> UIConstants.GREEN
                    StepStatus.FAILED -> UIConstants.RED
                    else -> UIUtil.getLabelForeground()
                }
                font = font.deriveFont(Font.BOLD, 12f)
            }, BorderLayout.WEST)
        }
        panel.add(summaryCard)
        panel.add(Box.createVerticalStrut(8))

        // If report has depth/concept info (Gauge), render directly from report steps
        val hasGaugeSteps = report.steps.any { it.depth > 0 || it.isConcept }

        if (hasGaugeSteps || scenario.steps.isEmpty()) {
            for (reportStep in report.steps) {
                val keyword = if (reportStep.isConcept) "▸ Concept" else if (reportStep.depth > 0) "↳" else "Step"
                val stepObj = ScenarioStep(
                    keyword = keyword, text = reportStep.text, line = 0,
                    status = reportStep.status, errorMessage = reportStep.errorMessage,
                    duration = reportStep.duration, screenshotBase64 = reportStep.screenshotBase64
                )
                val indent = reportStep.depth * 24
                val stepRow = createStepRow(stepObj, panel, indent)
                panel.add(stepRow)
                val screenshotPanel = stepRow.getClientProperty("screenshotPanel") as? JPanel
                if (screenshotPanel != null) panel.add(screenshotPanel)
                if (reportStep.status == StepStatus.FAILED && reportStep.errorMessage != null) {
                    panel.add(createErrorView(reportStep.errorMessage, indent))
                }
            }
        } else {
            for ((idx, step) in scenario.steps.withIndex()) {
                val reportStep = report.steps.getOrNull(idx)
                val mergedStep = if (reportStep != null) {
                    step.copy(
                        status = reportStep.status,
                        errorMessage = reportStep.errorMessage,
                        duration = reportStep.duration,
                        screenshotBase64 = reportStep.screenshotBase64
                    )
                } else step

                val stepRow = createStepRow(mergedStep, panel)
                panel.add(stepRow)
                val screenshotPanel = stepRow.getClientProperty("screenshotPanel") as? JPanel
                if (screenshotPanel != null) panel.add(screenshotPanel)
                if (mergedStep.dataTable != null) panel.add(createDataTableView(mergedStep.dataTable))
                if (mergedStep.status == StepStatus.FAILED && mergedStep.errorMessage != null) {
                    panel.add(createErrorView(mergedStep.errorMessage))
                }
            }

            if (report.steps.size > scenario.steps.size) {
                for (i in scenario.steps.size until report.steps.size) {
                    val extra = report.steps[i]
                    val extraStep = ScenarioStep(
                        keyword = "Hook", text = extra.text, line = 0,
                        status = extra.status, errorMessage = extra.errorMessage,
                        duration = extra.duration, screenshotBase64 = extra.screenshotBase64
                    )
                    val stepRow = createStepRow(extraStep, panel)
                    panel.add(stepRow)
                    val screenshotPanel = stepRow.getClientProperty("screenshotPanel") as? JPanel
                    if (screenshotPanel != null) panel.add(screenshotPanel)
                    if (extra.status == StepStatus.FAILED && extra.errorMessage != null) {
                        panel.add(createErrorView(extra.errorMessage))
                    }
                }
            }
        }

        panel.add(Box.createVerticalGlue())
        return JBScrollPane(panel).apply { border = JBUI.Borders.empty(4) }
    }

    private fun createStepRow(step: ScenarioStep, parentPanel: JPanel? = null, extraIndent: Int = 0): JPanel {
        var screenshotImagePanel: JPanel? = null

        if (step.screenshotBase64 != null && parentPanel != null) {
            screenshotImagePanel = JPanel(BorderLayout()).apply {
                isVisible = false
                border = JBUI.Borders.empty(2, 36, 8, 16)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            try {
                val cleanBase64 = step.screenshotBase64.replace("\\s".toRegex(), "")
                val bytes = Base64.getDecoder().decode(cleanBase64)
                val icon = ImageIcon(bytes)
                val maxWidth = 600
                val img = if (icon.iconWidth > maxWidth) {
                    val scale = maxWidth.toDouble() / icon.iconWidth
                    val newH = (icon.iconHeight * scale).toInt()
                    ImageIcon(icon.image.getScaledInstance(maxWidth, newH, Image.SCALE_SMOOTH))
                } else icon
                screenshotImagePanel.add(JLabel(img).apply {
                    border = BorderFactory.createCompoundBorder(
                        RoundedBorder(8, UIConstants.subtleBorder()),
                        JBUI.Borders.empty(2)
                    )
                }, BorderLayout.WEST)
                screenshotImagePanel.maximumSize = Dimension(Int.MAX_VALUE, img.iconHeight + 10)
            } catch (_: Exception) {
                screenshotImagePanel.add(JBLabel("[Screenshot decode error]").apply {
                    foreground = UIUtil.getLabelDisabledForeground()
                }, BorderLayout.WEST)
            }
        }

        val imgPanel = screenshotImagePanel

        // Step row with subtle left border color indicator
        val statusColor = when (step.status) {
            StepStatus.PASSED -> UIConstants.GREEN
            StepStatus.FAILED -> UIConstants.RED
            StepStatus.SKIPPED -> UIConstants.YELLOW
            else -> UIConstants.GRAY
        }

        return object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Left accent bar
                g2.color = statusColor
                g2.fillRoundRect(0, 4, 3, height - 8, 2, 2)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 10 + extraIndent, 4, 8)
            maximumSize = Dimension(Int.MAX_VALUE, 36)
            alignmentX = Component.LEFT_ALIGNMENT

            val statusIcon = JLabel(
                when (step.status) {
                    StepStatus.PASSED -> AllIcons.RunConfigurations.TestPassed
                    StepStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
                    StepStatus.SKIPPED -> AllIcons.RunConfigurations.TestSkipped
                    StepStatus.NOT_RUN -> AllIcons.Actions.Suspend
                    else -> AllIcons.Actions.Suspend
                }
            )

            val keywordLabel = JBLabel(step.keyword).apply {
                foreground = if (step.keyword.startsWith("▸")) UIConstants.YELLOW else UIConstants.BLUE
                font = font.deriveFont(if (step.keyword.startsWith("▸")) Font.BOLD else Font.BOLD, 12f)
            }
            val textLabel = JBLabel(step.text).apply {
                font = font.deriveFont(12f)
            }
            val durationLabel = JBLabel(
                step.duration?.let { formatDuration(it) } ?: ""
            ).apply {
                foreground = UIUtil.getLabelDisabledForeground()
                font = font.deriveFont(11f)
            }

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(statusIcon)
                add(keywordLabel)
                add(textLabel)
            }

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(durationLabel)

                if (imgPanel != null && parentPanel != null) {
                    val screenshotBtn = JButton(AllIcons.Actions.Preview).apply {
                        toolTipText = "Show Screenshot"
                        isFocusPainted = false
                        isContentAreaFilled = false
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        preferredSize = Dimension(24, 24)
                    }
                    screenshotBtn.addActionListener {
                        imgPanel.isVisible = !imgPanel.isVisible
                        screenshotBtn.toolTipText = if (imgPanel.isVisible) "Hide Screenshot" else "Show Screenshot"
                        parentPanel.revalidate()
                        parentPanel.repaint()
                        parentPanel.parent?.revalidate()
                        parentPanel.parent?.repaint()
                    }
                    add(screenshotBtn)
                }
            }

            add(leftPanel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
            toolTipText = step.errorMessage

            if (imgPanel != null) {
                putClientProperty("screenshotPanel", imgPanel)
            }
        }
    }

    private fun createErrorView(errorMessage: String, extraIndent: Int = 0): JPanel {
        return RoundedPanel(8).apply {
            layout = BorderLayout()
            background = UIConstants.RED.let { Color(it.red, it.green, it.blue, 15) }
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(8, UIConstants.RED.let { Color(it.red, it.green, it.blue, 40) }),
                JBUI.Borders.empty(8, 12)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)

            val textArea = JTextArea(errorMessage).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = Font("Monospaced", Font.PLAIN, 11)
                foreground = UIConstants.RED
                isOpaque = false
                border = JBUI.Borders.empty()
                rows = minOf(errorMessage.lines().size + 1, 8)
            }

            add(JBScrollPane(textArea).apply {
                preferredSize = Dimension(500, textArea.rows * 16 + 12)
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }, BorderLayout.CENTER)
        }
    }

    private fun createDataTableView(dataTable: DataTable): JPanel {
        val tableModel = object : DefaultTableModel(
            dataTable.rows.map { it.toTypedArray() }.toTypedArray(),
            dataTable.headers.toTypedArray()
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        val table = JBTable(tableModel).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 1)
            tableHeader.reorderingAllowed = false
            rowHeight = 28
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

            val renderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    if (!isSelected) {
                        background = if (row % 2 == 0) UIUtil.getTableBackground()
                        else UIUtil.getDecoratedRowColor()
                        foreground = UIUtil.getTableForeground()
                    }
                    border = JBUI.Borders.empty(2, 8)
                    return comp
                }
            }
            setDefaultRenderer(Any::class.java, renderer)

            tableHeader.defaultRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    font = font.deriveFont(Font.BOLD)
                    background = UIConstants.cardBackground()
                    foreground = UIUtil.getLabelForeground()
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 2, 0, UIConstants.subtleBorder()),
                        JBUI.Borders.empty(4, 8)
                    )
                    return comp
                }
            }
        }

        val totalHeight = table.tableHeader.preferredSize.height + dataTable.rows.size * 28 + 4
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 36, 8, 16)
            maximumSize = Dimension(Int.MAX_VALUE, totalHeight + 16)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBScrollPane(table).apply {
                preferredSize = Dimension(500, totalHeight)
                border = JBUI.Borders.empty()
            }, BorderLayout.CENTER)
        }
    }

    private fun runScenario(scenario: Scenario) {
        onRunScenario?.invoke(scenario)
    }

    private fun openFile(scenario: Scenario) {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(scenario.file) ?: return
        val descriptor = OpenFileDescriptor(project, vFile, scenario.line - 1, 0)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "%.1fs".format(ms / 1000.0)
        else -> "%dm %ds".format(ms / 60_000, (ms % 60_000) / 1000)
    }
}
