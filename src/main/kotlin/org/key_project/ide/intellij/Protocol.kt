package org.key_project.ide.intellij

import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import java.util.concurrent.CompletableFuture

/**
 * The wire form the bridge speaks.
 *
 * These declarations are deliberately a copy rather than a shared library: the bridge is
 * GPL-2.0-only because it links KeY, and this plugin stays independent of it by sharing
 * only the message format. What has to match is the JSON, not the code.
 */

data class InitializeParams(
    val clientName: String,
    val clientVersion: String,
    val protocolVersion: Int,
    val projectRoot: String,
)

data class InitializeResult(
    val keyVersion: String = "",
    val keyJarSha256: String = "",
    val bridgeVersion: String = "",
    val protocolVersion: Int = 0,
    val capabilities: List<String> = emptyList(),
)

data class ContextDto(
    val id: String = "",
    val javaSource: String = "",
    val classpath: List<String> = emptyList(),
    val bootclasspath: String? = null,
    val includes: List<String> = emptyList(),
    /** What this context differs from the project in, or null when it differs in nothing. */
    val options: ProofOptionsDto? = null,
)

data class ProjectConfigDto(
    val version: Int = 1,
    val contexts: List<ContextDto> = emptyList(),
    /** The directory where the project stores its proofs, relative to its root. */
    val proofDirectory: String = DEFAULT_PROOF_DIRECTORY,
    /** What proofs are attempted with unless a context or an obligation says otherwise. */
    val options: ProofOptionsDto? = null,
    /** Which prover runs the proofs, which the project states for all of them. */
    val prover: ProverOptionsDto = ProverOptionsDto(),
    /** What individual obligations say, by context and contract. */
    val obligationOptions: Map<String, Map<String, ProofOptionsDto>> = emptyMap(),
)

/**
 * The settings a proof is attempted with, as far as one level states them.
 *
 * A level states only what it differs in, so an empty field means the level above decides.
 */
data class ProofOptionsDto(
    val taclet: Map<String, String> = emptyMap(),
    val strategy: Map<String, String> = emptyMap(),
    /** How many rule applications a run may make, 0 to leave KeY's own. */
    val maxSteps: Int = 0,
    /** How long one attempt may take in ms. -1 is no timeout, 0 leaves the level above. */
    val timeout: Long = 0,
)

/**
 * Which prover the bridge runs proofs with.
 *
 * @property parallel whether to use the multi-core prover
 * @property threads how many workers it may use, 0 to let KeY choose
 */
data class ProverOptionsDto(val parallel: Boolean = false, val threads: Int = 0)

/** One value an option accepts, with what to show for it and what it means. */
data class OptionValueDto(
    val value: String = "",
    val label: String = "",
    val description: String = "",
)

/** One option and the values it accepts, in the order to offer them. */
data class OptionCategoryDto(
    val key: String = "",
    val label: String = "",
    val description: String = "",
    val values: List<OptionValueDto> = emptyList(),
)

/** What a context offers to choose from, and what it uses where no level states anything. */
data class AvailableOptionsDto(
    val taclet: List<OptionCategoryDto> = emptyList(),
    val strategy: List<OptionCategoryDto> = emptyList(),
    val defaults: ProofOptionsDto = ProofOptionsDto(),
)

/** Asks what KeY offers; a null context reads KeY's rules rather than a project. */
data class AvailableOptionsParams(val contextId: String? = null)

/**
 * An edit to what one level states.
 *
 * The dialog sends the fields the user touched and nothing else, so editing several
 * obligations at once leaves the rest of what each says alone. Clearing is listed apart
 * from setting, because inheriting is not a value.
 */
data class OptionChangeDto(
    val taclet: Map<String, String> = emptyMap(),
    val tacletCleared: List<String> = emptyList(),
    val strategy: Map<String, String> = emptyMap(),
    val strategyCleared: List<String> = emptyList(),
    val maxSteps: Int? = null,
    /** The timeout to set, -1 for none, 0 to inherit, or null when the form left it. */
    val timeout: Long? = null,
)

