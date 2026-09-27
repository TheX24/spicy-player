package com.tx24.spicyplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tx24.spicyplayer.ui.theme.SpicyColors
import com.tx24.spicyplayer.ui.theme.SpicyMotion
import com.tx24.spicyplayer.ui.theme.SpicyRadii
import com.tx24.spicyplayer.ui.theme.SpicySpacing
import com.tx24.spicyplayer.ui.theme.SpicyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * A short note at the top of the screen on what an action did, one at a time: it slides and
 * fades in, holds [TOAST_MS], and goes. A new message replaces the one showing.
 */
@Composable
fun SpicyToastHost(messages: Flow<String>, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf<String?>(null) }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(messages) {
        messages.collectLatest { message ->
            if (text != null) shown.animateTo(0f, tween(120))
            text = message
            shown.animateTo(1f, tween(SpicyMotion.MODAL_MS, easing = SpicyMotion.Modal))
            delay(TOAST_MS)
            shown.animateTo(0f, tween(SpicyMotion.MODAL_MS, easing = SpicyMotion.Standard))
            text = null
        }
    }
    val message = text ?: return
    val shape = RoundedCornerShape(SpicyRadii.Md)
    Box(modifier.fillMaxWidth().padding(horizontal = SpicySpacing.S4), contentAlignment = Alignment.TopCenter) {
        Text(
            message,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .graphicsLayer {
                    alpha = shown.value
                    translationY = (1f - shown.value) * -12.dp.toPx()
                }
                .clip(shape)
                .background(SpicyColors.BgElevated)
                .border(1.dp, SpicyColors.HairlineStrong, shape)
                .padding(horizontal = SpicySpacing.S4, vertical = SpicySpacing.S3),
            style = SpicyType.Caption.copy(color = SpicyColors.TextPrimary, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
        )
    }
}

private const val TOAST_MS = 3_000L
