package com.scenarioexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.scenarioexplorer.model.ReportEntry
import com.scenarioexplorer.model.ScenarioFile
import com.scenarioexplorer.model.StepStatus
import com.scenarioexplorer.parser.ScenarioScanner
import com.scenarioexplorer.report.ReportReader
import com.scenarioexplorer.runner.RunHandle
import com.scenarioexplorer.runner.ScenarioRunner
import com.scenarioexplorer.settings.ScenarioExplorerSettings
import java.awt.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.text.*

class ScenarioExplorerPanel(private val project: Project) : JPanel(BorderLayout()) {

    var onDataRefreshed: ((List<ScenarioFile>, Map<String, ReportEntry>, Map<String, List<ReportEntry>>) -> Unit)? = null

    private var allFiles: List<ScenarioFile> = emptyList()
    private var allReports: Map<String, List<ReportEntry>> = emptyMap()
    private var latestReports: Map<String, ReportEntry> = emptyMap()

    private val detailPanel = ScenarioDetailPanel(project)
    private val treePanel = CheckboxTreePanel { scenario ->
        val reports = allReports[scenario.name] ?: emptyList()
        detailPanel.showScenario(scenario, reports)
    }
    // Tabbed output area — each run gets its own tab
    private val outputTabs = JTabbedPane(JTabbedPane.TOP).apply {
        border = JBUI.Borders.empty()
    }

    private val reportPathLabel = JBLabel("Report: not set").apply {
        foreground = UIUtil.getLabelDisabledForeground()
        border = JBUI.Borders.emptyLeft(8)
        font = font.deriveFont(11f)
    }
    private val summaryLabel = JBLabel().apply {
        border = JBUI.Borders.empty(6, 10)
        foreground = UIUtil.getLabelDisabledForeground()
        font = font.deriveFont(Font.BOLD, 12f)
    }

    // Track runs
    private val activeRuns = mutableListOf<OutputTab>()

    // Retry mekanizması state
    private var retryRunning = false
    private var retryCancelled = false

