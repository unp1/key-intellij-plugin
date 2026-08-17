package org.key_project.ide.intellij

import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers how often a bridge is started before the plugin gives up on it.
 *
 * A start fails for reasons that pass, so it is worth trying again, and for reasons that do
 * not, so trying forever would leave a project starting processes on its own.
 */
class BridgeRestartTest {

    private var starts = 0
    private var stops = 0
    private val waits = mutableListOf<Duration>()

    private fun attempt(start: () -> Unit) = BridgeRestart.attempt(
        stop = { stops++ },
        start = { starts++; start() },
        pause = { waits.add(it) },
    )

    @Test
    fun `a bridge that starts is started once`() {
        val outcome = attempt { }

        assertTrue(outcome.isSuccess)
        assertEquals(1, starts)
        assertEquals(emptyList(), waits)
    }

    @Test
    fun `a start that fails is tried again after the delay`() {
        val outcome = attempt { if (starts < 2) throw IOException("The port was still held.") }

        assertTrue(outcome.isSuccess)
        assertEquals(2, starts)
        assertEquals(listOf(BridgeRestart.DELAY), waits)
    }

    @Test
    fun `a bridge that never starts is given up on, and says why`() {
        val outcome = attempt { throw IOException("No KeY jar is configured.") }

        assertEquals("No KeY jar is configured.", outcome.exceptionOrNull()?.message)
        assertEquals(BridgeRestart.ATTEMPTS, starts)
        assertEquals(BridgeRestart.ATTEMPTS - 1, waits.size)
    }

    @Test
    fun `every attempt stops what an earlier one may have left behind`() {
        attempt { throw IOException("The bridge exited before reporting an address.") }

        assertEquals(BridgeRestart.ATTEMPTS, stops)
    }
}
