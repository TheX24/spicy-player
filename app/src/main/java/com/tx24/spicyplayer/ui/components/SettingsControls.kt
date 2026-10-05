package com.tx24.spicyplayer.ui.components

import androidx.compose.ui.composed
import androidx.compose.runtime.ReadOnlyComposable
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.em
import com.tx24.spicyplayer.ui.theme.SpicyColors
import com.tx24.spicyplayer.ui.theme.SpicyMotion
import com.tx24.spicyplayer.ui.theme.SpicyRadii
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import com.tx24.spicyplayer.ui.theme.SpicyType
import kotlin.math.roundToInt

/*
 * The settings screen's controls, with search built in: rows hide themselves when they don't
 * match [LocalSettingsQuery].
 */

/** What the settings search box holds; blank shows everything. */
val LocalSettingsQuery = compositionLocalOf { "" }

/** Whether the interface animates (playback buttons, lists, pop-up screens): the setting, and not low performance mode. */
val LocalUiAnimations = compositionLocalOf { true }

/** Whether the label or the description contains the query, ignoring case. */
fun settingMatches(query: String, vararg terms: String?): Boolean {
    val q = query.trim()
    return q.isEmpty() || terms.any { it?.contains(q, ignoreCase = true) == true }
}

/** Shows [content] only when one of [terms] matches the search. */
@Composable
fun Searchable(vararg terms: String?, content: @Composable () -> Unit) {
    if (settingMatches(LocalSettingsQuery.current, *terms)) content()
}

/**
 * A section title: a headline over its rows, after a hairline unless [divider] is off.
 * Takes no room at all when every row under it is hidden by the search.
 */
@Composable
fun SettingsSection(title: String?, modifier: Modifier = Modifier, divider: Boolean = true, content: @Composable () -> Unit) {
    CollapsingColumn(
        header = { if (title != null) SectionTitle(title, divider) },
        modifier = modifier,
        content = content,
    )
}

/** A header over [content]; both vanish when [content] lays out empty. */
@Composable
fun CollapsingColumn(header: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(
        contents = listOf(header, { Column { content() } }),
        modifier = modifier,
    ) { (headerMeasurables, bodyMeasurables), constraints ->
        val loose = constraints.copy(minHeight = 0)
        val body = bodyMeasurables.map { it.measure(loose) }
        val bodyHeight = body.sumOf { it.height }
        if (bodyHeight == 0) return@Layout layout(0, 0) {}
        val header = headerMeasurables.map { it.measure(loose) }
        val headerHeight = header.sumOf { it.height }
        val width = (header + body).maxOf { it.width }.coerceAtLeast(constraints.minWidth)
        layout(width, headerHeight + bodyHeight) {
            var y = 0
            (header + body).forEach { it.place(0, y); y += it.height }
        }
    }
}

@Composable
fun SectionTitle(text: String, divider: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.padding(top = SpicySpacing.S2).fillMaxWidth().height(1.dp).background(SpicyColors.Hairline))
        Text(
            text,
            style = SpicyType.Headline.copy(letterSpacing = (-0.005).em),
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = if (divider) SpicySpacing.S4 else SpicySpacing.S2, bottom = SpicySpacing.S2),
        )
    }
}

/**
 * A settings row: label and description on the left, the control on the right, or under them when
 * [stacked]. Tapping the row runs [onClick]. Hidden when it doesn't match the search.
 */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    stacked: Boolean = false,
    control: @Composable () -> Unit = {},
) {
    if (!settingMatches(LocalSettingsQuery.current, label, description)) return
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    RowFrame(
        label, description, icon, interaction, enabled, stacked,
        modifier.then(
            if (onClick != null && enabled) {
                Modifier.clickable(interaction, indication = null) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                }
            } else Modifier,
        ),
        control,
    )
}

