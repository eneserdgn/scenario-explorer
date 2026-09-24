package com.scenarioexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import com.scenarioexplorer.model.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class ScenarioTreePanel(
    private val onScenarioSelected: (Scenario) -> Unit
) {
    private val rootNode = DefaultMutableTreeNode("Scenarios")
    private val treeModel = DefaultTreeModel(rootNode)

    val tree: Tree = Tree(treeModel).apply {
        cellRenderer = ScenarioTreeCellRenderer()
        isRootVisible = false
        showsRootHandles = true
        // Double-click is handled below (folders/files toggle, scenarios re-open)
        toggleClickCount = 0
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        addTreeSelectionListener {
            val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            if (node.userObject is Scenario) {
                onScenarioSelected(node.userObject as Scenario)
            }
        }

        // Double-click to expand/collapse folders and files, open scenario
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
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

    fun updateTree(files: List<ScenarioFile>, reports: Map<String, ReportEntry>) {
        // Save expanded state and selection before rebuild
        val expandedPaths = mutableSetOf<String>()
        val selectedScenarioName = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
            ?.let { (it.userObject as? Scenario)?.name }

        saveExpandedPaths(rootNode, "")  { expandedPaths.add(it) }

        rootNode.removeAllChildren()

        val grouped = files.groupBy { it.file.parentFile?.path ?: "" }

        for ((dirPath, scenarioFiles) in grouped.toSortedMap()) {
            val dirName = java.io.File(dirPath).name.ifEmpty { dirPath }

            val dirStats = computeDirStats(scenarioFiles, reports)
            val dirData = DirNodeData(dirName, dirStats)
            val dirNode = DefaultMutableTreeNode(dirData)

            for (sf in scenarioFiles) {
                val fileStats = computeFileStats(sf, reports)
                val fileData = FileNodeData(sf.featureName, sf.type, sf.featureTags, fileStats)
                val fileNode = DefaultMutableTreeNode(fileData)

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

                    fileNode.add(DefaultMutableTreeNode(enriched))
                }
                dirNode.add(fileNode)
            }
            rootNode.add(dirNode)
        }

        treeModel.reload()

        // Restore expanded state
        restoreExpandedPaths(rootNode, "", expandedPaths)

        // Restore selection
        if (selectedScenarioName != null) {
            selectScenarioByName(selectedScenarioName)
        }
    }

    private fun saveExpandedPaths(node: DefaultMutableTreeNode, prefix: String, collector: (String) -> Unit) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
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

    private fun restoreExpandedPaths(node: DefaultMutableTreeNode, prefix: String, expandedPaths: Set<String>) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
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

    fun selectScenarioByName(name: String): Boolean {
        val node = findScenarioNode(rootNode, name) ?: return false
        val path = buildTreePath(node)
        tree.expandPath(path.parentPath)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        return true
    }

    private fun findScenarioNode(node: DefaultMutableTreeNode, name: String): DefaultMutableTreeNode? {
        if (node.userObject is Scenario && (node.userObject as Scenario).name == name) return node
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val found = findScenarioNode(child, name)
            if (found != null) return found
        }
        return null
    }

    private fun buildTreePath(node: DefaultMutableTreeNode): TreePath {
        val nodes = mutableListOf<Any>()
        var current: javax.swing.tree.TreeNode? = node
        while (current != null) {
            nodes.add(0, current)
            current = current.parent
        }
        return TreePath(nodes.toTypedArray())
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

    private class ScenarioTreeCellRenderer : ColoredTreeCellRenderer() {
        // Estimated per-depth-level indent (px) — used to keep our forced row width
        // from overrunning the tree's visible edge on deeply-nested (scenario) rows.
        private val indentPerLevel = 20

        // Row width we report from getPreferredSize(), computed per-node in customizeCellRenderer.
        private var rowWidth = 0

        override fun getPreferredSize(): Dimension {
            val natural = super.getPreferredSize()
            return if (rowWidth > natural.width) Dimension(rowWidth, natural.height) else natural
        }

        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            // Use the enclosing scroll pane's viewport width, not tree.width: the viewport
            // is sized externally (by the split pane), whereas tree.width is itself derived
            // from each row's preferred size — using it here would create a growth feedback loop.
            val viewportWidth = (tree.parent as? JViewport)?.width ?: tree.width
            val indentOffset = node.level * indentPerLevel
            rowWidth = (viewportWidth - indentOffset).coerceAtLeast(0)
            val available = (rowWidth - END_MARGIN).coerceAtLeast(0)
            val fm = if (available > 0) tree.getFontMetrics(font) else null

            when (val userObj = node.userObject) {
                is Scenario -> renderScenario(userObj, available, fm)
                is FileNodeData -> renderFile(userObj, available, fm)
                is DirNodeData -> renderDir(userObj, available, fm)
            }
        }

        private fun renderScenario(s: Scenario, available: Int, fm: FontMetrics?) {
            icon = when (s.status) {
                StepStatus.PASSED -> AllIcons.RunConfigurations.TestPassed
                StepStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
                StepStatus.NOT_RUN -> AllIcons.Actions.Suspend
                else -> AllIcons.Actions.Suspend
            }

            val durationText = if (s.duration != null && s.duration > 0) formatDuration(s.duration) else null
            val rightWidth = if (fm != null && durationText != null) fm.stringWidth("  $durationText") else 0
            val nameBudget = (available - ICON_BUDGET - rightWidth - MARGIN).coerceAtLeast(0)
            val name = if (fm != null && nameBudget > 0) truncate(" ${s.name} ", nameBudget, fm) else " ${s.name} "

            append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (s.tags.isNotEmpty()) {
                append(" ${s.tags.joinToString(" ")} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            if (durationText != null) {
                append("  $durationText", fileDurationAttrs())
                if (available > 0) appendTextPadding(available, SwingConstants.RIGHT)
            }
        }

        private fun renderFile(f: FileNodeData, available: Int, fm: FontMetrics?) {
            icon = AllIcons.FileTypes.Text
            appendNodeRow(f.name, f.stats, available, fm, isDir = false)
        }

        private fun renderDir(d: DirNodeData, available: Int, fm: FontMetrics?) {
            icon = AllIcons.Nodes.Folder
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
                append(name, nameAttrs)
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

            append(truncatedName, nameAttrs)
            append("  $statsText", statsAttrs)
            if (statsTargetX > 0) appendTextPadding(statsTargetX, SwingConstants.RIGHT)
            append("  $durationText", durationAttrs)
            if (durationTargetX > 0) appendTextPadding(durationTargetX, SwingConstants.RIGHT)
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
