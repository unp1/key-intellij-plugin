package org.key_project.ide.intellij

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Marks the KeY tool window with KeY's logo when a project opens.
 *
 * Without this the stripe carries the placeholder the platform gives every tool window until
 * the window is opened for the first time, which is the one moment the factory runs.
 */
class ShowKeyLogo : ProjectActivity {

    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) { KeyLogo.showOnToolWindow(project) }
    }
}
