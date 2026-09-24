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
    private val treePanel = ScenarioTreePanel { scenario ->
        val reports = allReports[scenario.name] ?: emptyList()
        detailPanel.showScenario(scenario, reports)
    }
    // Tabbed output area — each run gets its own tab
    private class ScrollableOutputTabs : JPanel(BorderLayout()) {
        private data class Entry(val key: String, val header: TabHeader, val content: JComponent)
        private val entries = mutableListOf<Entry>()

        // Tab header — draws a 2px accent line at top when selected
        private inner class TabHeader(val label: JBLabel, val stopBtn: JButton, val closeBtn: JButton) : JPanel() {
            var selected = false
            init {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = true
                border = JBUI.Borders.empty(0, 8, 0, 6)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(label); add(Box.createHorizontalStrut(5)); add(stopBtn); add(closeBtn)
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (selected) {
                    val g2 = g.create() as Graphics2D
                    g2.color = UIConstants.BLUE
                    g2.fillRect(0, 0, width, 2)
                    g2.dispose()
                }
            }
        }

        private val tabBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        private val tabScroll = JBScrollPane(tabBar).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy   = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBar.preferredSize = Dimension(0, 0)  // hide scrollbar visually
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, UIConstants.subtleBorder())
            isOpaque = false; viewport.isOpaque = false
            val h = 28
            minimumSize   = Dimension(0, h)
            preferredSize = Dimension(Int.MAX_VALUE, h)
            maximumSize   = Dimension(Int.MAX_VALUE, h)
            val scroll = { e: java.awt.event.MouseWheelEvent ->
                horizontalScrollBar.value += (e.wheelRotation * 40).toInt()
            }
            addMouseWheelListener(scroll)
            tabBar.addMouseWheelListener(scroll)
        }
        private val contentArea = JPanel(CardLayout())
        private var selectedKey: String? = null

        init {
            add(tabScroll, BorderLayout.NORTH)
            add(contentArea, BorderLayout.CENTER)
        }

        fun addTab(label: JBLabel, stopBtn: JButton, closeBtn: JButton, content: JComponent): String {
            val key = "tab${entries.size}"
            label.font = label.font.deriveFont(Font.PLAIN, 11f)
            stopBtn.preferredSize = Dimension(18, 18); stopBtn.maximumSize = Dimension(18, 18)
            closeBtn.preferredSize = Dimension(18, 18); closeBtn.maximumSize = Dimension(18, 18)
            val header = TabHeader(label, stopBtn, closeBtn).apply {
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = selectKey(key)
                })
                addMouseWheelListener { e -> tabScroll.horizontalScrollBar.value += (e.wheelRotation * 40).toInt() }
            }
            entries.add(Entry(key, header, content))
            contentArea.add(content, key)
            tabBar.add(header)
            tabBar.revalidate(); tabBar.repaint()
            selectKey(key)
            return key
        }

        fun removeByContent(content: JComponent) {
            val entry = entries.find { it.content === content } ?: return
            val idx = entries.indexOf(entry)
            entries.removeAt(idx)
            tabBar.remove(entry.header)
            contentArea.remove(entry.content)
            tabBar.revalidate(); tabBar.repaint()
            if (selectedKey == entry.key) {
                val next = entries.getOrNull(idx) ?: entries.lastOrNull()
                if (next != null) selectKey(next.key) else selectedKey = null
            }
            if (entries.isEmpty()) isVisible = false
        }

        private fun selectKey(key: String) {
            selectedKey = key
            (contentArea.layout as CardLayout).show(contentArea, key)
            val bg = UIUtil.getPanelBackground()
            val dimBg = Color((bg.red - 8).coerceAtLeast(0), (bg.green - 8).coerceAtLeast(0), (bg.blue - 8).coerceAtLeast(0))
            entries.forEach { e ->
                e.header.selected = e.key == key
                e.header.background = if (e.key == key) bg else dimBg
                e.header.label.font = e.header.label.font.deriveFont(if (e.key == key) Font.BOLD else Font.PLAIN)
                e.header.repaint()
            }
        }
    }

    private val outputTabs = ScrollableOutputTabs().apply { isVisible = false }

    private val summaryLabel = JBLabel().apply {
        border = JBUI.Borders.empty(6, 10)
        foreground = UIUtil.getLabelDisabledForeground()
        font = font.deriveFont(Font.BOLD, 12f)
    }

    // Track runs
    private val activeRuns = mutableListOf<OutputTab>()

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
                                tab.markFinished(exitCode)
                                refresh()
                            }
                        }
                    )?.let { handle -> tab.runHandle = handle }
                }.start()
            }
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

        val treeScroll = JBScrollPane(treePanel.tree).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        val leftPanel = JPanel(BorderLayout()).apply {
            add(topPanel, BorderLayout.NORTH)
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
        var tabLabel: JBLabel? = null

        fun stop() {
            runHandle?.stop()
            appendAnsi(textPane, "\n--- Stopped by user ---\n")
            stopButton.isEnabled = false
            tabLabel?.let {
                it.text = "⏹ $title"
                it.foreground = UIUtil.getLabelDisabledForeground()
            }
        }

        fun markFinished(exitCode: Int = 0) {
            isFinished = true
            stopButton.isVisible = false
            tabLabel?.let {
                if (exitCode == 0) {
                    it.text = "✓ $title"
                    it.foreground = UIConstants.GREEN
                } else {
                    it.text = "✗ $title"
                    it.foreground = UIConstants.RED
                }
            }
        }
    }

    private fun createOutputTab(title: String): OutputTab {
        val tab = OutputTab(title)
        val scroll = JBScrollPane(tab.textPane).apply { border = JBUI.Borders.empty(2) }

        val tabLabel = JBLabel("▶ $title").apply { font = font.deriveFont(11f) }
        tab.tabLabel = tabLabel

        outputTabs.isVisible = true
        outputTabs.addTab(tabLabel, tab.stopButton, tab.closeButton, scroll)

        tab.closeButton.addActionListener {
            tab.stop()
            outputTabs.removeByContent(scroll)
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
        val setReportPathAction = object : AnAction("Set Report Path", "Choose report JSON folder", AllIcons.Actions.MenuOpen) {
            override fun actionPerformed(e: AnActionEvent) = chooseReportPath()
        }
        val setScanPathsAction = object : AnAction("Set Scan Paths", "Configure which feature files to show", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) = editScanPaths()
        }

        val group = DefaultActionGroup().apply {
            add(refreshAction)
            addSeparator()
            add(setReportPathAction)
            add(setScanPathsAction)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ScenarioExplorer", group, true)
        toolbar.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
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
                        StepStatus.PASSED -> "✓"; StepStatus.FAILED -> "✗"; else -> "○"
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
                            else -> 2
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
        var total = 0; var passed = 0; var failed = 0; var dur = 0L; var runCount = 0
        for (sf in files) { for (s in sf.scenarios) {
            total++
            val r = reports[s.name]
            when (r?.status) { StepStatus.PASSED -> passed++; StepStatus.FAILED -> failed++; else -> {} }
            val d = r?.duration ?: 0L
            if (d > 0) { dur += d; runCount++ }
        }}
        val notRun = total - passed - failed
        val h = dur / 3_600_000; val m = (dur % 3_600_000) / 60_000; val sec = (dur % 60_000) / 1000
        val timeStr = "%02dh %02dm %02ds".format(h, m, sec)
        val avgStr = if (runCount > 0) {
            val avgMs = dur / runCount
            val ah = avgMs / 3_600_000; val am = (avgMs % 3_600_000) / 60_000; val as_ = (avgMs % 60_000) / 1000
            "%02dh %02dm %02ds".format(ah, am, as_)
        } else "00h 00m 00s"
        summaryLabel.text = "📊 $total  │  ✓ $passed  ✗ $failed  ○ $notRun  │  ⏱ $timeStr  │  ø $avgStr"
    }
}