/** A [SettingRow] with a toggle; the whole row flips it. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    if (!settingMatches(LocalSettingsQuery.current, label, description)) return
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    RowFrame(
        label, description, icon, interaction, enabled, stacked = false,
        modifier = modifier.toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Switch) {
            haptics.toggled(it)
            onCheckedChange(it)
        },
    ) { SpicyToggle(checked) }
}

@Composable
private fun RowFrame(
    label: String,
    description: String?,
    icon: ImageVector?,
    interaction: MutableInteractionSource,
    enabled: Boolean,
    stacked: Boolean,
    modifier: Modifier,
    control: @Composable () -> Unit,
) {
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed) SpicyColors.TintBg else Color.Transparent,
        tween(SpicyMotion.FAST_MS, easing = SpicyMotion.Standard),
        label = "rowPress",
    )
    val frame = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(SpicyRadii.Md))
        .background(background)
        .padding(SpicySpacing.S3)
        .alpha(if (enabled) 1f else DISABLED_ALPHA)
    if (stacked) {
        Column(frame, verticalArrangement = Arrangement.spacedBy(SpicySpacing.S3)) {
            RowLabel(label, description, Modifier.fillMaxWidth(), icon)
            control()
        }
    } else {
        Row(frame, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S4)) {
            RowLabel(label, description, Modifier.weight(1f), icon)
            control()
        }
    }
}

/** A row's label over its description, after an optional icon. */
@Composable
fun RowLabel(label: String, description: String?, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S3)) {
        // Level with the label's first line, however long the description runs.
        if (icon != null) Icon(icon, null, tint = SpicyColors.TextSecondary, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = SpicyType.Body.copy(fontWeight = FontWeight.Medium))
            if (description != null) Text(description, style = DescriptionStyle)
        }
    }
}

/** A row's description: caption in secondary text at 85%. */
val DescriptionStyle @Composable @ReadOnlyComposable get() = SpicyType.Caption.copy(color = SpicyColors.TextSecondary.copy(alpha = 0.6f * 0.85f), lineHeight = 1.4.em)

/**
 * A toggle whose state shows through brightness alone. Off is a dark groove with a cream knob,
 * on a lit track with a white knob. Pass [onCheckedChange] only when nothing around it toggles.
 */
@Composable
fun SpicyToggle(checked: Boolean, modifier: Modifier = Modifier, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val progress by animateFloatAsState(
        if (checked) 1f else 0f,
        tween(SpicyMotion.MODAL_MS, easing = SpicyMotion.Modal),
        label = "toggle",
    )
    val haptics = LocalHapticFeedback.current
    val click = if (onCheckedChange != null) {
        Modifier.toggleable(checked, remember { MutableInteractionSource() }, indication = null, role = Role.Switch) {
            haptics.toggled(it)
            onCheckedChange(it)
        }
    } else {
        Modifier
    }
    Canvas(modifier.then(click).size(42.dp, 24.dp)) {
        val r = size.height / 2f
        val px = 1.dp.toPx()
        drawRoundRect(lerp(ToggleTrackOff, ToggleTrackOn, progress), cornerRadius = CornerRadius(r))
        drawRoundRect(
            lerp(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.24f), progress),
            topLeft = Offset(px / 2f, px / 2f),
            size = Size(size.width - px, size.height - px),
            cornerRadius = CornerRadius(r - px / 2f),
            style = Stroke(px),
        )
        val knobR = 10.dp.toPx()
        val knob = Offset(2.dp.toPx() + knobR + progress * 18.dp.toPx(), r)
        drawCircle(Color.Black.copy(alpha = 0.22f), knobR + 1.5f * px, knob.copy(y = knob.y + 1.5f * px))
        drawCircle(lerp(ToggleKnobOff, Color.White, progress), knobR, knob)
    }
}

/** A switch's feedback: [HapticFeedbackType.ToggleOn] or [HapticFeedbackType.ToggleOff]. */
fun HapticFeedback.toggled(on: Boolean) =
    performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

