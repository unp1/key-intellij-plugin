package org.key_project.ide.intellij

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

/**
 * A field for one path, with a button that opens a file chooser.
 *
 * The path can be typed or picked, and both are stored the same way, so the two cannot
 * disagree about whether a path is relative or absolute.
 *
 * @param project the project the settings belong to
 * @param descriptor what the chooser accepts, titled for the field it belongs to
 * @return the field
 */
internal fun pathField(project: Project, descriptor: FileChooserDescriptor):
    TextFieldWithBrowseButton {
    val field = TextFieldWithBrowseButton()
    field.addActionListener {
        val start = ProjectPaths.startingPoint(project, field.text)
        FileChooser.chooseFile(descriptor, project, start) { picked ->
            field.text = ProjectPaths.stored(project, picked.toNioPath())
        }
    }
    return field
}

/**
 * A list of paths with buttons to add, edit and remove entries.
 *
 * Both buttons that write open the same dialog, where a path can be typed or picked.
 *
 * @param project the project the settings belong to
 * @param what the name of the list, used in the dialog title
 * @param descriptor what the chooser accepts
 */
internal class PathList(
    private val project: Project,
    private val what: String,
    private val descriptor: FileChooserDescriptor,
) {

    private val model = CollectionListModel<String>()
    private val list = JBList(model)

    /** The list and its buttons, ready to be put on a form. */
    val component: JPanel = ToolbarDecorator.createDecorator(list)
        .setAddAction { add() }
        .setEditAction { edit() }
        .setRemoveAction { model.remove(list.selectedIndex) }
        .setPreferredSize(Dimension(0, JBUI.scale(90)))
        .createPanel()

    /** The paths in the list, in the order they are shown. */
    var paths: List<String>
        get() = model.items.toList()
        set(value) {
            model.replaceAll(value)
        }

    /**
     * Runs a listener whenever an entry is added, changed or removed.
     *
     * @param listener what to run
     */
    fun onChanged(listener: () -> Unit) {
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(event: ListDataEvent) = listener()
            override fun intervalRemoved(event: ListDataEvent) = listener()
            override fun contentsChanged(event: ListDataEvent) = listener()
        })
    }

    private fun add() {
        val dialog = PathEntryDialog(project, "Add to $what", descriptor, "")
        if (dialog.showAndGet()) {
            dialog.entries().forEach { model.add(it) }
        }
    }

    private fun edit() {
        val index = list.selectedIndex
        if (index < 0) {
            return
        }
        val dialog =
            PathEntryDialog(project, "Edit $what Entry", descriptor, model.getElementAt(index))
        if (dialog.showAndGet()) {
            val entries = dialog.entries()
            model.remove(index)
            model.addAll(index, entries)
        }
    }
}

/**
 * Asks for one or more paths, typed or picked.
 *
 * Picking several at once is what one does with jars, so the chooser allows it and the
 * field then holds them separated the way a classpath separates them.
 *
 * @param project the project the settings belong to
 * @param dialogTitle what the dialog is for
 * @param descriptor what the chooser accepts
 * @param start what the field says when the dialog opens
 */
private class PathEntryDialog(
    private val project: Project,
    dialogTitle: String,
    private val descriptor: FileChooserDescriptor,
    start: String,
) : DialogWrapper(project) {

    private val field = TextFieldWithBrowseButton().apply {
        text = start
        addActionListener {
            val from = ProjectPaths.startingPoint(project, text)
            FileChooser.chooseFiles(descriptor, project, from) { picked ->
                text = picked.joinToString(File.pathSeparator) {
                    ProjectPaths.stored(project, it.toNioPath())
                }
            }
        }
    }

    init {
        title = dialogTitle
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Path:", field)
        .addComponentToRightColumn(
            JBLabel(
                "Paths inside the project are stored relative to the project root.",
                UIUtil.ComponentStyle.SMALL,
            ),
        )
        .panel
        .apply { preferredSize = Dimension(JBUI.scale(520), preferredSize.height) }

    override fun getPreferredFocusedComponent(): JComponent = field.textField

    /** The paths the field names, with blanks left out. */
    fun entries(): List<String> =
        field.text.split(File.pathSeparator).map { it.trim() }.filter { it.isNotEmpty() }
}
