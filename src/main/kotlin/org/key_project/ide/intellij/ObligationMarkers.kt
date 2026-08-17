package org.key_project.ide.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * Marks in the gutter what can be verified, and how far it has got.
 *
 * A mark appears beside every method that has a contract, and beside the class, in the way
 * a test runner marks a test. It shows how far the obligations of that declaration have got,
 * and KeY's continue button where KeY has judged none of them. Clicking one offers the same
 * KeY actions as anywhere else, applied to what that line stands for.
 *
 * The line numbers come from the bridge, which knows where KeY read each declaration.
 * Nothing here parses Java, so the marks appear whether or not the IDE has a Java language
 * plugin.
 */
@Service(Service.Level.PROJECT)
class ObligationMarkers(private val project: Project) : Disposable {

    private val installed = ConcurrentHashMap<Editor, List<RangeHighlighter>>()

    init {
        // Every path that changes a proof reports it here, so a mark is redrawn whether the
        // proof was run from a pane, from the gutter, on a save, or outside the IDE.
        KeyProject.of(project).onChanged(this) { refreshOpenEditors() }
    }

    /**
     * There is nothing to release. This service only marks how long its listener for changed
     * proof states lives, which is as long as the project.
     */
    override fun dispose() = Unit

    /**
     * KeY's continue icon, held for the renderer.
     *
     * The renderer draws on the event thread, where asking KeY for an icon is not allowed,
     * so the icon is taken while the marks are read and kept here until they are read again.
     */
    @Volatile
    private var verifyIcon: Icon? = null

    /** Marks every open editor again, after proof states have changed. */
    fun refreshOpenEditors() {
        FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<TextEditor>()
            .forEach { editor -> mark(editor.editor, editor.file) }
    }

    /**
     * Marks one editor.
     *
     * @param editor the editor to mark
     * @param file the file it shows
     */
    fun mark(editor: Editor, file: VirtualFile?) {
        if (file == null || file.extension != "java") {
            return
        }
        KeyTasks.of(project).launch("Marking what KeY can verify") {
            val marks = try {
                marksFor(Path.of(file.path))
            } catch (refused: Exception) {
                // KeY could not read the file, so nothing it said about it before holds. The
                // marks come off rather than staying green over a source KeY refuses; the
                // refusal itself is reported where the save that caused it is reported.
                LOG.info("no marks for ${file.name}: ${KeyNotifications.describe(refused)}")
                emptyMap()
            }
            LOG.info("marking ${file.name}: ${marks.keys.sorted()}")
            withContext(Dispatchers.EDT) { install(editor, marks) }
        }
    }

    /**
     * What to mark in a file.
     *
     * The bridge says which line carries which mark and what it says when hovered. What the
     * line stands for is read from the listing, since pressing a mark acts on those
     * obligations.
     *
     * @param file the file to mark
     * @return the marks, by line
     */
    private fun marksFor(file: Path): Map<Int, LineMark> {
        verifyIcon = KeyIcons.of(project).verify(GUTTER_SIZE)
        val reported = KeyProject.of(project).marks(file)
        val contextId = reported.contextId ?: return emptyMap()
        if (reported.marks.isEmpty()) {
            return emptyMap()
        }

        val root = Path.of(project.basePath ?: return emptyMap())
        val ofFile = KeyProject.of(project).obligations(contextId)
            .filter { root.resolve(it.sourceFile).normalize() == file }
        return reported.marks.associate { mark ->
            mark.line to LineMark(
                ProofMark.valueOf(mark.mark),
                mark.tooltip,
                ofFile.filter { it.targetLine == mark.line || it.classLine == mark.line }
                    .map { KeySelection.Selected(contextId, it.contractName, it.proofFile) },
            )
        }
    }

    /**
     * One line as the gutter shows it.
     *
     * @param mark how far the declaration has got, as the bridge decided
     * @param tooltip the sentence shown when the mark is hovered
     * @param obligations what the KeY actions act on when the mark is pressed
     */
    private data class LineMark(
        val mark: ProofMark,
        val tooltip: String,
        val obligations: List<KeySelection.Selected>,
    )

    private fun install(editor: Editor, marks: Map<Int, LineMark>) {
        if (editor.isDisposed) {
            return
        }
        val markup = editor.markupModel
        installed.remove(editor)?.forEach { markup.removeHighlighter(it) }

        val added = marks.mapNotNull { (line, mark) ->
            // The bridge counts lines from one, the editor from zero.
            val index = line - 1
            if (index < 0 || index >= editor.document.lineCount) {
                return@mapNotNull null
            }
            markup.addLineHighlighter(null, index, HighlighterLayer.ADDITIONAL_SYNTAX)
                .apply { gutterIconRenderer = MarkRenderer(mark) }
        }
        installed[editor] = added
    }

    /** The mark itself, which shows the state and opens the KeY menu when clicked. */
    private inner class MarkRenderer(
        private val marked: LineMark,
    ) : com.intellij.openapi.editor.markup.GutterIconRenderer() {

        private val obligations = marked.obligations

        /**
         * How far this line has got, or KeY's continue button where KeY has judged nothing:
         * a line with no state to show is an invitation to verify it.
         */
        override fun getIcon(): Icon =
            StatusMarks.icon(marked.mark, GUTTER_SIZE)
                ?: verifyIcon ?: KeyLogo.gutterIcon() ?: EMPTY

        override fun getTooltipText(): String = marked.tooltip

        override fun isNavigateAction(): Boolean = true

        override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(event: com.intellij.openapi.actionSystem.AnActionEvent) {
                showMenu(event.inputEvent as? MouseEvent)
            }
        }

        /**
         * Offers the actions this line stands for.
         *
         * The menu is given what the mark stands for rather than reading the caret, so
         * that clicking a mark acts on that method wherever the caret happens to be.
         */
        private fun showMenu(event: MouseEvent?) {
            val group = ActionManager.getInstance()
                .getAction("org.key_project.ide.intellij.KeyGroup") as? ActionGroup ?: return
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(KeySelection.OBLIGATIONS, obligations)
                .build()
            val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                "KeY",
                group,
                context,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
            )
            if (event != null) {
                popup.show(RelativePoint(event))
            } else {
                popup.showInFocusCenter()
            }
        }

        override fun equals(other: Any?): Boolean =
            other is MarkRenderer && other.marked == marked

        override fun hashCode(): Int = marked.hashCode()
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ObligationMarkers::class.java)
        private const val GUTTER_SIZE = 12
        private val EMPTY: Icon = com.intellij.util.ui.EmptyIcon.create(12)

        fun of(project: Project): ObligationMarkers = project.service()
    }
}
