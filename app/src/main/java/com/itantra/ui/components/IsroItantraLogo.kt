package com.itantra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.ui.theme.IsroBlue
import com.itantra.ui.theme.IsroOrange

@Composable
fun IsroItantraLogo(
    modifier: Modifier = Modifier,
    scale: Float = 1.0f
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Star Chevron & Solar Wings Canvas
        Canvas(
            modifier = Modifier
                .width((36 * scale).dp)
                .height((50 * scale).dp)
        ) {
            val w = size.width
            val h = size.height

            // Orange Rocket Chevron
            val rocketPath = Path().apply {
                moveTo(w * 0.5f, h * 0.05f)
                lineTo(w * 0.9f, h * 0.95f)
                lineTo(w * 0.5f, h * 0.75f)
                lineTo(w * 0.1f, h * 0.95f)
                close()
            }
            drawPath(rocketPath, color = IsroOrange)

            // Blue Solar Cross-Wing
            drawLine(
                color = IsroBlue,
                start = Offset(w * 0.05f, h * 0.40f),
                end = Offset(w * 0.95f, h * 0.55f),
                strokeWidth = 4f * scale
            )
        }

        Spacer(modifier = Modifier.width((4 * scale).dp))

        // English "itantra" with top horizontal bar
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.width((68 * scale).dp).height((2.5 * scale).dp)) {
                drawLine(
                    color = IsroBlue,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = size.height
                )
            }
            Text(
                text = "itantra",
                fontSize = (22 * scale).sp,
                fontWeight = FontWeight.ExtraBold,
                color = IsroBlue,
                letterSpacing = (-0.5).sp
            )
        }
    }
}
