package org.key_project.ide.intellij

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The KeY tool window, for the actions that have something to show in it.
 *
 * An action reaches the window through the platform rather than through a reference of its
 * own, so nothing has to be kept alive between them.
 */
object KeyToolWindow {

    /** The window's registered name. */
    const val ID = "KeY"

    /** The tab that shows what a proof rests on. */
    const val DEPENDENCIES_TAB = "Dependencies"

    /** The panes of a project's window, so an action can hand one something to show. */
    private val panes = mutableMapOf<Project, DependencyPane>()

    /** Registers the pane of a project's window as it is built. */
    fun register(project: Project, pane: DependencyPane) {
        panes[project] = pane
    }

    /** Forgets a project's pane, so a closed project leaves nothing behind. */
    fun forget(project: Project) {
        panes.remove(project)
    }

    /**
     * Shows what one obligation rests on, bringing the tab forward.
     *
     * @param project the project it belongs to
     * @param contextId the context
     * @param contractName the obligation
     * @param label how it reads to the user
     */
    suspend fun showDependencies(
        project: Project,
        contextId: String,
        contractName: String,
        label: String,
    ) {
        val pane = panes[project] ?: return
        pane.show(contextId, contractName, label)
        // A tool window is opened on the event thread, and the caller is a coroutine that
        // runs in the background.
        withContext(Dispatchers.EDT) {
            val window = ToolWindowManager.getInstance(project).getToolWindow(ID)
                ?: return@withContext
            window.show {
                window.contentManager.contents
                    .firstOrNull { it.displayName == DEPENDENCIES_TAB }
                    ?.let { window.contentManager.setSelectedContent(it) }
            }
        }
    }
}
