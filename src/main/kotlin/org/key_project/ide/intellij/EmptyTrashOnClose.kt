package org.key_project.ide.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Empties the trash of replaced proofs when a project closes, for a user who asked for
 * that.
 *
 * It listens for the project closing rather than for the plugin's own disposal: at that
 * moment the project is still there to be asked about, and nothing has been shut down yet.
 */
class EmptyTrashOnClose : ProjectManagerListener {

    override fun projectClosing(project: Project) {
        ProofTrash.of(project).applyOnClose()
    }
}
