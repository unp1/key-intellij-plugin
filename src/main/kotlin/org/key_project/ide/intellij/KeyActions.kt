package org.key_project.ide.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * What KeY can be asked to do with a selection.
 *
 * Each action is written once and offered wherever a selection can be made, because the
 * meaning of a selection is defined in one place. Working out which obligations a selection
 * means requires loading a context, so it runs in the background, after any question to the
 * user has been asked on the event thread.
 */
sealed class KeyAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val description = KeySelection.describe(event)
        event.presentation.isEnabledAndVisible = project != null && description != null
        if (description != null) {
            event.presentation.text = textFor(description)
        }
        // A project that declares no context has nothing KeY can be asked about. The
        // entries stay visible and say what is missing, rather than disappearing and
        // leaving the user to guess where they went.
        if (project != null && !KeyProject.of(project).anyContext()) {
            event.presentation.isEnabled = false
            event.presentation.description = NO_CONTEXT
        }
    }

    /** The menu text for a given selection, which is where the "in ..." comes from. */
    protected abstract fun textFor(description: KeySelection.Description): String

    /** Asked on the event thread, before anything is loaded or done. */
    protected open fun confirm(project: Project, description: KeySelection.Description): Boolean =
        true

    /** Performs the action on the obligations the selection means. */
    protected abstract suspend fun perform(
        project: Project,
        description: KeySelection.Description,
        work: Map<String, List<String>>,
    )

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val description = KeySelection.describe(event) ?: return
        if (!confirm(project, description)) {
            return
        }
        KeyTasks.of(project).launch("Finding proof obligations") {
            val work = KeySelection.obligations(project, description)
            if (work.isEmpty()) {
                KeyNotifications.warning(project, "Nothing to prove in ${description.label}.")
                return@launch
            }
            perform(project, description, work)
        }
    }

    protected fun verification(project: Project) =
        KeyBridge.of(project).connected().getVerificationService()

    companion object {
        /** Said where an action would work if the project declared a context. */
        const val NO_CONTEXT =
            "Define a context first, in the KeY tool window or in Settings, Tools, KeY, Contexts."
    }
}

/** Proves the obligations the selection means, without opening a window. */
class VerifyAction : KeyAction() {

    override fun textFor(description: KeySelection.Description): String =
        "Verify Proof Obligations in '${description.label}'"

    /**
     * Starts one run for each context, all at the same time.
     *
     * The bridge proves different contexts at the same time, so a selection that covers
     * several of them takes as long as the slowest context rather than as long as all of
     * them together.
     */
    override suspend fun perform(
        project: Project,
        description: KeySelection.Description,
        work: Map<String, List<String>>,
    ) {
        work.forEach { (contextId, contracts) ->
            val what =
                if (work.size == 1) description.label else "${description.label} ($contextId)"
            Verification.prove(project, contextId, contracts, what)
        }
    }
}

/** Replays the saved proofs of the selection and reports what they turn out to be. */
class ReplayAction : KeyAction() {

    override fun textFor(description: KeySelection.Description): String =
        "Replay Saved Proofs in '${description.label}'"

    override suspend fun perform(
        project: Project,
        description: KeySelection.Description,
        work: Map<String, List<String>>,
    ) {
        val results = Verification.replay(project, work)
        KeyNotifications.info(project, Verification.summaryOf(results))
    }
}

/** Deletes the saved proofs of the selection. */
class RemoveProofAction : KeyAction() {

    override fun textFor(description: KeySelection.Description): String =
        "Remove Saved Proofs in '${description.label}'"

    override fun confirm(project: Project, description: KeySelection.Description): Boolean =
        Messages.showYesNoDialog(
            project,
            "Delete the saved proofs of ${description.label}? This cannot be undone.",
            "Remove Saved Proof",
            Messages.getWarningIcon(),
        ) == Messages.YES

    override suspend fun perform(
        project: Project,
        description: KeySelection.Description,
        work: Map<String, List<String>>,
    ) {
        var removed = 0
        work.forEach { (contextId, contracts) ->
            removed += verification(project).removeProof(ObligationsParams(contextId, contracts))
                .await(Deadline.CONTEXT).removed
        }
        KeyVfs.refreshProofs(project)
        KeyNotifications.info(project, "Removed $removed saved proof(s).")
    }
}

/** Opens a saved proof in a KeY window. This works on one proof at a time. */
class OpenProofAction : KeyAction() {

    override fun textFor(description: KeySelection.Description): String = "Open Proof in KeY"

    override fun update(event: AnActionEvent) {
        super.update(event)
        // One window per proof would be useless, so the action is enabled only when a
        // single obligation is selected.
        val selected = event.getData(KeySelection.OBLIGATIONS)
        if (selected != null) {
            event.presentation.isEnabled = selected.size == 1
        }
    }

    /**
     * Opens the saved proof, or a new one when none is saved.
     *
     * A proof saved under other settings than the current ones is not opened without asking:
     * the user sees what differs and chooses between the saved proof and a new one.
     */
    override suspend fun perform(
        project: Project,
        description: KeySelection.Description,
        work: Map<String, List<String>>,
    ) {
        val proofs = KeySelection.proofFiles(project, description)
        val proof = proofs.singleOrNull()
        if (proof == null) {
            KeyNotifications.warning(project, "Choose one proof to open.")
            return
        }
        val (contextId, contracts) = work.entries.first()
        val contractName = contracts.first()
        if (!ProofWindow.isSaved(project, proof)) {
            ProofWindow.startNew(project, contextId, contractName)
            return
        }
        val differing = KeyProject.of(project).obligations(contextId)
            .firstOrNull { it.contractName == contractName }?.differingSettings.orEmpty()
        if (differing.isEmpty()) {
            ProofWindow.openSaved(project, proof)
            return
        }
        when (SettingsDifferences.ask(project, description.label, differing)) {
            SettingsDifferences.Choice.OPEN_SAVED -> ProofWindow.openSaved(project, proof)
            SettingsDifferences.Choice.START_NEW ->
                ProofWindow.startNew(project, contextId, contractName)
            null -> Unit
        }
    }
}

