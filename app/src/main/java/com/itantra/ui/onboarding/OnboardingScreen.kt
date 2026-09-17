package com.itantra.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.ai.model.Language
import com.itantra.transport.TransportMode
import com.itantra.ui.components.IsroItantraLogo
import com.itantra.ui.theme.*

@Composable
fun OnboardingScreen(
    onContinueToWalkie: (Language, TransportMode) -> Unit
) {
    var selectedLanguage by remember { mutableStateOf(Language.HINDI) }
    var selectedTransport by remember { mutableStateOf(TransportMode.WIFI_DIRECT) }

    val allLanguages = remember { Language.entries }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Official ISRO itantra Branding
        IsroItantraLogo(scale = 1.05f)

        Text(
            text = "Offline Voice Mesh for Disaster Rescue",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
        )

        // 1. Mesh Transport Protocol Selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceSubtle),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MESH TRANSPORT PROTOCOL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextSecondary
                    )
                    Text(
                        text = "P2P DIRECT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = IsroBlue
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Wi-Fi Direct Option
                    val isWfd = selectedTransport == TransportMode.WIFI_DIRECT
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isWfd) IsroBlueLight else PureWhite)
                            .border(
                                width = 1.5.dp,
                                color = if (isWfd) IsroBlue else BorderMedium,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedTransport = TransportMode.WIFI_DIRECT }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Wi-Fi Direct",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isWfd) IsroBlue else TextPrimary
                            )
                            Text(
                                text = "High Range (~150m)",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    // Bluetooth SPP Option
                    val isBt = selectedTransport == TransportMode.BLUETOOTH_SPP
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isBt) IsroOrangeLight else PureWhite)
                            .border(
                                width = 1.5.dp,
                                color = if (isBt) IsroOrange else BorderMedium,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedTransport = TransportMode.BLUETOOTH_SPP }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Bluetooth SPP",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isBt) IsroOrange else TextPrimary
                            )
                            Text(
                                text = "Low Power Mesh",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 2. Language Selection Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Select Preferred Language",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "10 Indian + Eng",
                style = MaterialTheme.typography.labelSmall,
                color = IsroOrange,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Language Selection Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(allLanguages) { lang ->
                val isSelected = (lang == selectedLanguage)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(if (isSelected) IsroOrange else PureWhite)
                        .border(
                            width = 1.5.dp,
                            color = if (isSelected) IsroOrange else BorderMedium,
                            shape = CircleShape
                        )
                        .clickable { selectedLanguage = lang }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = lang.nativeName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) PureWhite else TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Continue Action Button
        Button(
            onClick = { onContinueToWalkie(selectedLanguage, selectedTransport) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IsroOrange),
            shape = CircleShape,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Text(
                text = "Enter Walkie-Talkie ➔",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = PureWhite
            )
        }
    }
}
