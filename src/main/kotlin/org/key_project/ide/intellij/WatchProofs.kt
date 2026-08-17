package org.key_project.ide.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Notices when what the views show has stopped being true, without the plugin doing it.
 *
 * A proof deleted in the project view, or by anything else on the machine, leaves every
 * view saying what used to be true, and so does an edit to a verified source: KeY judged
 * the sources as they were. The bridge reports what it does itself; this reports the rest,
 * so that a manual refresh is for when this has failed rather than for ordinary work.
 */
@Service(Service.Level.PROJECT)
class WatchProofs(private val project: Project, scope: CoroutineScope) : Disposable {

    /**
     * The directory the project keeps its proofs in.
     *
     * Read once, in the background, because reading it asks the configuration bridge and
     * this is consulted for every batch of file events. Until it is known, a file named
     * like a proof is taken to be one.
     */
    @Volatile
    private var proofs: Path? = null

    /**
     * The directories the contexts verify.
     *
     * An edit under one of them makes KeY load the context again, after which nothing it
     * said about the old sources applies.
     */
    @Volatile
    private var sources: Map<String, Path> = emptyMap()

    init {
        // Read now, and read again whenever the project changes: a context added later has
        // to be watched too, and a first read that fails because the bridge is not up yet
        // must not leave every later save unwatched.
        scope.launch(Dispatchers.IO) { readContexts() }
        KeyProject.of(project).onChanged(this) {
            scope.launch(Dispatchers.IO) { readContexts() }
        }
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        // This is called on the event thread inside the write that changed
                        // the files. Only the cheap question of which contexts are affected
                        // is answered here; telling the views and starting a verification
                        // is left to the moment the write is over, so that saving a file
                        // costs the editor nothing.
                        val edited = events.flatMap(::contextsOf).toSet()
                        val proofsTouched = events.any(::touchesProofs)
                        if (edited.isEmpty() && !proofsTouched) {
                            return
                        }
                        ApplicationManager.getApplication().invokeLater({
                            if (project.isDisposed) {
                                return@invokeLater
                            }
                            KeyProject.of(project).forget()
                            if (edited.isNotEmpty()) {
                                VerifyOnSave.of(project).sourcesSaved(edited)
                            }
                        }, project.disposed)
                    }
                },
            )
    }

    /** Reads which directories the contexts verify, keeping what was known if that fails. */
    private fun readContexts() {
        val base = project.basePath?.let(Path::of) ?: return
        val config = runCatching { KeyProject.of(project).config() }.getOrNull() ?: return
        proofs = base.resolve(config.proofDirectory).normalize()
        sources = config.contexts.associate { it.id to base.resolve(it.javaSource).normalize() }
    }

    /**
     * Whether an event is about the project's proofs.
     *
     * A proof file answers for itself. A directory answers only by where it is, since
     * deleting one reports the directory and not what was inside it.
     *
     * @param event what changed
     * @return whether the views should be told
     */
    private fun touchesProofs(event: VFileEvent): Boolean {
        val path = event.path
        if (path.endsWith(PROOF_SUFFIX)) {
            return true
        }
        val root = proofs ?: return false
        return Path.of(path).normalize().startsWith(root)
    }

    /**
     * The contexts an event is about, being those whose sources it changed.
     *
     * KeY judged the sources as it read them, so an edit to one of them makes everything
     * it said about that context a statement about a program that no longer exists.
     *
     * @param event what changed
     * @return the contexts whose sources this touched, empty when it touched none
     */
    private fun contextsOf(event: VFileEvent): List<String> {
        val path = event.path
        if (!path.endsWith(JAVA_SUFFIX) && !path.endsWith(JML_SUFFIX)) {
            return emptyList()
        }
        val file = Path.of(path).normalize()
        return sources.filterValues(file::startsWith).keys.toList()
    }

    override fun dispose() = Unit

    companion object {
        private const val PROOF_SUFFIX = ".proof"
        private const val JAVA_SUFFIX = ".java"
        private const val JML_SUFFIX = ".jml"

        /**
         * Starts watching, if it is not watching already.
         *
         * @param project the project to watch
         */
        fun start(project: Project) {
            project.service<WatchProofs>()
        }
    }
}
