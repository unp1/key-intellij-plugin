package org.key_project.ide.intellij

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * What the user has picked out, whatever they picked it out in.
 *
 * The editor, the project view, the obligation tree and the verification table all mean
 * the same thing by a selection: a set of obligations to act on. Because that meaning is
 * defined once, each action can be written once and offered in every view.
 */
object KeySelection {

    /**
     * Obligations a view knows directly.
     *
     * A tool window that shows obligations already knows which ones are selected. It
     * publishes them here, instead of them being derived from files and positions.
     */
    val OBLIGATIONS: DataKey<List<Selected>> = DataKey.create("key.selected.obligations")

    /** One obligation a view has selected. */
    data class Selected(val contextId: String, val contractName: String, val proofFile: String)

    /** What a selection is, as much as can be known without loading anything. */
    sealed interface Description {
        /** How the selection reads in a progress bar or a message. */
        val label: String
    }

    /** A caret in a file: the method it sits in, or the file when it sits in none. */
    data class AtPosition(
        val file: Path,
        val line: Int,
        val column: Int,
        override val label: String,
    ) : Description

    /** Files and directories: everything they hold, and any proofs among them. */
    data class InFiles(val files: List<VirtualFile>, override val label: String) : Description

    /** Obligations a view named itself. */
    data class Obligations(
        val selected: List<Selected>,
        override val label: String,
    ) : Description

    /**
     * What the event has picked out, or null when it has picked out nothing this plugin
     * can act on.
     *
     * Cheap enough for an action's update: it reads the context and nothing else.
     */
    fun describe(event: AnActionEvent): Description? {
        event.getData(OBLIGATIONS)?.takeIf { it.isNotEmpty() }?.let { selected ->
            return Obligations(selected, "${selected.size} obligation(s)")
        }

        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
            .filter { it.isDirectory || it.extension == "java" || it.extension == "proof" }
        val editor = event.getData(CommonDataKeys.EDITOR)
        val open = event.getData(CommonDataKeys.VIRTUAL_FILE)

        // A caret in a Java file is more specific than a file selected in a tree, so it
        // takes precedence when both are present.
        if (editor != null && open != null && open.extension == "java") {
            val document = editor.document
            val line = document.getLineNumber(editor.caretModel.offset)
            val column = editor.caretModel.offset - document.getLineStartOffset(line)
            // The bridge counts lines and columns from one; the editor counts from zero.
            return AtPosition(Path.of(open.path), line + 1, column + 1, open.name)
        }
        if (files.isNotEmpty()) {
            val label = if (files.size == 1) files.first().name else "${files.size} selection(s)"
            return InFiles(files, label)
        }
        return null
    }

    /**
     * The obligations a selection means, by context.
     *
     * This loads contexts, so it belongs in a background task rather than in an action's
     * update.
     *
     * @param project the project acted in
     * @param description what was picked out
     * @return the contracts to act on, by context
     */
    fun obligations(project: Project, description: Description): Map<String, List<String>> =
        when (description) {
            is Obligations -> description.selected.groupBy({ it.contextId }, { it.contractName })
            is AtPosition -> atPosition(project, description)
            is InFiles -> inFiles(project, description)
        }

    /** The proof files of a selection, for the actions that open or delete one. */
    fun proofFiles(project: Project, description: Description): List<String> =
        when (description) {
            is Obligations -> description.selected.map { it.proofFile }
            else -> {
                val root = root(project)
                obligations(project, description).flatMap { (contextId, contracts) ->
                    KeyProject.of(project).obligations(contextId)
                        .filter { it.contractName in contracts }
                        .map { root.resolve(it.proofFile).toString() }
                }
            }
        }

    private fun atPosition(project: Project, at: AtPosition): Map<String, List<String>> {
        val stands = KeyProject.of(project).at(at.file, at.line, at.column)
        val contextId = stands.contextId ?: throw IllegalStateException(
            "No context in .key/settings.json covers ${at.file}.",
        )
        return if (stands.contractNames.isEmpty()) {
            emptyMap()
        } else {
            mapOf(contextId to stands.contractNames)
        }
    }


    private fun inFiles(project: Project, selection: InFiles): Map<String, List<String>> {
        val root = root(project)
        val targets = selection.files.map { Path.of(it.path).normalize() }
        val config = KeyProject.of(project).config()

        return buildMap {
            for (context in config.contexts) {
                val matching = KeyProject.of(project).obligations(context.id)
                    .filter { obligation ->
                        val source = root.resolve(obligation.sourceFile).normalize()
                        val proof = root.resolve(obligation.proofFile).normalize()
                        targets.any { target ->
                            source == target || proof == target || source.startsWith(target)
                        }
                    }
                    .map { it.contractName }
                if (matching.isNotEmpty()) {
                    put(context.id, matching)
                }
            }
        }
    }

    private fun root(project: Project): Path = Path.of(project.basePath ?: "")

}
