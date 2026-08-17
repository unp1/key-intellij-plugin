package org.key_project.ide.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the KeY process this project talks to.
 *
 * KeY is started once and kept running, because starting it takes several seconds and a
 * user usually verifies several methods in a row. The window it opens is KeY's own: this
 * plugin drives KeY rather than reimplementing it.
 */
@Service(Service.Level.PROJECT)
class KeyBridge(private val project: Project) : Disposable {

    private var connection: BridgeConnection? = null

    /**
     * The handler that receives each run's progress, by run id.
     *
     * Several runs can be going at the same time, one for each context, so every progress
     * report is passed to the handler of the run it belongs to.
     */
    private val progressHandlers = ConcurrentHashMap<String, (ProveProgressDto) -> Unit>()

    /**
     * Follows a run's progress.
     *
     * @param runId the id of the run to follow
     * @param handler what to call with its progress
     */
    fun followProgress(runId: String, handler: (ProveProgressDto) -> Unit) {
        progressHandlers[runId] = handler
    }

    /**
     * Stops following a run. A caller does this when the run has ended.
     *
     * @param runId the id of the run to stop following
     */
    fun stopFollowing(runId: String) {
        progressHandlers.remove(runId)
    }

    /**
     * The bridge, launching KeY first if it is not running.
     *
     * @throws IOException if KeY cannot be started or does not report an address
     */
    @Synchronized
    @Throws(IOException::class)
    fun connected(): BridgeService {
        val running = connection
        if (running != null && running.isAlive()) {
            return running.service
        }
        shutdown()

        val settings = KeySettings.instance()
        settings.problem()?.let { throw IOException(it) }

        val started = BridgeConnection.launch(
            commandFor = { runtimeDir ->
                listOfNotNull(
                    BridgeConnection.javaExecutable(),
                    keyHome()?.let { "-Dkey.home=$it" },
                    // KeY touches Swing while reading its settings, and a JVM that does so
                    // with a display attached puts a Java icon in the macOS dock and steals
                    // the focus. Headless, it does neither; the bridge opens no window and
                    // still draws KeY's icons off screen.
                    "-Djava.awt.headless=true",
                    "-cp",
                    listOf(settings.keyJarPath, settings.bridgeJarPath)
                        .joinToString(File.pathSeparator),
                    "org.key_project.ide.server.BridgeMain",
                    runtimeDir.toString(),
                )
            },
            projectRoot = projectRoot(),
            clientName = "key-intellij",
            timeoutSeconds = KEY_TIMEOUT_SECONDS,
            onObligationsChanged = { KeyProject.of(project).forget() },
            onProgress = { progress -> progressHandlers[progress.runId]?.invoke(progress) },
        )
        connection = started
        return started.service
    }

    private fun projectRoot(): String = project.basePath ?: System.getProperty("user.dir")

    /**
     * Opens a saved proof in a KeY of its own.
     *
     * The bridge has no user interface, so looking at a proof means starting a second
     * program that reads the file the run wrote.
     *
     * @param proofFile the proof to open, relative to the project or absolute
     */
    fun openInKeY(proofFile: String) {
        val settings = KeySettings.instance()
        settings.problem()?.let { throw IOException(it) }
        val file = Path.of(projectRoot()).resolve(proofFile).normalize()
        if (!Files.isRegularFile(file)) {
            throw IOException("There is no proof at $file yet. Verify it first.")
        }
        ProcessBuilder(
            BridgeConnection.javaExecutable(),
            "-cp", settings.keyJarPath,
            "de.uka.ilkd.key.core.Main",
            file.toString(),
        ).start()
    }

    /**
     * The home directory the bridge's KeY keeps its files in.
     *
     * By default this is `.key/tool` in the project, so that nothing of one project reaches
     * another and nothing of the user's own KeY reaches the bridge. The bridge resets the
     * settings files of a home it was given, so a proof run with its own options does not
     * leave them behind as the defaults of the next start, and the logs and the caches stay.
     *
     * @return the directory KeY is told to use, or null to let KeY use the user's own
     */
    private fun keyHome(): Path? {
        if (KeySettings.instance().keyHome == KeySettings.KeyHome.STANDARD) {
            return null
        }
        return Files.createDirectories(Path.of(projectRoot()).resolve(".key/tool"))
    }

    /**
     * Stops KeY and starts it again.
     * <p>
     * The views are told afterwards: the new KeY has judged nothing yet, so what they show
     * is out of date whether or not the restart succeeded.
     *
     * @return whether KeY is running again
     */
    fun restart(): Boolean {
        val restarted = BridgeRestart.restart(project, BRIDGE_NAME, ::shutdown) { connected() }
        KeyProject.of(project).forget()
        return restarted
    }

    /**
     * Restarts KeY if it has stopped answering, and leaves it alone otherwise.
     *
     * A request that passes its deadline says nothing on its own: a proof is allowed to take
     * as long as it takes. What decides is whether the bridge still answers a ping.
     */
    fun restartIfUnresponsive() {
        val running = synchronized(this) { connection } ?: return
        if (running.isResponsive()) {
            return
        }
        KeyNotifications.warning(project, "KeY has stopped answering. Restarting it.")
        restart()
    }

    /** Closes the connection and stops listening. KeY then exits on its own. */
    @Synchronized
    fun shutdown() {
        connection?.close()
        connection = null
    }

    override fun dispose() = shutdown()

    companion object {
        /** KeY has to build a window and read its taclets before it can answer. */
        private const val KEY_TIMEOUT_SECONDS = 180L

        /** How this bridge reads in a message to the user. */
        private const val BRIDGE_NAME = "KeY bridge"

        fun of(project: Project): KeyBridge = project.service()
    }
}