    init {
        // Wire detail panel to use tabbed output
        detailPanel.onRunScenario = { scenario ->
            // Refresh first, then run
            refresh {
                val title = scenario.name.take(30)
                val tab = createOutputTab(title)
                activeRuns.add(tab)
                Thread {
                    ScenarioRunner.run(
                        project, scenario,
                        onOutput = { text -> SwingUtilities.invokeLater { appendAnsi(tab.textPane, text) } },
                        onFinished = { exitCode ->
                            SwingUtilities.invokeLater {
                                appendAnsi(tab.textPane, "\n--- Finished (exit code: $exitCode) ---\n")
                                tab.markFinished()
                                refresh()
                            }
                        }
                    )?.let { handle -> tab.runHandle = handle }
                }.start()
            }
        }

        val currentPath = ScenarioExplorerSettings.getInstance(project).state.reportPath
        if (currentPath.isNotEmpty()) {
            reportPathLabel.text = "Report: $currentPath"
            reportPathLabel.foreground = UIUtil.getLabelForeground()
        }

        val toolbar = createToolbar()
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2)
            add(toolbar)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 4)
                add(summaryLabel, BorderLayout.CENTER)
            })
        }

        val treeScroll = JBScrollPane(treePanel.tree).apply { border = JBUI.Borders.empty() }
        val leftPanel = JPanel(BorderLayout()).apply {
            val northPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(topPanel)
                add(treePanel.filterButtonsPanel)
            }
            add(northPanel, BorderLayout.NORTH)
            add(treeScroll, BorderLayout.CENTER)
        }

        val mainSplitter = JBSplitter(false, 0.35f).apply {
            firstComponent = leftPanel
            secondComponent = detailPanel
            dividerWidth = 8
        }

        val verticalSplitter = JBSplitter(true, 0.7f).apply {
            firstComponent = mainSplitter
            secondComponent = outputTabs
            dividerWidth = 8
        }

        add(verticalSplitter, BorderLayout.CENTER)

        refresh()
    }

    // --- Output Tab ---

    private inner class OutputTab(val title: String) {
        val textPane = JTextPane().apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 12).let { f ->
                if (f.family == "JetBrains Mono") f else Font("Monospaced", Font.PLAIN, 12)
            }
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8)
        }
        var runHandle: RunHandle? = null
        var isFinished = false
            private set

        val stopButton = JButton(AllIcons.Actions.Suspend).apply {
            toolTipText = "Durdur"
            isFocusPainted = false
            preferredSize = Dimension(20, 20)
            addActionListener { stop() }
        }
        val closeButton = JButton(AllIcons.Actions.Close).apply {
            toolTipText = "Kapat"
            isFocusPainted = false
            preferredSize = Dimension(20, 20)
        }

        fun stop() {
            runHandle?.stop()
            appendAnsi(textPane, "\n--- Stopped by user ---\n")
            stopButton.isEnabled = false
        }

        fun markFinished() {
            isFinished = true
            stopButton.isVisible = false
        }
    }

    private fun createOutputTab(title: String): OutputTab {
        val tab = OutputTab(title)
        val scroll = JBScrollPane(tab.textPane).apply { border = JBUI.Borders.empty(2) }
        val time = SimpleDateFormat("HH:mm:ss").format(Date())
        val tabTitle = "$title  $time"

        outputTabs.addTab(tabTitle, scroll)
        val idx = outputTabs.tabCount - 1
        outputTabs.selectedIndex = idx

        // Custom tab component with stop + close buttons
        val tabComponent = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(JBLabel(tabTitle).apply { font = font.deriveFont(11f) })
            add(tab.stopButton)
            add(tab.closeButton)
        }
        outputTabs.setTabComponentAt(idx, tabComponent)

        tab.closeButton.addActionListener {
            tab.stop()
            val i = outputTabs.indexOfComponent(scroll)
            if (i >= 0) outputTabs.removeTabAt(i)
        }

        return tab
    }

    // --- ANSI Color Support ---

    private val ANSI_PATTERN = Pattern.compile("\u001B\\[(\\d+(?:;\\d+)*)m")

    private fun appendAnsi(textPane: JTextPane, text: String) {
        val doc = textPane.styledDocument
        val matcher = ANSI_PATTERN.matcher(text)
        var lastEnd = 0
        var currentAttrs = SimpleAttributeSet()

        while (matcher.find()) {
            // Append text before this ANSI code
            if (matcher.start() > lastEnd) {
                val plain = text.substring(lastEnd, matcher.start())
                doc.insertString(doc.length, plain, currentAttrs)
            }
            // Parse ANSI codes
            val codes = matcher.group(1).split(";").mapNotNull { it.toIntOrNull() }
            currentAttrs = SimpleAttributeSet(currentAttrs)
            for (code in codes) {
                when (code) {
                    0 -> { // Reset
                        currentAttrs = SimpleAttributeSet()
                    }
                    1 -> StyleConstants.setBold(currentAttrs, true)
                    30 -> StyleConstants.setForeground(currentAttrs, Color.DARK_GRAY)
                    31 -> StyleConstants.setForeground(currentAttrs, Color(0xE0, 0x40, 0x40))
                    32 -> StyleConstants.setForeground(currentAttrs, Color(0x59, 0xA8, 0x69))
                    33 -> StyleConstants.setForeground(currentAttrs, Color(0xCC, 0xA7, 0x00))
                    34 -> StyleConstants.setForeground(currentAttrs, Color(0x58, 0x9D, 0xF6))
                    35 -> StyleConstants.setForeground(currentAttrs, Color(0xB0, 0x6C, 0xD4))
                    36 -> StyleConstants.setForeground(currentAttrs, Color(0x2A, 0xA1, 0x98))
                    37 -> StyleConstants.setForeground(currentAttrs, UIUtil.getLabelForeground())
                    90 -> StyleConstants.setForeground(currentAttrs, Color.GRAY)
                    91 -> StyleConstants.setForeground(currentAttrs, Color(0xFF, 0x60, 0x60))
                    92 -> StyleConstants.setForeground(currentAttrs, Color(0x70, 0xD0, 0x80))
                    93 -> StyleConstants.setForeground(currentAttrs, Color(0xFF, 0xD7, 0x00))
                    94 -> StyleConstants.setForeground(currentAttrs, Color(0x80, 0xB0, 0xFF))
                    95 -> StyleConstants.setForeground(currentAttrs, Color(0xD0, 0x90, 0xF0))
                    96 -> StyleConstants.setForeground(currentAttrs, Color(0x50, 0xD0, 0xC0))
                    97 -> StyleConstants.setForeground(currentAttrs, UIUtil.getLabelForeground())
                }
            }
            lastEnd = matcher.end()
        }
        // Remaining text after last ANSI code
        if (lastEnd < text.length) {
            doc.insertString(doc.length, text.substring(lastEnd), currentAttrs)
        }
        // Auto-scroll to bottom
        textPane.caretPosition = doc.length
    }

    // --- Toolbar ---

    private fun createToolbar(): JPanel {
        val refreshAction = object : AnAction("Refresh", "Rescan scenarios and reports", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        }
        val runSelectedAction = object : AnAction("Run Selected", "Run all checked scenarios", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) = runSelected()
        }
        val retrySelectedAction = object : AnAction("Retry Selected", "Run checked scenarios with auto-retry", AllIcons.Actions.Rerun) {
            override fun actionPerformed(e: AnActionEvent) = retrySelected()
        }
        val setReportPathAction = object : AnAction("Set Report Path", "Choose report JSON folder", AllIcons.Actions.MenuOpen) {
            override fun actionPerformed(e: AnActionEvent) = chooseReportPath()
        }
        val setScanPathsAction = object : AnAction("Set Scan Paths", "Configure which feature files to show", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) = editScanPaths()
        }

        val group = DefaultActionGroup().apply {
            add(refreshAction)
            add(runSelectedAction)
            add(retrySelectedAction)
            addSeparator()
            add(setReportPathAction)
            add(setScanPathsAction)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ScenarioExplorer", group, true)
        toolbar.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(reportPathLabel, BorderLayout.CENTER)
        }
    }

    private fun chooseReportPath() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = "Select Report Folder"
            description = "Choose the folder containing Cucumber/Gauge JSON report files"
        }
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = chosen.path
        val settings = ScenarioExplorerSettings.getInstance(project)
        settings.loadState(settings.state.copy(reportPath = path))
        reportPathLabel.text = "Report: $path"
        reportPathLabel.foreground = UIUtil.getLabelForeground()
        refresh()
    }

    private fun editScanPaths() {
        val allScanned = ScenarioScanner.scanProjectUnfiltered(project)
        if (allScanned.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Hiç feature/spec dosyası bulunamadı.", "Scan Paths", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val settings = ScenarioExplorerSettings.getInstance(project)
        val hiddenFileSet = settings.state.hiddenFiles.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val hiddenScenarioSet = settings.state.hiddenScenarios.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val basePath = project.basePath ?: ""
        val baseDir = java.io.File(basePath)

        // Projeye göre relative path hesapla
        fun relPath(f: java.io.File): String = try {
            f.relativeTo(baseDir).path
        } catch (_: Exception) {
            f.path.removePrefix(basePath).removePrefix("/")
        }

        // Ortak dizin prefix'ini bul ve çıkar
        val allDirPaths = allScanned.map { relPath(it.file.parentFile ?: baseDir) }
        val commonPrefix = findCommonDirectoryPrefix(allDirPaths)

        fun displayDir(f: java.io.File): String {
            val rel = relPath(f.parentFile ?: baseDir)
            val stripped = if (commonPrefix.isEmpty()) rel
                           else rel.removePrefix(commonPrefix).removePrefix("/")
            return stripped.ifEmpty { "." }
        }

        // Klasöre göre grupla (ortak prefix çıkarılmış görüntü adı)
        val grouped = allScanned
            .groupBy { displayDir(it.file) }
            .toSortedMap()

        // ── State ────────────────────────────────────────────────────────
        val fileCheckboxMap     = mutableMapOf<String, JCheckBox>()
        val scenarioCheckboxMap = mutableMapOf<String, JCheckBox>()
        val allFolderCbs        = mutableListOf<JCheckBox>()
        val allFileCbs          = mutableListOf<JCheckBox>()
        val allScenarioCbs      = mutableListOf<JCheckBox>()
        val folderExpanded      = mutableMapOf<String, Boolean>()
        val fileExpanded        = mutableMapOf<String, Boolean>()

        // Flat parallel lists — one entry per file/scenario row
        val fileRowPanels     = mutableListOf<JPanel>()
        val fileRowFolderKeys = mutableListOf<String>()
        val fileRowPaths      = mutableListOf<String>()
        val scenRowPanels     = mutableListOf<JPanel>()
        val scenRowFolderKeys = mutableListOf<String>()
        val scenRowFilePaths  = mutableListOf<String>()
        val scenRowStatuses   = mutableListOf<StepStatus?>()

        // ── Panels — declared early so updateVisibility can capture them ──
        val checkboxPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 4, 6, 4)
        }
        val innerWrap = JPanel(BorderLayout()).apply {
            add(checkboxPanel, BorderLayout.NORTH)
        }

        // ── Single visibility function ────────────────────────────────────
        fun updateVisibility() {
            for (i in fileRowPanels.indices)
                fileRowPanels[i].isVisible = folderExpanded[fileRowFolderKeys[i]] == true
            for (i in scenRowPanels.indices)
                scenRowPanels[i].isVisible =
                    folderExpanded[scenRowFolderKeys[i]] == true &&
                    fileExpanded[scenRowFilePaths[i]] == true
            innerWrap.revalidate()
            innerWrap.repaint()
        }

        // ── Selected count label ─────────────────────────────────────────
        val countLabel = JBLabel("").apply {
            font       = font.deriveFont(11f)
            foreground = UIUtil.getLabelDisabledForeground()
        }
        fun refreshCount() {
            val sel   = allScenarioCbs.count { it.isSelected }
            val total = allScenarioCbs.size
            countLabel.text = "$sel / $total senaryo seçili"
        }

        // ── Arrow helper ──────────────────────────────────────────────────
        fun makeArrow() = JLabel("▶").apply {
            font          = font.deriveFont(Font.PLAIN, 10f)
            foreground    = UIUtil.getLabelForeground()
            cursor        = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(12, 12)
            minimumSize   = Dimension(12, 12)
            maximumSize   = Dimension(12, 12)
        }

        // Consistent CB styling — all indent via struts, CB itself minimal border
        fun styleCheckbox(cb: JCheckBox, bold: Boolean = false) {
            cb.isOpaque       = false
            cb.isFocusPainted = false
            cb.border         = JBUI.Borders.empty(2, 2, 2, 4)
            cb.maximumSize    = Dimension(Int.MAX_VALUE, 24)
            if (bold) cb.font = cb.font.deriveFont(Font.BOLD)
        }

        // ── Common prefix info ────────────────────────────────────────────
        if (commonPrefix.isNotEmpty()) {
            checkboxPanel.add(JBLabel("Ortak yol: $commonPrefix").apply {
                font        = font.deriveFont(Font.ITALIC, 11f)
                foreground  = UIUtil.getLabelDisabledForeground()
                alignmentX  = Component.LEFT_ALIGNMENT
                border      = JBUI.Borders.empty(0, 4, 6, 4)
                maximumSize = Dimension(Int.MAX_VALUE, 20)
            })
        }

        // ── Build rows ────────────────────────────────────────────────────
        // Indent grid: folder=4  file=22  scenario=48
        //   folder arrow at 4px
        //   file   arrow at 22px  (4 + 12arrow + 6gap = 22)
        //   scen   cb    at 48px  (22 + 12arrow + 6gap + 8extra = 48)
        for ((folderDisplay, files) in grouped) {
            val folderKey     = folderDisplay
            val folderFileCbs = mutableListOf<JCheckBox>()
            val folderScenCbs = mutableListOf<JCheckBox>()

            val scenarioCount = files.sumOf { it.scenarios.size }
            val anyFolderVisible = files.any { sf ->
                val fp = sf.file.path
                fp !in hiddenFileSet && sf.scenarios.any { "${fp}::${it.name}" !in hiddenScenarioSet }
            }
            val folderCb = JCheckBox("$folderDisplay  (${files.size} dosya, $scenarioCount senaryo)", anyFolderVisible)
            styleCheckbox(folderCb, bold = true)
            folderCb.font = folderCb.font.deriveFont(12f)
            allFolderCbs.add(folderCb)

            val folderArrow = makeArrow()
            val folderRow = JPanel().apply {
                layout      = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque    = false
                alignmentX  = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 26)
                add(Box.createHorizontalStrut(4))
                add(folderArrow)
                add(Box.createHorizontalStrut(6))
                add(folderCb)
                add(Box.createHorizontalGlue())
            }
            checkboxPanel.add(folderRow)

            val onFolderToggle = {
                folderExpanded[folderKey] = folderExpanded[folderKey] != true
                folderArrow.text = if (folderExpanded[folderKey] == true) "▼" else "▶"
                updateVisibility()
            }
            folderArrow.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onFolderToggle()
            })

            for (sf in files.sortedBy { it.featureName }) {
                val filePath   = sf.file.path
                val fileHidden = filePath in hiddenFileSet

                val anyScenVisible = sf.scenarios.any { "${filePath}::${it.name}" !in hiddenScenarioSet }
                val fileCb = JCheckBox("${sf.featureName}  (${sf.scenarios.size})", !fileHidden && anyScenVisible)
                styleCheckbox(fileCb, bold = true)
                fileCheckboxMap[filePath] = fileCb
                folderFileCbs.add(fileCb)
                allFileCbs.add(fileCb)

                val fileArrow = makeArrow()
                val fileRow = JPanel().apply {
                    layout      = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque    = false
                    alignmentX  = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, 26)
                    isVisible   = false
                    add(Box.createHorizontalStrut(22))
                    add(fileArrow)
                    add(Box.createHorizontalStrut(6))
                    add(fileCb)
                    add(Box.createHorizontalGlue())
                }
                checkboxPanel.add(fileRow)
                fileRowPanels.add(fileRow)
                fileRowFolderKeys.add(folderKey)
                fileRowPaths.add(filePath)

                val onFileToggle = {
                    fileExpanded[filePath] = fileExpanded[filePath] != true
                    fileArrow.text = if (fileExpanded[filePath] == true) "▼" else "▶"
                    updateVisibility()
                }
                fileArrow.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = onFileToggle()
                })

                val scenarioCbs = mutableListOf<JCheckBox>()
                for (scenario in sf.scenarios) {
                    val key            = "${filePath}::${scenario.name}"
                    val scenarioHidden = key in hiddenScenarioSet
                    val scenStatus     = latestReports[scenario.name]?.status
                    val icon           = when (scenStatus) {
                        StepStatus.PASSED  -> "✓"; StepStatus.FAILED  -> "✗"
                        StepStatus.SKIPPED -> "⊘"; else               -> "○"
                    }
                    val sCb = JCheckBox("$icon  ${scenario.name}", !fileHidden && !scenarioHidden)
                    styleCheckbox(sCb)
                    sCb.maximumSize = Dimension(Int.MAX_VALUE, 22)
                    sCb.isEnabled   = fileCb.isSelected
                    scenarioCheckboxMap[key] = sCb
                    scenarioCbs.add(sCb)
                    folderScenCbs.add(sCb)
                    allScenarioCbs.add(sCb)

                    val scenRow = JPanel().apply {
                        layout      = BoxLayout(this, BoxLayout.X_AXIS)
                        isOpaque    = false
                        alignmentX  = Component.LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, 22)
                        isVisible   = false
                        add(Box.createHorizontalStrut(48))
                        add(sCb)
                        add(Box.createHorizontalGlue())
                    }
                    checkboxPanel.add(scenRow)
                    scenRowPanels.add(scenRow)
                    scenRowFolderKeys.add(folderKey)
                    scenRowFilePaths.add(filePath)
                    scenRowStatuses.add(scenStatus)
                }

                fileCb.addActionListener {
                    scenarioCbs.forEach { it.isEnabled = fileCb.isSelected; it.isSelected = fileCb.isSelected }
                    folderCb.isSelected = folderFileCbs.any { it.isSelected }
                    refreshCount()
                }
            }

            folderCb.addActionListener {
                val v = folderCb.isSelected
                folderFileCbs.forEach { it.isSelected = v }
                folderScenCbs.forEach { it.isSelected = v; it.isEnabled = v }
                refreshCount()
            }
        }

        refreshCount()  // initial count after all rows built

        // ── Status bulk-select toggles ────────────────────────────────────
        fun statusToggle(label: String, status: StepStatus?) = ToggleSwitchButton(label).apply {
            addActionListener {
                val targetCbs = scenRowStatuses.indices
                    .filter { scenRowStatuses[it] == status }
                    .map { allScenarioCbs[it] }
                targetCbs.forEach { it.isSelected = isSelected; if (isSelected) it.isEnabled = true }
                refreshCount()
                checkboxPanel.repaint()
            }
        }

        val swSelectAll = ToggleSwitchButton("☑ Tümünü Seç").apply {
            addActionListener {
                if (isSelected) {
                    allFolderCbs.forEach { it.isSelected = true }
                    allFileCbs.forEach   { it.isSelected = true }
                }
                allScenarioCbs.forEach { it.isSelected = isSelected; it.isEnabled = true }
                refreshCount()
                checkboxPanel.repaint()
            }
        }

        val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(swSelectAll)
            add(javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(1, 16) })
            add(statusToggle("✓ Passed", StepStatus.PASSED))
            add(statusToggle("✗ Failed", StepStatus.FAILED))
            add(statusToggle("⊘ Skipped", StepStatus.SKIPPED))
            add(statusToggle("○ Not Run", null))
        }

        // ── Dialog layout ─────────────────────────────────────────────────
        val scrollPane = JBScrollPane(innerWrap).apply {
            preferredSize             = Dimension(700, 460)
            border                    = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Görmek istediğiniz feature/spec dosyalarını ve senaryoları seçin:"), BorderLayout.WEST)
            add(countLabel, BorderLayout.EAST)
        }
        val topBar = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            border   = JBUI.Borders.empty(0, 0, 4, 0)
            add(titleRow,  BorderLayout.NORTH)
            add(filterBar, BorderLayout.SOUTH)
        }
        val dialogPanel = JPanel(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.empty(8, 8, 4, 8)
            add(topBar,     BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        val result = JOptionPane.showConfirmDialog(this, dialogPanel, "Feature & Senaryo Seçimi",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (result == JOptionPane.OK_OPTION) {
            val newHiddenFiles = fileCheckboxMap.filter { !it.value.isSelected }.keys.joinToString("\n")
            val newHiddenScenarios = scenarioCheckboxMap.filter { !it.value.isSelected }.keys.joinToString("\n")
            settings.loadState(settings.state.copy(hiddenFiles = newHiddenFiles, hiddenScenarios = newHiddenScenarios))
            refresh()
        }
    }

    private fun findCommonDirectoryPrefix(dirs: List<String>): String {
        val nonEmpty = dirs.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return ""
        val split = nonEmpty.map { it.split("/") }
        val minLen = split.minOf { it.size }
        var common = 0
        while (common < minLen && split.all { it[common] == split[0][common] }) common++
        return split[0].take(common).joinToString("/")
    }

    fun refresh(onComplete: (() -> Unit)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            allFiles = ScenarioScanner.scanProject(project)
            val settings = ScenarioExplorerSettings.getInstance(project)
            if (settings.state.reportPath.isNotEmpty()) {
                allReports = ReportReader.readReports(settings.state.reportPath)
                latestReports = allReports.mapValues { (_, reports) ->
                    reports.sortedWith(compareBy {
                        when (it.status) {
                            StepStatus.PASSED -> 0
                            StepStatus.FAILED -> 1
                            StepStatus.SKIPPED -> 2
                            else -> 3
                        }
                    }).first()
                }
            } else {
                allReports = emptyMap()
                latestReports = emptyMap()
            }
            ApplicationManager.getApplication().invokeLater {
                treePanel.updateTree(allFiles, latestReports)
                updateSummary(allFiles, latestReports)
                refreshDetailPanel()
                onDataRefreshed?.invoke(allFiles, latestReports, allReports)
                onComplete?.invoke()
            }
        }
    }

    private fun refreshDetailPanel() {
        val scenario = detailPanel.getCurrentScenario() ?: return
        val reports = allReports[scenario.name] ?: emptyList()
        detailPanel.showScenario(scenario, reports)
    }

    fun navigateToScenario(scenarioName: String) {
        treePanel.selectScenarioByName(scenarioName)
    }

    private fun updateSummary(files: List<ScenarioFile>, reports: Map<String, ReportEntry>) {
        var total = 0; var passed = 0; var failed = 0; var skipped = 0; var dur = 0L; var runCount = 0
        for (sf in files) { for (s in sf.scenarios) {
            total++
            val r = reports[s.name]
            when (r?.status) { StepStatus.PASSED -> passed++; StepStatus.FAILED -> failed++; StepStatus.SKIPPED -> skipped++; else -> {} }
            val d = r?.duration ?: 0L
            if (d > 0) { dur += d; runCount++ }
        }}
        val notRun = total - passed - failed - skipped
        val h = dur / 3_600_000; val m = (dur % 3_600_000) / 60_000; val sec = (dur % 60_000) / 1000
        val timeStr = if (h > 0) "%dh %02dm %02ds".format(h, m, sec) else "%dm %02ds".format(m, sec)
        val avgStr = if (runCount > 0) {
            val avgMs = dur / runCount
            val as_ = (avgMs % 60_000) / 1000
            val am  = avgMs / 60_000
            if (am > 0) "${am}m ${as_}s" else "${as_}s"
        } else "-"
        summaryLabel.text = "📊 $total  │  ✓ $passed  ✗ $failed  ⊘ $skipped  ○ $notRun  │  ⏱ $timeStr  │  ø $avgStr"
    }

    private fun runSelected() {
        val selected = treePanel.getCheckedScenarios()
        if (selected.isEmpty()) return

        // Refresh first, then run
        refresh {
            if (selected.size == 1) {
                val scenario = selected.first()
                val tab = createOutputTab(scenario.name.take(30))
                activeRuns.add(tab)
                appendAnsi(tab.textPane, "Running: ${scenario.name}...\n\n")

                Thread {
                    ScenarioRunner.run(
                        project, scenario,
                        onOutput = { text -> SwingUtilities.invokeLater { appendAnsi(tab.textPane, text) } },
                        onFinished = { exitCode ->
                            SwingUtilities.invokeLater {
                                appendAnsi(tab.textPane, "\n--- Finished (exit code: $exitCode) ---\n")
                                tab.markFinished()
                                refresh()
                            }
                        }
                    )?.let { handle -> tab.runHandle = handle }
                }.start()
            } else {
                val tab = createOutputTab("Batch (${selected.size})")
                activeRuns.add(tab)
                appendAnsi(tab.textPane, "Running ${selected.size} scenario(s)...\n")
                appendAnsi(tab.textPane, "Scenarios: ${selected.joinToString(", ") { it.name }}\n\n")

                Thread {
                    ScenarioRunner.runBatch(
                        project, selected,
                        onOutput = { text -> SwingUtilities.invokeLater { appendAnsi(tab.textPane, text) } },
                        onFinished = { exitCode ->
                            SwingUtilities.invokeLater {
                                appendAnsi(tab.textPane, "\n--- Batch finished (exit code: $exitCode) ---\n")
                                tab.markFinished()
                                refresh()
                            }
                        }
                    )?.let { handle -> tab.runHandle = handle }
                }.start()
            }
        }
    }

    private fun retrySelected() {
        val selected = treePanel.getCheckedScenarios()
        if (selected.isEmpty()) return

        // Retry ayarlarını sor
        val retryCountSpinner = JSpinner(javax.swing.SpinnerNumberModel(3, 1, 20, 1))
        val retryDelaySpinner = JSpinner(javax.swing.SpinnerNumberModel(120, 0, 600, 10))
        val maxParallelSpinner = JSpinner(javax.swing.SpinnerNumberModel(5, 1, 20, 1))
        val startDelaySpinner = JSpinner(javax.swing.SpinnerNumberModel(30, 0, 600, 5))
        val nextDelaySpinner = JSpinner(javax.swing.SpinnerNumberModel(120, 0, 600, 10))
        val settingsPanel = JPanel(java.awt.GridLayout(5, 2, 8, 4)).apply {
            add(com.intellij.ui.components.JBLabel("Max Paralel:"))
            add(maxParallelSpinner)
            add(com.intellij.ui.components.JBLabel("Başlatma Arası (sn):"))
            add(startDelaySpinner)
            add(com.intellij.ui.components.JBLabel("Biten Sonrası (sn):"))
            add(nextDelaySpinner)
            add(com.intellij.ui.components.JBLabel("Retry sayısı:"))
            add(retryCountSpinner)
            add(com.intellij.ui.components.JBLabel("Retry bekleme (sn):"))
            add(retryDelaySpinner)
        }
        val result = JOptionPane.showConfirmDialog(this, settingsPanel,
            "Retry Ayarları (${selected.size} senaryo)", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (result != JOptionPane.OK_OPTION) return

        val maxRetries = retryCountSpinner.value as Int
        val retrySec = retryDelaySpinner.value as Int
        val maxParallel = maxParallelSpinner.value as Int
        val startDelaySec = startDelaySpinner.value as Int
        val nextDelaySec = nextDelaySpinner.value as Int

        val tab = createOutputTab("Retry (${selected.size})")
        activeRuns.add(tab)
        retryRunning = true
        retryCancelled = false

        tab.stopButton.addActionListener { retryCancelled = true }

        Thread {
            var currentScenarios = selected.toList()
            for (attempt in 1..maxRetries) {
                if (retryCancelled || currentScenarios.isEmpty()) break

                SwingUtilities.invokeLater {
                    appendAnsi(tab.textPane, "\n═══════════════════════════════════\n")
                    appendAnsi(tab.textPane, "🔄 Retry $attempt/$maxRetries — ${currentScenarios.size} senaryo koşuluyor (max $maxParallel paralel)\n")
                    appendAnsi(tab.textPane, "═══════════════════════════════════\n\n")
                }

                // Pre-retry komutu (ilk koşum hariç)
                if (attempt > 1) {
                    val preRetryCmd = ScenarioExplorerSettings.getInstance(project).state.preRetryCommand.trim()
                    if (preRetryCmd.isNotEmpty() && !retryCancelled) {
                        SwingUtilities.invokeLater { appendAnsi(tab.textPane, "⚙ Pre-retry komutu: $preRetryCmd\n") }
                        try {
                            val basePath = project.basePath ?: ""
                            val parts = preRetryCmd.split("\\s+".toRegex())
                            val cmdLine = com.intellij.execution.configurations.GeneralCommandLine().apply {
                                workDirectory = java.io.File(basePath)
                                exePath = parts.first()
                                if (parts.size > 1) addParameters(parts.drop(1))
                            }
                            val preLatch = java.util.concurrent.CountDownLatch(1)
                            val handler = com.intellij.execution.process.OSProcessHandler(cmdLine)
                            handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                                override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                                    SwingUtilities.invokeLater { appendAnsi(tab.textPane, event.text) }
                                }
                                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                                    preLatch.countDown()
                                }
                            })
                            handler.startNotify()
                            preLatch.await()
                            SwingUtilities.invokeLater { appendAnsi(tab.textPane, "✓ Pre-retry komutu tamamlandı\n\n") }
                        } catch (e: Exception) {
                            SwingUtilities.invokeLater { appendAnsi(tab.textPane, "⚠ Pre-retry hatası: ${e.message}\n\n") }
                        }
                    }

                    // Retry bekleme
                    if (retrySec > 0 && !retryCancelled) {
                        for (i in retrySec downTo 1) {
                            if (retryCancelled) break
                            SwingUtilities.invokeLater { appendAnsi(tab.textPane, "\r⏳ Retry bekleme: ${i}sn...") }
                            Thread.sleep(1000)
                        }
                        SwingUtilities.invokeLater { appendAnsi(tab.textPane, "\n") }
                    }
                }

                if (retryCancelled) break

                // Feature bazlı grupla
                val featureGroups = currentScenarios.groupBy { it.file.path }

                // Shared target oluştur — compile bir kez yapılsın
                val basePath = project.basePath ?: break
                val sharedTarget = ScenarioRunner.createIsolatedTargetDir(basePath, "retry")
                val settings = ScenarioExplorerSettings.getInstance(project).state

                // Compile
                if (settings.buildBeforeRun) {
                    SwingUtilities.invokeLater { appendAnsi(tab.textPane, "🔨 Compile ediliyor...\n") }
                    val mvnExe = if (java.io.File(basePath, "mvnw").exists()) "./mvnw"
                                 else if (java.io.File(basePath, "mvnw.cmd").exists()) "mvnw.cmd"
                                 else "mvn"
                    val compileLatch = java.util.concurrent.CountDownLatch(1)
                    var compileOk = false
                    val cmd = com.intellij.execution.configurations.GeneralCommandLine().apply {
                        workDirectory = java.io.File(basePath)
                        exePath = mvnExe
                        addParameters("clean", "compile", "test-compile")
                        addParameter("-Dmaven.build.dir=${sharedTarget.absolutePath}")
                    }
                    try {
                        val handler = com.intellij.execution.process.OSProcessHandler(cmd)
                        handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                            override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                                SwingUtilities.invokeLater { appendAnsi(tab.textPane, event.text) }
                            }
                            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                                compileOk = event.exitCode == 0; compileLatch.countDown()
                            }
                        })
                        handler.startNotify()
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater { appendAnsi(tab.textPane, "✗ Compile hatası: ${e.message}\n") }
                        compileLatch.countDown()
                    }
                    compileLatch.await()
                    if (!compileOk || retryCancelled) {
                        SwingUtilities.invokeLater { appendAnsi(tab.textPane, "✗ Compile başarısız, retry iptal.\n") }
                        try { sharedTarget.deleteRecursively() } catch (_: Exception) {}
                        break
                    }
                    SwingUtilities.invokeLater { appendAnsi(tab.textPane, "✓ Compile tamamlandı\n\n") }
                }

                // Paralel koşum — feature grupları sırayla queue'ya girer
                val queue = java.util.concurrent.ConcurrentLinkedQueue(featureGroups.entries.toList())
                val activeCount = java.util.concurrent.atomic.AtomicInteger(0)
                val allDone = java.util.concurrent.CountDownLatch(featureGroups.size)
                var launched = 0

                while (queue.isNotEmpty() && !retryCancelled) {
                    if (activeCount.get() >= maxParallel) { Thread.sleep(1000); continue }
                    val entry = queue.poll() ?: break
                    if (retryCancelled) { allDone.countDown(); break }

                    val isInitial = launched < maxParallel
                    val delaySec = if (isInitial) startDelaySec else nextDelaySec
                    if (launched > 0 && delaySec > 0) {
                        SwingUtilities.invokeLater { appendAnsi(tab.textPane, "⏳ ${delaySec}sn bekleniyor...\n") }
                        for (i in 0 until delaySec) { if (retryCancelled) break; Thread.sleep(1000) }
                        if (retryCancelled) { allDone.countDown(); break }
                    }

                    launched++
                    activeCount.incrementAndGet()
                    val featureName = entry.value.firstOrNull()?.file?.name ?: "?"
                    SwingUtilities.invokeLater { appendAnsi(tab.textPane, "▶ Başlatılıyor: $featureName (${entry.value.size} senaryo)\n") }

                    Thread {
                        try {
                            val batchLatch = java.util.concurrent.CountDownLatch(1)
                            var batchExit = -1
                            val handle = ScenarioRunner.runBatch(
                                project, entry.value,
                                onOutput = { text -> SwingUtilities.invokeLater { appendAnsi(tab.textPane, text) } },
                                onFinished = { code -> batchExit = code; batchLatch.countDown() },
                                sharedTargetDir = sharedTarget
                            )
                            handle?.let { tab.runHandle = it }
                            batchLatch.await()
                            SwingUtilities.invokeLater {
                                val icon = if (batchExit == 0) "✓" else "✗"
                                appendAnsi(tab.textPane, "$icon $featureName tamamlandı (exit: $batchExit)\n")
                            }
                        } catch (e: Exception) {
                            SwingUtilities.invokeLater { appendAnsi(tab.textPane, "✗ Hata: $featureName — ${e.message}\n") }
                        } finally {
                            activeCount.decrementAndGet()
                            allDone.countDown()
                        }
                    }.start()
                }

                // Tüm feature'ların bitmesini bekle
                allDone.await()

                // Shared target temizle
                try { sharedTarget.deleteRecursively() } catch (_: Exception) {}

                if (retryCancelled) break

                // Refresh reports ve fail olanları bul
                val refreshLatch = java.util.concurrent.CountDownLatch(1)
                SwingUtilities.invokeLater { refresh { refreshLatch.countDown() } }
                refreshLatch.await()

                val failedScenarios = currentScenarios.filter { s ->
                    val report = latestReports[s.name]
                    report == null || report.status == StepStatus.FAILED || report.status == StepStatus.NOT_RUN
                }

                val passedCount = currentScenarios.size - failedScenarios.size
                SwingUtilities.invokeLater {
                    appendAnsi(tab.textPane, "\n--- Retry $attempt sonuç: ✓$passedCount ✗${failedScenarios.size} ---\n")
                }

                if (failedScenarios.isEmpty()) {
                    SwingUtilities.invokeLater { appendAnsi(tab.textPane, "\n✓ Tüm senaryolar geçti!\n") }
                    break
                }

                currentScenarios = failedScenarios

                if (attempt == maxRetries) {
                    SwingUtilities.invokeLater {
                        appendAnsi(tab.textPane, "\n✗ ${failedScenarios.size} senaryo $maxRetries retry sonrası hâlâ fail:\n")
                        failedScenarios.forEach { appendAnsi(tab.textPane, "  - ${it.name}\n") }
                    }
                }
            }

            SwingUtilities.invokeLater {
                tab.markFinished()
                retryRunning = false
            }
        }.start()
    }
}
