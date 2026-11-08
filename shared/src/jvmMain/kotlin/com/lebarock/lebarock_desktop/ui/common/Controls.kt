package com.lebarock.lebarock_desktop.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lebarock.lebarock_desktop.ui.theme.Dimens

// The counter's controls (client, 2026-11-08: buttons, texts, margins and paddings must be right):
// one height for every button and field (Dimens.control), the main action taller (Dimens.primary),
// one corner radius, one way to show a choice (green), help text never below 14 sp.

enum class ButtonKind { PRIMARY, SECONDARY, TEXT, DANGER }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    large: Boolean = false,
    focusable: Boolean = true,
) {
    val height = if (large) Dimens.primary else Dimens.control
    val base = modifier
        .heightIn(min = height)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .focusProperties { canFocus = focusable }
    val padding = PaddingValues(horizontal = if (kind == ButtonKind.TEXT) Dimens.sm else 20.dp, vertical = 0.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = androidx.compose.material3.LocalContentColor.current)
            Spacer(Modifier.width(Dimens.s))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.s))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val active = enabled && !loading
    when (kind) {
        ButtonKind.PRIMARY -> Button(onClick, base, active, Dimens.shape, contentPadding = padding, content = content)
        ButtonKind.DANGER -> Button(
            onClick, base, active, Dimens.shape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            contentPadding = padding, content = content,
        )
        ButtonKind.SECONDARY -> OutlinedButton(
            onClick, base, active, Dimens.shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
            contentPadding = padding, content = content,
        )
        ButtonKind.TEXT -> TextButton(onClick, base, active, Dimens.shape, contentPadding = padding, content = content)
    }
}

/** A single-line field, [Dimens.control] tall, with the app's border (green when focused, red on error). */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    large: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val border = when {
        isError -> colors.error
        focused -> colors.primary
        else -> colors.outline
    }
    val textStyle = (if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
        .copy(color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(if (large) Dimens.primary else Dimens.control),
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize()
                    .background(if (enabled) colors.surface else colors.surfaceContainerLow, Dimens.shape)
                    .border(if (focused || isError) 2.dp else 1.dp, border, Dimens.shape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = textStyle.copy(color = colors.onSurfaceVariant.copy(alpha = 0.7f)), maxLines = 1)
                    }
                    inner()
                }
                if (trailing != null) {
                    Spacer(Modifier.width(Dimens.s))
                    trailing()
                }
            }
        },
    )
}

/**
 * A labelled form control: the label above (same left edge as everything in the section), the
 * control, then one line of help or the error. The help line is always there, so fields side by
 * side stay aligned.
 */
@Composable
fun FormField(
    label: String,
    modifier: Modifier = Modifier,
    help: String? = null,
    error: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Dimens.s))
        content()
        Spacer(Modifier.height(6.dp))
        Text(
            error ?: help ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.heightIn(min = 19.dp),
        )
    }
}

data class Choice<T>(val value: T, val label: String)

/**
 * Pick one of a few values: equal boxes side by side, the chosen one green with a check. Every
 * box keeps its width, so choosing never moves the others.
 */
@Composable
fun <T> ChoiceRow(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemWidth: Dp? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        choices.forEach { choice ->
            val on = choice.value == selected
            val itemModifier = (if (itemWidth != null) Modifier.width(itemWidth) else Modifier.weight(1f))
                .height(Dimens.control)
                .clip(Dimens.shape)
                .background(if (on) colors.primaryContainer else colors.surface)
                .border(if (on) 2.dp else 1.dp, if (on) colors.primary else colors.outline, Dimens.shape)
                .clickable(enabled = enabled) { onSelect(choice.value) }
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .padding(horizontal = Dimens.sm)
            Row(itemModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (on) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    choice.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) colors.onPrimaryContainer else if (enabled) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A field-looking list to pick from (Windows printers…). */
@Composable
fun SelectField(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choisir…",
    isError: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.control)
                .clip(Dimens.shape)
                .background(colors.surface)
                .border(if (open || isError) 2.dp else 1.dp, if (isError) colors.error else if (open) colors.primary else colors.outline, Dimens.shape)
                .clickable { open = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.takeIf { it.isNotBlank() } ?: placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected.isNullOrBlank()) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(text = { Text("Aucune", color = colors.onSurfaceVariant) }, onClick = { open = false }, enabled = false)
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = MaterialTheme.typography.bodyLarge) },
                    onClick = { open = false; onSelect(option) },
                    leadingIcon = if (option == selected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.primary) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = Dimens.control).widthIn(min = 320.dp),
                )
            }
        }
    }
}

/** The top bar of every screen: back, logo, title, the screen's actions on the right. */
@Composable
fun AppTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.topBar).padding(horizontal = Dimens.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            if (onBack != null) {
                AppButton("Retour", onBack, kind = ButtonKind.SECONDARY, icon = Icons.AutoMirrored.Outlined.ArrowBack)
            }
            LogoImage(40.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/**
 * A scrolling page: content centred at a readable width, and a scrollbar that shows there is more
 * below (Compose shows none by itself; client, 2026-11-08).
 */
@Composable
fun ScrollPage(
    modifier: Modifier = Modifier,
    maxWidth: Dp = Dimens.contentMaxWidth,
    state: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(state).padding(horizontal = Dimens.l, vertical = Dimens.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.l), content = content)
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = Dimens.xs, horizontal = Dimens.xs),
            style = LocalScrollbarStyle.current,
        )
    }
}

/**
 * A titled white block of a page. The title follows its icon; the description and the content
 * start at the block's inner edge, so every line of the block shares one left edge.
 */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Dimens.cardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Dimens.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.icon))
                Spacer(Modifier.width(Dimens.sm))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            if (description != null) {
                Spacer(Modifier.height(Dimens.s))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Dimens.l))
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.l), content = content)
        }
    }
}

/** A small heading inside a block ("Protocole", "Fermer la session après"). */
@Composable
fun SubHeading(text: String, hint: String? = null) {
    Column {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        if (hint != null) {
            Spacer(Modifier.height(Dimens.xs))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Label / value line: one text size for both, one baseline, the value wraps under itself. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(Dimens.labelColumn))
        // A path breaks after a folder separator, not in the middle of a name.
        Text(value.replace("\\", "\\​").replace("/", "/​"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Every pop-up window: title, content, actions on the right; one padding. */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    dismissible: Boolean = true,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = { if (dismissible) onDismiss() }, properties = DialogProperties(dismissOnClickOutside = dismissible, dismissOnBackPress = dismissible)) {
        Surface(shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.width(width).padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (subtitle != null) {
                        Spacer(Modifier.height(Dimens.xs))
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                content()
                Row(
                    Modifier.fillMaxWidth().padding(top = Dimens.s),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/** "Enregistré automatiquement": for sections that save on each change. */
@Composable
fun AutoSavedNote() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Dimens.xs))
        Text("Enregistré automatiquement", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
    }
}
