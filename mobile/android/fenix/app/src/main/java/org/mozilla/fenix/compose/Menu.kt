/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.button.PrimaryButton
import org.mozilla.fenix.R
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Root popup action dropdown menu.
 *
 * @param menuItems List of items to be displayed in the menu.
 * @param showMenu Whether or not the menu is currently displayed to the user.
 * @param cornerShape Shape to apply to the corners of the dropdown.
 * @param modifier Modifier to be applied to the menu.
 * @param canShowCheckItems Whether the user can check items on the dropdown menu.
 * @param offset Offset to be added to the position of the menu.
 * @param onDismissRequest Invoked when user dismisses the menu or on orientation changes.
 */
@Suppress("LongMethod")
@Composable
private fun Menu(
    menuItems: List<MenuItem>,
    showMenu: Boolean,
    cornerShape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    canShowCheckItems: Boolean = false,
    offset: DpOffset = DpOffset.Zero,
    onDismissRequest: () -> Unit,
) {
    DisposableEffect(LocalConfiguration.current.orientation) {
        onDispose { onDismissRequest() }
    }
    val localDensity = LocalDensity.current

    var columnHeightDp by remember {
        mutableStateOf(0.dp)
    }

    var selectedItemIndex by remember {
        mutableIntStateOf(0)
    }

    MaterialTheme(shapes = MaterialTheme.shapes.copy(medium = cornerShape)) {
        DropdownMenu(
            expanded = showMenu && menuItems.isNotEmpty(),
            onDismissRequest = onDismissRequest,
            offset = offset,
            scrollState = ScrollState(with(localDensity) { columnHeightDp.toPx() * selectedItemIndex }.toInt()),
            modifier = Modifier
                .background(color = FirefoxTheme.colors.layer2)
                .then(modifier),
        ) {
            val hasCheckedItems = menuItems.any { it.isChecked }
            menuItems.forEachIndexed { index, item ->
                val checkmarkModifier = if (hasCheckedItems) {
                    Modifier.selectable(
                        selected = item.isChecked,
                        role = Role.Button,
                        onClick = {
                            onDismissRequest()
                            item.onClick()
                        },
                    )
                } else {
                    Modifier
                }
                val leadingIcon: @Composable (() -> Unit)? =
                    if (item.isChecked && (hasCheckedItems || canShowCheckItems)) {
                        selectedItemIndex = index
                        {
                            Icon(
                                painter = painterResource(id = R.drawable.mozac_ic_checkmark_24),
                                modifier = Modifier
                                    .size(24.dp),
                                contentDescription = null,
                                tint = FirefoxTheme.colors.iconPrimary,
                            )
                        }
                } else {
                    null
                }

                DropdownMenuItem(
                    text = {
                        Text(
                            text = item.title,
                            color = item.color ?: FirefoxTheme.colors.textPrimary,
                            maxLines = 1,
                            style = FirefoxTheme.typography.subtitle1,
                        )
                    },
                    modifier = Modifier
                        .testTag(item.testTag)
                        .align(alignment = Alignment.CenterHorizontally)
                        .then(checkmarkModifier)
                        .onGloballyPositioned { coordinates ->
                            columnHeightDp = with(localDensity) { coordinates.size.height.toDp() }
                        }
                        .semantics { if (item.isChecked) traversalIndex = -1f },
                    leadingIcon = leadingIcon,
                    onClick = {
                        onDismissRequest()
                        item.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Dropdown menu for presenting context-specific actions.
 *
 * @param menuItems List of items to be displayed in the menu.
 * @param showMenu Whether or not the menu is currently displayed to the user.
 * @param modifier Modifier to be applied to the menu.
 * @param canShowCheckItems Whether the user can check items on the dropdown menu.
 * @param offset Offset to be added to the position of the menu.
 * @param onDismissRequest Invoked when user dismisses the menu or on orientation changes.
 */
@Deprecated(
    message = "Use DropdownMenu instead with updated parameters and MenuItem type",
    replaceWith = ReplaceWith(
        expression = "DropdownMenu( menuItems = menuItems, expanded = showMenu, modifier = modifier," +
            " offset = offset, onDismissRequest = onDismissRequest)",
        imports = ["org.mozilla.fenix.compose.menu.DropdownMenu", "org.mozilla.fenix.compose.menu.MenuItem"],
    ),
    level = DeprecationLevel.WARNING,
)
@Composable
fun ContextualMenu(
    menuItems: List<MenuItem>,
    showMenu: Boolean,
    modifier: Modifier = Modifier,
    canShowCheckItems: Boolean = false,
    offset: DpOffset = DpOffset.Zero,
    onDismissRequest: () -> Unit,
) {
    Menu(
        menuItems = menuItems,
        showMenu = showMenu,
        modifier = modifier,
        canShowCheckItems = canShowCheckItems,
        cornerShape = RoundedCornerShape(size = 5.dp),
        offset = offset,
        onDismissRequest = onDismissRequest,
    )
}

/**
 * Represents a text item from the dropdown menu.
 *
 * @property title Text the item should display.
 * @property color Color used to display the text.
 * @property isChecked Whether a checkmark should appear next to the text.
 * @property testTag Tag used to identify the item in automated tests.
 * @property onClick Callback to be called when the item is clicked.
 */
data class MenuItem(
    val title: String,
    val color: Color? = null,
    val isChecked: Boolean = false,
    val testTag: String = "",
    val onClick: () -> Unit,
)

@PreviewLightDark
@Composable
@Suppress("Deprecation")
private fun ContextualMenuPreview() {
    var showMenu by remember { mutableStateOf(false) }
    FirefoxTheme {
        Box(modifier = Modifier.size(400.dp)) {
            PrimaryButton(
                text = "Show menu",
                modifier = Modifier.fillMaxWidth(),
            ) {
                showMenu = true
            }

            ContextualMenu(
                menuItems = listOf(
                    MenuItem("Rename") {},
                    MenuItem("Share") {},
                    MenuItem("Remove") {},
                ),
                showMenu = showMenu,
                onDismissRequest = { showMenu = false },
            )
        }
    }
}
