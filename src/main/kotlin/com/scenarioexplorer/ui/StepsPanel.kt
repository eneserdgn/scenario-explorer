package com.scenarioexplorer.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.scenarioexplorer.model.ReportEntry
import com.scenarioexplorer.model.ScenarioFile
import com.scenarioexplorer.model.StepStatus
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class StepsPanel : JPanel(BorderLayout()) {

    var onNavigateToScenario: ((String) -> Unit)? = null

    private val contentPanel = JPanel(BorderLayout())
    private val detailPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)
    }

    init {
        add(contentPanel, BorderLayout.CENTER)
    }

    data class StepInfo(
        val scenarioName: String,
        val status: StepStatus,
        val duration: Long?
    )

    fun update(files: List<ScenarioFile>, reports: Map<String, ReportEntry>) {
        contentPanel.removeAll()

        val stepMap = mutableMapOf<String, MutableList<StepInfo>>()

        for (sf in files) {
            for (scenario in sf.scenarios) {
                val report = reports[scenario.name] ?: continue
                for ((idx, step) in scenario.steps.withIndex()) {
                    val reportStep = report.steps.getOrNull(idx) ?: continue
                    val key = "${step.keyword} ${step.text}"
                    stepMap.getOrPut(key) { mutableListOf() }.add(
                        StepInfo(scenario.name, reportStep.status, reportStep.duration)
                    )
                }
            }
        }

        if (stepMap.isEmpty()) {
            contentPanel.add(JPanel(GridBagLayout()).apply {
                isOpaque = false
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(JBLabel("📋").apply {
                        font = font.deriveFont(32f)
                        alignmentX = Component.CENTER_ALIGNMENT
                    })
                    add(Box.createVerticalStrut(8))
                    add(JBLabel("Rapor verisi bulunamadı").apply {
                        font = font.deriveFont(Font.BOLD, 14f)
                        foreground = UIUtil.getLabelDisabledForeground()
                        alignmentX = Component.CENTER_ALIGNMENT
                    })
                })
            }, BorderLayout.CENTER)
            contentPanel.revalidate()
            contentPanel.repaint()
            return
        }

        val uniqueSteps = stepMap.size
        val totalExecutions = stepMap.values.sumOf { it.size }

        // Summary card
        val summaryCard = RoundedPanel(UIConstants.CARD_ARC).apply {
            layout = FlowLayout(FlowLayout.LEFT, 16, 8)
            background = UIConstants.cardBackground()
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(UIConstants.CARD_ARC, UIConstants.subtleBorder()),
                JBUI.Borders.empty(6, 12)
            )
            add(JBLabel("📊 Benzersiz Adım: $uniqueSteps").apply {
                font = font.deriveFont(Font.BOLD, 13f)
            })
            add(JBLabel("│").apply { foreground = UIConstants.subtleBorder() })
            add(JBLabel("🔄 Toplam Koşum: $totalExecutions").apply {
                font = font.deriveFont(Font.BOLD, 13f)
            })
        }

        val stepDataList = stepMap.map { (stepText, infos) ->
            val count = infos.size
            val passed = infos.count { it.status == StepStatus.PASSED }
            val failed = infos.count { it.status == StepStatus.FAILED }
            val passRate = if (count > 0) "%.0f%%".format(passed * 100.0 / count) else "-"
            val durations = infos.mapNotNull { it.duration }
            val avgDur = if (durations.isNotEmpty()) durations.average().toLong() else 0L
            val maxDur = durations.maxOrNull() ?: 0L
            val passedIn = infos.filter { it.status == StepStatus.PASSED }.map { it.scenarioName }.distinct()
            val failedIn = infos.filter { it.status == StepStatus.FAILED }.map { it.scenarioName }.distinct()
            StepRow(stepText, count, passed, failed, passRate, avgDur, maxDur, passedIn, failedIn)
        }.sortedByDescending { it.count }

        val columns = arrayOf("Adım", "Koşum", "✓", "✗", "Oran", "Ort. Süre", "Max Süre")
        val tableData = stepDataList.map { r ->
            arrayOf<Any>(r.stepText, r.count as Any, r.passed as Any, r.failed as Any, r.passRate, r.avgDur as Any, r.maxDur as Any)
        }

        val model = object : DefaultTableModel(tableData.toTypedArray(), columns) {
            override fun isCellEditable(row: Int, column: Int) = false
            override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
                1, 2, 3 -> java.lang.Integer::class.java
                5, 6 -> java.lang.Long::class.java
                else -> String::class.java
            }
        }

        val table = JBTable(model).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 30
            tableHeader.reorderingAllowed = false
            autoCreateRowSorter = true
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

            columnModel.getColumn(0).preferredWidth = 350
            columnModel.getColumn(1).preferredWidth = 50
            columnModel.getColumn(2).preferredWidth = 40
            columnModel.getColumn(3).preferredWidth = 40
            columnModel.getColumn(4).preferredWidth = 50
            columnModel.getColumn(5).preferredWidth = 80
            columnModel.getColumn(6).preferredWidth = 80

            val renderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val modelCol = convertColumnIndexToModel(column)
                    val displayVal = if (modelCol in setOf(5, 6) && value is Long) formatDuration(value) else value
                    val comp = super.getTableCellRendererComponent(table, displayVal, isSelected, hasFocus, row, column)
                    if (!isSelected) {
                        val base = UIUtil.getTableBackground()
                        background = if (row % 2 == 0) base
                        else {
                            if (UIConstants.isDark()) Color(base.red + 8, base.green + 8, base.blue + 8)
                            else Color(base.red - 6, base.green - 6, base.blue - 6)
                        }
                        foreground = UIUtil.getTableForeground()
                        if (modelCol == 3 && (value as? Int ?: 0) > 0) foreground = UIConstants.RED
                        if (modelCol == 2 && (value as? Int ?: 0) > 0) foreground = UIConstants.GREEN
                    }
                    border = JBUI.Borders.empty(4, 8)
                    return comp
                }
            }
            setDefaultRenderer(Any::class.java, renderer)
            setDefaultRenderer(java.lang.Integer::class.java, renderer)
            setDefaultRenderer(java.lang.Long::class.java, renderer)

            tableHeader.defaultRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    font = font.deriveFont(Font.BOLD, 12f)
                    background = UIConstants.cardBackground()
                    foreground = UIUtil.getLabelForeground()
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 2, 0, UIConstants.subtleBorder()),
                        JBUI.Borders.empty(6, 8)
                    )
                    return comp
                }
            }

            selectionModel.addListSelectionListener { e ->
                if (!e.valueIsAdjusting && selectedRow >= 0) {
                    val modelRow = convertRowIndexToModel(selectedRow)
                    if (modelRow in stepDataList.indices) {
                        showStepDetail(stepDataList[modelRow])
                    }
                }
            }
        }

        val tableScroll = JBScrollPane(table).apply { border = JBUI.Borders.empty(4) }
        val detailScroll = JBScrollPane(detailPanel).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                    RoundedBorder(8, UIConstants.subtleBorder()),
                    " Senaryo Detayları ",
                    javax.swing.border.TitledBorder.LEFT,
                    javax.swing.border.TitledBorder.TOP,
                    UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f),
                    UIUtil.getLabelForeground()
                ),
                JBUI.Borders.empty(4)
            )
            preferredSize = Dimension(0, 200)
        }

        val splitter = com.intellij.ui.JBSplitter(true, 0.65f).apply {
            firstComponent = tableScroll
            secondComponent = detailScroll
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(12, 12, 8, 12)
            add(summaryCard, BorderLayout.CENTER)
        }

        contentPanel.add(topBar, BorderLayout.NORTH)
        contentPanel.add(splitter, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun showStepDetail(row: StepRow) {
        detailPanel.removeAll()

        if (row.passedIn.isNotEmpty()) {
            detailPanel.add(createCollapsibleList(
                "✓ Pass Senaryolar (${row.passedIn.size})",
                row.passedIn,
                UIConstants.GREEN
            ))
            detailPanel.add(Box.createVerticalStrut(8))
        }

        if (row.failedIn.isNotEmpty()) {
            detailPanel.add(createCollapsibleList(
                "✗ Fail Senaryolar (${row.failedIn.size})",
                row.failedIn,
                UIConstants.RED
            ))
        }

        if (row.passedIn.isEmpty() && row.failedIn.isEmpty()) {
            detailPanel.add(JBLabel("Senaryo bilgisi yok.").apply {
                foreground = UIUtil.getLabelDisabledForeground()
            })
        }

        detailPanel.add(Box.createVerticalGlue())
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    private fun createCollapsibleList(title: String, items: List<String>, color: Color): JPanel {
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = false
            border = JBUI.Borders.emptyLeft(20)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        for (item in items) {
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = false
            }
            row.add(JBLabel("• $item").apply { font = font.deriveFont(12f) })
            val goBtn = JButton("→").apply {
                toolTipText = "Senaryoya git"
                isFocusPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(28, 20)
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = UIConstants.BLUE
                addActionListener { onNavigateToScenario?.invoke(item) }
            }
            row.add(goBtn)
            row.maximumSize = Dimension(Int.MAX_VALUE, 26)
            listPanel.add(row)
        }

        val toggleBtn = JBLabel("▶ $title").apply {
            foreground = color
            font = font.deriveFont(Font.BOLD, 12f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0)

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    listPanel.isVisible = !listPanel.isVisible
                    text = (if (listPanel.isVisible) "▼ " else "▶ ") + title.removePrefix("▼ ").removePrefix("▶ ")
                    wrapper.revalidate()
                    wrapper.repaint()
                }
            })
        }

        wrapper.add(toggleBtn)
        wrapper.add(listPanel)
        return wrapper
    }

    data class StepRow(
        val stepText: String,
        val count: Int,
        val passed: Int,
        val failed: Int,
        val passRate: String,
        val avgDur: Long,
        val maxDur: Long,
        val passedIn: List<String>,
        val failedIn: List<String>
    )

    private fun formatDuration(ms: Long): String {
        if (ms == 0L) return "-"
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> "%.1fs".format(ms / 1000.0)
            else -> {
                val m = ms / 60_000
                val s = (ms % 60_000) / 1000
                "%02dm %02ds".format(m, s)
            }
        }
    }
}
