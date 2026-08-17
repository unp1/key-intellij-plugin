package org.key_project.ide.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

/**
 * Edits the verification contexts of a project.
 *
 * A context is one set of paths KeY can load: the sources to verify, the classpath, the
 * boot classpath and any includes. There can be several, because a project with several
 * modules needs one context per module.
 *
 * The page lists the contexts and edits the selected one below the list, so that each path
 * has a labelled field wide enough to read and a button to pick it. Every path can also be
 * typed.
 *
 * The values are read and written through the bridge, so the schema of the file is
 * implemented once rather than once per IDE. That bridge needs no KeY, so this page opens
 * without starting a prover.
 */
class KeyContextsConfigurable(private val project: Project) : Configurable {

    private val model = ContextTableModel()
    private val table = JBTable(model)
    private val form = ContextForm(project) { edited ->
        val row = table.selectedRow
        if (row >= 0) {
            model.set(row, edited)
        }
    }
    private val problems = JTextArea(6, 80)
    private val proofDirectory = JBTextField(20)
    private var loaded: List<ContextDto> = emptyList()
    private var loadedProofDirectory: String = DEFAULT_PROOF_DIRECTORY

    /**
     * The configuration as last read.
     *
     * The page edits the contexts and the proof directory, and the file holds more than
     * that. Saving edits this rather than building a configuration out of the two fields
     * on screen, so what the page does not show survives being saved.
     */
    private var config: ProjectConfigDto = ProjectConfigDto()

    override fun getDisplayName(): String = "KeY Contexts"

