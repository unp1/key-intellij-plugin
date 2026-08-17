package org.key_project.ide.intellij

import com.intellij.openapi.project.Project
import java.time.Duration

/**
 * Stops a bridge and starts it again.
 *
 * A bridge is a process, and a process can be stuck or gone. Restarting it is the remedy
 * either way, so it is offered as an action and taken by the plugin itself when a bridge
 * has stopped answering.
 *
 * A start that fails is tried again, because the reasons it fails are often over in a
 * moment: a socket still held by the process that just died, or a machine too busy to start
 * a JVM within the deadline. After [ATTEMPTS] tries the plugin gives up and says so, rather
 * than starting processes for as long as the project is open.
 */
object BridgeRestart {

    /** How often a bridge is started before the plugin gives up on it. */
    const val ATTEMPTS = 3

    /** How long to wait before starting a bridge again. */
    val DELAY: Duration = Duration.ofSeconds(10)

    /**
     * Restarts a bridge and tells the user what came of it.
     *
     * @param project the project whose user is told
     * @param name the bridge, as the message names it
     * @param stop stops the bridge
     * @param start starts it, throwing if it cannot be started
     * @return whether the bridge is running again
     */
    fun restart(project: Project, name: String, stop: () -> Unit, start: () -> Unit): Boolean =
        attempt(stop, start) { Thread.sleep(it.toMillis()) }.fold(
            onSuccess = {
                KeyNotifications.info(project, "The $name was restarted.")
                true
            },
            onFailure = { failure ->
                KeyNotifications.error(project,
                    "The $name did not start after $ATTEMPTS attempts: " +
                        KeyNotifications.describe(failure))
                false
            },
        )

    /**
     * Stops, then starts, up to [ATTEMPTS] times with [DELAY] between the attempts.
     *
     * Each attempt stops the bridge first, since an attempt that failed part way may have
     * left a process behind.
     *
     * @param stop stops the bridge
     * @param start starts it, throwing if it cannot be started
     * @param pause how to wait between attempts
     * @return the attempt that succeeded, or the failure of the last one
     */
    internal fun attempt(
        stop: () -> Unit,
        start: () -> Unit,
        pause: (Duration) -> Unit,
    ): Result<Unit> {
        var outcome: Result<Unit> = Result.failure(IllegalStateException("Nothing was tried."))
        for (attempt in 1..ATTEMPTS) {
            if (attempt > 1) {
                pause(DELAY)
            }
            runCatching { stop() }
            outcome = runCatching { start() }
            if (outcome.isSuccess) {
                return outcome
            }
        }
        return outcome
    }
}
