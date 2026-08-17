package org.key_project.ide.intellij

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers what counts as proved in this plugin.
 *
 * Which mark a line carries is the bridge's answer, and is tested there. What is left here
 * is the question the views ask of a single status.
 */
class ProofStatusTest {

    @Test
    fun `a proof closed from the cache counts as closed`() {
        assertTrue(ProofStatus.isClosed(ProofStatus.CLOSED_BY_CACHE))
        assertTrue(ProofStatus.isClosed(ProofStatus.CLOSED))
    }

    @Test
    fun `a proof that rests on unproved contracts is not closed`() {
        assertFalse(ProofStatus.isClosed(ProofStatus.CLOSED_BUT_LEMMAS_LEFT))
        assertFalse(ProofStatus.isClosed(ProofStatus.OPEN))
        assertFalse(ProofStatus.isClosed(ProofStatus.SAVED))
        assertFalse(ProofStatus.isClosed(ProofStatus.NONE))
    }
}