    override fun createComponent(): JComponent {
        problems.isEditable = false
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                form.show(model.at(table.selectedRow))
            }
        }

        val list = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                select(model.add())
            }
            .setRemoveAction {
                val removed = table.selectedRow
                model.removeAt(removed)
                select(minOf(removed, model.rowCount - 1))
            }
            .addExtraAction(EditOptions())
            .setPreferredSize(Dimension(0, JBUI.scale(140)))
            .createPanel()

        val built = JPanel(BorderLayout()).apply {
            // The settings dialog scrolls a page that does not fit, and its scrollbar is
            // drawn over the page's right edge. The margin keeps the fields out from under
            // it.
            border = JBUI.Borders.emptyRight(PAGE_MARGIN)
            add(header(), BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    add(list, BorderLayout.NORTH)
                    add(form.component, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            add(footer(), BorderLayout.SOUTH)
        }
        reset()
        return built
    }

    /**
     * Edits the settings of the selected context.
     *
     * The dialog stores what it changed itself, so what it changed is put back into the
     * page rather than read again, which would throw away paths the user has typed and
     * not yet applied.
     */
    private fun editOptions() {
        val row = table.selectedRow
        val context = model.at(row) ?: return
        KeyTasks.of(project).launch("Reading KeY options") {
            val updated = KeyOptions.editContext(project, context.id) ?: return@launch
            val options = updated.contexts.firstOrNull { it.id == context.id }?.options
            onDialogThread {
                config = updated
                model.at(row)?.let { model.set(row, it.copy(options = options)) }
                loaded = loaded.map {
                    if (it.id == context.id) it.copy(options = options) else it
                }
                form.show(model.at(row))
            }
        }
    }

    /** The toolbar button that opens the settings of the selected context. */
    private inner class EditOptions : AnAction(
        "Proof Options\u2026",
        "Set the taclet and strategy options this context is proved with",
        AllIcons.General.Settings,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = table.selectedRow >= 0
        }

        override fun actionPerformed(event: AnActionEvent) = editOptions()
    }

    /** The proof directory, above the list of contexts. */
    private fun header(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        add(JBLabel("Proofs are stored in: "))
        add(proofDirectory)
    }

    /** What applying the page reported. */
    private fun footer(): JComponent = JPanel(BorderLayout()).apply {
        add(JBLabel("Report:"), BorderLayout.NORTH)
        add(JBScrollPane(problems), BorderLayout.CENTER)
    }

    /**
     * Selects a row and shows it in the form.
     *
     * @param row the row to select, or -1 to select nothing
     */
    private fun select(row: Int) {
        if (row in 0 until model.rowCount) {
            table.selectionModel.setSelectionInterval(row, row)
        } else {
            table.selectionModel.clearSelection()
            form.show(null)
        }
    }

    override fun isModified(): Boolean =
        model.contexts() != loaded || proofDirectoryText() != loadedProofDirectory

    /**
     * What the field says, with an empty field meaning the default directory.
     *
     * @return the directory to store proofs in
     */
    private fun proofDirectoryText(): String =
        proofDirectory.text.trim().ifBlank { DEFAULT_PROOF_DIRECTORY }

    /**
     * Stores what is on screen and reports what KeY would refuse to load.
     *
     * Applying is the moment the contexts become the ones the project uses, so it is also
     * the moment to say whether they can be loaded. An error keeps the dialog open, so that
     * pressing OK cannot leave the page with the report unread.
     *
     * @throws ConfigurationException if a context names something that is not there
     */
    override fun apply() {
        var errors = emptyList<ProblemDto>()
        run("saved") {
            val service = KeyConfigBridge.of(project).configService()
            config = config.copy(
                contexts = model.contexts(),
                proofDirectory = proofDirectoryText(),
            )
            service.set(config).await(Deadline.CONFIG)
            loaded = model.contexts()
            loadedProofDirectory = proofDirectoryText()
            // The views and the menus follow from whether the project declares a context.
            KeyProject.of(project).forget()

            val found = service.validate(ValidateParams(null)).await(Deadline.CONFIG).problems
            errors = found.filter { it.severity.equals("ERROR", ignoreCase = true) }
            if (found.isEmpty()) {
                "Saved ${loaded.size} context(s). Every context can be loaded."
            } else {
                "Saved ${loaded.size} context(s).\n" + found.joinToString("\n") {
                    "${it.severity} ${it.contextId}.${it.field}: ${it.message}"
                }
            }
        }
        if (errors.isNotEmpty()) {
            throw ConfigurationException(errors.joinToString("\n") {
                "${it.contextId}.${it.field}: ${it.message}"
            })
        }
    }

    override fun reset() {
        run("read") {
            config = KeyProject.of(project).config()
            loaded = config.contexts
            loadedProofDirectory = config.proofDirectory
            model.setContexts(config.contexts)
            proofDirectory.text = config.proofDirectory
            select(if (config.contexts.isEmpty()) -1 else 0)
            "Read ${config.contexts.size} context(s)."
        }
    }

    /**
     * Runs a call against the bridge and reports the outcome in the message area, so a
     * missing jar or an unreadable file is visible on the page rather than only in the
     * log.
     */
    private fun run(action: String, call: () -> String) {
        problems.text = try {
            call()
        } catch (failure: Exception) {
            val cause = generateSequence(failure as Throwable) { it.cause }
                .lastOrNull { !it.message.isNullOrBlank() } ?: failure
            "The configuration could not be $action: ${cause.message}"
        }
    }

    private companion object {
        /** Room between the fields and the scrollbar of the settings dialog, in pixels. */
        const val PAGE_MARGIN = 16
    }

    /**
     * The contexts as a table, one row each.
     *
     * The table names the contexts and shows what each verifies. The rest of a context is
     * edited in the form below, where the fields are wide enough to read.
     */
    private class ContextTableModel : AbstractTableModel() {

        private val columns = listOf("Context", "Java source")
        private val rows = mutableListOf<ContextDto>()

        fun contexts(): List<ContextDto> = rows.toList()

        fun setContexts(contexts: List<ContextDto>) {
            rows.clear()
            rows.addAll(contexts)
            fireTableDataChanged()
        }

        /**
         * The context in a row.
         *
         * @param row the row, or -1 when nothing is selected
         * @return the context, or null when the row is not one of the rows
         */
        fun at(row: Int): ContextDto? = rows.getOrNull(row)

        /**
         * Replaces a context with an edited one.
         *
         * @param row the row it is in
         * @param context what it says now
         */
        fun set(row: Int, context: ContextDto) {
            if (rows[row] != context) {
                rows[row] = context
                fireTableRowsUpdated(row, row)
            }
        }

        /**
         * Appends a context with a name no other context has.
         *
         * @return the row it was added as
         */
        fun add(): Int {
            val taken = rows.map { it.id }.toSet()
            val id = generateSequence(rows.size + 1) { it + 1 }
                .map { "context$it" }
                .first { it !in taken }
            rows.add(ContextDto(id = id))
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
            return rows.size - 1
        }

        fun removeAt(row: Int) {
            if (row in rows.indices) {
                rows.removeAt(row)
                fireTableRowsDeleted(row, row)
            }
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun isCellEditable(row: Int, column: Int): Boolean = false

        override fun getValueAt(row: Int, column: Int): String {
            val context = rows[row]
            return if (column == 0) context.id else context.javaSource
        }
    }

    /**
     * The paths of one context, each on its own line.
     *
     * The form reports every edit at once, so the list above it and the Apply button see
     * what is typed as it is typed, without waiting for the field to lose focus.
     *
     * @param project the project the settings belong to
     * @param onEdited what to do with the context the fields describe after an edit
     */
    private class ContextForm(project: Project, private val onEdited: (ContextDto) -> Unit) {

        private val id = JBTextField()
        private val javaSource = pathField(
            project,
            FileChooserDescriptorFactory.singleDir()
                .withTitle("Java Source Directory")
                .withDescription("The sources KeY loads and verifies."),
        )
        private val bootclasspath = pathField(
            project,
            FileChooserDescriptorFactory.singleDir()
                .withTitle("Boot Classpath Directory")
                .withDescription("The stubs KeY uses instead of the JDK."),
        )
        private val classpath = PathList(
            project,
            "Classpath",
            FileChooserDescriptorFactory.multiFilesOrDirs()
                .withTitle("Classpath Entries")
                .withDescription("Jars and directories the sources are compiled against."),
        )
        private val includes = PathList(
            project,
            "Includes",
            FileChooserDescriptorFactory.multiFiles()
                .withTitle("Include Files")
                .withDescription("Key files loaded with the context.")
                .withExtensionFilter("key"),
        )

        /** Set while the fields are being filled in, so that filling them is not an edit. */
        private var filling = false

        /**
         * The context being edited.
         *
         * An edit is reported as a copy of this rather than as a context built out of the
         * fields, so that what the form does not show, such as the settings the context
         * states, is not lost by editing a path.
         */
        private var shown: ContextDto? = null

        val component: JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", id)
            .addLabeledComponent("Java source:", javaSource)
            .addLabeledComponent("Boot classpath:", bootclasspath)
            .addLabeledComponent("Classpath:", classpath.component, true)
            .addLabeledComponent("Includes:", includes.component, true)
            .addComponentToRightColumn(
                JBLabel(
                    "Paths inside the project are stored relative to the project root.",
                    UIUtil.ComponentStyle.SMALL,
                ),
            )
            .panel
            .apply { border = JBUI.Borders.emptyTop(8) }

        init {
            id.whenTextChanges(::report)
            javaSource.textField.whenTextChanges(::report)
            bootclasspath.textField.whenTextChanges(::report)
            classpath.onChanged(::report)
            includes.onChanged(::report)
            show(null)
        }

        /**
         * Shows a context, or nothing.
         *
         * @param context the context to edit, or null when no context is selected
         */
        fun show(context: ContextDto?) {
            shown = context
            filling = true
            try {
                id.text = context?.id.orEmpty()
                javaSource.text = context?.javaSource.orEmpty()
                bootclasspath.text = context?.bootclasspath.orEmpty()
                classpath.paths = context?.classpath.orEmpty()
                includes.paths = context?.includes.orEmpty()
            } finally {
                filling = false
            }
            UIUtil.setEnabled(component, context != null, true)
        }

        private fun report() {
            val context = shown
            if (!filling && context != null) {
                onEdited(
                    context.copy(
                        id = id.text.trim(),
                        javaSource = javaSource.text.trim(),
                        classpath = classpath.paths,
                        bootclasspath = bootclasspath.text.trim().ifBlank { null },
                        includes = includes.paths,
                    ),
                )
            }
        }
    }
}

/**
 * Runs a listener whenever the text of a field changes, however it changed.
 *
 * @param listener what to run
 */
private fun JTextField.whenTextChanges(listener: () -> Unit) {
    document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(event: DocumentEvent) = listener()
    })
}
