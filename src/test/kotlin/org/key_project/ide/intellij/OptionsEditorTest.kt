package org.key_project.ide.intellij

import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers what the options editor reports after the user has chosen values.
 *
 * The editor is driven the way a user drives it, by pressing the buttons it shows, so that
 * a value the user picks and a value the editor reports cannot come apart.
 */
class OptionsEditorTest {

    private val loop = OptionCategoryDto(
        key = "LOOP_OPTIONS_KEY",
        label = "Loop treatment",
        values = listOf(
            OptionValueDto("LOOP_SCOPE_INVARIANT", "Invariant (Loop Scope)"),
            OptionValueDto("LOOP_NONE", "None"),
        ),
    )
    private val method = OptionCategoryDto(
        key = "METHOD_OPTIONS_KEY",
        label = "Method treatment",
        values = listOf(
            OptionValueDto("METHOD_CONTRACT", "Contract"),
            OptionValueDto("METHOD_EXPAND", "Expand"),
        ),
    )
    private val expansion = OptionCategoryDto(
        key = "methodExpansion",
        label = "methodExpansion",
        values = listOf(
            OptionValueDto("methodExpansion:modularOnly", "modularOnly"),
            OptionValueDto("methodExpansion:noRestriction", "noRestriction"),
        ),
    )
    private val available = AvailableOptionsDto(
        taclet = listOf(expansion),
        strategy = listOf(loop, method),
        defaults = ProofOptionsDto(
            taclet = mapOf("methodExpansion" to "methodExpansion:modularOnly"),
            strategy = mapOf("LOOP_OPTIONS_KEY" to "LOOP_NONE", "METHOD_OPTIONS_KEY" to "METHOD_CONTRACT"),
            maxSteps = 10000,
        ),
    )

    private fun editor(vararg stated: ProofOptionsDto): OptionsEditor {
        val editor = OptionsEditor(available, FALLBACK)
        editor.show(stated.toList().ifEmpty { listOf(ProofOptionsDto()) }, available.defaults)
        return editor
    }

    private companion object {
        /** What an option not set here falls back to, as this editor names it. */
        const val FALLBACK = "the context's setting"
    }

    /** Every button anywhere in a component, since the editor nests its panels. */
    private fun buttons(root: Component): List<AbstractButton> = when (root) {
        is AbstractButton -> listOf(root)
        is Container -> root.components.flatMap { buttons(it) }
        else -> emptyList()
    }

    private fun press(editor: OptionsEditor, label: String) {
        val button = buttons(editor.component).firstOrNull { it.text == label }
        assertNotNull(button, "No button reads '$label'. The buttons shown are " +
            buttons(editor.component).map { it.text })
        button.doClick()
    }

    @Test
    fun theFirstOptionOffersItsValuesAsSoonAsTheEditorOpens() {
        val editor = editor()

        // The taclet tab shows its first option, and the strategy tab shows its first.
        val shown = buttons(editor.component).map { it.text }
        assertTrue("noRestriction" in shown, "The first taclet option offers no values: $shown")
        assertTrue("Invariant (Loop Scope)" in shown,
            "The first strategy option offers no values: $shown")
    }

    @Test
    fun aValueTheUserPicksIsReported() {
        val editor = editor()

        press(editor, "Invariant (Loop Scope)")

        assertEquals(mapOf("LOOP_OPTIONS_KEY" to "LOOP_SCOPE_INVARIANT"), editor.change().strategy)
        assertTrue(editor.edited())
    }

    @Test
    fun aValueTheUserDoesNotTouchIsNotReported() {
        val editor = editor()

        press(editor, "noRestriction")

        assertEquals(mapOf("methodExpansion" to "methodExpansion:noRestriction"),
            editor.change().taclet)
        assertEquals(emptyMap(), editor.change().strategy)
        assertEquals(emptyList(), editor.change().strategyCleared)
    }

    @Test
    fun choosingInheritedAgainClearsWhatTheLevelStated() {
        val editor = editor(ProofOptionsDto(strategy = mapOf("LOOP_OPTIONS_KEY" to "LOOP_SCOPE_INVARIANT")))

        press(editor, "Use the context's setting (None)")

        assertEquals(listOf("LOOP_OPTIONS_KEY"), editor.change().strategyCleared)
        assertEquals(emptyMap(), editor.change().strategy)
    }

    @Test
    fun resettingAllPutsEveryStatedOptionBackToInherited() {
        val editor = editor(
            ProofOptionsDto(
                taclet = mapOf("methodExpansion" to "methodExpansion:noRestriction"),
                strategy = mapOf("LOOP_OPTIONS_KEY" to "LOOP_SCOPE_INVARIANT"),
                maxSteps = 500,
            ),
        )

        editor.resetAllToInherited()

        val change = editor.change()
        assertEquals(listOf("methodExpansion"), change.tacletCleared)
        assertEquals(listOf("LOOP_OPTIONS_KEY"), change.strategyCleared)
        assertEquals(0, change.maxSteps)
    }

    @Test
    fun theChangesAreToldInWords() {
        val editor = editor(ProofOptionsDto(strategy = mapOf("METHOD_OPTIONS_KEY" to "METHOD_EXPAND")))

        press(editor, "Invariant (Loop Scope)")
        editor.resetAllToInherited()
        press(editor, "Invariant (Loop Scope)")

        assertEquals(
            listOf(
                "Loop treatment: Invariant (Loop Scope)",
                "Method treatment: back to the context's setting",
            ),
            editor.changesInWords(),
        )
    }

    @Test
    fun whereTheObligationsDisagreeEachIsNamedWithItsValue() {
        val editor = OptionsEditor(available, FALLBACK)
        editor.show(
            listOf(
                ProofOptionsDto(strategy = mapOf("LOOP_OPTIONS_KEY" to "LOOP_NONE")),
                ProofOptionsDto(),
            ),
            available.defaults,
            listOf("deposit", "withdraw"),
        )

        val labels = labels(editor.component)
        assertTrue("deposit: None" in labels, "Not named: $labels")
        assertTrue("withdraw: the context's setting" in labels, "Not named: $labels")
    }

    /** Every label anywhere in a component. */
    private fun labels(root: Component): List<String> = when (root) {
        is javax.swing.JLabel -> listOf(root.text)
        is Container -> root.components.flatMap { labels(it) }
        else -> emptyList()
    }

    @Test
    fun anUntouchedEditorReportsNothing() {
        assertTrue(!editor().edited())
    }
}
