package org.key_project.ide.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.IOException

/**
 * The bridge that reads and writes the project's KeY settings.
 *
 * Editing settings does not need a prover, so this runs the bridge jar on its own instead
 * of starting KeY. Opening the settings page therefore costs one small process, and works
 * before KeY has been configured at all.
 */
@Service(Service.Level.PROJECT)
class KeyConfigBridge(private val project: Project) : Disposable {

    private var connection: BridgeConnection? = null

    /**
     * The configuration service, starting the bridge if it is not running.
     *
     * @throws IOException if the bridge jar is unset or missing, or the process is silent
     */
    @Synchronized
    @Throws(IOException::class)
    fun configService(): ConfigService {
        val running = connection
        if (running != null && running.isAlive()) {
            return running.service.getConfigService()
        }
        shutdown()

        val settings = KeySettings.instance()
        settings.bridgeProblem()?.let { throw IOException(it) }
        val bridgeJar = settings.bridgeJarPath

        val started = BridgeConnection.launch(
            commandFor = { runtimeDir ->
                listOf(
                    BridgeConnection.javaExecutable(),
                    "-Djava.awt.headless=true",
                    "-cp", bridgeJar,
                    "org.key_project.ide.server.ConfigBridgeMain",
                    runtimeDir.toString(),
                )
            },
            projectRoot = project.basePath ?: System.getProperty("user.dir"),
            clientName = "key-intellij-config",
            timeoutSeconds = TIMEOUT_SECONDS,
        )
        connection = started
        return started.service.getConfigService()
    }

    /**
     * Stops the configuration bridge and starts it again.
     *
     * @return whether it is running again
     */
    fun restart(): Boolean =
        BridgeRestart.restart(project, BRIDGE_NAME, ::shutdown) { configService() }

    /** Restarts the configuration bridge if it has stopped answering. */
    fun restartIfUnresponsive() {
        val running = synchronized(this) { connection } ?: return
        if (running.isResponsive()) {
            return
        }
        KeyNotifications.warning(project, "The $BRIDGE_NAME has stopped answering. Restarting it.")
        restart()
    }

    @Synchronized
    fun shutdown() {
        connection?.close()
        connection = null
    }

    override fun dispose() = shutdown()

    companion object {
        /** There is no prover to load, so this starts in about the time a JVM takes. */
        private const val TIMEOUT_SECONDS = 30L

        /** How this bridge reads in a message to the user. */
        private const val BRIDGE_NAME = "KeY settings bridge"

        fun of(project: Project): KeyConfigBridge = project.service()
    }
}