/**
 * Opens the source an obligation comes from, at the declaration it is about.
 *
 * KeY records where it read each declaration, so the obligation itself says which file and
 * which line. This is offered only for a selection that names obligations, since a
 * selection of files is already in the source.
 */
class GoToSourceAction : KeyAction() {

    override fun textFor(description: KeySelection.Description): String = "Go to Source"

    override fun update(event: AnActionEvent) {
        super.update(event)
        val selected = event.getData(KeySelection.OBLIGATIONS)
        event.presentation.isEnabledAndVisible =
            event.presentation.isEnabledAndVisible && !selected.isNullOrEmpty()
    }

    override suspend fun perform(
        project: Project,
        description: KeySelection.Description,
        work: Map<String, List<String>>,
    ) {
        val obligation = firstOf(project, work)
        if (obligation == null) {
            KeyNotifications.warning(project, "KeY knows no source for ${description.label}.")
            return
        }
        val root = Path.of(project.basePath ?: return)
        val source = LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(root.resolve(obligation.sourceFile).normalize())
        if (source == null) {
            KeyNotifications.warning(project, "There is no file at ${obligation.sourceFile}.")
            return
        }
        val line = lineOf(obligation)
        withContext(Dispatchers.EDT) {
            // The obligation counts lines from one, the editor from zero.
            OpenFileDescriptor(project, source, (line - 1).coerceAtLeast(0), 0).navigate(true)
        }
    }

    /**
     * The obligation a selection is about, which is its first one.
     *
     * Several obligations of one class share a file, so the first of them leads to the
     * right place.
     *
     * @param project the project acted in
     * @param work the contracts to act on, by context
     * @return the obligation, or null if the context no longer lists any of them
     */
    private fun firstOf(project: Project, work: Map<String, List<String>>): ObligationDto? =
        work.entries.firstNotNullOfOrNull { (contextId, contracts) ->
            KeyProject.of(project).obligations(contextId)
                .firstOrNull { it.contractName in contracts }
        }

    companion object {
        /**
         * The line to show for an obligation.
         *
         * The method it is about, when KeY recorded a position for it, and otherwise the
         * class. A constructor and a model method have no position, so those land on the
         * class declaration.
         *
         * @param obligation the obligation to show
         * @return the 1-based line, or 1 when neither position is known
         */
        fun lineOf(obligation: ObligationDto): Int = when {
            obligation.targetLine > 0 -> obligation.targetLine
            obligation.classLine > 0 -> obligation.classLine
            else -> 1
        }
    }
}

/** Edits the settings the selected obligations are proved with. */
class OptionsAction : KeyAction() {

    override fun textFor(description: KeySelection.Description): String =
        "Proof Options for '${description.label}'…"

    override suspend fun perform(
        project: Project,
        description: KeySelection.Description,
        work: Map<String, List<String>>,
    ) {
        KeyOptions.editObligations(project, work, description.label)
    }
}

/**
 * Shows what the selected obligation's proof rests on.
 *
 * KeY says what a proof used while it holds it, so this shows what KeY has said so far;
 * an obligation nobody has verified in this session says so rather than saying nothing.
 */
class ShowDependenciesAction : KeyAction() {

    override fun textFor(description: KeySelection.Description): String = "Show Dependencies"

    override fun update(event: AnActionEvent) {
        super.update(event)
        val selected = event.getData(KeySelection.OBLIGATIONS)
        event.presentation.isEnabledAndVisible =
            event.presentation.isEnabledAndVisible && selected?.size == 1
    }

    override suspend fun perform(
        project: Project,
        description: KeySelection.Description,
        work: Map<String, List<String>>,
    ) {
        val (contextId, contracts) = work.entries.first()
        KeyToolWindow.showDependencies(project, contextId, contracts.first(), description.label)
    }
}

/**
 * Proves what the selected obligation's proof rests on and is not closed.
 *
 * Which those are is what KeY reported, shown in the dependencies tab; this proves them so
 * that KeY can call the proof that used them closed rather than closed but for lemmas.
 */
class VerifyDependenciesAction : KeyAction() {

    override fun textFor(description: KeySelection.Description): String =
        "Verify Dependencies of '${description.label}'"

    override fun update(event: AnActionEvent) {
        super.update(event)
        val selected = event.getData(KeySelection.OBLIGATIONS)
        event.presentation.isEnabledAndVisible =
            event.presentation.isEnabledAndVisible && selected?.size == 1
    }

    override suspend fun perform(
        project: Project,
        description: KeySelection.Description,
        work: Map<String, List<String>>,
    ) {
        val (contextId, contracts) = work.entries.first()
        val contractName = contracts.first()
        val reported = KeyProject.of(project).dependencies(contextId)
        val used = reported[contractName]
        if (used == null) {
            KeyNotifications.warning(project,
                "KeY has not held this proof yet, so it has not said what it uses. " +
                    "Verify or replay it first.")
            return
        }
        val obligations = KeyProject.of(project).obligations(contextId).associateBy {
            it.contractName
        }
        val toProve = used.filter { obligations[it]?.status != ProofStatus.CLOSED }
        if (toProve.isEmpty()) {
            KeyNotifications.info(project, "Everything this proof uses is closed already.")
            return
        }
        Verification.prove(project, contextId, toProve, "what ${description.label} uses")
    }
}
