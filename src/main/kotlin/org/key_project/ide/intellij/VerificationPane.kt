package org.key_project.ide.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Shows where each of the project's proof obligations stands.
 *
 * There is one row per obligation, showing how it ended and, once a run in this session has
 * attempted it, what it cost. The actions on a row are the ones that are useful here: prove
 * it, replay a saved proof, delete a saved proof, or open one in KeY.
 */
class VerificationPane(private val project: Project) : Disposable {

    private val model = ResultsModel()
    /**
     * The table, which publishes what is selected in the form every view uses, so that the
     * KeY menu offers the same actions here as anywhere else.
     */
    private val table = object : JBTable(model), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            sink[KeySelection.OBLIGATIONS] = selected().map {
                KeySelection.Selected(it.contextId, it.contractName, it.outcome.proofFile)
            }
        }

        /** The status cell says when the proof's settings differ; the tooltip says how. */
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            val column = columnAtPoint(event.point)
            if (row < 0 || convertColumnIndexToModel(column) != STATUS_COLUMN) {
                return null
            }
            val differing =
                this@VerificationPane.model.rowAt(convertRowIndexToModel(row)).differingSettings
            return if (differing.isEmpty()) null else SettingsDifferences.tooltip(differing)
        }
    }

    val component: JPanel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    init {
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.autoCreateRowSorter = true
        table.columnModel.getColumn(TIME_COLUMN).cellRenderer =
            MeasurementRenderer { "%.1f s".format(it.toDouble()) }
        table.columnModel.getColumn(NODES_COLUMN).cellRenderer = MeasurementRenderer { "$it" }
        table.columnModel.getColumn(BRANCHES_COLUMN).cellRenderer = MeasurementRenderer { "$it" }
        table.columnModel.getColumn(STATUS_COLUMN).cellRenderer = StatusRenderer()
        FragmentLink(::noteAt) { tag ->
            val row = tag as KeyProject.Row
            SettingsDifferences.show(project, row.contextId, row.contractName,
                model.labelOf(row), row.outcome.proofFile, row.differingSettings)
        }.install(table)
        installMenu()
        // A run, a replay, a removal or a save changes what the table should say. Reading
        // the rows may load a context into KeY, so it is done off the event thread and only
        // what was read is put on it.
        KeyProject.of(project).onChanged(this) {
            if (component.isShowing) {
                listObligations()
            }
        }
        listWhenShown()
    }

    /** Lists the obligations again, for a user who says what they see is out of date. */
    fun refresh() = listObligations()

    /**
     * Lists the obligations the first time this tab is looked at.
     *
     * Listing loads every context, which costs seconds, and a project that is open with the
     * tool window closed should not pay for it. It is paid the moment the window opens.
     */
    private fun listWhenShown() {
        if (component.isShowing) {
            listObligations()
            return
        }
        component.addHierarchyListener(object : HierarchyListener {
            override fun hierarchyChanged(event: HierarchyEvent) {
                if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() == 0L) {
                    return
                }
                if (component.isShowing) {
                    component.removeHierarchyListener(this)
                    listObligations()
                }
            }
        })
    }

    /**
     * Lists everything the project can be asked to prove, so that the table shows how much
     * of it is done rather than only what this session has run.
     *
     * This loads the contexts, which takes several seconds and opens no window. An
     * obligation nobody has proved has no measurements to show, and neither has a proof
     * read from a file, because the file does not record what it cost.
     */
    private fun listObligations() {
        KeyTasks.of(project).launch("Reading proof obligations") {
            val config = KeyProject.of(project).config()
            withContext(Dispatchers.EDT) { table.emptyText.text = "Reading proof obligations…" }
            // The rows are read here, off the event thread, since reading them may load a
            // context into KeY. The icons are taken here too, because a cell draws on the
            // event thread and may not ask KeY for anything.
            val rows = try {
                KeyProject.of(project).rows()
            } catch (failure: Exception) {
                // An empty table has to say why it is empty: "nothing to show" over a source
                // KeY refused reads as "nothing to prove", which is not the case.
                withContext(Dispatchers.EDT) {
                    table.emptyText.text = KeyNotifications.describe(failure)
                    model.show(emptyList())
                }
                throw failure
            }
            runCatching { KeyIcons.of(project).fetch(ICON_SIZE) }
            withContext(Dispatchers.EDT) {
                table.emptyText.text = if (config.contexts.isEmpty()) NO_CONTEXT else NO_ROWS
                model.show(rows)
            }
            if (config.contexts.isEmpty()) {
                KeyNotifications.info(project, NO_CONTEXT)
            }
        }
    }

    /**
     * The menu on a row.
     *
     * Every action except opening KeY works on all selected rows, because re-proving
     * several at once is the normal case. Opening KeY works on a single row, since one
     * window per row would be useless.
     */
    private fun installMenu() {
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = selectUnderPointer(event)

            override fun mouseReleased(event: MouseEvent) = selectUnderPointer(event)

            private fun selectUnderPointer(event: MouseEvent) {
                if (!event.isPopupTrigger) {
                    return
                }
                val row = table.rowAtPoint(event.point)
                if (row < 0) {
                    return
                }
                // A right click outside the selection selects the row it landed on. A
                // right click inside it leaves the selection alone.
                if (!table.isRowSelected(row)) {
                    table.setRowSelectionInterval(row, row)
                }
            }
        })
        installKeyMenu(table)
    }

    /**
     * There is nothing to release here. This object only marks how long the results
     * listener lives, and the tool window's tab disposes it.
     */
    override fun dispose() = Unit

    private fun selected(): List<KeyProject.Row> =
        table.selectedRows.map { model.rowAt(table.convertRowIndexToModel(it)) }

    /** The rows, as columns a reader can compare. */
    private inner class ResultsModel : AbstractTableModel() {

        private val columns =
            listOf("Proof obligation", "Time", "Nodes", "Branches", "Status")
        private var rows: List<KeyProject.Row> = emptyList()

        /**
         * Shows rows that were read off the event thread.
         *
         * Rebuilding the table clears the selection, so the selected rows are found again
         * by their identity rather than by their position.
         */
        fun show(read: List<KeyProject.Row>) {
            val selected = selected().map { it.contextId to it.contractName }.toSet()
            rows = read
            fireTableDataChanged()
            reselect(selected)
        }

        private fun reselect(selected: Set<Pair<String, String>>) {
            if (selected.isEmpty()) {
                return
            }
            rows.forEachIndexed { index, row ->
                if ((row.contextId to row.contractName) in selected) {
                    val view = table.convertRowIndexToView(index)
                    if (view >= 0) {
                        table.addRowSelectionInterval(view, view)
                    }
                }
            }
        }

        fun rowAt(index: Int): KeyProject.Row = rows[index]

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        /**
         * The measured columns hold numbers, so that sorting them compares sizes rather
         * than spellings. What they read as is the renderer's business.
         */
        override fun getColumnClass(column: Int): Class<*> = when (column) {
            TIME_COLUMN -> Double::class.javaObjectType
            NODES_COLUMN, BRANCHES_COLUMN -> Int::class.javaObjectType
            else -> String::class.java
        }

        override fun getValueAt(row: Int, column: Int): Any? {
            val result = rows[row]
            // A proof read from disk has no measurements until it is replayed. Null
            // means "not measured"; a zero would look like a proof that took no time.
            val measured = result.outcome.nodes > 0
            return when (column) {
                TIME_COLUMN -> if (measured) result.outcome.milliseconds / 1000.0 else null
                NODES_COLUMN -> if (measured) result.outcome.nodes else null
                BRANCHES_COLUMN -> if (measured) result.outcome.branches else null
                STATUS_COLUMN -> statusOf(result)
                else -> result.label
            }
        }

        /** The status, with what went wrong appended when something did. */
        private fun statusOf(row: KeyProject.Row): String {
            val outcome = row.outcome
            val status = statusText(outcome.status)
            return if (outcome.message.isBlank()) status else "$status — ${outcome.message}"
        }

        /** How a row's contract reads in the table. */
        fun labelOf(row: KeyProject.Row): String = row.label
    }

    /**
     * Draws the status, and after it a note when the saved proof was made under other
     * settings than the current ones. The note is a link to the full list, tagged with the
     * row so that a click on it is told from a click on the status.
     *
     * The state carries KeY's own icon, which is what every other view shows for it.
     */
    private inner class StatusRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ) {
            val result = model.rowAt(table.convertRowIndexToModel(row))
            // KeY's own icon for the state, as the obligation tree and the gutter show it.
            icon = KeyIcons.of(project).forStatus(result.outcome.status, ICON_SIZE)
            append(value?.toString().orEmpty())
            if (result.differingSettings.isNotEmpty()) {
                append("  ")
                append(SettingsDifferences.NOTE, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, result)
            }
        }
    }

    /**
     * The row whose settings note is under the pointer.
     *
     * @return the row, or null when the pointer is not on such a note
     */
    private fun noteAt(event: MouseEvent): KeyProject.Row? {
        val row = table.rowAtPoint(event.point)
        val column = table.columnAtPoint(event.point)
        if (row < 0 || column < 0 || table.convertColumnIndexToModel(column) != STATUS_COLUMN) {
            return null
        }
        val renderer = table.prepareRenderer(table.getCellRenderer(row, column), row, column)
        val cell = table.getCellRect(row, column, false)
        return FragmentLink.tagOf(renderer, event.x - cell.x) as? KeyProject.Row
    }

    /** Draws a measurement right-aligned, or a dash when there is none. */
    private class MeasurementRenderer(
        private val format: (Number) -> String,
    ) : DefaultTableCellRenderer() {

        init {
            horizontalAlignment = SwingConstants.RIGHT
        }

        override fun setValue(value: Any?) {
            text = if (value is Number) format(value) else "—"
        }
    }

    private companion object {
        /** The edge length KeY's status icons are drawn at in a table row. */
        const val ICON_SIZE = 16

        const val NO_CONTEXT =
            "This project declares no context. Add one in Settings, Tools, KeY, Contexts."

        const val NO_ROWS = "No proof obligations: the contexts declare no contracts."

        /**
         * How a proof state reads in the table.
         *
         * KeY calls the state of an obligation nobody has proved NONE, which says nothing
         * to a reader looking for what is left to do.
         *
         * @param status the state as the bridge reports it
         * @return what to show
         */
        fun statusText(status: String): String =
            if (status == "NONE") "NOT YET STARTED" else status


        const val TIME_COLUMN = 1
        const val NODES_COLUMN = 2
        const val BRANCHES_COLUMN = 3
        const val STATUS_COLUMN = 4
    }
}
