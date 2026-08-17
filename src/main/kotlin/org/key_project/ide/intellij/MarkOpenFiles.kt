package org.key_project.ide.intellij

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Marks a file as it is opened.
 *
 * The marks are added when a file is opened rather than on a timer, so a file that is
 * never opened costs nothing and a file that is opened is marked once.
 */
class MarkOpenFiles(private val project: Project) : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        source.getEditors(file).filterIsInstance<TextEditor>().forEach {
            ObligationMarkers.of(project).mark(it.editor, file)
            RefusedSources.of(project).mark(it.editor, java.nio.file.Path.of(file.path))
        }
    }
}