private val ToggleTrackOff = Color.Black.copy(alpha = 0.42f)
private val ToggleTrackOn = Color.White.copy(alpha = 0.42f)
private val ToggleKnobOff = Color(245, 245, 245).copy(alpha = 0.78f)

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/**
 * A tinted pill showing the chosen label with a chevron; tapped, it opens the options on a dark
 * plate under the pill (over it when there's no room below), the chosen one ticked.
 */
@Composable
fun SpicySelect(
    value: String,
    options: List<String>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    labels: List<String> = options,
    enabled: Boolean = true,
) {
    // Drives the open and close animation; the popup stays up until the close finishes.
    val menu = remember { MutableTransitionState(false) }
    // A tap on the pill while open lands after the popup has closed itself on touch-down;
    // without this it would open again at once.
    var closedAt by remember { mutableStateOf(0L) }
    val close = {
        if (menu.targetState) closedAt = SystemClock.uptimeMillis()
        menu.targetState = false
    }
    val open = menu.targetState
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(SpicyRadii.Sm)
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Row(
            Modifier
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(shape)
                .background(if (pressed || open) SpicyColors.TintBgPressed else SpicyColors.TintBg)
                .border(1.dp, if (pressed || open) SpicyColors.HairlineStrong else SpicyColors.Hairline, shape)
                .clickable(interaction, indication = null, enabled = enabled, role = Role.DropdownList) {
                    if (open || SystemClock.uptimeMillis() - closedAt < REOPEN_GUARD_MS) return@clickable
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    menu.targetState = true
                }
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(labels.getOrElse(options.indexOf(value)) { value }, style = SpicyType.Caption.copy(fontWeight = FontWeight.Medium))
            val turn by animateFloatAsState(if (open) 180f else 0f, tween(SpicyMotion.FAST_MS), label = "selectChevron")
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = SpicyColors.TextSecondary,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn },
            )
        }
        if (menu.currentState || menu.targetState) {
            val margin = with(LocalDensity.current) { SpicySpacing.S2.roundToPx() }
            val position = remember(margin) { SelectMenuPosition(margin) }
            Popup(popupPositionProvider = position, onDismissRequest = close, properties = PopupProperties(focusable = true)) {
                val origin = TransformOrigin(1f, if (position.above) 1f else 0f)
                AnimatedVisibility(
                    visibleState = menu,
                    enter = fadeIn(tween(SpicyMotion.FAST_MS)) +
                        scaleIn(tween(SpicyMotion.FAST_MS, easing = SpicyMotion.Modal), initialScale = 0.94f, transformOrigin = origin),
                    exit = fadeOut(tween(SpicyMotion.FAST_MS)) +
                        scaleOut(tween(SpicyMotion.FAST_MS), targetScale = 0.94f, transformOrigin = origin),
                ) {
                    val plate = RoundedCornerShape(SpicyRadii.Md)
                    Column(
                        Modifier
                            .widthIn(min = 180.dp, max = 280.dp)
                            .width(IntrinsicSize.Max)
                            .heightIn(max = 360.dp)
                            .clip(plate)
                            .background(MENU_FILL)
                            .border(1.dp, SpicyColors.HairlineStrong, plate)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = SpicySpacing.S1),
                    ) {
                        options.forEachIndexed { i, option ->
                            SelectOption(labels.getOrElse(i) { option }, selected = option == value) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                close()
                                if (option != value) onChange(option)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = SpicySpacing.S1)
            .clip(RoundedCornerShape(SpicyRadii.Sm))
            .background(if (pressed) SpicyColors.TintBgPressed else Color.Transparent)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = SpicySpacing.S3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S3),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = SpicyType.Body.copy(
                color = if (selected) SpicyColors.TextPrimary else SpicyColors.TextSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
        Box(Modifier.size(18.dp)) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = SpicyColors.TextPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Puts the menu under its pill, right edges lined up (the pills sit at a row's end), or over the
 * pill when it doesn't fit below; always [margin] inside the window.
 */
private class SelectMenuPosition(private val margin: Int) : PopupPositionProvider {
    var above by mutableStateOf(false)
        private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val gap = margin / 2
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(margin, maxX)
        val below = anchorBounds.bottom + gap
        val fitsBelow = below + popupContentSize.height <= windowSize.height - margin
        above = !fitsBelow && anchorBounds.top - gap - popupContentSize.height >= margin
        val y = if (above) anchorBounds.top - gap - popupContentSize.height else below
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        return IntOffset(x, y.coerceIn(margin, maxY))
    }
}

private val MENU_FILL = Color(30, 30, 34).copy(alpha = 0.98f)
private const val REOPEN_GUARD_MS = 300L

/** A flat tinted pill that shrinks to 0.97 while held. */
@Composable
fun SpicyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Narrower for a row of small buttons sharing the width. */
    horizontalPadding: androidx.compose.ui.unit.Dp = 14.dp,
    /** A platform's colour, which takes over the tint, the ring and the label. */
    brand: Color? = null,
    /** Marks a link that leaves the app with a small arrow. */
    external: Boolean = false,
) {
    val color = brand ?: SpicyColors.TextPrimary
    PressSurface(onClick, modifier, enabled, horizontalPadding, brand) {
        Text(text, style = SpicyType.Caption.copy(color = color, fontWeight = FontWeight.SemiBold, letterSpacing = 0.01.em))
        if (external) {
            Spacer(Modifier.width(6.dp))
            Image(
                rememberVectorPainter(Icons.Rounded.ArrowOutward),
                null,
                Modifier.size(12.dp).alpha(0.7f),
                colorFilter = ColorFilter.tint(color),
            )
        }
    }
}

/**
 * A value in a row of [SpicyButton]s that resets it when tapped. At its default it's plain text;
 * off it, it becomes a button with a reset mark, so the one way back sits on the value itself.
 */
@Composable
fun SpicyResettableValue(text: String, atDefault: Boolean, onReset: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val style = SpicyType.Caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    if (atDefault) {
        Box(modifier.heightIn(min = 36.dp), contentAlignment = Alignment.Center) {
            Text(text, style = style.copy(color = SpicyColors.TextSecondary))
        }
    } else {
        PressSurface(onReset, modifier.semantics { contentDescription = "$text, tap to reset" }, enabled, horizontalPadding = 0.dp) {
            Text(text, style = style)
            Spacer(Modifier.width(SpicySpacing.S1))
            Image(
                rememberVectorPainter(Icons.Rounded.Replay),
                null,
                Modifier.size(14.dp),
                colorFilter = ColorFilter.tint(SpicyColors.TextSecondary),
            )
        }
    }
}

/** A square [SpicyButton] around an icon, e.g. the source cards' up and down. */
@Composable
fun SpicyIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    PressSurface(onClick, modifier.size(36.dp), enabled, horizontalPadding = 0.dp, content = { content() })
}

@Composable
private fun PressSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp = 14.dp,
    brand: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "buttonScale")
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(SpicyRadii.Sm)
    Row(
        modifier
            .heightIn(min = 36.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(
                when {
                    brand != null -> brand.copy(alpha = if (pressed) 0.24f else 0.14f)
                    pressed -> SpicyColors.TintBgPressed
                    else -> SpicyColors.TintBg
                },
            )
            .border(
                1.dp,
                when {
                    brand != null -> brand.copy(alpha = if (pressed) 0.55f else 0.35f)
                    pressed -> SpicyColors.HairlineStrong
                    else -> SpicyColors.Hairline
                },
                shape,
            )
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

/**
 * A text field: tinted, hairline ring, brighter while focused.
 *
 * Built on the state-based text field, whose cursor handle scrolls a long line when dragged to
 * the edge; the older value-based one stops at what's visible. [value] still drives it: an
 * outside change replaces the text, and typing reports through [onValueChange].
 */
@Composable
fun SpicyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** The keyboard's action key becomes Search and calls this. */
    onSearch: (() -> Unit)? = null,
    /** Grows to a few lines, Enter adding one. */
    multiline: Boolean = false,
) {
    val state = rememberTextFieldState(value)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { if (it != latestValue) latestOnValueChange(it) }
    }

    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SpicyRadii.Sm)
    val fieldModifier = modifier.onFocusChanged { focused = it.isFocused }
    val onKeyboardAction = onSearch?.let { search -> KeyboardActionHandler { search() } }
    val decorator = TextFieldDecorator { field ->
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(if (focused) SpicyColors.TintBgPressed else SpicyColors.TintBg)
                .border(1.dp, if (focused) SpicyColors.HairlineStrong else SpicyColors.Hairline, shape)
                .padding(horizontal = 10.dp, vertical = if (multiline) 9.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpicySpacing.S2),
        ) {
            leading?.invoke()
            Box(Modifier.weight(1f)) {
                if (state.text.isEmpty()) Text(placeholder, style = SpicyType.Body.copy(color = SpicyColors.TextSecondary))
                field()
            }
            trailing?.invoke()
        }
    }
    val cursorBrush = SolidColor(SpicyColors.TextPrimary)
    if (password) {
        BasicSecureTextField(
            state = state,
            modifier = fieldModifier,
            textStyle = SpicyType.Body,
            onKeyboardAction = onKeyboardAction,
            cursorBrush = cursorBrush,
            decorator = decorator,
        )
    } else {
        BasicTextField(
            state = state,
            modifier = fieldModifier,
            textStyle = SpicyType.Body,
            keyboardOptions = if (onSearch != null) KeyboardOptions(imeAction = ImeAction.Search) else KeyboardOptions.Default,
            onKeyboardAction = onKeyboardAction,
            lineLimits = if (multiline) TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6) else TextFieldLineLimits.SingleLine,
            cursorBrush = cursorBrush,
            decorator = decorator,
        )
    }
}

