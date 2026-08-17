package org.key_project.ide.intellij

import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Edits the settings the project uses where nothing else says otherwise.
 *
 * A context may differ from the project and one obligation may differ from its context.
 * Those two are edited where they are seen: a context on the contexts page, an obligation
 * through the Proof Options entry of its context menu. This page is the level underneath both.
 *
 * The taclet options a project can choose from are the choices KeY read from its rule
 * files, so the page has to load a context to know them. That takes seconds, so the fields
 * appear once it is loaded rather than the dialog waiting for them.
 */
class KeyOptionsConfigurable(private val project: Project) : Configurable {

    private val cards = CardLayout()
    private val component = JPanel(cards)
    private val prover = ProverForm()

    private var editor: OptionsEditor? = null
    private var config: ProjectConfigDto = ProjectConfigDto()
    private var statedProver = ProverOptionsDto()

    override fun getDisplayName(): String = "Proof Options"

    override fun createComponent(): JComponent {
        component.add(message("Reading the options KeY offers…"), READING)
        cards.show(component, READING)
        load()
        return component
    }

    private fun message(text: String): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JBLabel(text)) }

    /** Reads the configuration and what the first context offers, then builds the fields. */
    private fun load() {
        KeyTasks.of(project).launch("Reading KeY options") {
            val read = KeyProject.of(project).config()
            // Which options KeY offers does not depend on the project, so this page works
            // before any context is declared: what is set here is what every context starts
            // from.
            val available = KeyOptions.available(project, read.contexts.firstOrNull()?.id)
            onDialogThread {
                config = read
                statedProver = read.prover
                build(available)
            }
        }
    }

    /** Puts the fields on the page, with what the project states already in them. */
    private fun build(available: AvailableOptionsDto) {
        val built = OptionsEditor(available, "KeY's default")
        built.show(listOf(config.options ?: ProofOptionsDto()), available.defaults)
        prover.show(statedProver)
        editor = built

        component.add(
            JPanel(BorderLayout()).apply {
                // The settings dialog draws its scrollbar over the page's right edge.
                border = JBUI.Borders.emptyRight(16)
                add(built.component, BorderLayout.CENTER)
                add(
                    JPanel(BorderLayout()).apply {
                        add(prover.component, BorderLayout.NORTH)
                        add(staleSettings(), BorderLayout.SOUTH)
                    },
                    BorderLayout.SOUTH,
                )
            },
            FIELDS,
        )
        cards.show(component, FIELDS)
    }

    /**
     * The button that removes settings whose proof obligation is gone.
     *
     * Settings are kept under the name of the contract they were set for, so a renamed or
     * removed class or method leaves them behind under a name nothing answers to. They go
     * only on request and only after the user has seen them, since a method may be gone
     * only for a while.
     */
    private fun staleSettings(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            "Clean up dangling proof obligation settings (e.g., class/method removed or " +
                "renamed):",
            JButton("Find and Remove…").apply { addActionListener { removeStaleSettings() } },
        )
        .panel
        .apply { border = JBUI.Borders.emptyTop(12) }

    private fun removeStaleSettings() {
        KeyTasks.of(project).launch("Looking for dangling proof obligation settings") {
            val service = KeyBridge.of(project).connected().getVerificationService()
            val contexts = KeyProject.of(project).contextIds()
            val stale = contexts.associateWith { contextId ->
                service.staleOptions(ListObligationsParams(contextId)).await(Deadline.CONTEXT)
                    .contractNames
            }.filterValues { it.isNotEmpty() }
            if (stale.isEmpty()) {
                KeyNotifications.info(project, "No dangling proof obligation settings.")
                return@launch
            }
            val listed = stale.entries.joinToString("\n\n") { (contextId, names) ->
                "In $contextId:\n" +
                    names.joinToString("\n") { "    $it" }
            }
            val remove = onDialogThread {
                Messages.showYesNoDialog(
                    project,
                    "These proof obligations have settings but no longer exist:\n\n$listed\n\n" +
                        "Remove their settings?",
                    "Dangling Proof Obligation Settings",
                    "Remove", "Keep", Messages.getQuestionIcon(),
                ) == Messages.YES
            }
            if (!remove) {
                return@launch
            }
            var removed = 0
            for (contextId in stale.keys) {
                removed += service.removeStaleOptions(ListObligationsParams(contextId))
                    .await(Deadline.CONTEXT).contractNames.size
            }
            KeyNotifications.info(project, "Removed $removed dangling setting(s).")
        }
    }

    override fun isModified(): Boolean =
        editor?.edited() == true || prover.stated() != statedProver

    override fun apply() {
        val edited = editor ?: return
        KeyTasks.of(project).launch("Saving KeY options") {
            val service = KeyConfigBridge.of(project).configService()
            if (edited.edited()) {
                val words = edited.changesInWords()
                config = service.setOptions(SetOptionsParams(null, emptyList(), edited.change()))
                    .await(Deadline.CONFIG)
                KeyNotifications.info(project,
                    "Project settings changed:\n" + words.joinToString("\n"))
            }
            if (prover.stated() != statedProver) {
                config = service.setProver(SetProverParams(prover.stated()))
                    .await(Deadline.CONFIG)
                // The toolbar shows the prover too.
                KeyProject.of(project).forget()
            }
            statedProver = config.prover
            onDialogThread {
                edited.show(listOf(config.options ?: ProofOptionsDto()), defaults(edited))
            }
            // Every saved proof of the project may now be marked as made under other
            // settings, or no longer be.
            KeyProject.of(project).forget()
        }
    }

    /** What KeY uses where the project states nothing, which the fields already know. */
    private fun defaults(edited: OptionsEditor): ProofOptionsDto =
        KeyOptions.inherited(ProjectConfigDto(), null, edited.available())

    override fun reset() {
        if (editor != null) {
            load()
        }
    }

    private companion object {
        const val READING = "reading"
        const val FIELDS = "fields"
    }
}

