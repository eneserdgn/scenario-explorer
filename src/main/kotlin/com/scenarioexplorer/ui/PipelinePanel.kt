package com.scenarioexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.scenarioexplorer.model.Scenario
import com.scenarioexplorer.model.ScenarioFile
import com.scenarioexplorer.model.StepStatus
import com.scenarioexplorer.model.ReportEntry
import com.scenarioexplorer.report.ReportReader
import com.scenarioexplorer.runner.RunHandle
import com.scenarioexplorer.runner.ScenarioRunner
import com.scenarioexplorer.runner.PipelineStateManager
import com.scenarioexplorer.settings.ScenarioExplorerSettings
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*

class PipelinePanel(private val project: Project) : JPanel(BorderLayout()) {

    private var allFiles: List<ScenarioFile> = emptyList()
    private var latestReports: Map<String, ReportEntry> = emptyMap()

    private val pipelines = mutableListOf<PipelineDefinition>()
    private var activePipelineIndex = -1

    // One independent run per pipeline — several pipelines can run at the same time
    private val runs = mutableListOf<PipelineRun>()
    private var onListPage = true

    /** Fired when a pipeline run finishes/stops so reports can be re-read. */
    var onRunFinished: (() -> Unit)? = null

    private val maxParallelSpinner = JSpinner(SpinnerNumberModel(5, 1, 20, 1))
    private val startDelaySpinner = JSpinner(SpinnerNumberModel(5, 0, 600, 5))
    private val nextDelaySpinner = JSpinner(SpinnerNumberModel(5, 0, 600, 5))
    private val chunkSizeSpinner = JSpinner(SpinnerNumberModel(1, 1, 50, 1))

    private val sourceListModel = DefaultListModel<FeatureSourceItem>()
    private val sourceList = JBList(sourceListModel)

