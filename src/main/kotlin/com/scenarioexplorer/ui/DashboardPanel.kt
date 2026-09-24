package com.scenarioexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.scenarioexplorer.model.ReportEntry
import com.scenarioexplorer.model.ScenarioFile
import com.scenarioexplorer.model.StepStatus
import com.scenarioexplorer.report.HtmlExporter
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class DashboardPanel : JPanel(BorderLayout()) {

    private var currentFiles: List<ScenarioFile> = emptyList()
    private var currentReports: Map<String, ReportEntry> = emptyMap()
    private var currentAllReports: Map<String, List<ReportEntry>> = emptyMap()

    private val exportButton = JButton("Export HTML", AllIcons.ToolbarDecorator.Export).apply {
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Dashboard verilerini HTML olarak dışa aktar"
        addActionListener { exportHtml() }
    }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(16, 20)
        background = UIConstants.surfaceBackground()
    }

    init {
        background = UIConstants.surfaceBackground()

        val topBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 12)
            add(exportButton)
        }

        add(topBar, BorderLayout.NORTH)
        add(JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            background = UIConstants.surfaceBackground()
        }, BorderLayout.CENTER)
    }

    fun update(files: List<ScenarioFile>, reports: Map<String, ReportEntry>, allReports: Map<String, List<ReportEntry>> = emptyMap()) {
        currentFiles = files
        currentReports = reports
        currentAllReports = allReports
        contentPanel.removeAll()

        val allScenarios = files.flatMap { it.scenarios }
        val total = allScenarios.size
        val passed = allScenarios.count { reports[it.name]?.status == StepStatus.PASSED }
        val failed = allScenarios.count { reports[it.name]?.status == StepStatus.FAILED }
        val notRun = total - passed - failed
        val totalDur = allScenarios.sumOf { reports[it.name]?.duration ?: 0L }

        // Total duration across ALL reports (not just latest)
        val allReportsTotalDur = allReports.values.flatten().sumOf { it.duration ?: 0L }

        // Toplam koşum sayısı (tüm raporlardaki entry sayısı)
        val totalExecutions = allReports.values.sumOf { it.size }

        // --- Summary Cards ---
        contentPanel.add(UIComponents.sectionTitle("Genel Bakış"))
        val summaryPanel = JPanel(GridLayout(1, 7, 14, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 90)
            isOpaque = false
        }
        summaryPanel.add(UIComponents.statCard("Toplam", "$total", UIConstants.BLUE, "📊"))
        summaryPanel.add(UIComponents.statCard("Passed", "$passed", UIConstants.GREEN, "✓"))
        summaryPanel.add(UIComponents.statCard("Failed", "$failed", UIConstants.RED, "✗"))
        summaryPanel.add(UIComponents.statCard("Not Run", "$notRun", UIConstants.GRAY, "○"))
        summaryPanel.add(UIComponents.statCard("Koşum Sayısı", "$totalExecutions", UIConstants.BLUE, "🔁"))
        summaryPanel.add(UIComponents.statCard("Son Koşum", formatDuration(totalDur), UIConstants.BLUE, "⏱"))
        summaryPanel.add(UIComponents.statCard("Toplam Süre", formatDuration(allReportsTotalDur), UIConstants.BLUE, "⏳"))
        contentPanel.add(summaryPanel)
        contentPanel.add(Box.createVerticalStrut(20))

        // --- Pass Rate ---
        if (total > 0 && notRun < total) {
            val ran = total - notRun
            val passRate = if (ran > 0) (passed * 100.0 / ran) else 0.0
            contentPanel.add(UIComponents.sectionTitle("Başarı Oranı"))
            contentPanel.add(UIComponents.progressBar(passRate, ran))
            contentPanel.add(Box.createVerticalStrut(20))
        }

        // --- Daily Test Duration Table ---
        val dailyMap = mutableMapOf<String, Long>() // date -> total ms
        for (entries in allReports.values) {
            for (entry in entries) {
                val ts = entry.timestamp ?: continue
                val date = ts.split(" ").firstOrNull() ?: continue
                dailyMap[date] = (dailyMap[date] ?: 0L) + (entry.duration ?: 0L)
            }
        }
        if (dailyMap.isNotEmpty()) {
            contentPanel.add(UIComponents.sectionTitle("Günlük Test Koşum Süreleri"))
            val dailyData = dailyMap.entries.sortedByDescending { it.key }.map { (date, ms) ->
                arrayOf<Any>(date, ms as Any)
            }
            val dailyColumns = arrayOf("Tarih", "Süre")
            val dailyModel = object : DefaultTableModel(
                dailyData.toTypedArray(), dailyColumns
            ) {
                override fun isCellEditable(row: Int, column: Int) = false
                override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
                    1 -> java.lang.Long::class.java
                    else -> String::class.java
                }
            }
            contentPanel.add(createStyledTable(dailyModel, durationColumns = setOf(1)))
            contentPanel.add(Box.createVerticalStrut(20))
        }

        // --- Feature Table ---
        contentPanel.add(UIComponents.sectionTitle("Feature Bazlı Dağılım"))
        val featureData = files.map { sf ->
            val scenarios = sf.scenarios
            val t = scenarios.size
            val p = scenarios.count { reports[it.name]?.status == StepStatus.PASSED }
            val f = scenarios.count { reports[it.name]?.status == StepStatus.FAILED }
            val nr = t - p - f
            val d = scenarios.sumOf { reports[it.name]?.duration ?: 0L }
            val rate = if (p + f > 0) "%.0f%%".format(p * 100.0 / (p + f)) else "-"
            arrayOf<Any>(sf.featureName, t as Any, p as Any, f as Any, nr as Any, rate, d as Any)
        }
        val featureColumns = arrayOf("Feature", "Toplam", "✓", "✗", "○", "Oran", "Süre")
        val featureModel = object : DefaultTableModel(
            featureData.toTypedArray(), featureColumns
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
            override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
                1, 2, 3, 4 -> java.lang.Integer::class.java
                6 -> java.lang.Long::class.java
                else -> String::class.java
            }
        }
        contentPanel.add(createStyledTable(featureModel, durationColumns = setOf(6)))
        contentPanel.add(Box.createVerticalStrut(20))

        // --- Tag Table ---
        contentPanel.add(UIComponents.sectionTitle("Tag Bazlı Dağılım"))
        val tagMap = mutableMapOf<String, MutableList<String>>()
        for (s in allScenarios) {
            for (tag in s.tags) {
                tagMap.getOrPut(tag) { mutableListOf() }.add(s.name)
            }
        }
        if (tagMap.isNotEmpty()) {
            val tagData = tagMap.map { (tag, names) ->
                val t = names.size
                val p = names.count { reports[it]?.status == StepStatus.PASSED }
                val f = names.count { reports[it]?.status == StepStatus.FAILED }
                val nr = t - p - f
                val rate = if (p + f > 0) "%.0f%%".format(p * 100.0 / (p + f)) else "-"
                arrayOf<Any>(tag, t as Any, p as Any, f as Any, nr as Any, rate)
            }.sortedByDescending { it[1] as Int }
            val tagColumns = arrayOf("Tag", "Toplam", "✓", "✗", "○", "Oran")
            val tagModel = object : DefaultTableModel(
                tagData.toTypedArray(), tagColumns
            ) {
                override fun isCellEditable(row: Int, column: Int) = false
                override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
                    1, 2, 3, 4 -> java.lang.Integer::class.java
                    else -> String::class.java
                }
            }
            contentPanel.add(createStyledTable(tagModel))
        } else {
            contentPanel.add(JBLabel("Tag bulunamadı.").apply {
                foreground = UIUtil.getLabelDisabledForeground()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyLeft(4)
            })
        }

        contentPanel.add(Box.createVerticalGlue())
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun createStyledTable(model: DefaultTableModel, durationColumns: Set<Int> = emptySet()): JPanel {
        val table = JBTable(model).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 32
            tableHeader.reorderingAllowed = false
            autoCreateRowSorter = true
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

            // Modern row renderer
            val renderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val modelCol = convertColumnIndexToModel(column)
                    val displayVal = if (modelCol in durationColumns && value is Long) formatDuration(value) else value
                    val comp = super.getTableCellRendererComponent(table, displayVal, isSelected, hasFocus, row, column)
                    if (!isSelected) {
                        background = if (row % 2 == 0) UIUtil.getTableBackground()
                        else {
                            val base = UIUtil.getTableBackground()
                            if (UIConstants.isDark()) Color(base.red + 8, base.green + 8, base.blue + 8)
                            else Color(base.red - 6, base.green - 6, base.blue - 6)
                        }
                        foreground = UIUtil.getTableForeground()
                        // Color pass/fail/notrun columns
                        if (modelCol == 2 && (value as? Int ?: 0) > 0) foreground = UIConstants.GREEN
                        if (modelCol == 3 && (value as? Int ?: 0) > 0) foreground = UIConstants.RED
                        if (modelCol == 4 && (value as? Int ?: 0) > 0) foreground = UIConstants.GRAY
                    }
                    border = JBUI.Borders.empty(4, 10)
                    return comp
                }
            }
            setDefaultRenderer(Any::class.java, renderer)
            setDefaultRenderer(java.lang.Integer::class.java, renderer)
            setDefaultRenderer(java.lang.Long::class.java, renderer)

            // Modern header
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
                        JBUI.Borders.empty(6, 10)
                    )
                    return comp
                }
            }
        }

        val h = table.tableHeader.preferredSize.height + model.rowCount * 32 + 8
        return RoundedPanel(UIConstants.CARD_ARC).apply {
            layout = BorderLayout()
            background = UIConstants.cardBackground()
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(UIConstants.CARD_ARC, UIConstants.subtleBorder()),
                JBUI.Borders.empty(4)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, h + 24)
            add(JBScrollPane(table).apply {
                preferredSize = Dimension(500, h)
                border = JBUI.Borders.empty()
                background = UIConstants.cardBackground()
                viewport.background = UIConstants.cardBackground()
            }, BorderLayout.CENTER)
        }
    }

    private fun formatDuration(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1000
        return "%02dh %02dm %02ds".format(hours, minutes, seconds)
    }

    private fun exportHtml() {
        if (currentFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Dışa aktarılacak veri yok.", "Export", JOptionPane.INFORMATION_MESSAGE)
            return
        }

        // --- Selection dialog ---
        val selectionPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
        }

        data class ScenarioCheck(val scenario: com.scenarioexplorer.model.Scenario, val checkbox: JCheckBox)
        data class FileCheck(val sf: ScenarioFile, val checkbox: JCheckBox, val scenarios: List<ScenarioCheck>)
        data class DirCheck(val dirPath: String, val checkbox: JCheckBox, val files: List<FileCheck>)

        val dirChecks = mutableListOf<DirCheck>()
        val grouped = currentFiles.groupBy { it.file.parentFile?.path ?: "" }

        for ((dirPath, scenarioFiles) in grouped.toSortedMap()) {
            val dirName = java.io.File(dirPath).name.ifEmpty { dirPath }
            val fileChecks = mutableListOf<FileCheck>()

            val dirCb = JCheckBox("📁 $dirName", true).apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(6, 0, 2, 0)
            }
            selectionPanel.add(dirCb)

            for (sf in scenarioFiles) {
                val scenarioChecks = mutableListOf<ScenarioCheck>()
                val report = currentReports
                val total = sf.scenarios.size
                val passed = sf.scenarios.count { report[it.name]?.status == com.scenarioexplorer.model.StepStatus.PASSED }
                val failed = sf.scenarios.count { report[it.name]?.status == com.scenarioexplorer.model.StepStatus.FAILED }

                val fileCb = JCheckBox("📄 ${sf.featureName}  [$total | ✓$passed ✗$failed]", true).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyLeft(24)
                }
                selectionPanel.add(fileCb)

                for (scenario in sf.scenarios) {
                    val r = currentReports[scenario.name]
                    val statusIcon = when (r?.status) {
                        com.scenarioexplorer.model.StepStatus.PASSED -> "✓"
                        com.scenarioexplorer.model.StepStatus.FAILED -> "✗"
                        else -> "○"
                    }
                    val sCb = JCheckBox("$statusIcon ${scenario.name}", true).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyLeft(48)
                        font = font.deriveFont(Font.PLAIN, 12f)
                    }
                    selectionPanel.add(sCb)
                    scenarioChecks.add(ScenarioCheck(scenario, sCb))
                }

                val fc = FileCheck(sf, fileCb, scenarioChecks)
                fileChecks.add(fc)

                // File checkbox toggles its scenarios
                fileCb.addActionListener {
                    fc.scenarios.forEach { it.checkbox.isSelected = fileCb.isSelected }
                }
            }

            val dc = DirCheck(dirPath, dirCb, fileChecks)
            dirChecks.add(dc)

            // Dir checkbox toggles its files and scenarios
            dirCb.addActionListener {
                dc.files.forEach { f ->
                    f.checkbox.isSelected = dirCb.isSelected
                    f.scenarios.forEach { it.checkbox.isSelected = dirCb.isSelected }
                }
            }
        }

        // Helper: toggle scenarios by status
        fun toggleByStatus(status: com.scenarioexplorer.model.StepStatus?, select: Boolean) {
            dirChecks.forEach { d ->
                d.files.forEach { f ->
                    f.scenarios.forEach { sc ->
                        val r = currentReports[sc.scenario.name]?.status
                        val match = if (status == null) r == null || r == com.scenarioexplorer.model.StepStatus.NOT_RUN
                                    else r == status
                        if (match) sc.checkbox.isSelected = select
                    }
                    f.checkbox.isSelected = f.scenarios.any { it.checkbox.isSelected }
                }
                d.checkbox.isSelected = d.files.any { it.checkbox.isSelected }
            }
        }

        val allBtns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JButton("Tümünü Seç").apply { addActionListener {
                dirChecks.forEach { d -> d.checkbox.isSelected = true; d.files.forEach { f -> f.checkbox.isSelected = true; f.scenarios.forEach { it.checkbox.isSelected = true } } }
            }})
            add(JButton("Tümünü Kaldır").apply { addActionListener {
                dirChecks.forEach { d -> d.checkbox.isSelected = false; d.files.forEach { f -> f.checkbox.isSelected = false; f.scenarios.forEach { it.checkbox.isSelected = false } } }
            }})
        }
        val statusBtns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JButton("✓ Passed Seç").apply { addActionListener { toggleByStatus(com.scenarioexplorer.model.StepStatus.PASSED, true) } })
            add(JButton("✓ Passed Kaldır").apply { addActionListener { toggleByStatus(com.scenarioexplorer.model.StepStatus.PASSED, false) } })
            add(JButton("✗ Failed Seç").apply { addActionListener { toggleByStatus(com.scenarioexplorer.model.StepStatus.FAILED, true) } })
            add(JButton("✗ Failed Kaldır").apply { addActionListener { toggleByStatus(com.scenarioexplorer.model.StepStatus.FAILED, false) } })
            add(JButton("○ Not Run Seç").apply { addActionListener { toggleByStatus(null, true) } })
            add(JButton("○ Not Run Kaldır").apply { addActionListener { toggleByStatus(null, false) } })
        }

        val buttonBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(allBtns)
            add(statusBtns)
        }

        val scrollPane = JBScrollPane(selectionPanel).apply {
            preferredSize = Dimension(700, 400)
        }
        val dialogPanel = JPanel(BorderLayout(0, 4)).apply {
            add(JBLabel("Rapora dahil edilecek senaryoları seçin:").apply {
                border = JBUI.Borders.empty(4)
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(buttonBar, BorderLayout.SOUTH)
        }

        val result = JOptionPane.showConfirmDialog(this, dialogPanel, "Export Seçimi",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (result != JOptionPane.OK_OPTION) return

        // Build filtered file list
        val filteredFiles = dirChecks.flatMap { dc ->
            dc.files.mapNotNull { fc ->
                val selectedScenarios = fc.scenarios.filter { it.checkbox.isSelected }.map { it.scenario }
                if (selectedScenarios.isEmpty()) null
                else fc.sf.copy(scenarios = selectedScenarios)
            }
        }

        if (filteredFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Hiç senaryo seçilmedi.", "Export", JOptionPane.WARNING_MESSAGE)
            return
        }

        // File save dialog
        val descriptor = FileSaverDescriptor("Export HTML Report", "HTML raporu kaydet", "html")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, this)
        val wrapper = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "scenario-report.html") ?: return

        try {
            val html = HtmlExporter.export(filteredFiles, currentReports, currentAllReports)
            wrapper.file.writeText(html, Charsets.UTF_8)
            JOptionPane.showMessageDialog(this, "Rapor kaydedildi:\n${wrapper.file.absolutePath}",
                "Export", JOptionPane.INFORMATION_MESSAGE)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Hata: ${e.message}", "Export Error", JOptionPane.ERROR_MESSAGE)
        }
    }
}
