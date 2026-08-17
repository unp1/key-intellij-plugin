package org.key_project.ide.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeoutException

/**
 * Runs the plugin's work away from the event thread.
 *
 * The scope belongs to the project, so everything started here is cancelled when the
 * project closes. Each piece of work appears as a background task the user can stop, and
 * stopping it cancels the coroutine, which is what lets a proof run be told to stop.
 */
@Service(Service.Level.PROJECT)
class KeyTasks(private val project: Project, private val scope: CoroutineScope) {

    /**
     * Runs work in the background, showing it as a task the user can stop.
     *
     * A failure is reported to the user rather than thrown into the scope, since there is
     * nobody above to catch it. Cancellation is not a failure and is passed on, so that
     * the machinery above knows the work stopped. Where the failure is a deadline that
     * passed, the bridges are asked whether they still answer, and one that does not is
     * restarted.
     *
     * The work runs on the dispatcher for blocking calls, because nearly all of it waits
     * on the bridge. Anything that touches the user interface asks for the event thread
     * itself.
     *
     * @param title how the work reads in the progress bar
     * @param work what to do
     * @return the job, for a caller that wants to wait or cancel
     */
    fun launch(title: String, work: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try {
            withBackgroundProgress(project, title, true) {
                withContext(Dispatchers.IO) { work() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            KeyNotifications.error(project, failure)
            if (failure is TimeoutException) {
                // Every request to a bridge is waited for in here, so this is where a bridge
                // that has stopped answering shows itself. Which of them it was is not known
                // at this point, and asking each is cheap.
                withContext(Dispatchers.IO) {
                    KeyBridge.of(project).restartIfUnresponsive()
                    KeyConfigBridge.of(project).restartIfUnresponsive()
                }
            }
        }
    }

    companion object {
        fun of(project: Project): KeyTasks = project.service()
    }
}
