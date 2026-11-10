@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.kartikkamboj.arena.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kartikkamboj.arena.data.model.AccountStatus
import com.kartikkamboj.arena.data.model.AppError
import com.kartikkamboj.arena.ui.theme.ArenaPalette
import com.kartikkamboj.arena.ui.theme.LocalDarkTheme

/** Card with a soft gradient, hairline border and no elevation: depth comes from the surface tint. */
@Composable
fun ArenaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: BorderStroke? = null,
    contentPadding: androidx.compose.ui.unit.Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val effectiveBorder = border ?: BorderStroke(
        1.dp,
        if (LocalDarkTheme.current) ArenaPalette.Outline else ArenaPalette.LightOutline,
    )
    Column(
        modifier = modifier
            .clip(shape)
            .background(ArenaPalette.cardGradient)
            .border(effectiveBorder, shape)
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(onClick = onClick)
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * The screen's ground: a flat obsidian field with one hairline under the header line.
 *
 * There used to be a blurred colour blob here. It cost a full-screen render pass on every frame the
 * header was on screen, which on a 2 GB device is memory and GPU time taken away from the page the
 * user is reading — and a gradient wash is also the single most generic thing a dark AI UI can do.
 * Depth in this app comes from surface steps and 1 dp hairlines, which cost nothing to draw.
 */
@Composable
fun ArenaBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (LocalDarkTheme.current) ArenaPalette.Charcoal else ArenaPalette.LightBackground,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 78.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    if (LocalDarkTheme.current) ArenaPalette.OutlineSoft else ArenaPalette.LightOutline,
                ),
        )
    }
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

/** Colour-coded status, because the five-slot screen is only useful if state is glanceable. */
@Composable
fun StatusPill(
    status: AccountStatus,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
) {
    val (label, tint) = statusLabelAndColor(status)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = if (LocalDarkTheme.current) 0.16f else 0.12f))
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.4f)), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun statusLabelAndColor(status: AccountStatus): Pair<String, Color> {
    val dark = LocalDarkTheme.current
    return when (status) {
        AccountStatus.NOT_CONNECTED -> "Not connected" to if (dark) ArenaPalette.TextMuted else ArenaPalette.LightTextMuted
        AccountStatus.CONNECTING -> "Connecting" to ArenaPalette.Amber
        AccountStatus.CONNECTED -> "Connected" to ArenaPalette.Mint
        AccountStatus.LOGIN_REQUIRED -> "Login required" to ArenaPalette.Amber
        AccountStatus.SESSION_EXPIRED -> "Session expired" to ArenaPalette.Rose
        AccountStatus.RATE_LIMITED -> "Rate limited" to ArenaPalette.Amber
        AccountStatus.CONNECTION_ERROR -> "Connection error" to ArenaPalette.Rose
    }
}

@Composable
fun InfoChip(
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val container = when {
        selected && dark() -> ArenaPalette.Emerald.copy(alpha = 0.16f)
        selected -> ArenaPalette.Emerald.copy(alpha = 0.11f)
        dark() -> ArenaPalette.SurfaceVariant
        else -> ArenaPalette.LightSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(container)
            .border(
                BorderStroke(1.dp, if (selected) ArenaPalette.Emerald.copy(alpha = 0.5f) else Color.Transparent),
                shape,
            )
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun dark() = LocalDarkTheme.current

@Composable
fun ErrorCard(
    error: AppError,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
    detail: String? = null,
) {
    ArenaCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, ArenaPalette.Rose.copy(alpha = 0.45f)),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = ArenaPalette.Rose,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = error.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (detail != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Detail · $detail",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
        if (onAction != null) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton(text = error.actionLabel, onClick = onAction, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun NoticeBanner(
    text: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val tint = when (tone) {
        NoticeTone.INFO -> ArenaPalette.Amber
        NoticeTone.SUCCESS -> ArenaPalette.Mint
        NoticeTone.WARN -> ArenaPalette.Amber
        NoticeTone.ERROR -> ArenaPalette.Rose
    }
    val icon = when (tone) {
        NoticeTone.ERROR, NoticeTone.WARN -> Icons.Filled.Warning
        NoticeTone.SUCCESS -> Icons.Filled.Check
        NoticeTone.INFO -> Icons.Filled.Info
    }
    val visibleScale = animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 180),
        label = "notice",
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .scale(visibleScale.value),
        shape = RoundedCornerShape(18.dp),
        color = tint.copy(alpha = if (LocalDarkTheme.current) 0.12f else 0.10f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f, fill = true),
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Dismiss",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

enum class NoticeTone { INFO, SUCCESS, WARN, ERROR }

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                if (enabled) ArenaPalette.Emerald else Color(0xFF23282A)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = if (enabled) ArenaPalette.OnAccent else ArenaPalette.TextFaint,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) ArenaPalette.OnAccent else ArenaPalette.TextFaint,
        )
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        destructive -> ArenaPalette.Rose
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.35f)), RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(7.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (LocalDarkTheme.current) ArenaPalette.SurfaceHigh else ArenaPalette.LightSurfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = ArenaPalette.Emerald, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(text = actionLabel, onClick = onAction, leadingIcon = Icons.Filled.Add)
        }
    }
}

@Composable
fun MetricCell(label: String, value: String, modifier: Modifier = Modifier, tint: Color? = null) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (LocalDarkTheme.current) ArenaPalette.SurfaceVariant else ArenaPalette.LightSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = tint ?: MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
        )
    }
}