/** The search bar: the magnifier, the field, and a clear button once there's text. */
@Composable
fun SpicySearchBar(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val glyph = SpicyColors.TextTertiary
    SpicyTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "Search settings…",
        modifier = modifier,
        leading = {
            Canvas(Modifier.size(14.dp)) {
                val s = size.width / 14f
                val stroke = Stroke(1.5f * s, cap = StrokeCap.Round)
                drawCircle(glyph, 4.5f * s, Offset(6f * s, 6f * s), style = stroke)
                drawLine(glyph, Offset(9.5f * s, 9.5f * s), Offset(13f * s, 13f * s), 1.5f * s, StrokeCap.Round)
            }
        },
        trailing = if (value.isEmpty()) null else {
            {
                Box(
                    Modifier
                        .size(28.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onValueChange("")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(10.dp)) {
                        val s = size.width / 10f
                        drawLine(glyph, Offset(s, s), Offset(9f * s, 9f * s), 1.5f * s, StrokeCap.Round)
                        drawLine(glyph, Offset(9f * s, s), Offset(s, 9f * s), 1.5f * s, StrokeCap.Round)
                    }
                }
            }
        },
    )
}

/**
 * A bipolar slider: a light groove with a centre tick, filled from zero out to the thumb, so
 * the fill's direction shows the sign. Under it, the value and a Reset once it's off [default].
 */
