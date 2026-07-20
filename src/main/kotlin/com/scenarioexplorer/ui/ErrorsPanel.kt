package com.scenarioexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.scenarioexplorer.model.ReportEntry
import com.scenarioexplorer.model.StepStatus
import com.scenarioexplorer.parser.ErrorParser
import java.awt.*
import java.util.Base64
import javax.swing.*

class ErrorsPanel : JPanel(BorderLayout()) {

    var onNavigateToScenario: ((String) -> Unit)? = null
    var onCountUpdated: ((Int, Int) -> Unit)? = null

    // ── Data ────────────────────────────────────────────────────────

    private data class ErrorOccurrence(
        val scenarioName: String,
        val timestamp: String?,
        val stepText: String,
        val screenshotBase64: String?
    )

    private data class ErrorGroup(
        val summary: String,
        val groupingKey: String,
        val occurrences: List<ErrorOccurrence>
    ) {
        val shortMessage: String = summary.lines().firstOrNull()?.trim()?.take(80) ?: ""
        val uniqueScenarios: Int = occurrences.map { it.scenarioName }.toSet().size
    }

    // ── UI ──────────────────────────────────────────────────────────

    private val errorListModel = DefaultListModel<ErrorGroup>()
    private val errorList = JBList(errorListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ErrorGroupCellRenderer()
    }

