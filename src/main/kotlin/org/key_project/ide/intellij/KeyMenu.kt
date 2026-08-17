package org.key_project.ide.intellij

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.ui.PopupHandler
import javax.swing.JComponent

/**
 * The KeY menu, wherever it is asked for.
 *
 * Every view shows the same registered group rather than a menu of its own, so the actions
 * offered in the editor, the project view and the tool windows are identical, and a new
 * action has to be added only once.
 */
private const val KEY_GROUP = "org.key_project.ide.intellij.KeyGroup"

private fun keyGroup(): ActionGroup? =
    ActionManager.getInstance().getAction(KEY_GROUP) as? ActionGroup

/**
 * Installs the KeY menu on a view, to be shown wherever that platform shows a context menu.
 *
 * The platform decides when a menu is due, which differs between systems and is not the
 * same as any one mouse event. Showing the menu from a press of our own competes with what
 * the view does with that press: a tree expands or collapses the row, and the menu is
 * dismissed as it opens.
 *
 * @param view the component to install it on
 */
fun installKeyMenu(view: JComponent) {
    val group = keyGroup() ?: return
    PopupHandler.installPopupMenu(view, group, ActionPlaces.POPUP)
}
