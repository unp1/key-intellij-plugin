package org.key_project.ide.intellij

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Covers where a selected obligation is opened.
 *
 * Which obligations a caret stands for is the bridge's answer, and is covered there. What
 * is left here is where the IDE puts the caret once one is chosen.
 */
class GoToSourceTest {

    private fun obligation(target: String, line: Int, contract: String) = ObligationDto(
        contractName = contract,
        className = "com.example.Account",
        target = "com.example.Account::$target",
        displayName = contract,
        status = "NONE",
        statusExplanation = "",
        sourceFile = "Account.java",
        classLine = 1,
        targetLine = line,
        proofFile = "proofs/$contract.proof",
        proofFileExists = false,
    )

    @Test
    fun `an obligation is shown at the method it is about`() {
        val obligation = obligation("deposit", line = 27, contract = "deposit(int)")

        assertEquals(27, GoToSourceAction.lineOf(obligation))
    }

    @Test
    fun `an obligation with no method position is shown at its class`() {
        val constructor = obligation("Account", line = 0, contract = "Account(int)")
            .copy(classLine = 9)

        assertEquals(9, GoToSourceAction.lineOf(constructor))
    }

    @Test
    fun `an obligation with no position at all is shown at the top of the file`() {
        val unknown = obligation("deposit", line = 0, contract = "deposit(int)")
            .copy(classLine = 0)

        assertEquals(1, GoToSourceAction.lineOf(unknown))
    }

}
