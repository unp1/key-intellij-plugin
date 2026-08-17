package org.key_project.ide.intellij

import com.google.gson.Gson
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The sources KeY refused to read, and everything the plugin does about them.
 *
 * KeY refuses a context whose Java or JML it cannot read, and names the file and the line of
 * each problem. This one service recognises such a refusal in a failure, remembers what each
 * context was last refused for, marks the places in the open editors the way a compile error
 * is marked, says so once in a balloon with a link per place, and forgets it all when the
 * context loads again.
 *
 * It is one service because the four views that list a context all hear of one refusal at
 * the same time. Each tells this service; the marks are replaced rather than added to, and
 * the balloon is shown for the first report only, so one refusal is one set of marks and
 * one message.
 */
@Service(Service.Level.PROJECT)
class RefusedSources(private val project: Project, private val scope: CoroutineScope) :
    Disposable {

    /** What each context was last refused for, while it is refused. */
    private val refused = ConcurrentHashMap<String, LoadFailureDto>()

    /** The marks drawn, by editor, so they can be taken off again. */
    private val drawn = ConcurrentHashMap<Editor, List<RangeHighlighter>>()

    init {
        // A load that succeeds shows itself as a change of the project, and [accept] has
        // already dropped the refusal; a redraw takes the marks off. A load that fails again
        // comes through [report].
        KeyProject.of(project).onChanged(this) { redraw() }
    }

    override fun dispose() = Unit

    /**
     * Reports a failure, if it is a refusal.
     *
     * A failure arrives wrapped: a request fails, which fails the task, which fails the
     * action. The bridge's own error is somewhere in that chain and carries the problems as
     * data.
     *
     * @param failure what went wrong
     * @return true when the failure was a refusal and has been dealt with here; false when
     *         it is some other failure the caller should report itself
     */
    fun report(failure: Throwable): Boolean {
        val refusal = refusalIn(failure) ?: return false
        val news = refused.put(refusal.contextId, refusal) != refusal
        redraw()
        if (news) {
            say(refusal)
        }
        return true
    }

    /** Forgets what a context was refused for, which is what a load that succeeds means. */
    fun accept(contextId: String) {
        if (refused.remove(contextId) != null) {
            redraw()
        }
    }

    /**
     * Marks an editor that has just been opened, if its file is one KeY refused.
     *
     * @param editor the editor
     * @param file the file it shows
     */
    fun mark(editor: Editor, file: Path) {
        scope.launch(Dispatchers.EDT) { install(editor, problemsIn(file)) }
    }

    /** The refusal inside a failure, or null when the failure is something else. */
    private fun refusalIn(failure: Throwable): LoadFailureDto? {
        val response = generateSequence(failure) { it.cause }
            .filterIsInstance<ResponseErrorException>()
            .firstOrNull()
            ?.responseError
            ?: return null
        if (response.code != ENVIRONMENT_LOAD_FAILED || response.data == null) {
            return null
        }
        val gson = Gson()
        return runCatching { gson.fromJson(gson.toJson(response.data), LoadFailureDto::class.java) }
            .getOrNull()
            ?.takeIf { it.problems.isNotEmpty() }
    }

    /** The problems KeY named in one file, across every refused context. */
    private fun problemsIn(file: Path): List<SourceProblemDto> =
        refused.values.flatMap { it.problems }.filter { placed(it) && pathOf(it) == file }

    private fun redraw() {
        scope.launch(Dispatchers.EDT) {
            if (project.isDisposed) {
                return@launch
            }
            FileEditorManager.getInstance(project).allEditors
                .filterIsInstance<TextEditor>()
                .forEach { editor ->
                    val file = editor.file?.path?.let(Path::of) ?: return@forEach
                    install(editor.editor, problemsIn(file))
                }
        }
    }

    /**
     * Puts the marks on one editor, replacing whatever it carried.
     *
     * A line KeY named that the document no longer has is left unmarked rather than mapped
     * to another line.
     */
    private fun install(editor: Editor, problems: List<SourceProblemDto>) {
        if (editor.isDisposed) {
            return
        }
        val markup = editor.markupModel
        drawn.remove(editor)?.forEach { markup.removeHighlighter(it) }
        val document = editor.document
        val attributes = EditorColorsManager.getInstance().globalScheme
            .getAttributes(CodeInsightColors.ERRORS_ATTRIBUTES)
        val added = problems.mapNotNull { problem ->
            // KeY counts lines and columns from one, the document from zero. The mark runs
            // from the column KeY named to the end of the line, when the column is on the
            // line; from the start of the line otherwise.
            val line = problem.line - 1
            if (line < 0 || line >= document.lineCount) {
                return@mapNotNull null
            }
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            val column = start + problem.column - 1
            val from = if (problem.column > 0 && column < end) column else start
            markup.addRangeHighlighter(
                from, end, HighlighterLayer.ERROR, attributes, HighlighterTargetArea.EXACT_RANGE,
            ).apply {
                setErrorStripeMarkColor(attributes.errorStripeColor)
                setErrorStripeTooltip("KeY: ${problem.message}")
            }
        }
        if (added.isNotEmpty()) {
            drawn[editor] = added
        }
    }

    /** Says what KeY refused, once, with a link to each place. */
    private fun say(refusal: LoadFailureDto) {
        val listed = refusal.problems.joinToString("\n") { problem ->
            val place = placeOf(problem)
            if (place == null) problem.message else "$place: ${problem.message}"
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(
                "KeY could not read the sources of '${refusal.contextId}'",
                listed,
                NotificationType.ERROR,
            )
        refusal.problems
            .filter(::placed)
            .distinctBy { it.uri to it.line }
            .forEach { problem ->
                notification.addAction(
                    NotificationAction.createSimple("Open ${placeOf(problem)}") { open(problem) },
                )
            }
        notification.notify(project)
    }

    private fun open(problem: SourceProblemDto) {
        val path = pathOf(problem) ?: return
        val file = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path) ?: return
        // KeY counts lines and columns from one, the editor from zero.
        OpenFileDescriptor(
            project,
            file,
            (problem.line - 1).coerceAtLeast(0),
            (problem.column - 1).coerceAtLeast(0),
        ).navigate(true)
    }

    companion object {
        fun of(project: Project): RefusedSources = project.service()

        private const val GROUP = "KeY"

        /** The code the bridge gives a context KeY could not load. */
        private const val ENVIRONMENT_LOAD_FAILED = -32004

        /** Whether KeY named a place that can be marked: a file, and a line above zero. */
        private fun placed(problem: SourceProblemDto): Boolean = problem.uri != null && problem.line > 0

        /**
         * The file a problem names, or null when it names none or names it in a form the
         * platform cannot read as a path.
         */
        private fun pathOf(problem: SourceProblemDto): Path? =
            problem.uri?.let { runCatching { Path.of(URI.create(it)) }.getOrNull() }

        /** How a problem's place reads: the file name, and the line where KeY named one. */
        private fun placeOf(problem: SourceProblemDto): String? {
            val uri = problem.uri ?: return null
            val name = uri.substringAfterLast('/')
            return if (problem.line > 0) "$name:${problem.line}" else name
        }
    }
}
