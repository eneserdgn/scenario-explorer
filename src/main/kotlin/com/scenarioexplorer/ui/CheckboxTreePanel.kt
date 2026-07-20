package com.scenarioexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.scenarioexplorer.model.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingConstants
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class CheckboxTreePanel(
    private val onScenarioSelected: (Scenario) -> Unit
) {
    private val rootNode = CheckedTreeNode("Scenarios")
    private val treeModel = DefaultTreeModel(rootNode)

    val tree: CheckboxTree = CheckboxTree(ScenarioCheckboxRenderer(), rootNode).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        addTreeSelectionListener {
            val node = lastSelectedPathComponent as? CheckedTreeNode ?: return@addTreeSelectionListener
            if (node.userObject is Scenario) {
                onScenarioSelected(node.userObject as Scenario)
            }
        }

        // Double-click to expand/collapse folders and files, open scenario
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? CheckedTreeNode ?: return
                    when (node.userObject) {
                        is Scenario -> onScenarioSelected(node.userObject as Scenario)
                        else -> {
                            if (isExpanded(path)) collapsePath(path) else expandPath(path)
                        }
                    }
                }
            }
        })
    }

    /** Button bar for quick check/uncheck by status */
    val filterButtonsPanel: javax.swing.JPanel = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
        border = JBUI.Borders.empty(2, 4)

        val swAll = ToggleSwitchButton("☑ Tümünü Seç").apply {
            addActionListener { setAllChecked(isSelected) }
        }

        val swFailed = ToggleSwitchButton("✗ Failed").apply {
            addActionListener {
                if (isSelected) checkByStatus(StepStatus.FAILED) else uncheckByStatus(StepStatus.FAILED)
            }
        }
        val swNotRun = ToggleSwitchButton("○ Not Run").apply {
            addActionListener {
                if (isSelected) checkByStatus(StepStatus.NOT_RUN) else uncheckByStatus(StepStatus.NOT_RUN)
            }
        }

        add(swAll)
        add(javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 16) })
        add(swFailed)
        add(javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 16) })
        add(swNotRun)
    }

    private fun setAllChecked(checked: Boolean) {
        visitScenarioNodes(rootNode) { it.isChecked = checked }
        syncParentStates(rootNode)
        treeModel.reload()
    }

    private fun checkByStatus(status: StepStatus) {
        visitScenarioNodes(rootNode) { node ->
            val s = node.userObject as Scenario
            if (s.status == status) node.isChecked = true
        }
        syncParentStates(rootNode)
        treeModel.reload()
    }

    private fun uncheckByStatus(status: StepStatus) {
        visitScenarioNodes(rootNode) { node ->
            val s = node.userObject as Scenario
            if (s.status == status) node.isChecked = false
        }
        syncParentStates(rootNode)
        treeModel.reload()
    }

    /** Sets each non-leaf node's isChecked to true if any child scenario is checked. */
    private fun syncParentStates(node: CheckedTreeNode) {
        for (i in 0 until node.childCount) {
            (node.getChildAt(i) as? CheckedTreeNode)?.let { syncParentStates(it) }
        }
        if (node.userObject !is Scenario && node.childCount > 0) {
            node.isChecked = (0 until node.childCount).any {
                (node.getChildAt(it) as? CheckedTreeNode)?.isChecked == true
            }
        }
    }

    private fun visitScenarioNodes(node: CheckedTreeNode, action: (CheckedTreeNode) -> Unit) {
        if (node.userObject is Scenario) {
            action(node)
        }
        for (i in 0 until node.childCount) {
            (node.getChildAt(i) as? CheckedTreeNode)?.let { visitScenarioNodes(it, action) }
        }
    }

    fun updateTree(files: List<ScenarioFile>, reports: Map<String, ReportEntry>) {
        // Save expanded state, selection, and checked state before rebuild
        val expandedPaths = mutableSetOf<String>()
        val selectedScenarioName = (tree.lastSelectedPathComponent as? CheckedTreeNode)
            ?.let { (it.userObject as? Scenario)?.name }
        val checkedNames = getCheckedScenarios().map { it.name }.toSet()

        saveExpandedPaths(rootNode, "")  { expandedPaths.add(it) }

        rootNode.removeAllChildren()

        val grouped = files.groupBy { it.file.parentFile?.path ?: "" }

        for ((dirPath, scenarioFiles) in grouped.toSortedMap()) {
            val dirName = dirPath.substringAfterLast("/").ifEmpty { dirPath }

            val dirStats = computeDirStats(scenarioFiles, reports)
            val dirData = DirNodeData(dirName, dirStats)
            val dirNode = CheckedTreeNode(dirData)

            for (sf in scenarioFiles) {
                val fileStats = computeFileStats(sf, reports)
                val fileData = FileNodeData(sf.featureName, sf.type, sf.featureTags, fileStats)
                val fileNode = CheckedTreeNode(fileData)

                for (scenario in sf.scenarios) {
                    val report = reports[scenario.name]
                    val enriched = if (report != null) {
                        scenario.copy(
                            status = report.status,
                            duration = report.duration,
                            steps = scenario.steps.mapIndexed { idx, step ->
                                val sr = report.steps.getOrNull(idx)
                                if (sr != null) step.copy(
                                    status = sr.status,
                                    errorMessage = sr.errorMessage,
                                    duration = sr.duration,
                                    screenshotBase64 = sr.screenshotBase64
                                ) else step
                            }
                        )
                    } else scenario

                    val scenarioNode = CheckedTreeNode(enriched)
                    // Preserve checked state: if we had previous checks, restore them; otherwise keep default
                    if (checkedNames.isNotEmpty()) {
                        scenarioNode.isChecked = enriched.name in checkedNames
                    }
                    fileNode.add(scenarioNode)
                }
                dirNode.add(fileNode)
            }
            rootNode.add(dirNode)
        }

        treeModel.reload()
        tree.model = treeModel

        // Restore expanded state
        restoreExpandedPaths(rootNode, "", expandedPaths)

        // Restore selection
        if (selectedScenarioName != null) {
            selectScenarioByName(selectedScenarioName)
        }
    }

    private fun saveExpandedPaths(node: CheckedTreeNode, prefix: String, collector: (String) -> Unit) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            val key = when (val obj = child.userObject) {
                is DirNodeData -> "$prefix/dir:${obj.name}"
                is FileNodeData -> "$prefix/file:${obj.name}"
                else -> continue
            }
            val path = buildTreePath(child)
            if (tree.isExpanded(path)) {
                collector(key)
                saveExpandedPaths(child, key, collector)
            }
        }
    }

    private fun restoreExpandedPaths(node: CheckedTreeNode, prefix: String, expandedPaths: Set<String>) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            val key = when (val obj = child.userObject) {
                is DirNodeData -> "$prefix/dir:${obj.name}"
                is FileNodeData -> "$prefix/file:${obj.name}"
                else -> continue
            }
            if (key in expandedPaths) {
                tree.expandPath(buildTreePath(child))
                restoreExpandedPaths(child, key, expandedPaths)
            }
        }
    }

    fun getCheckedScenarios(): List<Scenario> {
        val result = mutableListOf<Scenario>()
        collectChecked(rootNode, result)
        return result
    }

    fun selectScenarioByName(name: String): Boolean {
        val node = findScenarioNode(rootNode, name) ?: return false
        val path = buildTreePath(node)
        tree.expandPath(path.parentPath)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        return true
    }

    private fun findScenarioNode(node: CheckedTreeNode, name: String): CheckedTreeNode? {
        if (node.userObject is Scenario && (node.userObject as Scenario).name == name) return node
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            val found = findScenarioNode(child, name)
            if (found != null) return found
        }
        return null
    }

    private fun buildTreePath(node: CheckedTreeNode): TreePath {
        val nodes = mutableListOf<Any>()
        var current: javax.swing.tree.TreeNode? = node
        while (current != null) {
            nodes.add(0, current)
            current = current.parent
        }
        return TreePath(nodes.toTypedArray())
    }

    private fun collectChecked(node: CheckedTreeNode, result: MutableList<Scenario>) {
        if (node.userObject is Scenario && node.isChecked) {
            result.add(node.userObject as Scenario)
        }
        for (i in 0 until node.childCount) {
            (node.getChildAt(i) as? CheckedTreeNode)?.let { collectChecked(it, result) }
        }
    }

    // --- Stats computation ---

    data class NodeStats(
        val total: Int = 0,
        val passed: Int = 0,
        val failed: Int = 0,
        val totalDuration: Long = 0,
        val ranCount: Int = 0
    )

    private fun computeDirStats(files: List<ScenarioFile>, reports: Map<String, ReportEntry>): NodeStats {
        var total = 0; var passed = 0; var failed = 0; var dur = 0L; var ranCount = 0
        for (sf in files) {
            for (s in sf.scenarios) {
                total++
                val r = reports[s.name]
                when (r?.status) {
                    StepStatus.PASSED -> passed++
                    StepStatus.FAILED -> failed++
                    else -> {}
                }
                val d = r?.duration ?: 0
                if (d > 0) { dur += d; ranCount++ }
            }
        }
        return NodeStats(total, passed, failed, dur, ranCount)
    }

    private fun computeFileStats(sf: ScenarioFile, reports: Map<String, ReportEntry>): NodeStats {
        var total = 0; var passed = 0; var failed = 0; var dur = 0L; var ranCount = 0
        for (s in sf.scenarios) {
            total++
            val r = reports[s.name]
            when (r?.status) {
                StepStatus.PASSED -> passed++
                StepStatus.FAILED -> failed++
                else -> {}
            }
            val d = r?.duration ?: 0
            if (d > 0) { dur += d; ranCount++ }
        }
        return NodeStats(total, passed, failed, dur, ranCount)
    }

    // --- Data classes for tree nodes ---

    data class DirNodeData(val name: String, val stats: NodeStats)
    data class FileNodeData(val name: String, val type: ScenarioType, val tags: List<String>, val stats: NodeStats)

    // --- Renderer ---

    private class ScenarioCheckboxRenderer : CheckboxTree.CheckboxTreeCellRenderer() {
        // Estimated per-depth-level indent (px) — used to keep our forced row width
        // from overrunning the tree's visible edge on deeply-nested (scenario) rows.
        private val indentPerLevel = 20

        // Row width we report from getPreferredSize(), computed per-node in customizeRenderer.
        private var rowWidth = 0

        override fun getPreferredSize(): Dimension {
            val natural = super.getPreferredSize()
            return if (rowWidth > natural.width) Dimension(rowWidth, natural.height) else natural
        }

        override fun customizeRenderer(
            tree: JTree?, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? CheckedTreeNode ?: return
            // Use the enclosing scroll pane's viewport width, not tree.width: the viewport
            // is sized externally (by the split pane), whereas tree.width is itself derived
            // from each row's preferred size — using it here would create a growth feedback loop.
            val viewportWidth = (tree?.parent as? JViewport)?.width ?: tree?.width ?: 0
            val indentOffset = node.level * indentPerLevel
            rowWidth = (viewportWidth - indentOffset).coerceAtLeast(0)
            val available = (rowWidth - checkbox.preferredSize.width - END_MARGIN).coerceAtLeast(0)
            val fm = if (available > 0) tree?.getFontMetrics(textRenderer.font) else null

            when (val userObj = node.userObject) {
                is Scenario -> renderScenario(userObj, available, fm)
                is FileNodeData -> renderFile(userObj, available, fm)
                is DirNodeData -> renderDir(userObj, available, fm)
            }
        }

        private fun renderScenario(s: Scenario, available: Int, fm: FontMetrics?) {
            textRenderer.icon = when (s.status) {
                StepStatus.PASSED -> AllIcons.RunConfigurations.TestPassed
                StepStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
                StepStatus.NOT_RUN -> AllIcons.Actions.Suspend
                else -> AllIcons.Actions.Suspend
            }

            val durationText = if (s.duration != null && s.duration > 0) formatDuration(s.duration) else null
            val rightWidth = if (fm != null && durationText != null) fm.stringWidth("  $durationText") else 0
            val nameBudget = (available - ICON_BUDGET - rightWidth - MARGIN).coerceAtLeast(0)
            val name = if (fm != null && nameBudget > 0) truncate(" ${s.name} ", nameBudget, fm) else " ${s.name} "

            textRenderer.append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (s.tags.isNotEmpty()) {
                textRenderer.append(" ${s.tags.joinToString(" ")} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            if (durationText != null) {
                textRenderer.append("  $durationText", fileDurationAttrs())
                if (available > 0) textRenderer.appendTextPadding(available, SwingConstants.RIGHT)
            }
        }

        private fun renderFile(f: FileNodeData, available: Int, fm: FontMetrics?) {
            textRenderer.icon = AllIcons.FileTypes.Text
            appendNodeRow(f.name, f.stats, available, fm, isDir = false)
        }

        private fun renderDir(d: DirNodeData, available: Int, fm: FontMetrics?) {
            textRenderer.icon = AllIcons.Nodes.Folder
            appendNodeRow(d.name, d.stats, available, fm, isDir = true)
        }

        // Dirs get a bolder, accent-colored, full-size treatment; files get the same
        // weight but neutral color and a smaller stats/duration block — this is the
        // main cue for telling a folder row apart from a feature row at a glance.
        private fun appendNodeRow(name: String, stats: NodeStats, available: Int, fm: FontMetrics?, isDir: Boolean) {
            val nameAttrs = if (isDir) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UIConstants.BLUE)
            } else {
                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            }

            if (stats.total == 0) {
                textRenderer.append(name, nameAttrs)
                return
            }
            val notRun = stats.total - stats.passed - stats.failed
            val parts = mutableListOf("✓${stats.passed}", "✗${stats.failed}")
            if (notRun > 0) parts.add("○${notRun}")
            val statsText = "[${parts.joinToString(" ")}]"

            val durationText = formatDuration(stats.totalDuration) +
                if (stats.ranCount > 0) "  (ø ${formatDuration(stats.totalDuration / stats.ranCount)})" else ""

            val statsWidth = fm?.stringWidth(statsText) ?: 0
            val durationWidth = fm?.stringWidth(durationText) ?: 0
            val durationTargetX = available
            val statsTargetX = (available - durationWidth - MARGIN).coerceAtLeast(0)
            val nameBudget = (statsTargetX - statsWidth - MARGIN).coerceAtLeast(0)
            val truncatedName = if (fm != null && nameBudget > 0) truncate(name, nameBudget, fm) else name

            val statsAttrs = if (isDir) SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES
            val durationAttrs = if (isDir) SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES else fileDurationAttrs()

            textRenderer.append(truncatedName, nameAttrs)
            textRenderer.append("  $statsText", statsAttrs)
            if (statsTargetX > 0) textRenderer.appendTextPadding(statsTargetX, SwingConstants.RIGHT)
            textRenderer.append("  $durationText", durationAttrs)
            if (durationTargetX > 0) textRenderer.appendTextPadding(durationTargetX, SwingConstants.RIGHT)
        }

        // Smaller than a dir's duration text — reinforces that files/scenarios sit one level deeper.
        private fun fileDurationAttrs() = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_ITALIC or SimpleTextAttributes.STYLE_SMALLER,
            UIUtil.getLabelDisabledForeground()
        )

        /** Truncates [text] to fit [maxWidth] px (per [fm]), appending an ellipsis when cut. */
        private fun truncate(text: String, maxWidth: Int, fm: FontMetrics): String {
            if (fm.stringWidth(text) <= maxWidth) return text
            val ellipsisWidth = fm.stringWidth(ELLIPSIS)
            if (ellipsisWidth >= maxWidth) return ELLIPSIS
            var lo = 0; var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (fm.stringWidth(text.substring(0, mid)) + ellipsisWidth <= maxWidth) lo = mid else hi = mid - 1
            }
            return text.substring(0, lo).trimEnd() + ELLIPSIS
        }

        private fun formatDuration(ms: Long?): String {
            val total = ms ?: 0L
            val hours = total / 3_600_000
            val minutes = (total % 3_600_000) / 60_000
            val seconds = (total % 60_000) / 1000
            return "%02dh %02dm %02ds".format(hours, minutes, seconds)
        }

        companion object {
            private const val ICON_BUDGET = 20
            private const val MARGIN = 12
            private const val END_MARGIN = 10
            private const val ELLIPSIS = "…"
        }
    }
}
