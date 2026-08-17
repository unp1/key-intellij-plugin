package org.key_project.ide.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ToolTipManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * What a proof rests on: the contracts KeY says it used, and what those used in turn.
 *
 * A proof that closes by using another method's contract is only as good as that contract's
 * own proof, which is what KeY means by closed but for lemmas. This shows which contracts
 * those are, so that "which lemmas are left?" has an answer.
 *
 * Everything here is KeY's word. KeY says what a proof used while it holds that proof, so
 * an obligation KeY has not held says nothing rather than saying it used nothing, and the
 * pane says so. Nothing is worked out here: the edges are drawn as reported, and a contract
 * that leads back to one further up is marked and not followed, which is drawing rather
 * than deciding.
 *
 * The tree publishes its selection the way the other views do, so the KeY menu offers the
 * same actions: verify, replay, go to source, options.
 */
class DependencyPane(private val project: Project) : Disposable {

    private val root = DefaultMutableTreeNode("KeY")
    private val model = DefaultTreeModel(root)

    private val tree = object : Tree(model), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            sink[KeySelection.OBLIGATIONS] = selected()
        }
    }

    private val cards = CardLayout()

    val component: JPanel = JPanel(cards).apply {
        add(JBScrollPane(tree), TREE_CARD)
        add(hint(), EMPTY_CARD)
    }

    /** What is shown, so that it can be read again when the proofs change. */
    @Volatile
    private var showing: Shown? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = NodeRenderer()
        ToolTipManager.sharedInstance().registerComponent(tree)
        installKeyMenu(tree)
        cards.show(component, EMPTY_CARD)

        // Proving one of these contracts changes what KeY says about the others, so what
        // is on screen is read again rather than left as it was when it was asked for.
        KeyProject.of(project).onChanged(this) {
            showing?.let { show(it.contextId, it.contractName, it.label) }
        }
    }

    /** What the tab was last asked to show. */
    private data class Shown(val contextId: String, val contractName: String, val label: String)

    private fun hint(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JBLabel("Select a proof obligation and choose KeY, Show Dependencies."))
    }

    /**
     * Shows what one obligation rests on.
     *
     * @param contextId the context it belongs to
     * @param contractName the obligation
     * @param label how it reads to the user
     */
    fun show(contextId: String, contractName: String, label: String) {
        showing = Shown(contextId, contractName, label)
        KeyTasks.of(project).launch("Reading what $label rests on") {
            KeyIcons.of(project).fetch(ICON_SIZE)
            val obligations = KeyProject.of(project).obligations(contextId).associateBy { it.contractName }
            val reported = KeyProject.of(project).dependencies(contextId)
            withContext(Dispatchers.EDT) {
                root.removeAllChildren()
                root.add(nodeFor(contextId, contractName, obligations, reported, mutableSetOf()))
                model.reload()
                expandAll()
                cards.show(component, TREE_CARD)
            }
        }
    }

    /**
     * One obligation and, under it, the contracts KeY said its proof used.
     *
     * @param ancestors the obligations this one hangs under, so that a contract leading
     *        back to one of them is marked rather than followed forever
     */
    private fun nodeFor(
        contextId: String,
        contractName: String,
        obligations: Map<String, ObligationDto>,
        reported: Map<String, List<String>>,
        ancestors: MutableSet<String>,
    ): DefaultMutableTreeNode {
        val obligation = obligations[contractName] ?: ObligationDto(
            contractName = contractName,
            displayName = contractName,
            status = "UNKNOWN",
            statusExplanation = "This contract is not one this context lists.",
        )
        val used = reported[contractName]
        if (!ancestors.add(contractName)) {
            return DefaultMutableTreeNode(
                DependencyNode(contextId, obligation, known = true, repeated = true),
            )
        }
        val node = DefaultMutableTreeNode(
            DependencyNode(contextId, obligation, known = used != null, repeated = false),
        )
        used?.forEach { node.add(nodeFor(contextId, it, obligations, reported, ancestors)) }
        ancestors.remove(contractName)
        return node
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun selected(): List<KeySelection.Selected> =
        tree.selectionPaths.orEmpty()
            .mapNotNull { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject }
            .filterIsInstance<DependencyNode>()
            .map {
                KeySelection.Selected(it.contextId, it.obligation.contractName,
                    it.obligation.proofFile)
            }

    override fun dispose() {
        ToolTipManager.sharedInstance().unregisterComponent(tree)
    }

    /** One obligation in the tree, with the context it belongs to. */
    private class DependencyNode(
        val contextId: String,
        val obligation: ObligationDto,
        val known: Boolean,
        val repeated: Boolean,
    ) {
        override fun toString() = obligation.label.ifBlank { obligation.contractName }
    }

    /** Draws each obligation with KeY's icon, and says where KeY had nothing to say. */
    private inner class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? DependencyNode ?: return
            append(node.toString())
            icon = KeyIcons.of(project).forStatus(node.obligation.status, ICON_SIZE)
            when {
                node.repeated ->
                    append("  already above", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)

                !node.known ->
                    append("  verify it to see what it uses",
                        SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
            toolTipText = node.obligation.statusExplanation
        }
    }

    private companion object {
        const val TREE_CARD = "tree"
        const val EMPTY_CARD = "empty"
        const val ICON_SIZE = 16
    }
}
