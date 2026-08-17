package org.key_project.ide.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps the trash of replaced proofs as the user asked.
 *
 * A proof that is proved again is not overwritten but moved to `proofs/.trash`, so that a
 * good proof is never lost to a worse attempt. Left alone the trash only grows, so the user
 * says how long its contents are kept, and this applies that.
 *
 * Which proofs go is worked out by the bridge, since the layout of the trash is the
 * bridge's and both plugins keep it the same way.
 */
@Service(Service.Level.PROJECT)
class ProofTrash(private val project: Project, private val scope: CoroutineScope) {

    /**
     * Applies the policy, unless it is one that applies at another moment.
     *
     * This is called whenever proofs have been written, which is when the trash can have
     * grown. Emptying on quit is not applied here, since a user who asked for that wants
     * the trash while the project is open.
     */
    fun applyAfterProofsWritten() {
        val settings = KeySettings.instance()
        when (settings.trashPolicy) {
            KeySettings.TrashPolicy.BELOW_SIZE, KeySettings.TrashPolicy.OLDER_THAN ->
                prune(policyOf(settings), report = false)

            else -> Unit
        }
    }

    /**
     * Applies the policy for a project that is being closed, and waits for it.
     *
     * This runs while the project is still alive and on the thread that closes it, because
     * a coroutine started here would be cancelled with the project before it had emptied
     * anything. Deleting files is quick, and the bridge it asks needs no KeY.
     */
    fun applyOnClose() {
        if (KeySettings.instance().trashPolicy != KeySettings.TrashPolicy.ON_QUIT) {
            return
        }
        runCatching {
            KeyConfigBridge.of(project).configService()
                .pruneTrash(TrashPolicyDto(mode = "EMPTY"))
                .await(Deadline.CONFIG)
        }
    }

    /** Empties the trash now, whatever the policy says, and reports what went. */
    fun emptyNow() = prune(TrashPolicyDto(mode = "EMPTY"), report = true)

    /**
     * Asks the bridge to throw away what the policy no longer keeps.
     *
     * @param policy what to throw away
     * @param report whether to tell the user what went, which suits an emptying they asked
     *        for and not one that happens as a matter of course
     */
    private fun prune(policy: TrashPolicyDto, report: Boolean) {
        scope.launch(Dispatchers.IO) {
            val pruned = runCatching {
                KeyConfigBridge.of(project).configService().pruneTrash(policy)
                    .await(Deadline.CONFIG)
            }.getOrElse { failure ->
                if (report) {
                    KeyNotifications.warning(project,
                        "The trash could not be emptied: ${failure.message}")
                }
                return@launch
            }
            if (report || pruned.files > 0) {
                KeyNotifications.info(project, "Removed ${pruned.files} replaced proof(s), " +
                    "${pruned.bytes / 1024} kB.")
            }
        }
    }

    /** The policy as the bridge is told it. */
    private fun policyOf(settings: KeySettings): TrashPolicyDto = TrashPolicyDto(
        mode = settings.trashPolicy.name,
        megabytes = settings.trashMegabytes,
        days = settings.trashDays,
    )

    companion object {
        fun of(project: Project): ProofTrash = project.service()
    }
}
