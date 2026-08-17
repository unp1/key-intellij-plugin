package org.key_project.ide.intellij

import com.intellij.openapi.project.Project
import com.intellij.platform.util.progress.reportRawProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Runs proofs, without a window.
 *
 * Proving is the normal thing this plugin does, so it runs as a background task with a
 * progress bar the user can cancel. A KeY window is opened only when the user asks to look
 * at a proof.
 */
object Verification {

    /**
     * Attempts obligations and reports what came of them.
     *
     * @param project the project they belong to
     * @param contextId the context holding them
     * @param contractNames the contracts to attempt, empty for all of the context
     * @param what how the run reads in the progress bar
     * @param onFinished run once the outcomes are in, off the event thread
     */
    fun prove(
        project: Project,
        contextId: String,
        contractNames: List<String>,
        what: String,
        onFinished: (ProveResult) -> Unit = {},
    ) {
        val bridge = KeyBridge.of(project)
        // The run needs an id, so that its progress reaches this progress bar and
        // cancelling it does not cancel the other runs.
        val runId = UUID.randomUUID().toString()
        // How each obligation reads is the bridge's answer, kept from the listing so that a
        // progress line and a summary name what the views name.
        val names = KeyProject.of(project).labels(contextId)

        KeyTasks.of(project).launch("Verifying $what with KeY") {
            reportRawProgress { progress ->
                progress.text("Loading $contextId into KeY")
                bridge.followProgress(runId) { report ->
                    progress.text(names[report.contractName] ?: report.contractName)
                    if (report.total > 0) {
                        progress.fraction(report.completed.toDouble() / report.total)
                        progress.details("${report.completed} of ${report.total}")
                    }
                }
                try {
                    val result = attempt(bridge, runId, contextId, contractNames)
                    // The proofs were written by the bridge process, which the IDE does
                    // not watch, so it has to be told.
                    KeyVfs.refreshProofs(project)
                    // A run replaces proofs, and what it replaced went to the trash.
                    ProofTrash.of(project).applyAfterProofsWritten()
                    KeyProject.of(project).record(contextId, result.outcomes)
                    report(project, result, names)
                    onFinished(result)
                } finally {
                    bridge.stopFollowing(runId)
                }
            }
        }
    }

    /**
     * Reads saved proofs back and reports what they turn out to be.
     *
     * A replay judges what is already on disk rather than proving anything, so it takes no
     * progress bar of its own; what it finds is recorded in the same place a run's outcomes
     * are, which is what makes the views agree.
     *
     * @param project the project the proofs belong to
     * @param work the contracts to replay, by context, empty for all of a context
     * @return what each context's proofs turned out to be
     */
    suspend fun replay(
        project: Project,
        work: Map<String, List<String>>,
    ): Map<String, ProveResult> {
        val verification = KeyBridge.of(project).connected().getVerificationService()
        return work.mapValues { (contextId, contractNames) ->
            val result = verification.replay(ObligationsParams(contextId, contractNames))
                .await(Deadline.PROOF)
            KeyProject.of(project).record(contextId, result.outcomes)
            result
        }
    }

    /**
     * How a replay reads in one line.
     *
     * @param results what [replay] returned
     */
    fun summaryOf(results: Map<String, ProveResult>): String {
        val outcomes = results.values.flatMap { it.outcomes }
        val closed = outcomes.count { it.status == ProofStatus.CLOSED }
        return "Replayed ${outcomes.size} proof(s), $closed closed."
    }

    /**
     * Asks the bridge to prove, and waits.
     *
     * Cancelling the progress bar cancels this coroutine. The bridge is then told to stop
     * the run, and what it proved before stopping is still collected, which is why that
     * last wait is not itself cancellable.
     *
     * @return what came of each obligation
     */
    private suspend fun attempt(
        bridge: KeyBridge,
        runId: String,
        contextId: String,
        contractNames: List<String>,
    ): ProveResult {
        val verification = bridge.connected().getVerificationService()
        val running = verification.prove(ProveParams(runId, contextId, contractNames))
        return try {
            running.await()
        } catch (cancelled: CancellationException) {
            verification.cancel(CancelParams(runId))
            withContext(NonCancellable) { running.await() }
        }
    }

    /** Reports the result of a run in one line, naming the obligations left open. */
    private fun report(project: Project, result: ProveResult, names: Map<String, String>) {
        val closed = result.outcomes.count { it.status == ProofStatus.CLOSED }
        val summary = buildString {
            append("KeY proved $closed of ${result.outcomes.size}")
            if (result.cancelled) {
                append(", stopped before the rest")
            }
            val unfinished = result.outcomes.filter { it.status != ProofStatus.CLOSED }
            if (unfinished.isNotEmpty()) {
                append(". Left open: ")
                append(unfinished.joinToString(", ") {
                    names[it.contractName] ?: it.contractName
                })
            }
        }
        if (closed == result.outcomes.size && !result.cancelled) {
            KeyNotifications.info(project, summary)
        } else {
            KeyNotifications.warning(project, summary)
        }
    }

}
