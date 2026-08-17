package org.key_project.ide.intellij

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ClickListener
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * How a saved proof's settings differ from the ones its obligation would be attempted with
 * now, and what to do about it.
 *
 * Both views mark such a proof with a short note and a short tooltip. The note is a link,
 * and this is what it opens: every difference in a table, with the choice of opening the
 * proof as it was saved or starting a new one under the current settings.
 */
object SettingsDifferences {

    /** How many differences the tooltip lists before it refers to the full list. */
    private const val TOOLTIP_LINES = 3

    /** Appended to a marked proof, and what the user clicks to see the full list. */
    const val NOTE = "settings differ"

    /**
     * The tooltip of a marked proof.
     *
     * @param differing the options that differ
     * @return the tooltip, as HTML so that each option has its own line
     */
    fun tooltip(differing: List<OptionDifferenceDto>): String {
        val shown = differing.take(TOOLTIP_LINES).joinToString("") { "<br>&nbsp;&nbsp;" + it.sentence() }
        val rest = differing.size - TOOLTIP_LINES
        val more = if (rest > 0) "<br>&nbsp;&nbsp;… and $rest more" else ""
        return "<html>The saved proof was made under other settings:$shown$more" +
            "<br><br>Click “$NOTE” for the full list.</html>"
    }

    /** What the user chose in the dialog. */
    enum class Choice { OPEN_SAVED, START_NEW }

    /**
     * Shows the differences and asks what to do.
     *
     * @param project the project
     * @param what the obligation, for the title
     * @param differing the options that differ
     * @return what the user chose, or null to do nothing
     */
    suspend fun ask(project: Project, what: String, differing: List<OptionDifferenceDto>): Choice? =
        withContext(Dispatchers.EDT) {
            val dialog = DifferencesDialog(project, what, differing)
            dialog.show()
            dialog.choice
        }

    /**
     * Shows the differences of one obligation and acts on the answer.
     *
     * This is what the note in a view does when clicked, so it runs in the background and
     * reports failures the way every other action does.
     *
     * @param project the project
     * @param contextId the context of the obligation
     * @param contractName the obligation
     * @param label how the obligation reads to the user
     * @param proofFile the saved proof, relative to the project root
     * @param differing the options that differ
     */
    fun show(
        project: Project,
        contextId: String,
        contractName: String,
        label: String,
        proofFile: String,
        differing: List<OptionDifferenceDto>,
    ) {
        KeyTasks.of(project).launch("Showing settings differences") {
            when (ask(project, label, differing)) {
                Choice.OPEN_SAVED -> ProofWindow.openSaved(project, proofFile)
                Choice.START_NEW -> ProofWindow.startNew(project, contextId, contractName)
                null -> Unit
            }
        }
    }

    /** The dialog itself: the table, and the two things one can do next. */
    private class DifferencesDialog(
        project: Project,
        what: String,
        private val differing: List<OptionDifferenceDto>,
    ) : DialogWrapper(project) {

        var choice: Choice? = null
            private set

        init {
            title = "Settings Differences: $what"
            setCancelButtonText("Close")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val table = JBTable(DifferencesModel(differing)).apply {
                setShowGrid(false)
                autoCreateRowSorter = true
                columnModel.getColumn(0).preferredWidth = JBUI.scale(180)
                columnModel.getColumn(1).preferredWidth = JBUI.scale(160)
                columnModel.getColumn(2).preferredWidth = JBUI.scale(160)
                columnModel.getColumn(3).preferredWidth = JBUI.scale(70)
            }
            return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                add(
                    JBLabel(
                        "The saved proof was made under these settings; the obligation would " +
                            "be attempted with the current ones now. A taclet option changes " +
                            "what was proved, a strategy option only how it was found.",
                        UIUtil.ComponentStyle.SMALL,
                    ),
                    BorderLayout.NORTH,
                )
                add(JBScrollPane(table), BorderLayout.CENTER)
                preferredSize = Dimension(JBUI.scale(640), JBUI.scale(320))
            }
        }

