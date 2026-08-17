package org.key_project.ide.intellij

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Covers what a row of the verification table shows.
 *
 * The table shows every obligation the project has, so a listing decides the status, and
 * what a run measured is worth keeping only while that status still holds.
 */
class KeyProjectTest {

    private fun obligation(status: String) = ObligationDto(
        contractName = "com.example.Account[com.example.Account::deposit(int)].JML 0",
        className = "com.example.Account",
        target = "com.example.Account::deposit",
        label = "deposit(int)",
        status = status,
        statusExplanation = "explained",
        proofFile = "proofs/deposit.proof",
        proofFileExists = status != "NONE",
    )

    private fun measured(status: String, nodes: Int) = ProofOutcomeDto(
        contractName = "com.example.Account[com.example.Account::deposit(int)].JML 0",
        status = status,
        nodes = nodes,
        branches = 1,
        milliseconds = 1200,
        proofFile = "proofs/deposit.proof",
    )

    @Test
    fun `an obligation nothing has attempted shows what the listing says`() {
        val outcome = KeyProject.outcomeFor(obligation("NONE"), null)

        assertEquals("NONE", outcome.status)
        assertEquals(0, outcome.nodes)
    }

    @Test
    fun `what a run measured survives a later listing of the same status`() {
        val outcome = KeyProject.outcomeFor(obligation("CLOSED"), measured("CLOSED", nodes = 350))

        assertEquals(350, outcome.nodes)
        assertEquals(1200, outcome.milliseconds)
    }

    @Test
    fun `a status the bridge no longer asserts is not kept`() {
        // KeY said closed while it held the proof of the contract this one used. That proof
        // was deleted, so KeY says only that a file is there, and so must the table.
        val outcome = KeyProject.outcomeFor(obligation("SAVED"), measured("CLOSED", nodes = 162))

        assertEquals("SAVED", outcome.status)
        assertEquals(0, outcome.nodes)
    }
}
