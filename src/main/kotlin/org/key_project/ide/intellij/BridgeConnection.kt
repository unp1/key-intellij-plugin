package org.key_project.ide.intellij

import com.intellij.openapi.diagnostic.logger
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.IOException
import java.net.InetSocketAddress
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A bridge process and the connection to it.
 *
 * Both bridges are started the same way and differ only in what they serve: one runs
 * inside KeY and can verify, the other runs alone and only reads configuration. The
 * launching, waiting and connecting is identical, so it lives here once.
 */
class BridgeConnection private constructor(
    private val process: Process,
    private val channel: SocketChannel,
    private val listening: Future<Void>,
    val service: BridgeService,
    val info: InitializeResult,
    val runtimeDir: Path,
) : AutoCloseable {

    fun isAlive(): Boolean = process.isAlive

    /**
     * Whether the bridge still answers.
     *
     * The bridge answers a ping from its message loop, so this says whether it is serving
     * at all. It is not a measure of how busy it is: a bridge in the middle of a long proof
     * answers at once.
     */
    fun isResponsive(): Boolean =
        process.isAlive &&
            runCatching { service.ping().get(PING_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)

    /** The file the process's output was sent to, named in failure messages. */
    fun logFile(): Path = runtimeDir.resolve(LOG_NAME)

    override fun close() {
        runCatching { service.exit() }
        listening.cancel(true)
        runCatching { channel.close() }
        removeRuntimeDirectoryIfTheProcessIsGone()
    }

    /**
     * Removes the socket, the address and the log once the process that owned them ends.
     *
     * A bridge inside KeY keeps running after the IDE disconnects, because the window is
     * the user's, and its log is still being written. Such a directory is left alone. A
     * bridge that exists only for this IDE ends with the connection, and its directory is
     * of no further use.
     */
    private fun removeRuntimeDirectoryIfTheProcessIsGone() {
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            return
        }
        runCatching {
            Files.walk(runtimeDir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        private val log = logger<BridgeConnection>()

        private const val LOG_NAME = "bridge.log"

        /** A ping is answered from the message loop, so a bridge that serves is quick. */
        private const val PING_SECONDS = 5L
        private const val POLL_MILLIS = 200L

        /**
         * Starts a bridge and connects to it.
         *
         * @param commandFor builds the process to run, given the directory it must publish
         *        its address in
         * @param projectRoot what relative paths in the configuration are written against
         * @param clientName how this client identifies itself
         * @param onObligationsChanged run when the bridge reports that proof states moved
         * @param onProgress run as a proof run reports its progress
         * @param timeoutSeconds how long to wait for an address
         * @throws IOException if the process fails to start, refuses to serve, or is silent
         */
        @Throws(IOException::class)
        fun launch(
            commandFor: (Path) -> List<String>,
            projectRoot: String,
            clientName: String,
            timeoutSeconds: Long,
            onObligationsChanged: () -> Unit = {},
            onProgress: (ProveProgressDto) -> Unit = {},
        ): BridgeConnection {
            // Kept short: a Unix domain socket path is capped near 104 bytes, and the
            // socket is created inside this directory.
            val runtimeDir = Files.createTempDirectory("key-ide")
            val command = commandFor(runtimeDir)
            log.info("Starting bridge: ${command.joinToString(" ")}")
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(runtimeDir.resolve(LOG_NAME).toFile())
                .start()

            val address = try {
                awaitAddress(runtimeDir, process, timeoutSeconds)
            } catch (failure: IOException) {
                process.destroy()
                throw failure
            }

            val channel = connect(address)
            val client = object : IdeClient {
                override fun state(params: StateDto) = log.info("bridge state: ${params.state}")
                override fun log(params: LogDto) = log.info("bridge: ${params.text}")
                override fun obligationsChanged(params: ObligationsChangedDto) =
                    onObligationsChanged()
                override fun proveProgress(params: ProveProgressDto) = onProgress(params)
            }
            val launcher = Launcher.createLauncher(
                client,
                BridgeService::class.java,
                Channels.newInputStream(channel),
                Channels.newOutputStream(channel),
            )
            val listening = launcher.startListening()
            val service = launcher.remoteProxy
            val info = service.initialize(
                InitializeParams(clientName, PLUGIN_VERSION, PROTOCOL_VERSION, projectRoot),
            ).get(timeoutSeconds, TimeUnit.SECONDS)

            return BridgeConnection(process, channel, listening, service, info, runtimeDir)
        }

        /**
         * Waits for the address, which arrives through a file because KeY's log occupies
         * standard output. A bridge that cannot start writes its reason to the same file,
         * so a refusal is reported rather than waited out.
         */
        private fun awaitAddress(
            runtimeDir: Path,
            process: Process,
            timeoutSeconds: Long,
        ): Map<String, String> {
            val endpoint = runtimeDir.resolve("endpoint")
            val logFile = runtimeDir.resolve(LOG_NAME)
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            while (System.currentTimeMillis() < deadline) {
                if (Files.exists(endpoint)) {
                    val values = readEndpoint(endpoint)
                    values["error"]?.let { throw IOException("The bridge did not start: $it") }
                    if (values.containsKey("endpoint")) {
                        return values
                    }
                }
                if (!process.isAlive) {
                    throw IOException(
                        "The bridge exited with status ${process.exitValue()} before reporting " +
                            "an address. Its output is in $logFile.",
                    )
                }
                Thread.sleep(POLL_MILLIS)
            }
            throw IOException(
                "The bridge did not report an address within $timeoutSeconds seconds. " +
                    "Its output is in $logFile.",
            )
        }

        private fun readEndpoint(file: Path): Map<String, String> =
            Files.readAllLines(file, StandardCharsets.UTF_8).mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator < 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            }.toMap()

        private fun connect(address: Map<String, String>): SocketChannel {
            val endpoint = address.getValue("endpoint")
            if (endpoint.startsWith("unix:")) {
                return SocketChannel.open(
                    UnixDomainSocketAddress.of(endpoint.removePrefix("unix:")),
                )
            }
            val hostAndPort = endpoint.removePrefix("tcp:")
            val separator = hostAndPort.lastIndexOf(':')
            val socket = SocketChannel.open(
                InetSocketAddress(
                    hostAndPort.substring(0, separator),
                    hostAndPort.substring(separator + 1).toInt(),
                ),
            )
            // A loopback port is reachable by every local process, so it asks for the
            // token the bridge published alongside its address.
            val buffer = ByteBuffer.wrap(
                (address["token"].orEmpty() + "\n").toByteArray(StandardCharsets.US_ASCII),
            )
            while (buffer.hasRemaining()) {
                socket.write(buffer)
            }
            return socket
        }

        /**
         * The runtime of the IDE, which is a JDK 21 and therefore one KeY runs on.
         *
         * Named in full, including the extension Windows expects, rather than left to
         * whatever a process launcher appends to a name without one.
         */
        fun javaExecutable(): String {
            val name = if (System.getProperty("os.name").startsWith("Windows")) {
                "java.exe"
            } else {
                "java"
            }
            return Path.of(System.getProperty("java.home"), "bin", name).toString()
        }

        const val PROTOCOL_VERSION = 1
        const val PLUGIN_VERSION = "0.1.0-dev"
    }
}