/**
 * Edits what one level states.
 *
 * @property contextId the context, or null for the project
 * @property contractNames the obligations, empty for the context or the project itself
 */
data class SetOptionsParams(
    val contextId: String?,
    val contractNames: List<String> = emptyList(),
    val change: OptionChangeDto,
)

data class SetProverParams(val prover: ProverOptionsDto)

/** The proof directory of a configuration that names none. */
const val DEFAULT_PROOF_DIRECTORY = "proofs"

data class ProblemDto(
    val severity: String = "",
    val contextId: String = "",
    val field: String = "",
    val message: String = "",
)

/** Asks which context holds a file, named as a `file:` URI or as a path. */
data class ContextAtParams(val uri: String = "")

/** The context whose sources hold a file, or null when none covers it. */
data class ContextAtResult(val contextId: String? = null)

data class ValidateParams(val contextId: String?)

data class ValidateResult(val problems: List<ProblemDto> = emptyList())

data class ResolveParams(
    val contextId: String,
    val uri: String,
    val line: Int,
    val column: Int,
)

data class MethodDto(
    val className: String = "",
    val name: String = "",
    val parameterTypes: List<String> = emptyList(),
    val constructor: Boolean = false,
    val startLine: Int = 0,
    val endLine: Int = 0,
) {
    /** How the method reads in a message to the user. */
    fun signature(): String = "$className.$name(${parameterTypes.joinToString(", ")})"
}

data class ListObligationsParams(val contextId: String)

data class ObligationDto(
    val contractName: String = "",
    val className: String = "",
    val target: String = "",
    val displayName: String = "",
    /** How the obligation reads to a user: the target with its parameters, as KeY writes it. */
    val label: String = "",
    val status: String = "UNKNOWN",
    val statusExplanation: String = "",
    val sourceFile: String = "",
    val classLine: Int = 0,
    val targetLine: Int = 0,
    val proofFile: String = "",
    val proofFileExists: Boolean = false,
    /**
     * How the saved proof's settings differ from the ones the obligation is meant to be
     * attempted with now; empty when there is no saved proof or nothing differs.
     */
    val differingSettings: List<OptionDifferenceDto> = emptyList(),
)

/**
 * One option a saved proof was made with that is set otherwise now.
 *
 * @property kind `taclet` or `strategy`; a taclet option changes what the proof proved, a
 *   strategy option only how it was found
 * @property label the option, worded as KeY words it
 * @property saved what the proof was made with
 * @property current what the obligation is meant to be attempted with now
 */
data class OptionDifferenceDto(
    val kind: String = "",
    val label: String = "",
    val saved: String = "",
    val current: String = "",
) {
    /** How the difference reads in a list. */
    fun sentence(): String = "$label: $saved in the proof, $current now"
}

data class ObligationsResult(val obligations: List<ObligationDto> = emptyList())

/** Asks what to mark in a source file, named as a `file:` URI or as a path. */
data class MarksParams(val uri: String = "")

/**
 * One problem KeY found in a source, as the bridge reported it.
 *
 * @param uri the file, as a `file:` URI, or null when KeY named none
 * @param line the 1-based line, 0 when KeY named none
 * @param column the 1-based column, 0 when KeY named none
 * @param message what KeY said about it
 */
data class SourceProblemDto(
    val uri: String? = null,
    val line: Int = 0,
    val column: Int = 0,
    val message: String = "",
)

/** What a failed context load carries besides its message: where KeY refused the source. */
data class LoadFailureDto(
    val contextId: String = "",
    val problems: List<SourceProblemDto> = emptyList(),
)

/** A position in a source file, as a caret sits in it. */
data class PositionParams(val uri: String = "", val line: Int = 0, val column: Int = 0)

/**
 * What a position in a source file stands for, as the bridge decided.
 *
 * @param contextId the context whose sources hold the file, null when none covers it
 * @param contractNames the contracts the position stands for
 * @param label how the position reads to a user
 */
