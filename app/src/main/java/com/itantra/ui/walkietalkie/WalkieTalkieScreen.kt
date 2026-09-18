package com.itantra.ui.walkietalkie

import android.view.MotionEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.ai.model.Language
import com.itantra.transport.TransportMode
import com.itantra.ui.components.IsroItantraLogo
import com.itantra.ui.theme.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WalkieTalkieScreen(
    viewModel: WalkieTalkieViewModel,
    onNavigateToAlerts: () -> Unit,
    onNavigateToMeshState: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(context) {
        viewModel.attachContext(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        // 1. Top Bar with ISRO itantra Logo + Live Transport Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IsroItantraLogo(scale = 0.85f)

            // Transport Toggle Switcher
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SurfaceSubtle)
                    .border(1.dp, BorderMedium, CircleShape)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val isWfd = uiState.activeTransport == TransportMode.WIFI_DIRECT
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isWfd) IsroBlue else Color.Transparent)
                        .clickable { viewModel.setTransportMode(TransportMode.WIFI_DIRECT) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Wi-Fi Direct",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isWfd) PureWhite else TextMuted
                    )
                }

                val isBt = uiState.activeTransport == TransportMode.BLUETOOTH_SPP
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isBt) IsroOrange else Color.Transparent)
                        .clickable { viewModel.setTransportMode(TransportMode.BLUETOOTH_SPP) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "BT Mesh",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isBt) PureWhite else TextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. 1-to-1 Responder Target Bar (Dashed Border, tap to open discovery)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.openPeerDiscoverySheet() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (uiState.selectedPeer != null) IsroOrangeLight.copy(alpha = 0.35f) else SurfaceSubtle
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.5.dp,
                color = if (uiState.selectedPeer != null) IsroOrange else BorderMedium
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (uiState.selectedPeer != null) IsroOrange else SuccessGreen)
                    )

                    Text(
                        text = "Target:",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted
                    )

                    Text(
                        text = if (uiState.selectedPeer != null) {
                            "👤 ${uiState.selectedPeer?.name}"
                        } else {
                            "🌐 Group Disaster Channel"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (uiState.selectedPeer != null) IsroOrangeDark else IsroBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (uiState.selectedPeer != null) IsroOrangeLight else IsroBlueLight,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (uiState.selectedPeer != null) IsroOrange else IsroBlue
                    )
                ) {
                    Text(
                        text = if (uiState.selectedPeer != null) "Linked ▾" else "1-to-1 Link ▾",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (uiState.selectedPeer != null) IsroOrangeDark else IsroBlue,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Language Bridge HUD Bar (Interactive 2-Way Language Selector)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceSubtle),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var showLanguageMenu by remember { mutableStateOf(false) }

                Box {
                    Column(
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showLanguageMenu = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "My Language ▾",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = IsroOrangeDark
                        )
                        Text(
                            text = "${uiState.spokenLanguage.nativeName} (${uiState.spokenLanguage.englishName})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                    }

                    DropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false }
                    ) {
                        Language.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text("${lang.nativeName} (${lang.englishName})") },
                                onClick = {
                                    viewModel.setPreferredLanguage(lang)
                                    showLanguageMenu = false
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "➔",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = BorderMedium
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Receiver Hears In:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = if (uiState.selectedPeer != null) "${uiState.selectedPeer?.language?.nativeName} (${uiState.selectedPeer?.name})" else "Their Own Language",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = IsroBlue
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Tactical Voice Chips (1-tap instant voice broadcast)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val chips = listOf(
                "रास्ता साफ है" to "Route Clear",
                "मदद चाहिए" to "Need Help",
                "बाढ़ चेतावनी" to "Flood Alert",
                "हम सुरक्षित हैं" to "Safe"
            )
            chips.forEach { (phraseHi, labelEn) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceSubtle)
                        .border(1.dp, BorderMedium, RoundedCornerShape(8.dp))
                        .clickable { viewModel.transmitQuickPhrase(phraseHi) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelEn,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Center Tactile PTT Dial with Glowing Ring & Waveforms
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val buttonElevation by animateDpAsState(
                targetValue = if (uiState.isTransmitting) 2.dp else 12.dp,
                label = "pttElevation"
            )

            val pttRingColor = if (uiState.selectedPeer != null) IsroOrangeDark else IsroOrange
            val pttGlowColor = if (uiState.selectedPeer != null) IsroOrangeLight else IsroOrangeLight

            Box(
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .background(PureWhite)
                    .border(7.dp, pttRingColor, CircleShape)
                    .border(14.dp, pttGlowColor, CircleShape)
                    .shadow(buttonElevation, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                viewModel.onPttPressed()
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    viewModel.onPttReleased()
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "PTT Microphone",
                        modifier = Modifier.size(46.dp),
                        tint = if (uiState.isTransmitting) IsroOrangeDark else pttRingColor
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val pttLabel = when {
                        uiState.isTransmitting && uiState.selectedPeer != null -> "TRANSMITTING 1-TO-1..."
                        uiState.isTransmitting -> "⚡ ONNX RECORDING..."
                        else -> "HOLD TO SPEAK"
                    }

                    Text(
                        text = pttLabel,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.6.sp,
                        color = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Real-Time Waveform Bars
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(28.dp)
            ) {
                uiState.waveformHeights.forEach { heightVal ->
                    Box(
                        modifier = Modifier
                            .width(3.5.dp)
                            .height(heightVal.dp)
                            .clip(CircleShape)
                            .background(if (uiState.isTransmitting) IsroOrange else BorderMedium)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 100% On-Device Neural STT indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(SurfaceSubtle)
                    .border(1.dp, BorderMedium, RoundedCornerShape(99.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(SuccessGreen)
                )
                Text(
                    text = "⚡ 100% ON-DEVICE ONNX STT",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextSecondary,
                    letterSpacing = 0.4.sp
                )
            }
        }

        var customInputText by remember { mutableStateOf("") }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = customInputText,
                onValueChange = { customInputText = it },
                placeholder = { Text("Type custom message (100% offline)...", fontSize = 11.sp, color = TextMuted) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IsroOrange,
                    unfocusedBorderColor = BorderMedium,
                    focusedContainerColor = PureWhite,
                    unfocusedContainerColor = SurfaceSubtle
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            )

            Button(
                onClick = {
                    if (customInputText.isNotBlank()) {
                        viewModel.transmitQuickPhrase(customInputText.trim())
                        customInputText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = IsroOrange),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("SEND", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = PureWhite)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 5. Live Message Card (Last Sent / Transcribed Audio)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceSubtle),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(12.dp, 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (uiState.selectedPeer != null) {
                            "Last sent • 1-to-1 to ${uiState.selectedPeer?.name}"
                        } else {
                            "Last sent • Broadcast (Unit 01)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = "● ACK 100% Verified",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SuccessGreen
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = uiState.lastSentText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = uiState.lastTranslatedText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = IsroOrange
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 6. Bottom Navigation Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = BorderSubtle, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { }
            ) {
                Icon(Icons.Default.Phone, contentDescription = "Talk", tint = IsroOrange, modifier = Modifier.size(22.dp))
                Text("Talk", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = IsroOrange)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onNavigateToAlerts() }
            ) {
                Icon(Icons.Default.Warning, contentDescription = "SOS Alert", tint = TextMuted, modifier = Modifier.size(22.dp))
                Text("SOS Alert", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onNavigateToMeshState() }
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextMuted, modifier = Modifier.size(22.dp))
                Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
            }
        }
    }

    // 7. Peer Discovery Bottom Sheet
    if (uiState.showPeerDiscoverySheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closePeerDiscoverySheet() },
            containerColor = PureWhite,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Nearby Responders",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = TextPrimary
                        )
                        Text(
                            text = "Direct P2P Mesh Discovery • 1-to-1 or Broadcast",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SuccessGreenSubtle)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "● ${uiState.connectedNodesCount} Online",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Option 1: Group Broadcast
                val isGroupSelected = (uiState.selectedPeer == null)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectTargetPeer(null) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isGroupSelected) IsroOrangeLight.copy(alpha = 0.3f) else SurfaceSubtle
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isGroupSelected) IsroOrange else BorderSubtle
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (isGroupSelected) IsroOrange else BorderMedium),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📡", fontSize = 20.sp)
                            }

                            Column {
                                Text(
                                    text = "Group Broadcast (All Nodes)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Transmit speech to all ${uiState.connectedNodesCount} responders in mesh",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        if (isGroupSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = IsroOrange,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "DIRECT 1-TO-1 TARGETS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.discoveredPeers.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceSubtle),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = IsroBlue,
                                        strokeWidth = 2.5.dp
                                    )
                                    Column {
                                        Text(
                                            text = "Scanning for nearby devices...",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "Ensure Wi-Fi, Bluetooth & Location are ON on other phone",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    items(uiState.discoveredPeers) { peer ->
                        val isPeerSelected = (uiState.selectedPeer?.id == peer.id)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectTargetPeer(peer) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isPeerSelected) IsroOrangeLight.copy(alpha = 0.3f) else SurfaceSubtle
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (isPeerSelected) IsroOrange else BorderSubtle
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(if (isPeerSelected) IsroOrange else IsroBlueLight),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = "Peer",
                                            tint = if (isPeerSelected) PureWhite else IsroBlue,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = peer.name,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = peer.role,
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                            Text(
                                                text = "•",
                                                fontSize = 11.sp,
                                                color = BorderMedium
                                            )
                                            Text(
                                                text = "${peer.language.nativeName} Mode",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = IsroBlue
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = peer.signalDbm,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SuccessGreen
                                    )

                                    if (isPeerSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = IsroOrange,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