        override fun createActions(): Array<Action> = arrayOf(
            choose("Open as Saved", Choice.OPEN_SAVED),
            choose("Start New Proof", Choice.START_NEW),
            cancelAction,
        )

        private fun choose(text: String, chosen: Choice): Action =
            object : DialogWrapperAction(text) {
                override fun doAction(event: ActionEvent) {
                    choice = chosen
                    close(OK_EXIT_CODE)
                }
            }

        override fun getDimensionServiceKey(): String =
            "org.key_project.ide.intellij.SettingsDifferencesDialog"
    }

    /** The differences as rows: taclet options first, since they change what was proved. */
    private class DifferencesModel(differing: List<OptionDifferenceDto>) : AbstractTableModel() {

        private val columns = listOf("Option", "In the proof", "Now", "Kind")
        private val rows = differing.sortedBy { if (it.kind == "taclet") 0 else 1 }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(row: Int, column: Int): String {
            val difference = rows[row]
            return when (column) {
                0 -> difference.label
                1 -> difference.saved
                2 -> difference.current
                else -> difference.kind
            }
        }
    }
}

/**
 * Opens proofs in a KeY window.
 *
 * Both ways end in the same window; they differ in which proof it opens.
 */
object ProofWindow {

    /**
     * Opens a saved proof.
     *
     * @param project the project
     * @param proofFile the proof, relative to the project root
     */
    fun openSaved(project: Project, proofFile: String) {
        val root = Path.of(project.basePath ?: return)
        KeyBridge.of(project).openInKeY(root.resolve(proofFile).normalize().toString())
    }

    /**
     * Builds a proof under the settings the obligation is meant to be attempted with, and
     * opens it.
     *
     * The bridge saves the proof with nothing done to it, so the window opens it as a proof
     * of this project and saving there puts it where the project keeps its proofs. A proof
     * saved before goes to the trash.
     *
     * @param project the project
     * @param contextId the context of the obligation
     * @param contractName the obligation
     */
    suspend fun startNew(project: Project, contextId: String, contractName: String) {
        val prepared = KeyBridge.of(project).connected().getVerificationService()
            .prepare(StartParams(contextId, contractName))
            .await(Deadline.PROOF)
        openSaved(project, prepared.proofFile)
    }

    /**
     * Whether a proof is saved for an obligation.
     *
     * @param project the project
     * @param proofFile the proof, relative to the project root
     */
    fun isSaved(project: Project, proofFile: String): Boolean {
        val root = Path.of(project.basePath ?: return false)
        return Files.isRegularFile(root.resolve(proofFile).normalize())
    }
}

/**
 * Makes one text fragment of a tree or table cell act as a link.
 *
 * A cell is drawn by a renderer that is not on screen, so a click cannot be delivered to
 * it. Instead the renderer is asked what it would draw at the clicked point, and the
 * fragment found there carries a tag saying what the link is about.
 *
 * @param tagAt what the fragment under the pointer is tagged with, or null when there is
 *   no link there
 * @param onClick what to do with the tag when the link is clicked
 */
internal class FragmentLink(
    private val tagAt: (MouseEvent) -> Any?,
    private val onClick: (Any) -> Unit,
) : ClickListener() {

    override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
        if (event.button != MouseEvent.BUTTON1 || clickCount != 1) {
            return false
        }
        val tag = tagAt(event) ?: return false
        onClick(tag)
        return true
    }

    /** Installs the click handling and the hand cursor over the link. */
    fun install(component: JComponent) {
        installOn(component)
        component.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                component.cursor = if (tagAt(event) != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        })
    }

    companion object {
        /**
         * The tag of the fragment a renderer would draw at an x position.
         *
         * @param renderer the renderer, prepared for the cell
         * @param x the position, relative to the cell
         */
        fun tagOf(renderer: java.awt.Component?, x: Int): Any? =
            (renderer as? SimpleColoredComponent)?.getFragmentTagAt(x)
    }
}