data class PositionResult(
    val contextId: String? = null,
    val contractNames: List<String> = emptyList(),
    val label: String = "",
)

/** One line to mark, and what the mark says when hovered. */
data class MarkDto(val line: Int = 0, val mark: String = "", val tooltip: String = "")

/** What to mark in one file, and the context it belongs to. */
data class MarksResult(val contextId: String? = null, val marks: List<MarkDto> = emptyList())

data class IconsParams(val size: Int)

data class IconsResult(
    val icons: Map<String, String> = emptyMap(),
    val darkIcons: Map<String, String> = emptyMap(),
)

data class StartParams(val contextId: String, val contractName: String)

data class ObligationsChangedDto(val contextId: String? = null)

data class ProveParams(
    val runId: String,
    val contextId: String,
    val contractNames: List<String>,
)

data class CancelParams(val runId: String)

data class ProofOutcomeDto(
    val contractName: String = "",
    val status: String = "UNKNOWN",
    val statusExplanation: String = "",
    val nodes: Int = 0,
    val branches: Int = 0,
    val milliseconds: Long = 0,
    val proofFile: String = "",
    val message: String = "",
)

data class ProveResult(
    val outcomes: List<ProofOutcomeDto> = emptyList(),
    val cancelled: Boolean = false,
)

data class ObligationsParams(val contextId: String, val contractNames: List<String>)

data class RemovedResult(val removed: Int = 0)

data class PreparedResult(val proofFile: String = "")

/** Obligations that state settings but no longer exist, by contract name. */
data class StaleOptionsResult(val contractNames: List<String> = emptyList())

/** Which contracts one obligation's proof used, as KeY reported them. */
data class UsedContractsDto(
    val contractName: String = "",
    /** Whether KeY has said anything about it, which it has once it has held the proof. */
    val known: Boolean = false,
    val uses: List<String> = emptyList(),
)

/** What KeY reported about the proofs of a context. */
data class DependenciesResult(val obligations: List<UsedContractsDto> = emptyList())

/**
 * How the trash of replaced proofs is kept.
 *
 * @property mode NEVER, EMPTY, BELOW_SIZE or OLDER_THAN
 * @property megabytes the size to stay below, for BELOW_SIZE
 * @property days the age at which a proof is thrown away, for OLDER_THAN
 */
data class TrashPolicyDto(
    val mode: String = "NEVER",
    val megabytes: Int = 0,
    val days: Int = 0,
)

/** What pruning the trash threw away. */
data class PrunedResult(val files: Int = 0, val bytes: Long = 0)

data class ProveProgressDto(
    val runId: String = "",
    val contextId: String = "",
    val contractName: String = "",
    val completed: Int = 0,
    val total: Int = 0,
)

data class StateDto(val state: String = "", val detail: String? = null)

data class LogDto(val level: String = "", val text: String = "")

@JsonSegment("config")
interface ConfigService {
    @JsonRequest
    fun get(): CompletableFuture<ProjectConfigDto>

    @JsonRequest
    fun set(config: ProjectConfigDto): CompletableFuture<Void>

    @JsonRequest
    fun validate(params: ValidateParams): CompletableFuture<ValidateResult>

    /**
     * The context whose sources hold a file, preferring the most specific one.
     *
     * The bridge answers this: which source directory covers a file follows from the
     * configuration, which it owns.
     */
    @JsonRequest
    fun contextAt(params: ContextAtParams): CompletableFuture<ContextAtResult>

    /** Edits what one level states, leaving every field the dialog did not touch. */
    @JsonRequest
    fun setOptions(params: SetOptionsParams): CompletableFuture<ProjectConfigDto>

    /** Sets which prover runs the proofs. */
    @JsonRequest
    fun setProver(params: SetProverParams): CompletableFuture<ProjectConfigDto>

    /** Throws away replaced proofs the policy no longer keeps. */
    @JsonRequest
    fun pruneTrash(policy: TrashPolicyDto): CompletableFuture<PrunedResult>
}

