package org.key_project.ide.intellij

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.JTree
import javax.swing.ToolTipManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Shows what the project can be asked to prove.
 *
 * The contexts are read from the configuration, which does not need a prover. A context is
 * loaded into KeY only when the user expands it, because loading takes several seconds and
 * a project may have many contexts.
 */
class ObligationTree(private val project: Project) : Disposable {

    private val root = DefaultMutableTreeNode("KeY")
    private val model = DefaultTreeModel(root)
    /**
     * The tree, which publishes what is selected in the form every view uses, so that the
     * KeY menu offers the same actions here as anywhere else.
     */
    private val tree = object : Tree(model), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            sink[KeySelection.OBLIGATIONS] = selectedObligations()
        }
    }

    /**
     * What this tab shows: the obligations, or an invitation to declare a context.
     *
     * A project that declares none has nothing to list, and an empty tree says nothing
     * about why. The card is chosen whenever the contexts are read.
     */
    private val cards = CardLayout()

    val component: JPanel = JPanel(cards).apply {
        add(JBScrollPane(tree), OBLIGATIONS_CARD)
        add(defineContextsPanel(), NO_CONTEXTS_CARD)
    }

    /**
     * The invitation shown to a project that does not use KeY yet.
     *
     * @return a panel with one button, centred
     */
    private fun defineContextsPanel(): JPanel {
        val explain = JBLabel(
            "<html><center>This project has no KeY context yet.<br>" +
                "A context is the source directory KeY reads, with its classpath." +
                "</center></html>",
        ).apply { horizontalAlignment = SwingConstants.CENTER }
        val define = JButton("Define Contexts").apply {
            addActionListener {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, KeyContextsConfigurable::class.java)
            }
        }
        val stacked = JPanel(java.awt.GridBagLayout())
        val constraints = java.awt.GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            insets = com.intellij.util.ui.JBUI.insetsBottom(12)
        }
        stacked.add(explain, constraints)
        stacked.add(define, constraints.apply { gridy = 1; insets = com.intellij.util.ui.JBUI.emptyInsets() })
        return JPanel(java.awt.GridBagLayout()).apply { add(stacked, java.awt.GridBagConstraints()) }
    }

    /** Shows the obligations or the invitation, according to what the project declares. */
    private fun showCard(anyContext: Boolean) {
        cards.show(component, if (anyContext) OBLIGATIONS_CARD else NO_CONTEXTS_CARD)
    }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ObligationRenderer()
        ToolTipManager.sharedInstance().registerComponent(tree)

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                (node.userObject as? ContextNode)?.let { if (!it.loaded) load(node, it) }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                // Act on the row that was clicked, not on the current selection.
                // Double clicking a class or a method expands it and must not start a
                // proof.
                if (event.clickCount == 2) {
                    obligationAt(event)?.let {
                        Verification.prove(project, it.contextId, listOf(it.dto.contractName),
                            it.label)
                    }
                }
            }
        })
        installMenu()
        FragmentLink(::noteAt) { tag ->
            val node = tag as ObligationNode
            SettingsDifferences.show(project, node.contextId, node.dto.contractName, node.label,
                node.dto.proofFile, node.dto.differingSettings)
        }.install(tree)

        // KeY reports when a proof run ends, so the tree is updated without the user
        // having to ask for it.
        KeyProject.of(project).onChanged(this) { reloadLoaded() }
    }

    /**
     * There is nothing to release here. This object only marks how long the listener for
     * changed proof states lives, and the tool window's tab disposes it.
     */
    override fun dispose() = Unit

    /**
     * Lists the project's contexts again.
     *
     * A context that was already loaded is listed again and stays expanded, so that
     * refreshing does not lose the user's place in the tree.
     */
    fun refresh() {
        val loadedBefore = loadedContextIds()
        KeyTasks.of(project).launch("Reading KeY contexts") {
            val config = KeyProject.of(project).config()
            withContext(Dispatchers.EDT) { showCard(config.contexts.isNotEmpty()) }
            val toReopen = loadedBefore.intersect(config.contexts.map { it.id }.toSet())
            // The nodes to list again exist only once the tree has been rebuilt, which is
            // why this waits for the event thread rather than scheduling and going on.
            val nodes = withContext(Dispatchers.EDT) {
                root.removeAllChildren()
                for (context in config.contexts) {
                    val node = DefaultMutableTreeNode(ContextNode(context.id))
                    node.add(DefaultMutableTreeNode(PlaceholderNode("expand to load")))
                    root.add(node)
                }
                model.reload()
                toReopen.mapNotNull(::contextNode)
                    .onEach { (it.userObject as ContextNode).loaded = true }
            }
            nodes.forEach { reload(it) }
        }
    }

    /** Loads a context and lists its obligations under it. */
    private fun load(node: DefaultMutableTreeNode, context: ContextNode) {
        context.loaded = true
        node.removeAllChildren()
        node.add(DefaultMutableTreeNode(PlaceholderNode("loading, this starts KeY…")))
        model.reload(node)
        KeyTasks.of(project).launch("Loading ${context.id} into KeY") { reload(node) }
    }

    /** Lists a loaded context again, keeping it open. */
    private suspend fun reload(node: DefaultMutableTreeNode) {
        val context = node.userObject as? ContextNode ?: return
        val obligations = try {
            KeyIcons.of(project).fetch(ICON_SIZE)
            KeyProject.of(project).obligations(context.id)
        } catch (failure: Exception) {
            // A context that cannot be listed says why here as well as in the balloon: an
            // empty row under a context reads as "nothing to prove", which is not the case.
            withContext(Dispatchers.EDT) {
                node.removeAllChildren()
                node.add(DefaultMutableTreeNode(
                    PlaceholderNode(KeyNotifications.describe(failure))))
                model.reload(node)
            }
            throw failure
        }
        withContext(Dispatchers.EDT) {
            // Listing replaces every node below this one, so the rows the user had
            // expanded are remembered and expanded again. Otherwise the tree would
            // collapse every time an obligation is proved.
            val expanded = expandedKeys(node)
            node.removeAllChildren()
            fill(node, context.id, obligations)
            model.reload(node)
            tree.expandPath(TreePath(node.path))
            reopen(node, expanded)
        }
    }

    /** The rows open below a node, named by the labels on the way to them. */
    private fun expandedKeys(node: DefaultMutableTreeNode): Set<List<String>> {
        val paths = tree.getExpandedDescendants(TreePath(node.path)) ?: return emptySet()
        return paths.asSequence().map(::keyOf).toSet()
    }

    /** Opens the rows that were open before, matching them by those labels. */
    private fun reopen(node: DefaultMutableTreeNode, expanded: Set<List<String>>) {
        if (expanded.isEmpty()) {
            return
        }
        val pending = ArrayDeque<DefaultMutableTreeNode>()
        pending.add(node)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val path = TreePath(current.path)
            if (keyOf(path) in expanded) {
                tree.expandPath(path)
            }
            for (index in 0 until current.childCount) {
                pending.add(current.getChildAt(index) as DefaultMutableTreeNode)
            }
        }
    }

    private fun keyOf(path: TreePath): List<String> =
        path.path.map { (it as DefaultMutableTreeNode).userObject.toString() }

    /**
     * Lists every loaded context again, after KeY reports that proof states have changed.
     *
     * KeY reports this from a thread of its own. The tree is therefore read on the event
     * thread, which is the only thread that may touch it, while the listing itself runs in
     * the background.
     */
    private fun reloadLoaded() {
        KeyTasks.of(project).launch("Updating KeY proof obligations") {
            val nodes = withContext(Dispatchers.EDT) {
                loadedContextIds().mapNotNull(::contextNode)
            }
            nodes.forEach { reload(it) }
        }
    }

    /**
     * Adds a class node per class, and beneath it one node per method.
     *
     * Every row reads as the bridge named it, which is what the verification table and the
     * other editors show. A method with several specification cases gets one node per case,
     * since the cases differ in what the bridge appends to the name; the method itself is
     * named by the part they share.
     */
    private fun fill(
        node: DefaultMutableTreeNode,
        contextId: String,
        obligations: List<ObligationDto>,
    ) {
        for ((className, ofClass) in obligations.groupBy { it.className }) {
            val classNode = DefaultMutableTreeNode(ClassNode(className))
            for ((_, cases) in ofClass.groupBy { it.target }) {
                if (cases.size == 1) {
                    classNode.add(obligationNode(contextId, cases.first(), cases.first().label))
                } else {
                    val shared = sharedStart(cases.map { it.label })
                    val methodNode = DefaultMutableTreeNode(MethodNode(shared, cases.size))
                    cases.forEach { methodNode.add(obligationNode(contextId, it, it.label)) }
                    classNode.add(methodNode)
                }
            }
            node.add(classNode)
        }
        if (node.childCount == 0) {
            node.add(DefaultMutableTreeNode(PlaceholderNode("no contracts")))
        }
    }

    private fun obligationNode(contextId: String, dto: ObligationDto, label: String) =
        DefaultMutableTreeNode(ObligationNode(contextId, dto, label))

    /**
     * The start every label shares, which is the method they are all about.
     *
     * @param labels the labels of one target's contracts
     * @return their common start, without what separates it from the rest
     */
    private fun sharedStart(labels: List<String>): String {
        val first = labels.firstOrNull() ?: return ""
        var at = first.length
        for (label in labels) {
            while (at > 0 && label.take(at) != first.take(at)) {
                at--
            }
        }
        return first.take(at).trimEnd(' ', '\u2014').ifEmpty { first }
    }

    /**
     * Installs the menu shown on a row.
     */
    private fun installMenu() {
        // The row under the pointer is selected before the menu opens, so that the menu
        // acts on the row the user pointed at rather than on an earlier selection. When a
        // menu is due is left to the platform, which is what installKeyMenu arranges.
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = selectUnderPointer(event)

            override fun mouseReleased(event: MouseEvent) = selectUnderPointer(event)

            private fun selectUnderPointer(event: MouseEvent) {
                if (!event.isPopupTrigger) {
                    return
                }
                val path = tree.getPathForLocation(event.x, event.y) ?: return
                if (path !in tree.selectionPaths.orEmpty()) {
                    tree.selectionPath = path
                }
            }
        })
        installKeyMenu(tree)
    }

    /** The obligations the selected rows stand for, a row of any depth meaning all below it. */
    private fun selectedObligations(): List<KeySelection.Selected> =
        tree.selectionPaths.orEmpty().flatMap { path ->
            val node = path.lastPathComponent as DefaultMutableTreeNode
            val contextId = contextIdOf(node) ?: return@flatMap emptyList()
            obligationsUnder(node).map {
                KeySelection.Selected(contextId, it.contractName, it.proofFile)
            }
        }

    /** Every obligation a row stands for, itself or everything below it. */
    private fun obligationsUnder(node: DefaultMutableTreeNode): List<ObligationDto> {
        val payload = node.userObject
        if (payload is ObligationNode) {
            return listOf(payload.dto)
        }
        return buildList {
            for (index in 0 until node.childCount) {
                addAll(obligationsUnder(node.getChildAt(index) as DefaultMutableTreeNode))
            }
        }
    }

    private fun contextIdOf(node: DefaultMutableTreeNode): String? {
        var current: DefaultMutableTreeNode? = node
        while (current != null) {
            (current.userObject as? ContextNode)?.let { return it.id }
            (current.userObject as? ObligationNode)?.let { return it.contextId }
            current = current.parent as? DefaultMutableTreeNode
        }
        return null
    }

    private fun loadedContextIds(): Set<String> = buildSet {
        for (index in 0 until root.childCount) {
            val node = root.getChildAt(index) as DefaultMutableTreeNode
            (node.userObject as? ContextNode)?.let { if (it.loaded) add(it.id) }
        }
    }

    private fun contextNode(contextId: String): DefaultMutableTreeNode? {
        for (index in 0 until root.childCount) {
            val candidate = root.getChildAt(index) as DefaultMutableTreeNode
            if ((candidate.userObject as? ContextNode)?.id == contextId) {
                return candidate
            }
        }
        return null
    }

    /** The obligation under the pointer, or null when something else was clicked. */
    private fun obligationAt(event: MouseEvent): ObligationNode? {
        val path = tree.getPathForLocation(event.x, event.y) ?: return null
        return (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ObligationNode
    }

    private class ContextNode(val id: String) {
        var loaded = false
        override fun toString() = id
    }

    private class ClassNode(val className: String) {
        override fun toString() = className.substringAfterLast('.')
    }

    private class MethodNode(val target: String, private val cases: Int) {
        override fun toString() = "$target  ($cases specification cases)"
    }

    private class ObligationNode(
        val contextId: String,
        val dto: ObligationDto,
        val label: String,
    ) {
        override fun toString() = label
    }

    private class PlaceholderNode(private val text: String) {
        override fun toString() = text
    }

    /**
     * Draws KeY's own icon beside an obligation, and its explanation as the tooltip.
     *
     * A proof made under other settings than the current ones gets a note after its name,
     * which is a link to the full list of differences. The note carries the node as its
     * tag, which is how a click on it is told from a click on the name.
     */
    private inner class ObligationRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val payload = (value as? DefaultMutableTreeNode)?.userObject
            append(payload?.toString().orEmpty())
            if (payload !is ObligationNode) {
                // Everything that groups obligations is drawn as a folder, as it was when
                // the platform's own renderer chose the icons.
                icon = if (payload is PlaceholderNode) null else AllIcons.Nodes.Folder
                return
            }
            icon = iconFor(payload.dto.status)
            val differing = payload.dto.differingSettings
            if (differing.isEmpty()) {
                toolTipText = payload.dto.statusExplanation
            } else {
                append("  ")
                append(SettingsDifferences.NOTE, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, payload)
                toolTipText = SettingsDifferences.tooltip(differing)
            }
        }

        private fun iconFor(status: String): Icon? =
            KeyIcons.of(project).forStatus(status, ICON_SIZE)
    }

    /**
     * The obligation whose settings note is under the pointer.
     *
     * @return the node, or null when the pointer is not on such a note
     */
    private fun noteAt(event: MouseEvent): ObligationNode? {
        val path = tree.getPathForLocation(event.x, event.y) ?: return null
        val bounds = tree.getPathBounds(path) ?: return null
        val node = path.lastPathComponent
        val row = tree.getRowForPath(path)
        val renderer = tree.cellRenderer.getTreeCellRendererComponent(
            tree, node, tree.isRowSelected(row), tree.isExpanded(path), tree.model.isLeaf(node),
            row, false,
        )
        return FragmentLink.tagOf(renderer, event.x - bounds.x) as? ObligationNode
    }

    private companion object {
        const val OBLIGATIONS_CARD = "obligations"
        const val NO_CONTEXTS_CARD = "no-contexts"
        const val ICON_SIZE = 16
    }
}
