package com.itantra.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.ui.theme.*

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.platform.LocalContext

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.itantra.ITantraApp
import com.itantra.ai.model.Language

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.itantra.R

enum class DisasterType(
    @DrawableRes val drawableRes: Int,
    val iconTint: Color,
    val iconBg: Color,
    val englishTitle: String
) {
    FLOOD(R.drawable.ic_disaster_flood, Color(0xFF0284C7), Color(0xFFE0F2FE), "Flood"),
    MEDICAL(R.drawable.ic_disaster_medical, Color(0xFFDC2626), Color(0xFFFEE2E2), "Medical"),
    FIRE(R.drawable.ic_disaster_fire, Color(0xFFEA580C), Color(0xFFFFEDD5), "Fire"),
    EARTHQUAKE(R.drawable.ic_disaster_earthquake, Color(0xFFD97706), Color(0xFFFEF3C7), "Earthquake"),
    EVACUATE(R.drawable.ic_disaster_evacuate, Color(0xFF9333EA), Color(0xFFF3E8FF), "Evacuate"),
    HELP(R.drawable.ic_disaster_help, Color(0xFF2563EB), Color(0xFFDBEAFE), "Help");

    fun getTitleIn(lang: Language): String {
        return when (this) {
            FLOOD -> when (lang) {
                Language.HINDI -> "बाढ़"
                Language.MARATHI -> "पूर"
                Language.TAMIL -> "வெள்ளம்"
                Language.TELUGU -> "వరద"
                Language.BENGALI -> "বন্যা"
                Language.KANNADA -> "ಪ್ರವಾಹ"
                Language.MALAYALAM -> "വെള്ളപ്പೊക്കം"
                Language.GUJARATI -> "પૂર"
                Language.ODIA -> "ବନ୍ୟା"
                Language.PUNJABI -> "ਹੜ੍ਹ"
                Language.ENGLISH -> "Flood"
            }
            MEDICAL -> when (lang) {
                Language.HINDI -> "चिकित्सा"
                Language.MARATHI -> "वैद्यकीय"
                Language.TAMIL -> "மருத்துவம்"
                Language.TELUGU -> "వైద్యం"
                Language.BENGALI -> "চিকিৎসা"
                Language.KANNADA -> "ವೈದ್ಯಕೀಯ"
                Language.MALAYALAM -> "ചികിത്സ"
                Language.GUJARATI -> "તબીબી"
                Language.ODIA -> "ଡାକ୍ତରୀ"
                Language.PUNJABI -> "ਡਾਕਟਰੀ"
                Language.ENGLISH -> "Medical"
            }
            FIRE -> when (lang) {
                Language.HINDI -> "आग"
                Language.MARATHI -> "आग"
                Language.TAMIL -> "தீ"
                Language.TELUGU -> "అగ్ని"
                Language.BENGALI -> "আগুন"
                Language.KANNADA -> "ಬೆಂಕಿ"
                Language.MALAYALAM -> "തീപിടുത്തം"
                Language.GUJARATI -> "આગ"
                Language.ODIA -> "ନିଆଁ"
                Language.PUNJABI -> "ਅੱਗ"
                Language.ENGLISH -> "Fire"
            }
            EARTHQUAKE -> when (lang) {
                Language.HINDI -> "भूकंप"
                Language.MARATHI -> "भूकंप"
                Language.TAMIL -> "நிலநடுக்கம்"
                Language.TELUGU -> "భూకంపం"
                Language.BENGALI -> "ভূমিকম্প"
                Language.KANNADA -> "ಭೂಕಂಪ"
                Language.MALAYALAM -> "ഭൂകമ്പം"
                Language.GUJARATI -> "ભૂકંપ"
                Language.ODIA -> "ଭୂମିକମ୍ପ"
                Language.PUNJABI -> "ਭੂਚਾਲ"
                Language.ENGLISH -> "Earthquake"
            }
            EVACUATE -> when (lang) {
                Language.HINDI -> "खाली"
                Language.MARATHI -> "रिकामे करा"
                Language.TAMIL -> "வெளியேறு"
                Language.TELUGU -> "ఖాళీ"
                Language.BENGALI -> "খালি করুন"
                Language.KANNADA -> "ಖಾಲಿ ಮಾಡಿ"
                Language.MALAYALAM -> "ഒഴിപ്പിക്കുക"
                Language.GUJARATI -> "ખાલી કરો"
                Language.ODIA -> "ଖାଲି କରନ୍ତୁ"
                Language.PUNJABI -> "ਖਾਲੀ ਕਰੋ"
                Language.ENGLISH -> "Evacuate"
            }
            HELP -> when (lang) {
                Language.HINDI -> "मदद"
                Language.MARATHI -> "मदत"
                Language.TAMIL -> "உதவி"
                Language.TELUGU -> "సహాయం"
                Language.BENGALI -> "সাহায্য"
                Language.KANNADA -> "ಸಹಾಯ"
                Language.MALAYALAM -> "സഹായം"
                Language.GUJARATI -> "મદદ"
                Language.ODIA -> "ସାହାଯ୍ୟ"
                Language.PUNJABI -> "ਮਦਦ"
                Language.ENGLISH -> "Help"
            }
        }
    }
}

@Composable
fun EmergencyAlertScreen(
    onSendAlert: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val userLang by ITantraApp.instance.meshCoordinator.preferredLanguage.collectAsState(initial = Language.HINDI)

    fun triggerEmergencyAudioAndSend(alertText: String) {
        try {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)
        } catch (ignored: Exception) {}

        onSendAlert(alertText)
    }

    val alertsList = remember { DisasterType.entries }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        // Top Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.clickable { onBack() }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Alert Mode",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = EmergencyRed
                )
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(EmergencyRedSubtle)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "3x Urgent .wav",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = EmergencyRed
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 6-Tile Tactical Grid
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (i in alertsList.indices step 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val alert1 = alertsList[i]
                    val title1 = alert1.getTitleIn(userLang)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(PureWhite)
                            .border(2.dp, BorderMedium, RoundedCornerShape(18.dp))
                            .clickable { triggerEmergencyAudioAndSend("$title1 (${alert1.englishTitle})") }
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(alert1.iconBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = alert1.drawableRes),
                                    contentDescription = alert1.englishTitle,
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = title1,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = TextPrimary
                            )
                            Text(
                                text = alert1.englishTitle.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = TextMuted
                            )
                        }
                    }

                    if (i + 1 < alertsList.size) {
                        val alert2 = alertsList[i + 1]
                        val title2 = alert2.getTitleIn(userLang)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(PureWhite)
                                .border(2.dp, BorderMedium, RoundedCornerShape(20.dp))
                                .clickable { triggerEmergencyAudioAndSend("$title2 (${alert2.englishTitle})") }
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(alert2.iconBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = alert2.drawableRes),
                                        contentDescription = alert2.englishTitle,
                                        modifier = Modifier.size(56.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = title2,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TextPrimary
                                )
                                Text(
                                    text = alert2.englishTitle.uppercase(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Speak Custom Alert Button
        OutlinedButton(
            onClick = { triggerEmergencyAudioAndSend("Emergency Custom SOS Alert") },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(2.dp, EmergencyRed),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = EmergencyRed)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Custom Mic", modifier = Modifier.size(20.dp))
                Text(
                    text = "Speak Custom Alert",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