interface VerificationService {
    @JsonRequest("method/resolveAt")
    fun resolveAt(params: ResolveParams): CompletableFuture<MethodDto>

    @JsonRequest("key/verifyAt")
    fun verifyAt(params: ResolveParams): CompletableFuture<MethodDto>

    @JsonRequest("po/list")
    fun list(params: ListObligationsParams): CompletableFuture<ObligationsResult>

    @JsonRequest("po/start")
    fun start(params: StartParams): CompletableFuture<Void>

    /**
     * What to mark in the margin of a source file, by line.
     *
     * The bridge decides which mark a line carries, so that a mark means the same in every
     * editor.
     */
    @JsonRequest("po/marks")
    fun marks(params: MarksParams): CompletableFuture<MarksResult>

    @JsonRequest("po/at")
    fun at(params: PositionParams): CompletableFuture<PositionResult>

    @JsonRequest("po/icons")
    fun icons(params: IconsParams): CompletableFuture<IconsResult>

    /** Attempts obligations with KeY's automatic strategy, without a user interface. */
    @JsonRequest("po/prove")
    fun prove(params: ProveParams): CompletableFuture<ProveResult>

    /** Stops one run, keeping what it proved before the stop and leaving others going. */
    @JsonRequest("po/cancel")
    fun cancel(params: CancelParams): CompletableFuture<Void>

    /** Reads saved proofs back and reports what they turn out to be. */
    @JsonRequest("po/replay")
    fun replay(params: ObligationsParams): CompletableFuture<ProveResult>

    /** Deletes saved proofs. */
    @JsonRequest("po/removeProof")
    fun removeProof(params: ObligationsParams): CompletableFuture<RemovedResult>

    /**
     * Builds one proof with the settings its obligation is meant to be attempted with and
     * saves it without attempting it, so that a KeY window can open it.
     */
    @JsonRequest("po/prepare")
    fun prepare(params: StartParams): CompletableFuture<PreparedResult>

    /** Which contracts the proofs of a context used, as KeY reported them. */
    @JsonRequest("po/dependencies")
    fun dependencies(params: ListObligationsParams): CompletableFuture<DependenciesResult>

    /** Names the obligations of a context that state settings but no longer exist. */
    @JsonRequest("options/stale")
    fun staleOptions(params: ListObligationsParams): CompletableFuture<StaleOptionsResult>

    /** Drops the settings of the obligations of a context that no longer exist. */
    @JsonRequest("options/removeStale")
    fun removeStaleOptions(params: ListObligationsParams): CompletableFuture<StaleOptionsResult>

    /** Reads what a context offers to choose from, loading it if it is not loaded yet. */
    @JsonRequest("options/available")
    fun availableOptions(params: AvailableOptionsParams): CompletableFuture<AvailableOptionsDto>
}

interface BridgeService {
    @JsonRequest
    fun initialize(params: InitializeParams): CompletableFuture<InitializeResult>

    /**
     * Answers that the bridge is still serving.
     *
     * The bridge answers this from its message loop whatever it is proving, so a request
     * that has passed its deadline can be told apart from a bridge that has stopped
     * answering at all.
     */
    @JsonRequest("ping")
    fun ping(): CompletableFuture<Boolean>

    @JsonNotification
    fun exit()

    @JsonDelegate
    fun getConfigService(): ConfigService

    @JsonDelegate
    fun getVerificationService(): VerificationService
}

/** What the bridge may tell the IDE without being asked. */
interface IdeClient {
    @JsonNotification("key/state")
    fun state(params: StateDto)

    @JsonNotification("log/message")
    fun log(params: LogDto)

    /** Proof states may have moved on, so any listing of them is stale. */
    @JsonNotification("po/changed")
    fun obligationsChanged(params: ObligationsChangedDto)

    /** Reports how far a run has got. */
    @JsonNotification("po/progress")
    fun proveProgress(params: ProveProgressDto)
}
