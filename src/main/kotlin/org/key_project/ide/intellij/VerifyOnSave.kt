package org.key_project.ide.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a project's proofs up with its sources, when the user has asked for that.
 *
 * Saving a verified source makes KeY read the context again, after which nothing it said
 * about the old sources applies. What the proofs are worth now is KeY's to say, and saying
 * it means reading them back. Every saved proof of the context goes into one environment,
 * so KeY judges each of them against the others.
 *
 * What it then reports as unproved is attempted again: a proof that no longer closes, and
 * the contracts a proof rests on that are not proved themselves. Both are what the user
 * would do next.
 *
 * The work of one context is done one job at a time. Saving twice while a job runs asks
 * for one more job after it, not two, since the second would read the same files as the
 * first.
 */
@Service(Service.Level.PROJECT)
class VerifyOnSave(private val project: Project) {

    /** Whether a context is being worked on, and whether it has been asked for again. */
    private val running = ConcurrentHashMap<String, AtomicBoolean>()
    private val asked = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * Told when the sources of some contexts have been saved.
     *
     * @param contextIds the contexts whose sources changed
     */
    fun sourcesSaved(contextIds: Collection<String>) {
        if (!KeySettings.instance().verifyOnSave) {
            return
        }
        contextIds.forEach(::start)
    }

    private fun start(contextId: String) {
        val busy = running.computeIfAbsent(contextId) { AtomicBoolean() }
        if (!busy.compareAndSet(false, true)) {
            // Another save arrived while this context was being worked on. One more round
            // after this one covers it, whatever else arrives meanwhile.
            asked.computeIfAbsent(contextId) { AtomicBoolean() }.set(true)
            return
        }
        KeyTasks.of(project).launch("Verifying $contextId after the sources changed") {
            try {
                catchUp(contextId)
            } finally {
                busy.set(false)
                if (asked.computeIfAbsent(contextId) { AtomicBoolean() }.getAndSet(false)) {
                    start(contextId)
                }
            }
        }
    }

    /**
     * Reads the context's saved proofs back and attempts what they leave unproved.
     *
     * @param contextId the context to catch up
     */
    private suspend fun catchUp(contextId: String) {
        val replayed = Verification.replay(project, mapOf(contextId to emptyList()))
            .getValue(contextId)

        // A proof that was saved and no longer closes is what the edit broke. One that was
        // never proved is not attempted: saving a file is not a request to prove the
        // project.
        val broken = replayed.outcomes
            .filter {
                !ProofStatus.isClosed(it.status) && it.status != ProofStatus.NONE &&
                    it.status != ProofStatus.CLOSED_BUT_LEMMAS_LEFT
            }
            .map { it.contractName }
        val lemmas = lemmasLeftAfter(contextId, replayed)

        val toProve = (broken + lemmas).distinct()
        if (toProve.isEmpty()) {
            KeyNotifications.info(project,
                "$contextId: every saved proof still proves its contract after the change.")
            return
        }
        KeyNotifications.info(project, "$contextId: ${report(broken, lemmas)} Proving them.")
        Verification.prove(project, contextId, toProve, "$contextId after the change")
    }

    /**
     * The contracts the replayed proofs rest on that are not proved themselves.
     *
     * A proof that comes back closed but for lemmas is not the one to attempt again: it
     * closes, and what it waits for are the contracts it used. KeY reports which those are,
     * and the replay has just judged every saved proof of the context, so a contract it
     * reports as unproved has either no proof or one that does not close. Those are followed
     * as far as they lead, since a lemma may rest on lemmas of its own.
     *
     * @param contextId the context that was replayed
     * @param replayed what the replay reported
     * @return the contracts to prove, in the order they were reached
     */
    private fun lemmasLeftAfter(contextId: String, replayed: ProveResult): List<String> {
        val waiting = replayed.outcomes
            .filter { it.status == ProofStatus.CLOSED_BUT_LEMMAS_LEFT }
            .map { it.contractName }
        if (waiting.isEmpty()) {
            return emptyList()
        }
        val uses = KeyProject.of(project).dependencies(contextId)
        val status = KeyProject.of(project).obligations(contextId).associate {
            it.contractName to it.status
        }

        val unproved = LinkedHashSet<String>()
        val seen = waiting.toMutableSet()
        val pending = ArrayDeque(waiting)
        while (pending.isNotEmpty()) {
            for (used in uses[pending.removeFirst()].orEmpty()) {
                if (!seen.add(used) || ProofStatus.isClosed(status[used] ?: ProofStatus.NONE)) {
                    continue
                }
                unproved.add(used)
                pending.addLast(used)
            }
        }
        return unproved.toList()
    }

    /** What was found, for the line the user reads before the run starts. */
    private fun report(broken: List<String>, lemmas: List<String>): String = when {
        lemmas.isEmpty() -> "${broken.size} proof(s) no longer prove their contract."
        broken.isEmpty() -> "${lemmas.size} contract(s) the saved proofs rest on are not proved."
        else -> "${broken.size} proof(s) no longer prove their contract, and " +
            "${lemmas.size} contract(s) they rest on are not proved."
    }

    companion object {
        fun of(project: Project): VerifyOnSave = project.service()
    }
}
