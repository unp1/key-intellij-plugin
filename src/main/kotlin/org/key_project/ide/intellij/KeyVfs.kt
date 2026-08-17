package org.key_project.ide.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path

/**
 * Tells the IDE that the proofs directory has changed.
 *
 * Proofs are written and deleted by the bridge, which is a separate process, so the IDE
 * does not notice. Unless it is told, the project view keeps showing a proof that has been
 * deleted and misses one that was just written.
 */
object KeyVfs {


    /**
     * Refreshes what the IDE knows about the project's proofs.
     *
     * @param project the project whose proofs changed
     */
    fun refreshProofs(project: Project) {
        val base = project.basePath ?: return
        val proofs = Path.of(base).resolve(proofDirectoryOf(project))
        val directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(proofs) ?: return
        // Asynchronously, because this is called from whichever thread finished the
        // work, and recursively, because a run may have created new directories.
        VfsUtil.markDirtyAndRefresh(true, true, true, directory)
    }

    /**
     * Where this project stores its proofs.
     *
     * This is read from the configuration rather than assumed, because a project may
     * store them anywhere. The configuration bridge answers without starting a prover.
     *
     * @param project the project to ask about
     * @return the directory named by the configuration, or the default one if the
     *         configuration cannot be read
     */
    private fun proofDirectoryOf(project: Project): String = runCatching {
        KeyProject.of(project).config().proofDirectory
    }.getOrDefault(DEFAULT_PROOF_DIRECTORY)
}
