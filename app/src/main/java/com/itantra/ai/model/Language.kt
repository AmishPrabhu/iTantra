package com.itantra.ai.model

/**
 * 10 Indian Languages + English covered by iTantra.
 * Uses 1-byte ID (0x00 to 0x0A) for binary packet framing.
 */
enum class Language(
    val id: Byte,
    val isoCode: String,
    val nativeName: String,
    val englishName: String
) {
    ENGLISH(0x00, "en", "English", "English"),
    HINDI(0x01, "hi", "हिन्दी", "Hindi"),
    TAMIL(0x02, "ta", "தமிழ்", "Tamil"),
    TELUGU(0x03, "te", "తెలుగు", "Telugu"),
    MARATHI(0x04, "mr", "मराठी", "Marathi"),
    BENGALI(0x05, "bn", "বাংলা", "Bengali"),
    KANNADA(0x06, "kn", "ಕನ್ನಡ", "Kannada"),
    MALAYALAM(0x07, "ml", "മലയാളം", "Malayalam"),
    GUJARATI(0x08, "gu", "ગુજરાતી", "Gujarati"),
    ODIA(0x09, "or", "ଓଡ଼ିଆ", "Odia"),
    PUNJABI(0x0A, "pa", "ਪੰਜਾਬੀ", "Punjabi");

    companion object {
        fun fromId(id: Byte): Language = entries.firstOrNull { it.id == id } ?: ENGLISH
        fun fromIso(iso: String): Language = entries.firstOrNull { it.isoCode.equals(iso, ignoreCase = true) } ?: ENGLISH
    }
}
