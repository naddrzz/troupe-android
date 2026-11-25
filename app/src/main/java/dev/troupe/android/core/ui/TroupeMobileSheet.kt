package dev.troupe.android.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.troupe.android.core.theme.LocalTroupePalette

/** iOS / Vaul sheet curve matching `src/renderer/components/ui/mobile-sheet.tsx`. */
private val SheetSlideEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/** Navigation stack inside a [TroupeMobileSheet], keeping root sheet height stable across pushes. */
@Stable
class SheetStackState<T : Any> {
    private val stack = mutableStateListOf<T>()
    val top: T? get() = stack.lastOrNull()
    val depth: Int get() = stack.size
    val canPop: Boolean get() = stack.isNotEmpty()

    fun push(route: T) {
        stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset() {
        stack.clear()
    }
}

@Composable
fun <T : Any> rememberSheetStack(): SheetStackState<T> = remember { SheetStackState() }

/**
 * Troupe V2 mobile bottom sheet matching `MobileSheetContent` (`components/ui/mobile-sheet.tsx`):
 * - Grab handle at top (no `✕` button on root sheets).
 * - Centered title (`15sp` Medium); optional `←` Back on pushed sub-sheets.
 * - `popover` surface, `16.dp` top radius, `overlay` scrim at 80%, safe-area bottom padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroupeMobileSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    canPop: Boolean = false,
    onPop: (() -> Unit)? = null,
    skipPartiallyExpanded: Boolean = true,
    scrollable: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalTroupePalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.popover,
        contentColor = palette.foreground,
        scrimColor = palette.overlay.copy(alpha = 0.72f),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(palette.muted.copy(alpha = 0.45f)),
                )
            }
        },
        modifier = modifier,
    ) {
        BackHandler(enabled = canPop && onPop != null) {
            onPop?.invoke()
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // Header row: back button (sub-sheets only), centered title, balanced right spacer.
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canPop && onPop != null) {
                    TroupeIconButton(onClick = onPop) {
                        Icon(TroupeIcons.ArrowLeft, contentDescription = "Back", modifier = Modifier.size(20.dp))
                    }
                } else {
                    Spacer(Modifier.size(44.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    FadeEndText(
                        text = title,
                        color = palette.foreground,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (!subtitle.isNullOrBlank()) {
                        FadeEndText(
                            text = subtitle,
                            color = palette.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.size(44.dp))
            }

            val bodyMod = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp)
            Column(modifier = bodyMod, verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

            if (footer != null) {
                HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                Box(Modifier.fillMaxWidth().background(palette.popover).padding(8.dp)) {
                    footer()
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** Horizontal iOS-style slide transition for pushed sub-sheets inside [TroupeMobileSheet]. */
@Composable
fun <T> SheetStackContent(
    targetState: T,
    depthOf: (T) -> Int,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            val pushing = depthOf(targetState) >= depthOf(initialState)
            val spec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 320, easing = SheetSlideEasing)
            val fadeSpec = tween<Float>(durationMillis = 150)
            if (pushing) {
                (slideInHorizontally(spec) { it } + fadeIn(fadeSpec)) togetherWith
                    (slideOutHorizontally(spec) { -it / 3 } + fadeOut(fadeSpec))
            } else {
                (slideInHorizontally(spec) { -it / 3 } + fadeIn(fadeSpec)) togetherWith
                    (slideOutHorizontally(spec) { it } + fadeOut(fadeSpec))
            }
        },
        label = "sheet-stack-content",
    ) { state ->
        content(state)
    }
}

/** Convenience overload of [SheetStackContent] for a nullable root (`null` = depth 0, non-null = depth 1). */
@Composable
fun <T : Any> SheetStackContent(
    current: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T?) -> Unit,
) {
    Box(modifier = modifier) {
        SheetStackContent(
            targetState = current,
            depthOf = { if (it == null) 0 else 1 },
            content = content,
        )
    }
}

/**
 * Grouped card inside a mobile sheet (`muted` fill, `0.5.dp` `popoverEdge`, `12.dp` radius),
 * matching `SheetGroup` in `mobile-sheet.tsx`.
 */
@Composable
fun SheetGroup(
    modifier: Modifier = Modifier,
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalTroupePalette.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = palette.muted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.mutedSurface)
                .border(0.5.dp, palette.popoverEdge, RoundedCornerShape(12.dp)),
            content = content,
        )
    }
}

/** Hairline divider inset to the text column inside a [SheetGroup]. */
@Composable
fun SheetRowDivider(hasIcon: Boolean = false) {
    val palette = LocalTroupePalette.current
    HorizontalDivider(
        thickness = 0.5.dp,
        color = palette.popoverEdge,
        modifier = Modifier.padding(start = if (hasIcon) 44.dp else 14.dp),
    )
}

/**
 * Row inside a [SheetGroup]: 48dp (1 line) or 60dp (2 lines), `10%` picked fill (no tick on single pick),
 * `FadeEndText` for long titles/subtitles.
 */
@Composable
fun SheetRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    selected: Boolean = false,
    picked: Boolean = selected,
    destructive: Boolean = false,
    enabled: Boolean = true,
    showChevron: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = icon,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalTroupePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isPicked = selected || picked
    val leadingSlot = leading ?: icon
    val bg = when {
        isPicked -> palette.pickedFill
        pressed && enabled -> if (destructive) palette.error.copy(alpha = 0.10f) else palette.popoverHover
        else -> Color.Transparent
    }
    val titleColor = when {
        !enabled -> palette.disabledForeground
        destructive -> palette.error
        else -> palette.foreground
    }
    val minH = if (subtitle != null) 60.dp else 48.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minH)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingSlot != null) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { leadingSlot() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FadeEndText(text = title, color = titleColor, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                FadeEndText(text = subtitle, color = palette.muted, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                color = palette.muted,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        if (trailing != null) {
            trailing()
        } else if (showChevron) {
            Icon(
                TroupeIcons.ChevronRight,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 44dp, 16sp search field used inside mobile sheets & the drawer (`mobile-sheet.tsx`). */
@Composable
fun SheetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(999.dp),
) {
    val palette = LocalTroupePalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(palette.secondarySurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            TroupeIcons.Search,
            contentDescription = null,
            tint = palette.muted,
            modifier = Modifier.size(16.dp),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = palette.muted,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.foreground),
                cursorBrush = SolidColor(palette.brandText),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                TroupeIcons.Close,
                contentDescription = "Clear",
                tint = palette.muted,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = { onValueChange("") }),
            )
        }
    }
}