    // Two-page layout: "list" (all pipelines) and "detail" (one pipeline's contents + run)
    private val pagesLayout = CardLayout()
    private val pages = JPanel(pagesLayout)
    private val pipelineCardsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8, 12)
    }
    private val backButton = JButton("← Pipeline'lar")
    private val detailTitleLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD, 15f) }
    private val runCardsLayout = CardLayout()
    private val runCards = JPanel(runCardsLayout)

    private val pipelineItemsModel = DefaultListModel<PipelineEntry>()
    private val pipelineItemsList = JBList(pipelineItemsModel)

    private val statusPanel = JPanel().apply {
        layout = FlowLayout(FlowLayout.LEFT, 6, 6)
        border = JBUI.Borders.empty(4)
    }
    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true; string = "Hazır"
        preferredSize = Dimension(Int.MAX_VALUE, 24)
    }
    // Single output view — switches between pipeline log and per-feature output
    private val outputScrollPane = JBScrollPane().apply { border = JBUI.Borders.empty() }
    private val outputTitleLabel = JBLabel("📋 Pipeline Log").apply {
        font = font.deriveFont(Font.BOLD, 12f); border = JBUI.Borders.empty(4, 8)
    }

    private val addPipelineBtn = JButton("+ Pipeline Oluştur")
    private val addScenariosToPipelineBtn = JButton("→ Senaryo Ekle")
    private val removeFromPipelineBtn = JButton("✗ Çıkar")
    private val moveUpBtn = JButton("▲")
    private val moveDownBtn = JButton("▼")
    private val startButton = JButton("▶ Pipeline Başlat", AllIcons.Actions.Execute)
    private val stopButton = JButton("⏹ Durdur", AllIcons.Actions.Suspend).apply { isEnabled = false }
    private val retryFailedButton = JButton("🔄 Fail Tekrar").apply { isEnabled = false }

    // === Data classes ===

    data class PipelineDefinition(var name: String, val items: MutableList<PipelineEntry> = mutableListOf())

    data class PipelineEntry(val featurePath: String, val featureName: String, val scenarioNames: List<String> = emptyList()) {
        val displayName: String get() = if (scenarioNames.isEmpty()) "📄 $featureName" else "🎯 ${featureName} (${scenarioNames.size} senaryo)"
        val key: String get() = if (scenarioNames.isEmpty()) featurePath else "$featurePath::${scenarioNames.sorted().joinToString("|")}"
        val isScenarioLevel: Boolean get() = scenarioNames.isNotEmpty()
    }

    data class FeatureSourceItem(val sf: ScenarioFile, val stats: String)

    enum class RunItemStatus { WAITING, RUNNING, PASSED, FAILED, CANCELLED }

    data class PipelineRunItem(
        val entry: PipelineEntry,
        val sf: ScenarioFile,
        val scenarios: List<Scenario>,  // koşulacak senaryolar (ilk run: hepsi, retry: sadece fail olanlar)
        var status: RunItemStatus = RunItemStatus.WAITING,
        var duration: Long = 0,
        var handle: RunHandle? = null,
        val outputArea: JTextArea = JTextArea().apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 11).let { f ->
                if (f.family == "JetBrains Mono") f else Font("Monospaced", Font.PLAIN, 11)
            }
            border = JBUI.Borders.empty(4)
        }
    )

    /** All state of one pipeline's run: its items, queue, process handles, log and captured settings. */
    private class PipelineRun(val pipeline: PipelineDefinition) {
        val items = mutableListOf<PipelineRunItem>()
        val queue = ConcurrentLinkedQueue<PipelineRunItem>()
        val handles: MutableList<RunHandle> = Collections.synchronizedList(mutableListOf())
        val running = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val activeCount = AtomicInteger(0)
        @Volatile var sharedTarget: java.io.File? = null
        @Volatile var maxParallel = 5
        @Volatile var startDelaySec = 5
        @Volatile var nextDelaySec = 5
        var viewedItem: PipelineRunItem? = null // which output is shown in the detail page (null = log)
        val logArea = JTextArea().apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 11).let { f ->
                if (f.family == "JetBrains Mono") f else Font("Monospaced", Font.PLAIN, 11)
            }
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
        }
    }

    init {
        background = UIConstants.surfaceBackground()
        sourceList.cellRenderer = FeatureSourceRenderer()
        sourceList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        sourceList.dragEnabled = true
        sourceList.transferHandler = SourceTransferHandler()
        pipelineItemsList.cellRenderer = PipelineEntryRenderer()
        pipelineItemsList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        pipelineItemsList.dropMode = DropMode.INSERT
        pipelineItemsList.dragEnabled = true
        pipelineItemsList.transferHandler = PipelineItemTransferHandler()
        buildUI()
        wireActions()
        loadPipelines()
        restorePipelineState()
        refreshPipelineList()
    }

    private fun buildUI() {
        val leftPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4); preferredSize = Dimension(380, 0)
        }
        leftPanel.add(JBLabel("Feature'lar").apply {
            font = font.deriveFont(Font.BOLD, 13f); border = JBUI.Borders.empty(4, 8)
        }, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(sourceList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        leftPanel.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(addScenariosToPipelineBtn.apply { toolTipText = "Seçili feature'lardan senaryo seçerek pipeline'a ekle" })
        }, BorderLayout.SOUTH)

        val rightPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(4) }
        val itemsPanel = JPanel(BorderLayout())
        itemsPanel.add(JBScrollPane(pipelineItemsList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        itemsPanel.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(moveUpBtn.apply { toolTipText = "Yukarı taşı" })
            add(moveDownBtn.apply { toolTipText = "Aşağı taşı" })
            add(Box.createHorizontalStrut(8))
            add(removeFromPipelineBtn.apply { toolTipText = "Pipeline'dan çıkar" })
        }, BorderLayout.SOUTH)
        val pipelineContentPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("Pipeline İçeriği").apply {
                font = font.deriveFont(Font.BOLD, 13f); border = JBUI.Borders.empty(4, 8)
            }, BorderLayout.NORTH)
            add(itemsPanel, BorderLayout.CENTER)
        }

        val statusScroll = JBScrollPane(statusPanel,
            JBScrollPane.VERTICAL_SCROLLBAR_NEVER,
            JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ).apply {
            border = BorderFactory.createTitledBorder("Çalışma Durumu")
            preferredSize = Dimension(0, 110)
            minimumSize = Dimension(0, 110)
        }
        // Output panel: title + scrollable output area
        val outputPanel = JPanel(BorderLayout()).apply {
            add(outputTitleLabel, BorderLayout.NORTH)
            add(outputScrollPane, BorderLayout.CENTER)
        }

        // Run status + output only show for the pipeline that owns the current run state
        runCards.add(JPanel(BorderLayout()).apply {
            add(statusScroll, BorderLayout.NORTH)
            add(outputPanel, BorderLayout.CENTER)
        }, "run")
        runCards.add(JPanel(GridBagLayout()).apply {
            add(JBLabel("<html><div style='text-align:center'>Bu pipeline henüz koşulmadı<br>" +
                "Alttaki \"▶ Pipeline Başlat\" ile koşabilirsin</div></html>").apply {
                foreground = UIUtil.getLabelDisabledForeground()
            })
        }, "idle")
        runCardsLayout.show(runCards, "idle")

        val rightSplitter = com.intellij.ui.JBSplitter(true, 0.45f).apply {
            firstComponent = pipelineContentPanel
            secondComponent = runCards
            dividerWidth = 6
        }
        rightPanel.add(rightSplitter, BorderLayout.CENTER)

        val settingsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JBLabel("Max Paralel:")); add(maxParallelSpinner.apply { preferredSize = Dimension(60, 28) })
            add(Box.createHorizontalStrut(6))
            add(JBLabel("Chunk:")); add(chunkSizeSpinner.apply { preferredSize = Dimension(50, 28); toolTipText = "Senaryo bazlı entry'lerde her paralele düşen senaryo sayısı" })
            add(Box.createHorizontalStrut(6))
            add(JBLabel("Başlatma Arası (sn):")); add(startDelaySpinner.apply { preferredSize = Dimension(60, 28) })
            add(Box.createHorizontalStrut(6))
            add(JBLabel("Biten Sonrası (sn):")); add(nextDelaySpinner.apply { preferredSize = Dimension(60, 28) })
            add(Box.createHorizontalStrut(12))
            add(startButton); add(stopButton); add(retryFailedButton)
        }
        val bottomPanel = JPanel(BorderLayout()).apply {
            add(progressBar, BorderLayout.NORTH); add(settingsPanel, BorderLayout.CENTER)
        }

        val mainSplitter = com.intellij.ui.JBSplitter(false, 0.30f).apply {
            firstComponent = leftPanel; secondComponent = rightPanel; dividerWidth = 8
        }

        // Page 2: pipeline detail
        val detailHeader = JPanel(BorderLayout(10, 0)).apply {
            border = JBUI.Borders.empty(6, 8)
            add(backButton.apply { isFocusPainted = false }, BorderLayout.WEST)
            add(detailTitleLabel, BorderLayout.CENTER)
        }
        val detailPage = JPanel(BorderLayout()).apply {
            add(detailHeader, BorderLayout.NORTH)
            add(mainSplitter, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }

        // Page 1: pipeline list
        val listHeader = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 16, 4, 16)
            add(JBLabel("Pipeline'lar").apply { font = font.deriveFont(Font.BOLD, 16f) }, BorderLayout.WEST)
            add(addPipelineBtn.apply { isFocusPainted = false }, BorderLayout.EAST)
        }
        val listPage = JPanel(BorderLayout()).apply {
            add(listHeader, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(pipelineCardsPanel, BorderLayout.NORTH)
            }.let { JBScrollPane(it).apply { border = JBUI.Borders.empty() } }, BorderLayout.CENTER)
        }

        pages.add(listPage, "list")
        pages.add(detailPage, "detail")
        pagesLayout.show(pages, "list")
        add(pages, BorderLayout.CENTER)
    }

    private fun wireActions() {
        addPipelineBtn.addActionListener { createNewPipeline() }
        backButton.addActionListener { showListPage() }
        addScenariosToPipelineBtn.addActionListener { addScenariosToPipeline() }
        removeFromPipelineBtn.addActionListener { removeSelectedFromPipeline() }
        moveUpBtn.addActionListener { moveSelectedItems(-1) }
        moveDownBtn.addActionListener { moveSelectedItems(1) }
        startButton.addActionListener { startPipeline() }
        stopButton.addActionListener { viewedRun()?.let { stopPipeline(it) } }
        retryFailedButton.addActionListener { viewedRun()?.let { retryFailed(it) } }
    }

    fun update(files: List<ScenarioFile>, reports: Map<String, ReportEntry>) {
        allFiles = files; latestReports = reports
        if (!anyRunning()) rebuildSourceList()
        refreshPipelineList()
    }

    private fun rebuildSourceList() {
        sourceListModel.clear()
        for (sf in allFiles) {
            val total = sf.scenarios.size
            val passed = sf.scenarios.count { latestReports[it.name]?.status == StepStatus.PASSED }
            val failed = sf.scenarios.count { latestReports[it.name]?.status == StepStatus.FAILED }
            val notRun = total - passed - failed
            sourceListModel.addElement(FeatureSourceItem(sf, "[$total | ✓$passed ✗$failed ○$notRun]"))
        }
    }

    // === PIPELINE CRUD ===

    private fun createNewPipeline() {
        val name = JOptionPane.showInputDialog(this, "Pipeline adı:", "Yeni Pipeline", JOptionPane.PLAIN_MESSAGE)?.trim()
        if (name.isNullOrBlank()) return
        if (nameTaken(name, null)) { warnNameTaken(); return }
        pipelines.add(PipelineDefinition(name))
        refreshPipelines(); savePipelines()
    }

    private fun renamePipeline(p: PipelineDefinition) {
        val name = (JOptionPane.showInputDialog(this, "Yeni ad:", "Pipeline Adını Değiştir", JOptionPane.PLAIN_MESSAGE, null, null, p.name) as? String)?.trim()
        if (name.isNullOrBlank() || name == p.name) return
        if (nameTaken(name, p)) { warnNameTaken(); return }
        // Saved run state is keyed by the name — move it along with the rename
        val oldKey = keyFor(p)
        p.name = name
        PipelineStateManager.clear(project, oldKey)
        runFor(p)?.let { persistRun(it) }
        refreshPipelines(); savePipelines()
    }

    private fun deletePipeline(p: PipelineDefinition) {
        if (JOptionPane.showConfirmDialog(this, "'${p.name}' silinsin mi?", "Pipeline Sil", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        runFor(p)?.let { runs.remove(it) }
        PipelineStateManager.clear(project, keyFor(p))
        pipelines.remove(p)
        activePipelineIndex = -1
        refreshPipelines(); savePipelines()
    }

    private fun nameTaken(name: String, except: PipelineDefinition?): Boolean =
        pipelines.any { it !== except && it.name.equals(name, ignoreCase = true) }

    private fun warnNameTaken() {
        JOptionPane.showMessageDialog(this, "Bu isimde bir pipeline zaten var.", "Pipeline", JOptionPane.WARNING_MESSAGE)
    }

    /** The pipeline currently opened on the detail page (or last opened), if any. */
    private fun getActivePipeline(): PipelineDefinition? {
        if (activePipelineIndex < 0 || activePipelineIndex >= pipelines.size) return null
        return pipelines[activePipelineIndex]
    }

    private fun refreshPipelines() {
        refreshPipelineList()
        refreshPipelineItems()
    }

    // === RUN LOOKUP ===

    /** Stable key for a pipeline's saved run state (names are unique). */
    private fun keyFor(p: PipelineDefinition): String {
        val slug = p.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "pipeline" }.take(40)
        return "${slug}_${Integer.toHexString(p.name.hashCode())}"
    }

    private fun runFor(p: PipelineDefinition?): PipelineRun? = if (p == null) null else runs.firstOrNull { it.pipeline === p }

    private fun viewedRun(): PipelineRun? = runFor(getActivePipeline())

    private fun anyRunning(): Boolean = runs.any { it.running.get() }

    // === PAGES ===

    private fun openPipeline(p: PipelineDefinition) {
        activePipelineIndex = pipelines.indexOf(p)
        onListPage = false
        detailTitleLabel.text = p.name
        refreshPipelineItems()
        showRunAreaFor(p)
        refreshDetailButtons()
        pagesLayout.show(pages, "detail")
    }

    private fun showListPage() {
        onListPage = true
        refreshPipelineList()
        pagesLayout.show(pages, "list")
    }

    /** Run status/output/progress belong to one pipeline's run — show them for the pipeline being viewed. */
    private fun showRunAreaFor(p: PipelineDefinition?) {
        val run = runFor(p)
        runCardsLayout.show(runCards, if (run != null) "run" else "idle")
        progressBar.isVisible = run != null
        if (run != null) {
            rebuildStatusPanel(run)
            updateProgressBar(run)
            showOutputFor(run, run.viewedItem)
        }
    }

    private fun refreshDetailButtons() {
        val run = viewedRun()
        val isRunning = run?.running?.get() == true
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
        retryFailedButton.isEnabled = run != null && !isRunning &&
            run.items.any { it.status == RunItemStatus.FAILED || it.status == RunItemStatus.CANCELLED }
    }

    /** Called on the EDT whenever a run's state changes: persist it and refresh whatever is showing it. */
    private fun runChanged(run: PipelineRun) {
        persistRun(run)
        if (!onListPage && run === viewedRun()) { rebuildStatusPanel(run); updateProgressBar(run) }
        refreshDetailButtons()
        refreshPipelineList()
    }

    private fun refreshPipelineList() {
        if (!onListPage) return
        pipelineCardsPanel.removeAll()
        if (pipelines.isEmpty()) {
            pipelineCardsPanel.add(JBLabel("Henüz pipeline yok. Sağ üstteki \"+ Pipeline Oluştur\" ile başla.").apply {
                foreground = UIUtil.getLabelDisabledForeground()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(16, 4)
            })
        }
        for (p in pipelines) {
            pipelineCardsPanel.add(buildPipelineCard(p))
            pipelineCardsPanel.add(Box.createVerticalStrut(8))
        }
        pipelineCardsPanel.revalidate(); pipelineCardsPanel.repaint()
    }

    private fun scenarioNamesOf(p: PipelineDefinition): Set<String> = p.items.flatMap { e ->
        if (e.isScenarioLevel) e.scenarioNames
        else allFiles.find { it.file.path == e.featurePath }?.scenarios?.map { it.name } ?: emptyList()
    }.toSet()

    private fun buildPipelineCard(p: PipelineDefinition): JComponent {
        val names = scenarioNamesOf(p)
        val run = runFor(p)
        val isRunning = run?.running?.get() == true

        // While a run is active, finished items override the (older) report status so the counts move live
        val live: Map<String, Boolean> = if (run != null && isRunning) {
            run.items
                .filter { it.status == RunItemStatus.PASSED || it.status == RunItemStatus.FAILED }
                .flatMap { item -> item.scenarios.map { it.name to (item.status == RunItemStatus.PASSED) } }
                .toMap()
        } else emptyMap()
        var passed = 0; var failed = 0; var notRun = 0
        for (n in names) {
            val ok: Boolean? = live[n] ?: when (latestReports[n]?.status) {
                StepStatus.PASSED -> true
                StepStatus.FAILED -> false
                else -> null
            }
            when (ok) { true -> passed++; false -> failed++; null -> notRun++ }
        }

        val runLine = if (run != null && isRunning) {
            val done = run.items.count { it.status != RunItemStatus.WAITING && it.status != RunItemStatus.RUNNING }
            val active = run.items.count { it.status == RunItemStatus.RUNNING }
            "▶ Çalışıyor  $done/${run.items.size} tamamlandı • $active koşuyor"
        } else null

        val counts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            fun chip(text: String, color: Color) {
                add(JBLabel(text).apply { foreground = color; font = font.deriveFont(Font.BOLD, 12f) })
            }
            chip("✓ $passed", UIConstants.GREEN); add(Box.createHorizontalStrut(14))
            chip("✗ $failed", UIConstants.RED); add(Box.createHorizontalStrut(14))
            chip("○ $notRun", UIConstants.GRAY)
        }

        val info = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(p.name).apply { font = font.deriveFont(Font.BOLD, 14f); alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(2))
            add(JBLabel("${p.items.size} kayıt • ${names.size} senaryo").apply {
                foreground = UIUtil.getLabelDisabledForeground(); font = font.deriveFont(11f); alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(4))
            add(counts)
            if (runLine != null) {
                add(Box.createVerticalStrut(4))
                add(JBLabel(runLine).apply {
                    foreground = UIConstants.BLUE; font = font.deriveFont(Font.BOLD, 11f); alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(JButton("Aç").apply { isFocusPainted = false; addActionListener { openPipeline(p) } })
            add(JButton("✎").apply {
                toolTipText = "Yeniden adlandır"; isFocusPainted = false
                addActionListener { renamePipeline(p) }
            })
            add(JButton("🗑").apply {
                toolTipText = if (isRunning) "Çalışırken silinemez" else "Pipeline sil"
                isFocusPainted = false; isEnabled = !isRunning
                addActionListener { deletePipeline(p) }
            })
        }
        return RoundedPanel(UIConstants.CARD_ARC).apply {
            layout = BorderLayout(12, 0)
            background = UIConstants.cardBackground()
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(UIConstants.CARD_ARC, UIConstants.subtleBorder()),
                JBUI.Borders.empty(12, 16)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 120)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(info, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) { openPipeline(p) }
            })
        }
    }

    private fun refreshPipelineItems() {
        pipelineItemsModel.clear()
        val p = getActivePipeline() ?: return
        for (entry in p.items) pipelineItemsModel.addElement(entry)
    }

    // === ADD / REMOVE / REORDER ===

    private fun addScenariosToPipeline() {
        val pipeline = getActivePipeline() ?: run {
            JOptionPane.showMessageDialog(this, "Önce bir pipeline oluşturun.", "Pipeline Yok", JOptionPane.WARNING_MESSAGE); return
        }
        val selected = sourceList.selectedValuesList
        if (selected.isEmpty()) return

        // Senaryo seçim dialog'u
        val checkboxPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        data class ScenarioCheck(val scenario: Scenario, val sf: ScenarioFile, val cb: JCheckBox)
        val allChecks = mutableListOf<ScenarioCheck>()

        for (item in selected) {
            val sf = item.sf
            checkboxPanel.add(JBLabel("📄 ${sf.featureName}").apply {
                font = font.deriveFont(Font.BOLD); border = JBUI.Borders.empty(6, 0, 2, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            for (scenario in sf.scenarios) {
                val report = latestReports[scenario.name]
                val statusIcon = when (report?.status) {
                    StepStatus.PASSED -> "✓"
                    StepStatus.FAILED -> "✗"
                    else -> "○"
                }
                val cb = JCheckBox("$statusIcon ${scenario.name}", true).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyLeft(20)
                }
                checkboxPanel.add(cb)
                allChecks.add(ScenarioCheck(scenario, sf, cb))
            }
        }

        // Hızlı seçim butonları
        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JButton("Hepsi").apply { addActionListener { allChecks.forEach { it.cb.isSelected = true } } })
            add(JButton("Hiçbiri").apply { addActionListener { allChecks.forEach { it.cb.isSelected = false } } })
            add(JButton("✗ Failed").apply { addActionListener {
                allChecks.forEach { it.cb.isSelected = false }
                allChecks.filter { latestReports[it.scenario.name]?.status == StepStatus.FAILED }.forEach { it.cb.isSelected = true }
            }})
            add(JButton("○ Not Run").apply { addActionListener {
                allChecks.forEach { it.cb.isSelected = false }
                allChecks.filter { val s = latestReports[it.scenario.name]?.status; s == null || s == StepStatus.NOT_RUN }.forEach { it.cb.isSelected = true }
            }})
            add(JButton("✗+○ Fail & Not Run").apply { addActionListener {
                allChecks.forEach { it.cb.isSelected = false }
                allChecks.filter { val s = latestReports[it.scenario.name]?.status; s == null || s == StepStatus.FAILED || s == StepStatus.NOT_RUN }.forEach { it.cb.isSelected = true }
            }})
        }

        val dialogPanel = JPanel(BorderLayout(0, 4)).apply {
            add(btnPanel, BorderLayout.NORTH)
            add(JBScrollPane(checkboxPanel).apply { preferredSize = Dimension(600, 400) }, BorderLayout.CENTER)
        }

        val result = JOptionPane.showConfirmDialog(this, dialogPanel,
            "Senaryo Seçimi", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (result != JOptionPane.OK_OPTION) return

        // Seçili senaryoları feature bazlı grupla
        val selectedByFeature = allChecks.filter { it.cb.isSelected }.groupBy { it.sf.file.path }
        for ((featurePath, checks) in selectedByFeature) {
            val sf = checks.first().sf
            val scenarioNames = checks.map { it.scenario.name }
            val entry = PipelineEntry(featurePath = featurePath, featureName = sf.featureName, scenarioNames = scenarioNames)
            if (pipeline.items.none { it.key == entry.key }) pipeline.items.add(entry)
        }
        refreshPipelines(); savePipelines()
    }

    /** Resolves scenario names (e.g. from the Errors tab) to their features and adds them to a pipeline the user picks. Returns true on success. */
    fun addScenarioNamesToPipeline(scenarioNames: List<String>): Boolean {
        val pipeline = when (pipelines.size) {
            0 -> {
                JOptionPane.showMessageDialog(this, "Önce Pipeline sekmesinden bir pipeline oluşturun.", "Pipeline Yok", JOptionPane.WARNING_MESSAGE)
                return false
            }
            1 -> pipelines[0]
            else -> {
                val options = pipelines.mapIndexed { i, p -> "${i + 1}. ${p.name}  (${p.items.size})" }.toTypedArray()
                val initial = options[activePipelineIndex.coerceIn(0, options.size - 1)]
                val chosen = JOptionPane.showInputDialog(this, "Hangi pipeline'a eklensin?", "Pipeline Seç",
                    JOptionPane.QUESTION_MESSAGE, null, options, initial) as? String ?: return false
                pipelines[options.indexOf(chosen)]
            }
        }
        val nameSet = scenarioNames.toSet()
        val matchedByFeature = allFiles.mapNotNull { sf ->
            val matched = sf.scenarios.filter { it.name in nameSet }
            if (matched.isEmpty()) null else sf to matched
        }
        if (matchedByFeature.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Eşleşen senaryo bulunamadı.", "Pipeline'a Ekle", JOptionPane.WARNING_MESSAGE); return false
        }
        for ((sf, scenarios) in matchedByFeature) {
            val entry = PipelineEntry(featurePath = sf.file.path, featureName = sf.featureName, scenarioNames = scenarios.map { it.name })
            if (pipeline.items.none { it.key == entry.key }) pipeline.items.add(entry)
        }
        refreshPipelines(); savePipelines()
        return true
    }

    private fun removeSelectedFromPipeline() {
        val pipeline = getActivePipeline() ?: return
        for (idx in pipelineItemsList.selectedIndices.sortedDescending()) {
            if (idx in pipeline.items.indices) pipeline.items.removeAt(idx)
        }
        refreshPipelines(); savePipelines()
    }

    private fun moveSelectedItems(direction: Int) {
        val pipeline = getActivePipeline() ?: return
        val indices = pipelineItemsList.selectedIndices.toList()
        if (indices.isEmpty()) return
        val sorted = if (direction < 0) indices.sorted() else indices.sortedDescending()
        for (idx in sorted) {
            val target = idx + direction
            if (target < 0 || target >= pipeline.items.size) return
            Collections.swap(pipeline.items, idx, target)
        }
        refreshPipelineItems()
        pipelineItemsList.selectedIndices = indices.map { it + direction }.toIntArray()
        savePipelines()
    }

    // === PIPELINE EXECUTION ===

    private fun startPipeline() {
        val pipeline = getActivePipeline() ?: return
        if (pipeline.items.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Pipeline boş. Önce soldan senaryo ekleyin.", "Pipeline Boş", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        if (runFor(pipeline)?.running?.get() == true) return

        // A finished run of this pipeline is replaced by the new one
        runFor(pipeline)?.let { runs.remove(it) }
        PipelineStateManager.clear(project, keyFor(pipeline))

        val run = PipelineRun(pipeline)
        runs.add(run)
        run.running.set(true)
        run.maxParallel = maxParallelSpinner.value as Int
        run.startDelaySec = startDelaySpinner.value as Int
        run.nextDelaySec = nextDelaySpinner.value as Int
        showRunAreaFor(pipeline)

        // Senaryo bazlı entry'leri ve feature bazlı entry'leri ayır
        val scenarioPool = mutableListOf<Pair<ScenarioFile, Scenario>>() // havuz: tüm senaryo bazlı senaryolar
        val featureEntries = mutableListOf<Pair<PipelineEntry, ScenarioFile>>()

        for (entry in pipeline.items) {
            val sf = allFiles.find { it.file.path == entry.featurePath }
            if (sf == null) { log(run, "⚠ Bulunamadı: ${entry.featureName}"); continue }

            if (entry.isScenarioLevel) {
                val selectedScenarios = sf.scenarios.filter { it.name in entry.scenarioNames }
                if (selectedScenarios.isEmpty()) { log(run, "⚠ Senaryo bulunamadı: ${entry.featureName}"); continue }
                for (s in selectedScenarios) scenarioPool.add(sf to s)
            } else {
                featureEntries.add(entry to sf)
            }
        }

        // Senaryo havuzunu chunk boyutuna göre paketle
        if (scenarioPool.isNotEmpty()) {
            val chunkSize = chunkSizeSpinner.value as Int
            val chunks = scenarioPool.chunked(chunkSize)
            for ((idx, chunk) in chunks.withIndex()) {
                val scenarios = chunk.map { it.second }
                val featureNames = chunk.map { it.first.featureName }.distinct()
                val chunkName = "Paket ${idx + 1} (${scenarios.size} senaryo — ${featureNames.joinToString(", ") { it.take(15) }})"
                // Chunk'taki ilk feature'ı referans olarak kullan
                val primarySf = chunk.first().first
                val scenarioNames = scenarios.map { it.name }
                val chunkEntry = PipelineEntry(primarySf.file.path, chunkName, scenarioNames)
                run.items.add(PipelineRunItem(chunkEntry, primarySf, scenarios))
            }
        }

        // Feature bazlı entry'leri olduğu gibi ekle
        for ((entry, sf) in featureEntries) {
            run.items.add(PipelineRunItem(entry, sf, sf.scenarios))
        }
        if (run.items.isEmpty()) {
            log(run, "Çalıştırılacak feature bulunamadı.")
            run.running.set(false); runChanged(run)
            return
        }

        run.queue.addAll(run.items)

        // Reset output areas
        for (item in run.items) {
            item.outputArea.text = ""; item.outputArea.background = UIUtil.getPanelBackground()
        }
        showOutputFor(run, null) // start with pipeline log view

        runChanged(run)
        log(run, "Pipeline '${pipeline.name}' başlatıldı: ${run.items.size} feature, max ${run.maxParallel} paralel")

        // Shared target: compile once, then run all features against it
        Thread {
            val basePath = project.basePath ?: return@Thread
            val sharedTarget = ScenarioRunner.createIsolatedTargetDir(basePath, "pipeline")
            run.sharedTarget = sharedTarget
            log(run, "🔨 Ortak target oluşturuluyor ve compile ediliyor...")

            val settings = ScenarioExplorerSettings.getInstance(project).state
            val mvnExe = ScenarioRunner.resolveMvnExecutable(basePath)

            if (settings.buildBeforeRun) {
                val compileLatch = java.util.concurrent.CountDownLatch(1)
                var compileOk = false
                val cmd = com.intellij.execution.configurations.GeneralCommandLine().apply {
                    workDirectory = java.io.File(basePath)
                    exePath = mvnExe
                    addParameters("compile", "test-compile")
                    addParameters(ScenarioRunner.isolatedBuildParams(sharedTarget))
                }
                try {
                    val handler = com.intellij.execution.process.OSProcessHandler(cmd)
                    handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                        override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                            SwingUtilities.invokeLater { log(run, event.text.trimEnd()) }
                        }
                        override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                            compileOk = event.exitCode == 0
                            compileLatch.countDown()
                        }
                    })
                    handler.startNotify()
                } catch (e: Exception) {
                    log(run, "✗ Compile hatası: ${e.message}")
                    compileLatch.countDown()
                }
                compileLatch.await()

                if (!compileOk || run.cancelled.get()) {
                    log(run, "✗ Compile başarısız, pipeline iptal ediliyor.")
                    SwingUtilities.invokeLater {
                        run.items.forEach { it.status = RunItemStatus.CANCELLED }
                        run.running.set(false); runChanged(run)
                        onRunFinished?.invoke()
                    }
                    cleanupSharedTarget(run)
                    return@Thread
                }
                log(run, "✓ Compile tamamlandı, feature'lar koşuluyor...")
            }

            // The shared target is cleaned up when the run completes (see checkPipelineComplete),
            // not here — feedQueue returns as soon as the last item has been launched.
            feedQueue(run)
        }.start()
    }

    private fun feedQueue(run: PipelineRun) {
        val maxParallel = run.maxParallel
        val startDelaySec = run.startDelaySec
        val nextDelaySec = run.nextDelaySec
        var launched = 0

        while (run.queue.isNotEmpty() && !run.cancelled.get()) {
            if (run.activeCount.get() >= maxParallel) { Thread.sleep(1000); continue }
            val item = run.queue.poll() ?: break
            if (run.cancelled.get()) { item.status = RunItemStatus.CANCELLED; SwingUtilities.invokeLater { runChanged(run) }; break }

            val isInitial = launched < maxParallel
            val delaySec = if (isInitial) startDelaySec else nextDelaySec
            if (launched > 0 && delaySec > 0) {
                log(run, "⏳ ${delaySec}sn bekleniyor...")
                for (i in 0 until delaySec) { if (run.cancelled.get()) break; Thread.sleep(1000) }
                if (run.cancelled.get()) { item.status = RunItemStatus.CANCELLED; SwingUtilities.invokeLater { runChanged(run) }; break }
            }

            launched++; run.activeCount.incrementAndGet()
            item.status = RunItemStatus.RUNNING
            SwingUtilities.invokeLater { runChanged(run) }
            val startTime = System.currentTimeMillis()
            log(run, "▶ Başlatılıyor: ${item.entry.featureName}")

            Thread {
                try {
                    val handle = ScenarioRunner.runBatch(project, item.scenarios,
                        onOutput = { text -> SwingUtilities.invokeLater { item.outputArea.append(text); item.outputArea.caretPosition = item.outputArea.document.length } },
                        onFinished = { exitCode ->
                            item.duration = System.currentTimeMillis() - startTime
                            SwingUtilities.invokeLater {
                                item.status = if (exitCode == 0) RunItemStatus.PASSED else RunItemStatus.FAILED
                                log(run, if (exitCode == 0) "✓ Tamamlandı: ${item.entry.featureName} (${formatDuration(item.duration)})" else "✗ Fail: ${item.entry.featureName} (exit: $exitCode, ${formatDuration(item.duration)})")
                                run.activeCount.decrementAndGet(); runChanged(run); checkPipelineComplete(run)
                            }
                        },
                        sharedTargetDir = run.sharedTarget
                    )
                    item.handle = handle
                    if (handle != null) synchronized(run.handles) { run.handles.add(handle) }
                } catch (e: Exception) {
                    item.status = RunItemStatus.FAILED; item.duration = System.currentTimeMillis() - startTime
                    run.activeCount.decrementAndGet(); log(run, "✗ Hata: ${item.entry.featureName} — ${e.message}")
                    SwingUtilities.invokeLater { runChanged(run); checkPipelineComplete(run) }
                }
            }.start()
        }
    }

    private fun stopPipeline(run: PipelineRun) {
        run.cancelled.set(true); log(run, "⏹ Pipeline durduruluyor...")
        while (run.queue.isNotEmpty()) run.queue.poll()?.status = RunItemStatus.CANCELLED
        synchronized(run.handles) { run.handles.forEach { it.stop() }; run.handles.clear() }
        run.items.filter { it.status == RunItemStatus.RUNNING }.forEach { it.status = RunItemStatus.CANCELLED }
        run.running.set(false); runChanged(run)
        cleanupSharedTarget(run)
        log(run, "⏹ Pipeline durduruldu.")
        onRunFinished?.invoke()
    }

    private fun retryFailed(run: PipelineRun) {
        val failedItems = run.items.filter { it.status == RunItemStatus.FAILED || it.status == RunItemStatus.CANCELLED }
        if (failedItems.isEmpty()) { log(run, "Tekrar koşulacak feature yok."); return }

        // Raporları tekrar oku, fail olan case'leri tespit et
        val settings = ScenarioExplorerSettings.getInstance(project)
        val freshReports: Map<String, ReportEntry> = if (settings.state.reportPath.isNotEmpty()) {
            val allReps = ReportReader.readReports(settings.state.reportPath)
            allReps.mapValues { (_, reports) -> reports.first() }
        } else latestReports

        // Yeni run item'lar oluştur — her fail feature için sadece fail/not_run case'leri
        val retryItems = mutableListOf<PipelineRunItem>()
        for (item in failedItems) {
            val failedScenarios = item.sf.scenarios.filter { scenario ->
                val report = freshReports[scenario.name]
                // fail, not_run veya raporu olmayan case'leri tekrar koş
                report == null || report.status == StepStatus.FAILED || report.status == StepStatus.NOT_RUN
            }
            if (failedScenarios.isNotEmpty()) {
                val retryItem = PipelineRunItem(item.entry, item.sf, failedScenarios)
                retryItems.add(retryItem)
                log(run, "🔄 ${item.entry.featureName}: ${failedScenarios.size}/${item.sf.scenarios.size} case tekrar koşulacak")
            }
        }

        if (retryItems.isEmpty()) { log(run, "Tekrar koşulacak fail case bulunamadı."); return }

        // Eski fail item'ları listeden çıkar, yeni retry item'ları ekle
        run.items.removeAll(failedItems.toSet())
        run.items.addAll(retryItems)

        run.queue.clear(); run.queue.addAll(retryItems)

        // Reset retry output areas
        for (item in retryItems) {
            item.outputArea.text = ""; item.outputArea.background = UIUtil.getPanelBackground()
        }

        run.maxParallel = maxParallelSpinner.value as Int
        run.startDelaySec = startDelaySpinner.value as Int
        run.nextDelaySec = nextDelaySpinner.value as Int
        run.running.set(true); run.cancelled.set(false); run.activeCount.set(0); run.handles.clear()
        runChanged(run)
        log(run, "🔄 Fail tekrar başlatıldı: ${retryItems.size} feature, toplam ${retryItems.sumOf { it.scenarios.size }} case")
        Thread { feedQueue(run) }.start()
    }

    /** Bir run'ın state'ini diske kaydet — her status değişikliğinde çağrılır */
    private fun persistRun(run: PipelineRun) {
        val key = keyFor(run.pipeline)
        val items = run.items.mapIndexed { idx, item ->
            // Her item'ın output'unu da kaydet
            PipelineStateManager.saveItemOutput(project, key, idx, item.outputArea.text)
            PipelineStateManager.RunItemState(
                featurePath = item.entry.featurePath,
                featureName = item.entry.featureName,
                scenarioNames = item.entry.scenarioNames,
                status = item.status.name,
                duration = item.duration,
                scenarioCount = item.scenarios.size
            )
        }
        PipelineStateManager.saveState(project, key, run.pipeline.name, run.running.get(), items)
        PipelineStateManager.saveLog(project, key, run.logArea.text)
    }

    /** IDE yeniden açıldığında her pipeline'ın son koşum state'ini geri yükle */
    private fun restorePipelineState() {
        for ((key, state) in PipelineStateManager.listStates(project)) {
            val pipeline = pipelines.firstOrNull { it.name == state.pipelineName && keyFor(it) == key } ?: continue
            val run = PipelineRun(pipeline)

            // Log'u geri yükle
            val savedLog = PipelineStateManager.loadLog(project, key)
            if (savedLog.isNotEmpty()) {
                run.logArea.text = savedLog
                run.logArea.caretPosition = run.logArea.document.length
            }

            // Run item'ları geri oluştur
            for ((idx, itemState) in state.items.withIndex()) {
                val sf = allFiles.find { it.file.path == itemState.featurePath }
                val scenarios = if (sf != null && itemState.scenarioNames.isNotEmpty()) {
                    sf.scenarios.filter { it.name in itemState.scenarioNames }
                } else {
                    sf?.scenarios ?: emptyList()
                }
                val entry = PipelineEntry(itemState.featurePath, itemState.featureName, itemState.scenarioNames)
                val restoredStatus = try { RunItemStatus.valueOf(itemState.status) } catch (_: Exception) { RunItemStatus.CANCELLED }
                // Eğer RUNNING olarak kaydedilmişse ama artık process yok, CANCELLED olarak göster
                val finalStatus = if (restoredStatus == RunItemStatus.RUNNING) RunItemStatus.CANCELLED else restoredStatus

                val runItem = PipelineRunItem(
                    entry = entry,
                    sf = sf ?: ScenarioFile(java.io.File(itemState.featurePath), itemState.featureName, emptyList(), com.scenarioexplorer.model.ScenarioType.CUCUMBER, emptyList()),
                    scenarios = scenarios,
                    status = finalStatus,
                    duration = itemState.duration
                )
                // Output'u geri yükle
                val savedOutput = PipelineStateManager.loadItemOutput(project, key, idx)
                if (savedOutput.isNotEmpty()) {
                    runItem.outputArea.text = savedOutput
                }
                run.items.add(runItem)
            }

            if (run.items.isEmpty()) continue
            runs.add(run)
            // Eğer running olarak kaydedilmişse ama artık process yok, uyarı göster
            if (state.running) log(run, "⚠ IDE yeniden açıldı — önceki pipeline process'leri artık bağlı değil")
        }
    }

    private fun checkPipelineComplete(run: PipelineRun) {
        if (run.items.all { it.status != RunItemStatus.WAITING && it.status != RunItemStatus.RUNNING } && run.running.get()) {
            run.running.set(false)
            val passed = run.items.count { it.status == RunItemStatus.PASSED }
            val failed = run.items.count { it.status == RunItemStatus.FAILED }
            val cancelledN = run.items.count { it.status == RunItemStatus.CANCELLED }
            cleanupSharedTarget(run)
            runChanged(run)
            log(run, "═══════════════════════════════════")
            log(run, "Pipeline tamamlandı: ✓$passed ✗$failed ⊘$cancelledN | Toplam: ${formatDuration(run.items.sumOf { it.duration })}")
            log(run, "═══════════════════════════════════")
            onRunFinished?.invoke()
        }
    }

    private fun cleanupSharedTarget(run: PipelineRun) {
        run.sharedTarget?.let { dir ->
            try { dir.deleteRecursively() } catch (_: Exception) {}
            run.sharedTarget = null
        }
    }

    /** Switch the output view to a specific run item's output, or the pipeline log if item is null */
    private fun showOutputFor(run: PipelineRun, item: PipelineRunItem?) {
        run.viewedItem = item
        if (run !== viewedRun()) return
        if (item == null) {
            outputTitleLabel.text = "📋 Pipeline Log"
            outputScrollPane.setViewportView(run.logArea)
        } else {
            outputTitleLabel.text = "📋 ${item.entry.featureName} (${item.scenarios.size} case)"
            outputScrollPane.setViewportView(item.outputArea)
        }
    }

    // === STATUS PANEL ===

    private fun rebuildStatusPanel(run: PipelineRun) {
        statusPanel.removeAll()

        // Pipeline Log card — always first
        val logCard = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(120, 70); minimumSize = Dimension(120, 70); maximumSize = Dimension(120, 70)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIConstants.BLUE, 1, true), JBUI.Borders.empty(6, 8))
            isOpaque = true; background = UIConstants.cardBackground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(JBLabel("📋 Pipeline Log").apply { font = font.deriveFont(Font.BOLD, 11f); alignmentX = Component.LEFT_ALIGNMENT })
        }
        logCard.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { showOutputFor(run, null) }
        })
        statusPanel.add(logCard)

        for ((idx, item) in run.items.withIndex()) {
            val bgColor = when (item.status) {
                RunItemStatus.RUNNING -> Color(UIConstants.BLUE.red, UIConstants.BLUE.green, UIConstants.BLUE.blue, 40)
                RunItemStatus.PASSED -> Color(UIConstants.GREEN.red, UIConstants.GREEN.green, UIConstants.GREEN.blue, 40)
                RunItemStatus.FAILED -> Color(UIConstants.RED.red, UIConstants.RED.green, UIConstants.RED.blue, 40)
                RunItemStatus.CANCELLED -> Color(UIConstants.GRAY.red, UIConstants.GRAY.green, UIConstants.GRAY.blue, 40)
                else -> UIConstants.cardBackground()
            }
            val fgColor = when (item.status) {
                RunItemStatus.PASSED -> UIConstants.GREEN
                RunItemStatus.FAILED -> UIConstants.RED
                RunItemStatus.RUNNING -> UIConstants.BLUE
                else -> UIUtil.getLabelDisabledForeground()
            }
            val icon = when (item.status) {
                RunItemStatus.WAITING -> "⏳"; RunItemStatus.RUNNING -> "▶"; RunItemStatus.PASSED -> "✓"; RunItemStatus.FAILED -> "✗"; RunItemStatus.CANCELLED -> "⊘"
            }
            val statusText = when (item.status) {
                RunItemStatus.RUNNING -> "koşuyor..."; RunItemStatus.PASSED -> formatDuration(item.duration)
                RunItemStatus.FAILED -> "FAIL ${formatDuration(item.duration)}"; RunItemStatus.CANCELLED -> "iptal"; RunItemStatus.WAITING -> "sırada"
            }

            val card = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                preferredSize = Dimension(160, 70); minimumSize = Dimension(160, 70); maximumSize = Dimension(160, 70)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(fgColor, 1, true), JBUI.Borders.empty(6, 8))
                isOpaque = true; background = bgColor
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(JBLabel("$icon ${idx + 1}. ${item.entry.featureName.take(18)}").apply {
                    foreground = fgColor; font = font.deriveFont(Font.BOLD, 11f); alignmentX = Component.LEFT_ALIGNMENT
                })
                add(Box.createVerticalStrut(2))
                add(JBLabel("${item.scenarios.size} case").apply {
                    foreground = UIUtil.getLabelDisabledForeground(); font = font.deriveFont(10f); alignmentX = Component.LEFT_ALIGNMENT
                })
                add(Box.createVerticalStrut(2))
                add(JBLabel(statusText).apply {
                    foreground = fgColor; font = font.deriveFont(Font.BOLD, 10f); alignmentX = Component.LEFT_ALIGNMENT
                })
            }
            val clickItem = item
            card.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) { showOutputFor(run, clickItem) }
            })
            statusPanel.add(card)
        }
        statusPanel.revalidate(); statusPanel.repaint()
    }

    private fun updateProgressBar(run: PipelineRun) {
        val total = run.items.size
        if (total == 0) { progressBar.value = 0; progressBar.string = "Hazır"; return }
        val done = run.items.count { it.status != RunItemStatus.WAITING && it.status != RunItemStatus.RUNNING }
        val runN = run.items.count { it.status == RunItemStatus.RUNNING }
        progressBar.value = (done * 100) / total; progressBar.string = "$done/$total tamamlandı ($runN koşuyor)"
    }

    // === PERSISTENCE ===

    private fun savePipelines() {
        val sb = StringBuilder()
        for (p in pipelines) {
            sb.append("PIPELINE:${p.name}\n")
            for (item in p.items) {
                if (item.scenarioNames.isEmpty()) {
                    sb.append("FEATURE:${item.featurePath}\t${item.featureName}\n")
                } else {
                    sb.append("SCENARIOS:${item.featurePath}\t${item.featureName}\t${item.scenarioNames.joinToString("||")}\n")
                }
            }
        }
        ScenarioExplorerSettings.getInstance(project).let { it.loadState(it.state.copy(savedPipelines = sb.toString())) }
    }

    private fun loadPipelines() {
        val data = ScenarioExplorerSettings.getInstance(project).state.savedPipelines
        if (data.isBlank()) return
        pipelines.clear()
        var current: PipelineDefinition? = null
        for (line in data.lines()) {
            when {
                line.startsWith("PIPELINE:") -> { current = PipelineDefinition(line.removePrefix("PIPELINE:")); pipelines.add(current) }
                line.startsWith("SCENARIOS:") && current != null -> {
                    val parts = line.removePrefix("SCENARIOS:").split("\t")
                    if (parts.size >= 3) {
                        val scenarioNames = parts[2].split("||").filter { it.isNotEmpty() }
                        current.items.add(PipelineEntry(parts[0], parts[1], scenarioNames))
                    }
                }
                line.startsWith("FEATURE:") && current != null -> {
                    val parts = line.removePrefix("FEATURE:").split("\t")
                    if (parts.size >= 2) current.items.add(PipelineEntry(parts[0], parts[1]))
                }
            }
        }
        // Names identify pipelines (and their saved run state) — make older duplicates unique
        val seen = mutableSetOf<String>()
        for (p in pipelines) {
            var candidate = p.name; var n = 2
            while (!seen.add(candidate.lowercase())) candidate = "${p.name} ($n)".also { n++ }
            p.name = candidate
        }
        activePipelineIndex = -1
        refreshPipelines()
    }

    // === RENDERERS ===

    private inner class FeatureSourceRenderer : ListCellRenderer<FeatureSourceItem> {
        private val label = JBLabel()
        override fun getListCellRendererComponent(list: JList<out FeatureSourceItem>, value: FeatureSourceItem?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            if (value == null) return label
            label.text = "📄 ${value.sf.featureName}  ${value.stats}"
            label.font = label.font.deriveFont(Font.PLAIN, 12f)
            label.border = JBUI.Borders.empty(4, 6)
            label.icon = null
            label.isOpaque = true
            if (isSelected) { label.background = list.selectionBackground; label.foreground = list.selectionForeground }
            else { label.background = list.background; label.foreground = UIUtil.getLabelForeground() }
            return label
        }
    }

    private inner class PipelineEntryRenderer : ListCellRenderer<PipelineEntry> {
        private val label = JBLabel()
        override fun getListCellRendererComponent(list: JList<out PipelineEntry>, value: PipelineEntry?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            if (value == null) return label
            label.text = "${index + 1}. ${value.displayName}"
            label.font = label.font.deriveFont(Font.PLAIN, 12f)
            label.border = JBUI.Borders.empty(3, 6)
            label.isOpaque = true
            if (isSelected) { label.background = list.selectionBackground; label.foreground = list.selectionForeground }
            else {
                label.background = list.background
                label.foreground = if (value.isScenarioLevel) UIConstants.BLUE else UIUtil.getLabelForeground()
            }
            return label
        }
    }

    // === DRAG & DROP ===

    private inner class SourceTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent?) = COPY
        override fun createTransferable(c: JComponent?): Transferable? {
            val selected = sourceList.selectedValuesList
            if (selected.isEmpty()) return null
            return StringSelection(selected.joinToString("\n") { "FEATURE:${it.sf.file.path}\t${it.sf.featureName}" })
        }
    }

    private inner class PipelineItemTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent?) = MOVE
        override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.stringFlavor)

        override fun importData(support: TransferSupport): Boolean {
            val pipeline = getActivePipeline() ?: return false
            if (!support.isDrop) return false
            val data = support.transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return false
            val insertIndex = (support.dropLocation as? JList.DropLocation)?.index ?: pipeline.items.size
            val entries = mutableListOf<PipelineEntry>()
            for (line in data.lines()) {
                if (line.startsWith("FEATURE:")) {
                    val parts = line.removePrefix("FEATURE:").split("\t")
                    if (parts.size >= 2) entries.add(PipelineEntry(parts[0], parts[1]))
                }
            }
            var idx = insertIndex
            for (entry in entries) {
                if (pipeline.items.none { it.key == entry.key }) { pipeline.items.add(idx.coerceAtMost(pipeline.items.size), entry); idx++ }
            }
            refreshPipelines(); savePipelines()
            return entries.isNotEmpty()
        }

        override fun createTransferable(c: JComponent?): Transferable? {
            val selected = pipelineItemsList.selectedValuesList
            if (selected.isEmpty()) return null
            return StringSelection(selected.joinToString("\n") { "FEATURE:${it.featurePath}\t${it.featureName}" })
        }

        override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {}
    }

    // === UTILS ===

    private fun log(run: PipelineRun, message: String) {
        val time = SimpleDateFormat("HH:mm:ss").format(Date())
        SwingUtilities.invokeLater {
            run.logArea.append("[$time] $message\n")
            run.logArea.caretPosition = run.logArea.document.length
            PipelineStateManager.saveLog(project, keyFor(run.pipeline), run.logArea.text)
        }
    }

    private fun formatDuration(ms: Long): String {
        val h = ms / 3_600_000; val m = (ms % 3_600_000) / 60_000; val s = (ms % 60_000) / 1000
        return "%02dh %02dm %02ds".format(h, m, s)
    }
}
