package org.key_project.ide.intellij

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Reads and edits the settings proofs are attempted with.
 *
 * The settings live at three levels. The project states what everything uses, a context may
 * differ from the project, and one obligation may differ from its context. Each level states
 * only its differences, so changing the project changes every proof that does not say
 * otherwise.
 */
object KeyOptions {

    /**
     * Edits the settings of some obligations, all at once.
     *
     * @param project the project they belong to
     * @param work the obligations, by the context they belong to
     * @param what the selection, for the title of the dialog
     */
    suspend fun editObligations(project: Project, work: Map<String, List<String>>, what: String) {
        val config = KeyProject.of(project).config()
        val contextId = work.keys.first()
        val available = available(project, contextId)

        val stated = work.flatMap { (context, contracts) ->
            contracts.map { config.obligationOptions[context]?.get(it) ?: ProofOptionsDto() }
        }
        val names = work.flatMap { (context, contracts) ->
            contracts.map { KeyProject.of(project).obligation(context, it)?.label ?: it }
        }
        val inherited = inherited(config, contextId, available)

        val edited = ask(project, "Proof Options for $what", stated, inherited, available, names,
            "the context's setting") ?: return
        work.forEach { (context, contracts) ->
            KeyConfigBridge.of(project).configService()
                .setOptions(SetOptionsParams(context, contracts, edited.change))
                .await(Deadline.CONFIG)
        }
        val count = work.values.sumOf { it.size }
        KeyNotifications.info(project,
            "Settings changed for $count proof obligation(s):\n" + edited.inWords.joinToString("\n"))
        // Whether a saved proof was made under the settings now set is part of what the
        // views show, and it may have changed for every obligation edited.
        KeyProject.of(project).forget()
    }

    /**
     * Edits the settings of one context.
     *
     * @param project the project it belongs to
     * @param contextId the context to edit
     * @return the configuration as it now stands, or null when nothing was changed
     */
    suspend fun editContext(project: Project, contextId: String): ProjectConfigDto? {
        val service = KeyConfigBridge.of(project).configService()
        val config = service.get().await(Deadline.CONFIG)
        val available = available(project, contextId)
        val stated = config.contexts.firstOrNull { it.id == contextId }?.options
            ?: ProofOptionsDto()

        val edited = ask(project, "Proof Options for Context '$contextId'", listOf(stated),
            inherited(config, null, available), available, listOf(contextId),
            "the project's setting") ?: return null
        val updated = service.setOptions(SetOptionsParams(contextId, emptyList(), edited.change))
            .await(Deadline.CONFIG)
        KeyNotifications.info(project,
            "Settings changed for context '$contextId':\n" + edited.inWords.joinToString("\n"))
        // The saved proofs of the whole context may now be marked, or no longer be.
        KeyProject.of(project).forget()
        return updated
    }

    /**
     * What a context offers to choose from.
     *
     * This loads the context, which is what makes the taclet options of that project
     * knowable at all: they are choices KeY read from the rule files.
     *
     * @param project the project
     * @param contextId the context to ask about
     * @return the options and their values
     */
    /**
     * The options KeY offers.
     *
     * @param project the project asking
     * @param contextId a context to read them from where one is loaded, or null to read
     *        KeY's own rules, which is what a project that declares no context yet has
     */
    suspend fun available(project: Project, contextId: String?): AvailableOptionsDto =
        KeyBridge.of(project).connected().getVerificationService()
            .availableOptions(AvailableOptionsParams(contextId))
            .await(Deadline.CONFIG)

    /**
     * What a level shows where it states nothing.
     *
     * @param config the configuration as stored
     * @param contextId the context an obligation belongs to, or null for a context itself
     * @param available what KeY uses where no level states anything
     * @return the settings that show through
     */
    fun inherited(
        config: ProjectConfigDto,
        contextId: String?,
        available: AvailableOptionsDto,
    ): ProofOptionsDto {
        val levels = listOfNotNull(
            available.defaults,
            config.options,
            contextId?.let { id -> config.contexts.firstOrNull { it.id == id }?.options },
        )
        return levels.reduce { under, over ->
            ProofOptionsDto(
                taclet = under.taclet + over.taclet,
                strategy = under.strategy + over.strategy,
                maxSteps = if (over.maxSteps > 0) over.maxSteps else under.maxSteps,
            )
        }
    }

    /** What the user changed, and the same in words for telling them so. */
    class Edited(val change: OptionChangeDto, val inWords: List<String>)

    /**
     * Shows the dialog and waits for an answer.
     *
     * @param fallback what an option that is not set here uses instead, named as the user
     *        would name it
     * @return what the user changed, or null when the dialog was cancelled or nothing was
     *         changed
     */
    private suspend fun ask(
        project: Project,
        title: String,
        stated: List<ProofOptionsDto>,
        inherited: ProofOptionsDto,
        available: AvailableOptionsDto,
        names: List<String>,
        fallback: String,
    ): Edited? = withContext(Dispatchers.EDT) {
        val editor = OptionsEditor(available, fallback)
        editor.show(stated, inherited, names)
        val dialog = OptionsDialog(project, title, editor, stated.size)
        if (dialog.showAndGet() && editor.edited()) {
            Edited(editor.change(), editor.changesInWords())
        } else {
            null
        }
    }
}

/**
 * The dialog around an options editor.
 *
 * @param project the project the settings belong to
 * @param dialogTitle what is being edited
 * @param editor the fields
 * @param levels how many levels are being edited at once, which the user is told about
 */
private class OptionsDialog(
    project: Project,
    dialogTitle: String,
    private val editor: OptionsEditor,
    private val levels: Int,
) : DialogWrapper(project) {

    init {
        title = dialogTitle
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        if (levels > 1) {
            add(
                JBLabel(
                    "Editing $levels proof obligations. An option left alone keeps what each " +
                        "of them says; where they disagree, each is listed with its value.",
                    UIUtil.ComponentStyle.SMALL,
                ).apply { border = JBUI.Borders.emptyBottom(8) },
                BorderLayout.NORTH,
            )
        }
        add(editor.component, BorderLayout.CENTER)
        preferredSize = Dimension(JBUI.scale(820), JBUI.scale(620))
    }

    /** A button on the left that puts every option back to inheriting. */
    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction("Reset All to Inherited") {
            override fun doAction(event: ActionEvent) = editor.resetAllToInherited()
        },
    )

    /** Names the dialog to the platform, which then remembers its size and place. */
    override fun getDimensionServiceKey(): String = "org.key_project.ide.intellij.OptionsDialog"
}
