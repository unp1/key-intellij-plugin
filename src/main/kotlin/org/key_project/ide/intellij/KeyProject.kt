package org.key_project.ide.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the plugin knows about the project, and the only place it is kept.
 *
 * Every view and every action reads the project from here rather than asking the bridge for
 * itself, so a listing is made once per change instead of once per view, and no two views
 * can disagree about what a proof is worth.
 *
 * Reading is remembered until something changes it. What changes it is either an edit made
 * through this service, or the bridge reporting that proof states have moved; both end in
 * one notification, which is what the views redraw from.
 */
@Service(Service.Level.PROJECT)
class KeyProject(private val project: Project, private val scope: CoroutineScope) {

    /** One row of the verification table: an obligation, and what the last attempt measured. */
    data class Row(
        val contextId: String,
        val obligation: ObligationDto,
        val outcome: ProofOutcomeDto,
    ) {
        /** How the obligation reads to a user, as the bridge named it. */
        val label: String get() = obligation.label.ifBlank { obligation.contractName }

        val contractName: String get() = obligation.contractName

        /** How the saved proof's settings differ from the current ones, if they do. */
        val differingSettings: List<OptionDifferenceDto> get() = obligation.differingSettings
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var configuration: ProjectConfigDto? = null

    private val listings = ConcurrentHashMap<String, List<ObligationDto>>()
    private val uses = ConcurrentHashMap<String, Map<String, List<String>>>()

    /** What a run measured, by context and contract, until the obligation is listed otherwise. */
    private val measured = ConcurrentHashMap<String, ProofOutcomeDto>()

    /**
     * Reports a change of the project until the given owner is disposed.
     *
     * @param owner what the listener belongs to
     * @param listener what to run when the project has changed
     */
    fun onChanged(owner: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(owner) { listeners.remove(listener) }
    }

    /**
     * The project's configuration, as the settings file holds it.
     *
     * Reads it once and remembers it: a menu asks for the contexts every time it is drawn.
     */
    fun config(): ProjectConfigDto {
        configuration?.let { return it }
        val read = KeyConfigBridge.of(project).configService().get().await(Deadline.CONFIG)
        configuration = read
        return read
    }

    /**
     * The contexts this project declares, as far as is known.
     *
     * Answers from what was last read, and reads again in the background when nothing is
     * known yet, so that drawing a menu never waits for the bridge.
     *
     * @return the context ids, empty when the project declares none or nothing is read yet
     */
    fun contextIds(): List<String> {
        configuration?.let { return it.contexts.map { context -> context.id } }
        scope.launch(Dispatchers.IO) {
            runCatching { config() }.onSuccess { announce() }
        }
        return emptyList()
    }

    /** Whether the project declares any context, as far as is known. */
    fun anyContext(): Boolean = contextIds().isNotEmpty()

    /** Which prover the project runs its proofs with, as far as is known. */
    fun prover(): ProverOptionsDto = configuration?.prover ?: ProverOptionsDto()

    /** Everything a context can be asked to prove, with the status of each. */
    fun obligations(contextId: String): List<ObligationDto> {
        listings[contextId]?.let { return it }
        val listed = KeyBridge.of(project).connected().getVerificationService()
            .list(ListObligationsParams(contextId)).await(Deadline.CONTEXT).obligations
        listings[contextId] = listed
        // The context loaded, so nothing KeY refused it for before stands any more.
        RefusedSources.of(project).accept(contextId)
        return listed
    }

    /** Everything the project can be asked to prove, by context. */
    fun everything(): Map<String, List<ObligationDto>> =
        config().contexts.associate { it.id to obligations(it.id) }

    /** One obligation, or null where the context no longer holds it. */
    fun obligation(contextId: String, contractName: String): ObligationDto? =
        obligations(contextId).firstOrNull { it.contractName == contractName }

    /**
     * The context whose sources hold a file, preferring the most specific one.
     *
     * @return the context id, or null when no configured context covers the file
     */
    fun contextFor(file: Path): String? =
        KeyConfigBridge.of(project).configService()
            .contextAt(ContextAtParams(file.toUri().toString()))
            .await(Deadline.CONFIG)
            .contextId

    /** Which contracts each proof of a context used, as KeY reported them. */
    fun dependencies(contextId: String): Map<String, List<String>> {
        uses[contextId]?.let { return it }
        val reported = KeyBridge.of(project).connected().getVerificationService()
            .dependencies(ListObligationsParams(contextId)).await(Deadline.CONTEXT)
        val byContract = reported.obligations.associate { it.contractName to it.uses }
        uses[contextId] = byContract
        return byContract
    }

    /**
     * What a position in a file stands for, as the bridge decided.
     *
     * A caret inside a method means that method's contracts, and a caret anywhere else means
     * the whole file. Which method a caret sits in, and which contracts are about it, follow
     * from what KeY loaded, so the bridge answers both.
     *
     * @param file the file the caret is in
     * @param line the 1-based line
     * @param column the 1-based column
     */
    fun at(file: Path, line: Int, column: Int): PositionResult =
        KeyBridge.of(project).connected().getVerificationService()
            .at(PositionParams(file.toUri().toString(), line, column)).await(Deadline.CONTEXT)

    /** What to mark in the margin of a file, as the bridge decided. */
    fun marks(file: Path): MarksResult =
        KeyBridge.of(project).connected().getVerificationService()
            .marks(MarksParams(file.toUri().toString())).await(Deadline.CONTEXT)

    /**
     * Every obligation of the project, with what the last attempt measured.
     *
     * The listing says what a proof is worth; a run in this session also says what it cost,
     * and that is kept until the obligation is listed with another status.
     */
    fun rows(): List<Row> = everything().flatMap { (contextId, obligations) ->
        obligations.map { obligation ->
            Row(
                contextId,
                obligation,
                outcomeFor(obligation, measured[keyOf(contextId, obligation.contractName)]),
            )
        }
    }

    /**
     * How the obligations of a context read, by contract name.
     *
     * A run reads this for its progress line and its summary, so that they say what the
     * views say.
     */
    fun labels(contextId: String): Map<String, String> =
        obligations(contextId).associate { it.contractName to it.label.ifBlank { it.contractName } }

    /** The rows of the obligations a caller names, in the order it named them. */
    fun rowsOf(contextId: String, contractNames: Collection<String>): List<Row> =
        rows().filter { it.contextId == contextId && it.contractName in contractNames }

    /** Records what a run or a replay measured, and tells the views. */
    fun record(contextId: String, outcomes: List<ProofOutcomeDto>) {
        outcomes.forEach { measured[keyOf(contextId, it.contractName)] = it }
        forget(contextId)
    }

    /** Forgets what runs measured, which is what a user asks for before a fresh run. */
    fun clearMeasurements() {
        measured.clear()
        announce()
    }

    /** Changes the settings configured at one level, and tells the views. */
    fun setOptions(contextId: String?, contractNames: List<String>, change: OptionChangeDto) {
        KeyConfigBridge.of(project).configService()
            .setOptions(SetOptionsParams(contextId, contractNames, change))
            .await(Deadline.CONFIG)
        forget(contextId)
    }

    /** Chooses the prover the project runs its proofs with, and tells the views. */
    fun setProver(prover: ProverOptionsDto) {
        KeyConfigBridge.of(project).configService().setProver(SetProverParams(prover))
            .await(Deadline.CONFIG)
        forget()
    }

    /** Writes the configuration, and tells the views. */
    fun setConfig(config: ProjectConfigDto) {
        KeyConfigBridge.of(project).configService().set(config).await(Deadline.CONFIG)
        forget()
    }

    /**
     * Forgets what was read, and tells the views.
     *
     * @param contextId the context that changed, or null when the whole project did
     */
    fun forget(contextId: String? = null) {
        configuration = null
        if (contextId == null) {
            listings.clear()
            uses.clear()
        } else {
            listings.remove(contextId)
            uses.remove(contextId)
        }
        announce()
    }

    private fun announce() = listeners.forEach { it() }

    private fun keyOf(contextId: String, contractName: String) = "$contextId $contractName"

    companion object {

        /**
         * What a row shows for an obligation.
         *
         * The listing says what a proof is worth; a run in this session also says what it
         * cost. The measurements are kept while the listing still reports the status the run
         * ended in, and dropped once it reports another: a row reading closed beside an
         * obligation KeY no longer calls closed is a contradiction, and the run's own
         * numbers are not evidence of a state KeY has withdrawn.
         *
         * @param obligation the obligation as the bridge listed it
         * @param measured what a run measured for it, or null when none has
         */
        fun outcomeFor(obligation: ObligationDto, measured: ProofOutcomeDto?): ProofOutcomeDto =
            if (measured != null && measured.status == obligation.status) measured
            else ProofOutcomeDto(
                contractName = obligation.contractName,
                status = obligation.status,
                statusExplanation = obligation.statusExplanation,
                proofFile = obligation.proofFile,
            )

        fun of(project: Project): KeyProject = project.service()
    }
}