    // BoxLayout içerik paneli — doğal yüksekliğini alır, viewport'u doldurmaz
    private val detailContent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIConstants.surfaceBackground()
        border = JBUI.Borders.empty(10, 12)
    }
    private val detailScroll = JBScrollPane(
        JPanel(BorderLayout()).apply {
            background = UIConstants.surfaceBackground()
            add(detailContent, BorderLayout.NORTH)
        }
    ).apply {
        border = JBUI.Borders.empty()
        background = UIConstants.surfaceBackground()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val emptyStatePanel = JPanel(GridBagLayout()).apply {
        isOpaque = false
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(AllIcons.General.InspectionsOK).apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(12))
            add(JBLabel("Hata bulunamadı").apply {
                font = font.deriveFont(Font.BOLD, 14f)
                foreground = UIUtil.getLabelDisabledForeground()
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(4))
            add(JBLabel("Tüm senaryolar başarılı veya henüz çalıştırılmadı").apply {
                foreground = UIUtil.getLabelDisabledForeground()
                font = font.deriveFont(12f)
                alignmentX = Component.CENTER_ALIGNMENT
            })
        })
    }

    private val mainCard = CardLayout()
    private val mainPanel = JPanel(mainCard)

    init {
        background = UIConstants.surfaceBackground()

        val leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(300, 0)
            add(JBScrollPane(errorList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }

        val splitter = JBSplitter(false, 0.35f).apply {
            firstComponent = leftPanel
            secondComponent = detailScroll
            dividerWidth = 6
        }

        mainPanel.add(splitter, "content")
        mainPanel.add(emptyStatePanel, "empty")
        mainCard.show(mainPanel, "empty")
        add(mainPanel, BorderLayout.CENTER)

        errorList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = errorList.selectedValue ?: return@addListSelectionListener
                showErrorDetail(selected)
            }
        }
    }

    // ── Data update ─────────────────────────────────────────────────

    fun update(reports: Map<String, ReportEntry>) {
        data class GroupAccumulator(val summary: String, val occurrences: MutableList<ErrorOccurrence>)
        val errorMap = linkedMapOf<String, GroupAccumulator>()

        for ((scenarioName, entry) in reports) {
            if (entry.status != StepStatus.FAILED) continue
            for (step in entry.steps) {
                val raw = step.errorMessage?.trim() ?: continue
                if (raw.isBlank()) continue
                val parsed = ErrorParser.parse(raw)
                if (parsed.summary.isBlank()) continue
                val acc = errorMap.getOrPut(parsed.groupingKey) {
                    GroupAccumulator(parsed.summary, mutableListOf())
                }
                acc.occurrences.add(
                    ErrorOccurrence(
                        scenarioName = scenarioName,
                        timestamp = entry.timestamp,
                        stepText = step.text,
                        screenshotBase64 = step.screenshotBase64
                    )
                )
            }
        }

        val groups = errorMap.entries
            .map { (key, acc) -> ErrorGroup(summary = acc.summary, groupingKey = key, occurrences = acc.occurrences) }
            .sortedWith(compareBy<ErrorGroup> { it.summary.startsWith("TimeoutException") }
                .thenByDescending { it.occurrences.size })

        SwingUtilities.invokeLater {
            val previousKey = errorList.selectedValue?.groupingKey
            errorListModel.clear()
            groups.forEach { errorListModel.addElement(it) }

            onCountUpdated?.invoke(groups.size, groups.sumOf { it.uniqueScenarios })

            if (groups.isEmpty()) {
                detailContent.removeAll()
                mainCard.show(mainPanel, "empty")
            } else {
                mainCard.show(mainPanel, "content")
                val restoreIdx = groups.indexOfFirst { it.groupingKey == previousKey }
                errorList.selectedIndex = if (restoreIdx >= 0) restoreIdx else 0
            }
        }
    }

    // ── Detail rendering ─────────────────────────────────────────────

    private fun showErrorDetail(group: ErrorGroup) {
        detailContent.removeAll()

        // ── Hata mesajı kartı ───────────────────────────────────────
        // Satır sayısına göre yükseklik — boşluk kalmaz
        val lineCount = group.summary.lines().size
        val cardHeight = maxOf(lineCount * 18 + 24, 100)

        val errorText = JTextArea(group.summary).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font("Monospaced", Font.PLAIN, 11)
            foreground = UIConstants.RED
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        val errorCard = RoundedPanel(UIConstants.CARD_ARC).apply {
            layout = BorderLayout()
            background = UIConstants.RED.let { Color(it.red, it.green, it.blue, 15) }
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(UIConstants.CARD_ARC, UIConstants.RED.let { Color(it.red, it.green, it.blue, 50) }),
                JBUI.Borders.empty(8, 12)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, cardHeight)
            preferredSize = Dimension(100, cardHeight)
            add(errorText, BorderLayout.CENTER)
        }

        detailContent.add(sectionLabel("Hata Mesajı"))
        detailContent.add(errorCard)
        detailContent.add(Box.createVerticalStrut(8))
        detailContent.add(sectionLabel("Etkilenen Senaryolar  (${group.occurrences.size})"))

        for (occ in group.occurrences) {
            detailContent.add(buildOccurrenceRow(occ))
        }

        detailContent.revalidate()
        detailContent.repaint()
    }

    private fun buildOccurrenceRow(occ: ErrorOccurrence): JPanel {
        val screenshotContainer = if (occ.screenshotBase64 != null) buildScreenshot(occ.screenshotBase64) else null

        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(1, 4, 1, 4)
        }

        // Senaryo adı: link + timestamp
        val nameRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val link = LinkLabel<Nothing?>(occ.scenarioName, AllIcons.RunConfigurations.TestFailed).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            setListener({ _, _ -> onNavigateToScenario?.invoke(occ.scenarioName) }, null)
        }
        nameRow.add(link)
        if (occ.timestamp != null) {
            nameRow.add(JBLabel(occ.timestamp).apply {
                font = font.deriveFont(10f)
                foreground = UIUtil.getLabelDisabledForeground()
            })
        }
        container.add(nameRow)

        // Hangi adımda patladı
        val stepRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        stepRow.add(JBLabel("↳ ${occ.stepText}").apply {
            font = font.deriveFont(11f)
            foreground = UIUtil.getLabelDisabledForeground()
        })
        if (screenshotContainer != null) {
            val btn = JButton(AllIcons.Actions.Preview).apply {
                toolTipText = "Ekran görüntüsünü göster"
                isFocusPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(22, 22)
            }
            btn.addActionListener {
                screenshotContainer.isVisible = !screenshotContainer.isVisible
                btn.toolTipText = if (screenshotContainer.isVisible) "Gizle" else "Ekran görüntüsünü göster"
                detailContent.revalidate()
                detailContent.repaint()
            }
            stepRow.add(btn)
        }
        container.add(stepRow)

        if (screenshotContainer != null) container.add(screenshotContainer)
        return container
    }

    private fun buildScreenshot(base64: String): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            isVisible = false
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0, 8, 0)
        }
        try {
            val bytes = Base64.getDecoder().decode(base64.replace("\\s".toRegex(), ""))
            val raw = ImageIcon(bytes)
            val maxW = 620
            val img = if (raw.iconWidth > maxW) {
                val scale = maxW.toDouble() / raw.iconWidth
                ImageIcon(raw.image.getScaledInstance(maxW, (raw.iconHeight * scale).toInt(), Image.SCALE_SMOOTH))
            } else raw
            panel.add(JLabel(img).apply {
                border = BorderFactory.createCompoundBorder(
                    RoundedBorder(8, UIConstants.subtleBorder()),
                    JBUI.Borders.empty(2)
                )
            }, BorderLayout.WEST)
            panel.maximumSize = Dimension(Int.MAX_VALUE, img.iconHeight + 16)
        } catch (_: Exception) {
            panel.add(JBLabel("[Screenshot decode error]").apply {
                foreground = UIUtil.getLabelDisabledForeground()
            }, BorderLayout.WEST)
        }
        return panel
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun sectionLabel(text: String): JBLabel = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 13f)
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(0, 0, 4, 0)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    // ── List cell renderer ───────────────────────────────────────────

    private inner class ErrorGroupCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val group = value as? ErrorGroup
                ?: return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            val label = super.getListCellRendererComponent(list, null, index, isSelected, cellHasFocus) as JLabel
            val msgColor = if (isSelected) "white" else "#cc4444"
            val countColor = if (isSelected) "#dddddd" else "#888888"
            val short = group.shortMessage.let { if (it.length > 70) "${it.take(70)}…" else it }

            label.text = "<html><b style='color:$msgColor'>$short</b>" +
                    "<br><small style='color:$countColor'>${group.uniqueScenarios} senaryo</small></html>"
            label.border = JBUI.Borders.empty(6, 10)
            return label
        }
    }
}
