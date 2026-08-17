package org.key_project.ide.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Translates between the paths the settings page stores and the files on disk.
 *
 * A path inside the project is stored relative to the project root, so the configuration
 * still works after someone checks the project out somewhere else. A path outside the
 * project is stored as it is, because no relative form of it would survive that move.
 */
internal object ProjectPaths {

    /**
     * The text to store for a path the user picked.
     *
     * @param project the project the settings belong to
     * @param picked the path the file chooser returned
     * @return the path relative to the project root, or the path itself when it lies outside
     */
    fun stored(project: Project, picked: Path): String {
        val root = root(project) ?: return picked.toString()
        val inside = runCatching { root.relativize(picked.normalize()) }.getOrNull()
        return if (inside == null || inside.startsWith("..")) picked.toString() else inside.toString()
    }

    /**
     * Where to open a file chooser for a field.
     *
     * @param project the project the settings belong to
     * @param stored what the field says
     * @return the file the field names, the project root when it names nothing that exists,
     *         or null when the project has no root on disk
     */
    fun startingPoint(project: Project, stored: String): VirtualFile? =
        fileOf(project, stored) ?: root(project)?.let { file(it) }

    /**
     * The file a stored path names.
     *
     * @param project the project the settings belong to
     * @param stored what the field says
     * @return the file, or null when the field is empty or names something that is not there
     */
    fun fileOf(project: Project, stored: String): VirtualFile? {
        val text = stored.trim()
        if (text.isEmpty()) {
            return null
        }
        val path = runCatching { Path.of(text) }.getOrNull() ?: return null
        val absolute = if (path.isAbsolute) path else root(project)?.resolve(path) ?: return null
        return file(absolute)
    }

    private fun file(path: Path): VirtualFile? =
        LocalFileSystem.getInstance().findFileByNioFile(path)

    private fun root(project: Project): Path? =
        project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }
}