/**
 * Which prover runs the proofs.
 *
 * KeY chooses between the single-threaded and the parallel prover from a setting that
 * belongs to the prover rather than to any proof, so the project states it once and every
 * context uses it.
 */
private class ProverForm {

    private val single = JBRadioButton("Single core")
    private val parallel = JBRadioButton("Multi core")
    private val threads = JSpinner(SpinnerNumberModel(KEY_DEFAULT_THREADS, 1, CORES, 1))

    val component: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            "Prover:",
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(single)
                add(parallel)
            },
        )
        .addLabeledComponent("Worker threads:", threads)
        .addComponentToRightColumn(
            JBLabel(
                "Multi core only. KeY allows one worker per core at most; this machine has " +
                    "$CORES. KeY's own choice is $KEY_DEFAULT_THREADS.",
                UIUtil.ComponentStyle.SMALL,
            ),
        )
        .panel
        .apply { border = JBUI.Borders.emptyTop(8) }

    init {
        ButtonGroup().apply {
            add(single)
            add(parallel)
        }
        single.addActionListener { threads.isEnabled = false }
        parallel.addActionListener { threads.isEnabled = true }
    }

    /** Shows what the project states, or KeY's own choice where it states nothing. */
    fun show(stated: ProverOptionsDto) {
        single.isSelected = !stated.parallel
        parallel.isSelected = stated.parallel
        threads.value = if (stated.threads in 1..CORES) stated.threads else KEY_DEFAULT_THREADS
        threads.isEnabled = stated.parallel
    }

    /** What the fields say. */
    fun stated(): ProverOptionsDto = ProverOptionsDto(parallel.isSelected, threads.value as Int)

    private companion object {
        /** How many workers KeY starts the multi-core prover with. */
        const val KEY_DEFAULT_THREADS = 4

        /** KeY allows at most one worker per core, so the field offers no more. */
        val CORES: Int = Runtime.getRuntime().availableProcessors()
    }
}