@Composable
fun SpicyBipolarSlider(
    value: Int,
    range: IntRange,
    step: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    default: Int = 0,
    unit: String? = null,
    enabled: Boolean = true,
    /** False leaves out the value and Reset under the groove, for a control that shows them itself. */
    showValue: Boolean = true,
) {
    val clamped = value.coerceIn(range)
    val onChangeUpdated by rememberUpdatedState(onValueChange)
    val shown by rememberUpdatedState(clamped)
    // Read through state: the drag handler below outlives this composition.
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    // A tick for each step the value moves.
    val currentOnChange = { v: Int ->
        if (v != shown) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            onChangeUpdated(v)
        }
    }
    var held by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(if (held) 0.92f else 1f, tween(120), label = "thumb")
    val span = (range.last - range.first).coerceAtLeast(1)
    val thumbR = 8.dp
    fun valueAt(x: Float, width: Float, thumbPx: Float): Int {
        val f = ((x - thumbPx) / (width - 2f * thumbPx)).coerceIn(0f, 1f)
        val raw = range.first + f * span
        return ((raw / step).roundToInt() * step).coerceIn(range)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpicySpacing.S2)) {
        val colors = SpicyColors
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { currentOnChange(valueAt(it.x, size.width.toFloat(), thumbR.toPx())) }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        held = true
                        val width = size.width.toFloat()
                        val thumbPx = thumbR.toPx()
                        currentOnChange(valueAt(slop.position.x, width, thumbPx))
                        horizontalDrag(slop.id) {
                            it.consume()
                            currentOnChange(valueAt(it.position.x, width, thumbPx))
                        }
                        held = false
                    }
                },
        ) {
            val thumbPx = thumbR.toPx()
            fun posFor(f: Float) = thumbPx + f * (size.width - 2f * thumbPx)
            val cy = size.height / 2f
            val trackH = 4.dp.toPx()
            val zeroF = ((if (range.first < 0 && range.last > 0) 0 else range.first) - range.first) / span.toFloat()
            val f = (clamped - range.first) / span.toFloat()
            drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, cy - trackH / 2f), Size(size.width, trackH), CornerRadius(trackH / 2f))
            val from = posFor(minOf(zeroF, f))
            val to = posFor(maxOf(zeroF, f))
            drawRoundRect(colors.Accent, Offset(from, cy - trackH / 2f), Size(to - from, trackH), CornerRadius(trackH / 2f))
            val tickH = 12.dp.toPx()
            drawRoundRect(
                colors.HairlineStrong,
                Offset(posFor(zeroF) - 1.dp.toPx(), cy - tickH / 2f),
                Size(2.dp.toPx(), tickH),
                CornerRadius(1.dp.toPx()),
            )
            val thumb = Offset(posFor(f), cy)
            val r = thumbPx * thumbScale
            drawCircle(Color.Black.copy(alpha = 0.3f), r + 2.dp.toPx(), thumb.copy(y = thumb.y + 2.dp.toPx()))
            drawCircle(Color.White, r, thumb)
        }
        if (showValue) Row(Modifier.fillMaxWidth().heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            val sign = if (range.first < 0 && clamped > 0) "+" else ""
            Text(
                "$sign$clamped${unit?.let { " $it" }.orEmpty()}",
                style = SpicyType.Caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                modifier = Modifier.weight(1f),
            )
            if (clamped != default && enabled) {
                Text(
                    "Reset",
                    style = SpicyType.Caption.copy(fontWeight = FontWeight.Medium, color = SpicyColors.TextSecondary),
                    modifier = Modifier
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onValueChange(default)
                        }
                        .padding(vertical = SpicySpacing.S1, horizontal = SpicySpacing.S1),
                )
            }
        }
    }
}

/** A quiet outlined card: hairline ring, large radius. */
fun Modifier.outlinedCard(tinted: Boolean = false): Modifier = composed {
    val shape = RoundedCornerShape(SpicyRadii.Lg)
    clip(shape)
        .then(if (tinted) Modifier.background(SpicyColors.TintBg) else Modifier)
        .border(1.dp, SpicyColors.Hairline, shape)
}

/** A disabled row: label and control at 45%. */
const val DISABLED_ALPHA = 0.45f
