package org.key_project.ide.intellij

import com.intellij.ui.JBSplitter
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

/**
 * Edits the settings of one level, or of several obligations at once.
 *
 * The options are listed on the left and the selected one is chosen on the right, so that
 * each option is read with what it means rather than as one row in a wall of them. The list
 * shows the value every option has, and prints in bold the ones this level decides, which
 * is how one sees at a glance what the level changes.
 *
 * Every option offers what the level above it says as well as the values themselves, so a
 * field that was set can be put back to being inherited. Only the options the user actually
 * changed are reported, which is what makes editing a selection safe: an obligation keeps
 * everything that was not touched, rather than being overwritten with the settings of
 * whichever obligation the form happened to show.
 *
 * @param available what the context offers to choose from
 * @param fallback what an option that is not set here uses instead, named as the user
 *   would name it: KeY's default for the project, the project's setting for a context, the
 *   context's setting for an obligation
 */
class OptionsEditor(
    private val available: AvailableOptionsDto,
    private val fallback: String = "KeY's default",
) {

    private val taclet = available.taclet.map { OptionRow(it, fallback) }
    private val strategy = available.strategy.map { OptionRow(it, fallback) }
    private val tacletSection = Section(taclet)
    private val strategySection = Section(strategy)
    private val maxSteps = JBTextField(10)
    private var statedMaxSteps = ""
    private val timeout = JBTextField(10)
    private var statedTimeout = ""

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(
            JBTabbedPane().apply {
                addTab("Taclet Options", tacletSection.component)
                addTab("Strategy Options", strategySection.component)
            },
            BorderLayout.CENTER,
        )
        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("Maximum rule applications:", maxSteps)
                .addLabeledComponent("Timeout (ms, -1 for none):", timeout)
                .addComponentToRightColumn(
                    JBLabel("Empty means: use $fallback.", UIUtil.ComponentStyle.SMALL),
                )
                .panel
                .apply { border = JBUI.Borders.empty(8, 0, 0, 0) },
            BorderLayout.SOUTH,
        )
    }

    /**
     * One tab: the options on the left, the values of the selected one on the right.
     *
     * @param rows the options of this tab
     */
    private class Section(rows: List<OptionRow>) {

        private val list = JBList(CollectionListModel(rows)).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = OptionRenderer()
            border = JBUI.Borders.empty(4, 4, 4, 12)
        }
        private val detail = OptionDetail { list.repaint() }

        val component: JComponent = JBSplitter(false, 0.38f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = detail.component
            splitterProportionKey = "org.key_project.ide.intellij.options"
        }

        init {
            list.addListSelectionListener { detail.show(list.selectedValue) }
            if (rows.isNotEmpty()) {
                list.selectedIndex = 0
            }
        }

        /**
         * Draws the tab again once the rows have been given their values.
         *
         * The tab is built before the values are known, so what it drew first has no
         * values to offer. This is called once they are known, and again whenever they are
         * replaced.
         */
        fun refresh() {
            list.repaint()
            detail.show(list.selectedValue)
        }
    }

    /**
     * Shows what the edited levels state, against what they would inherit.
     *
     * @param stated what each edited level states, one entry per level being edited
     * @param inherited what a level shows where it states nothing
     */
    fun show(stated: List<ProofOptionsDto>, inherited: ProofOptionsDto) {
        show(stated, inherited, stated.indices.map { "" })
    }

    /**
     * Shows what the edited levels state, against what they would inherit, naming each
     * level so that where they disagree the user can see which says what.
     *
     * @param stated what each edited level states, one entry per level being edited
     * @param inherited what a level shows where it states nothing
     * @param names how each edited level reads to the user, in the order of [stated]
     */
    fun show(stated: List<ProofOptionsDto>, inherited: ProofOptionsDto, names: List<String>) {
        taclet.forEach { row ->
            row.show(stated.map { it.taclet[row.key()] }, inherited.taclet, names)
        }
        strategy.forEach { row ->
            row.show(stated.map { it.strategy[row.key()] }, inherited.strategy, names)
        }

        val statedSteps = stated.map { if (it.maxSteps > 0) it.maxSteps.toString() else "" }
        statedMaxSteps = statedSteps.distinct().singleOrNull() ?: VARIES
        maxSteps.text = statedMaxSteps
        maxSteps.emptyText.text = "${inherited.maxSteps} (inherited)"

        val statedTimeouts = stated.map { if (it.timeout != 0L) it.timeout.toString() else "" }
        statedTimeout = statedTimeouts.distinct().singleOrNull() ?: VARIES
        timeout.text = statedTimeout
        timeout.emptyText.text = "${timeoutText(inherited.timeout)} (inherited)"

        tacletSection.refresh()
        strategySection.refresh()
    }

    /** What the user changed, and nothing else. */
    fun change(): OptionChangeDto = OptionChangeDto(
        taclet = taclet.mapNotNull { it.setTo() }.toMap(),
        tacletCleared = taclet.filter { it.cleared() }.map { it.key() },
        strategy = strategy.mapNotNull { it.setTo() }.toMap(),
        strategyCleared = strategy.filter { it.cleared() }.map { it.key() },
        maxSteps = editedMaxSteps(),
        timeout = editedTimeout(),
    )

    /** Whether the user changed anything at all. */
    fun edited(): Boolean = change() != OptionChangeDto()

    /** Sets every option to inherit from the level above, which the user then confirms. */
    fun resetAllToInherited() {
        (taclet + strategy).forEach { it.inherit() }
        maxSteps.text = ""
        timeout.text = ""
        tacletSection.refresh()
        strategySection.refresh()
    }

    /**
     * What the user changed, in words, for a message once it is stored.
     *
     * @return one line per changed option, empty when nothing was changed
     */
    fun changesInWords(): List<String> {
        val lines = mutableListOf<String>()
        (taclet + strategy).filter { it.changed() }.forEach { row ->
            lines.add(
                if (row.cleared()) "${row.label()}: back to $fallback"
                else "${row.label()}: ${row.valueLabel()}",
            )
        }
        editedMaxSteps()?.let {
            lines.add(if (it == 0) "Maximum rule applications: back to $fallback"
            else "Maximum rule applications: $it")
        }
        editedTimeout()?.let {
            lines.add(if (it == 0L) "Timeout: back to $fallback"
            else "Timeout: ${timeoutText(it)}")
        }
        return lines
    }

    /** What the context offers, for a caller that has to work out what a level inherits. */
    fun available(): AvailableOptionsDto = available

    private fun editedMaxSteps(): Int? {
        val text = maxSteps.text.trim()
        return if (text == statedMaxSteps) null else text.toIntOrNull() ?: 0
    }

    /** What the user typed as a timeout, or null when the field was left as it was. */
    private fun editedTimeout(): Long? {
        val text = timeout.text.trim()
        return if (text == statedTimeout) null else text.toLongOrNull() ?: 0L
    }

    /** How a timeout reads: KeY's -1 is no timeout at all. */
    private fun timeoutText(timeout: Long): String =
        if (timeout == -1L) "no timeout" else "$timeout ms"

    /** The name of an option, with the value it has and whether this level decides it. */
    private class OptionRenderer : ColoredListCellRenderer<OptionRow>() {

        override fun customizeCellRenderer(
            list: JList<out OptionRow>,
            row: OptionRow?,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ) {
            if (row == null) {
                return
            }
            append(
                row.label(),
                if (row.decidedHere()) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                else SimpleTextAttributes.REGULAR_ATTRIBUTES,
            )
            append("   ${row.valueLabel()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (row.changed()) {
                // What the user has changed in this dialog, so that pressing OK holds no
                // surprise.
                append("  changed", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
        }
    }

    /**
     * The right half: what the selected option means, and the values it takes.
     *
     * @param onChosen what to do once a value has been chosen, which the list uses to
     *        redraw itself
     */
    private class OptionDetail(private val onChosen: () -> Unit) {

        val component = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(4, 12, 4, 16) }

        /** Shows one option, or nothing when the list has no selection. */
        fun show(row: OptionRow?) {
            component.removeAll()
            if (row != null) {
                component.add(built(row), BorderLayout.NORTH)
            }
            component.revalidate()
            component.repaint()
        }

        private fun built(row: OptionRow): JComponent {
            val meaning = wrapping()
            val values = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.emptyTop(8)
            }
            val group = ButtonGroup()
            row.choices.forEach { choice ->
                val button = JBRadioButton(choice.label).apply {
                    isSelected = choice == row.chosen
                    addActionListener {
                        row.chosen = choice
                        meaning.text = choice.description.ifEmpty { row.category.description }
                        onChosen()
                    }
                }
                group.add(button)
                values.add(button)
            }
            meaning.text = row.chosen.description.ifEmpty { row.category.description }

            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel(row.label()).apply { font = JBFont.label().asBold() })
                // Where the edited obligations disagree, each is named with what it says,
                // so the user can see what leaving the option alone would keep.
                row.statedByEach().forEach { line ->
                    add(JBLabel(line, UIUtil.ComponentStyle.SMALL))
                }
            }

            return JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(values, BorderLayout.CENTER)
                add(JBScrollPane(meaning).apply { border = JBUI.Borders.emptyTop(8) },
                    BorderLayout.SOUTH)
            }
        }

        /** A read-only text area, used as a label that wraps what KeY has to say. */
        private fun wrapping(): JTextArea = JTextArea(6, 30).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            font = JBFont.small()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty()
        }
    }

    /** One option, and what has been chosen for it. */
    private class OptionRow(val category: OptionCategoryDto, private val fallback: String) {

        /** What can be chosen, with what the level above says among them. */
        var choices: List<Choice> = emptyList()
            private set

        /** What was chosen when the dialog opened, so that an untouched option is known. */
        private var opened: Choice = Choice.Inherited(fallback, "", "")

        /** What is chosen now. */
        var chosen: Choice = opened

        /** What each edited level states, by the name of the level; empty when they agree. */
        private var byLevel: List<String> = emptyList()

        fun key(): String = category.key

        fun label(): String = category.label

        fun show(stated: List<String?>, inherited: Map<String, String>, names: List<String>) {
            // Levels that state nothing agree on stating nothing, which is not the same as
            // disagreeing, so the two are told apart before a value is looked for.
            val distinct = stated.distinct()
            val agree = distinct.size <= 1
            val agreed = if (agree) distinct.firstOrNull() else null
            byLevel = if (agree) emptyList() else stated.mapIndexed { index, value ->
                val name = names.getOrNull(index).orEmpty().ifEmpty { "Obligation ${index + 1}" }
                "$name: " + (valueOf(value)?.label ?: value ?: fallback)
            }

            val inheritedValue = valueOf(inherited[category.key])
            val offered = mutableListOf<Choice>()
            if (!agree) {
                offered.add(Choice.Varies)
            }
            offered.add(
                Choice.Inherited(
                    fallback,
                    inheritedValue?.label ?: inherited[category.key].orEmpty(),
                    inheritedValue?.description.orEmpty(),
                ),
            )
            category.values.forEach {
                offered.add(Choice.Value(it.value, it.label, it.description))
            }

            choices = offered
            opened = offered.firstOrNull { it.matches(agreed) } ?: offered.first()
            chosen = opened
        }

        /** The value this option was set to, or null when the user did not set one. */
        fun setTo(): Pair<String, String>? {
            val chosenValue = chosen
            return if (chosenValue != opened && chosenValue is Choice.Value) {
                category.key to chosenValue.value
            } else {
                null
            }
        }

        /** Whether the user asked for this option to be inherited again. */
        fun cleared(): Boolean = chosen != opened && chosen is Choice.Inherited

        /** Whether the user changed this option in the dialog. */
        fun changed(): Boolean = chosen != opened

        /** Chooses to inherit, as the user would by choosing that value. */
        fun inherit() {
            chosen = choices.firstOrNull { it is Choice.Inherited } ?: chosen
        }

        /** What each edited level states, one line each; empty when they all agree. */
        fun statedByEach(): List<String> = byLevel

        /** Whether the edited level decides this option rather than inheriting it. */
        fun decidedHere(): Boolean = chosen is Choice.Value

        /** What the option is set to, for the list. */
        fun valueLabel(): String = when (val current = chosen) {
            is Choice.Value -> current.label
            else -> current.label
        }

        private fun valueOf(value: String?) = category.values.firstOrNull { it.value == value }
    }

    /** What an option can be shown as. */
    private sealed interface Choice {

        val label: String

        /** What choosing this means, as KeY explains it. */
        val description: String

        /** Whether this stands for the value a level states. */
        fun matches(stated: String?): Boolean

        /** Nothing is set here, so whatever this level falls back to decides. */
        data class Inherited(
            private val fallback: String,
            private val value: String,
            override val description: String,
        ) : Choice {
            override val label: String
                get() = if (value.isEmpty()) "Use $fallback" else "Use $fallback ($value)"

            override fun matches(stated: String?): Boolean = stated == null
        }

        /** The edited levels do not agree, and leaving this alone keeps it that way. */
        data object Varies : Choice {
            override val label: String = VARIES

            override val description: String =
                "The obligations being edited do not agree on this option. " +
                    "Leaving it alone keeps what each of them says."

            override fun matches(stated: String?): Boolean = false
        }

        /** One of the values the option accepts. */
        data class Value(
            val value: String,
            override val label: String,
            override val description: String,
        ) : Choice {
            override fun matches(stated: String?): Boolean = stated == value
        }
    }

    private companion object {
        /** Shown where the levels being edited do not agree. */
        const val VARIES = "<varies>"
    }
}
