package org.key_project.ide.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * The balloons this plugin shows.
 *
 * Failures are shown to the user here rather than only written to the log, because the
 * interesting ones are things the user can fix: a jar that is not configured, a context
 * that covers no file, or a caret outside every method.
 */
object KeyNotifications {

    private const val GROUP = "KeY"

    fun info(project: Project, message: String) = notify(project, message, NotificationType.INFORMATION)

    fun warning(project: Project, message: String) =
        notify(project, message, NotificationType.WARNING)

    fun error(project: Project, message: String) = notify(project, message, NotificationType.ERROR)

    /**
     * Reports a failure, saying what actually went wrong.
     *
     * A failure arrives wrapped: a request fails, which fails the task, which fails the
     * action. The message shown is the first one in that chain that says anything.
     *
     * @param project the project to show it in
     * @param failure what went wrong
     */
    fun error(project: Project, failure: Throwable) {
        // A source KeY could not read is marked at the lines KeY named and said once, by
        // the service that owns refusals. Every other failure is a sentence.
        if (!RefusedSources.of(project).report(failure)) {
            error(project, describe(failure))
        }
    }

    /**
     * The message of the innermost thing in the chain that has one.
     *
     * A failure arrives wrapped, and a wrapper's message is often the wrapped exception's
     * class name followed by its message. The innermost message is the one written for a
     * reader.
     *
     * @param failure what went wrong
     * @return the message, or the type when nothing in the chain carries one
     */
    fun describe(failure: Throwable): String {
        val chain = generateSequence(failure) { it.cause }.toList()
        return chain.lastOrNull { !it.message.isNullOrBlank() }?.message
            ?: chain.last().toString()
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(message, type)
            .notify(project)
    }
}
