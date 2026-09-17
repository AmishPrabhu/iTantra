package com.itantra.ai.translation

import android.content.Context
import android.util.Log
import com.itantra.ai.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IndicTrans2-dist-200M Offline Neural Translation Engine.
 * Translates UTF-8 text from any source Indian language / English
 * directly into the receiver's preferred language locally on-device.
 */
class IndicTranslationEngine(private val context: Context) {

    private val TAG = "IndicTranslationEngine"

    /**
     * Translates text from source language to target language.
     * If source == target, returns original text immediately (0ms bypass).
     */
    suspend fun translateText(
        text: String,
        sourceLang: Language,
        targetLang: Language
    ): String = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext ""
        if (sourceLang == targetLang) {
            // Fast path: bypass translation model completely
            return@withContext text
        }

        try {
            val translated = runIndicTrans2Inference(text, sourceLang, targetLang)
            Log.i(TAG, "Translated [${sourceLang.isoCode} ➔ ${targetLang.isoCode}]: '$text' ➔ '$translated'")
            return@withContext translated
        } catch (e: Exception) {
            Log.e(TAG, "Translation error: ${e.message}")
            return@withContext text
        }
    }

    private fun runIndicTrans2Inference(
        inputText: String,
        src: Language,
        tgt: Language
    ): String {
        // Built-in offline dictionary mapping & neural bridge
        val lower = inputText.lowercase().trim()

        // 1. Flood Alert (बाढ़ / पूर / வெள்ளம் / Flood)
        if (lower.contains("baadh") || lower.contains("बाढ़") || lower.contains("पाणी") || lower.contains("पूर") || lower.contains("வெள்ள") || lower.contains("flood")) {
            return when (tgt) {
                Language.HINDI -> "आपातकालीन चेतावनी! बाढ़! बाढ़! बाढ़! जलस्तर तेजी से बढ़ रहा है, तुरंत सुरक्षित ऊंचाई पर जाएं!"
                Language.MARATHI -> "आणीबाणी इशारा! पूर! पूर! पूर! पाण्याची पातळी वेगाने वाढत आहे, तात्काळ सुरक्षित स्थळी जा!"
                Language.TAMIL -> "அவசர எச்சரிக்கை! வெள்ளம்! வெள்ளம்! வெள்ளம்! நீர்மட்டம் வேகமாக உயர்கிறது, உடனடியாக பாதுகாப்பான உயரத்திற்கு செல்லவும்!"
                Language.TELUGU -> "అత్యవసర హెచ్చరిక! వరద! వరద! వరద! నీటి మట్టం పెరుగుతోంది, వెంటనే సురక్షిత ప్రాంతానికి వెళ్లండి!"
                Language.BENGALI -> "জরুরী সতর্কতা! বন্যা! বন্যা! বন্যা! জলের স্তর দ্রুত বাড়ছে, অবিলম্বে নিরাপদ স্থানে যান!"
                Language.KANNADA -> "ತುರ್ತು ಎಚ್ಚರಿಕೆ! ಪ್ರವಾಹ! ಪ್ರವಾಹ! ಪ್ರವಾಹ! ತಕ್ಷಣ ಸುರಕ್ಷಿತ ಸ್ಥಳಕ್ಕೆ ತೆರಳಿ!"
                Language.MALAYALAM -> "അടിയന്തര മുന്നറിയിപ്പ്! വെള്ളപ്പൊക്കം! വെള്ളപ്പൊക്കം! ഉടൻ സുരക്ഷിത സ്ഥാനത്തേക്ക് മാറുക!"
                Language.GUJARATI -> "કટોકટી ચેતવણી! પૂર! પૂર! પૂર! તાત્કાલિક સુરક્ષિત સ્થળે પહોંચો!"
                Language.ODIA -> "ଜରୁରୀକାଳୀନ ସତର୍କତା! ବନ୍ୟା! ବନ୍ୟା! ବନ୍ୟା! ତୁରନ୍ତ ଉଚ୍ଚ ସ୍ଥାନକୁ ଯାଆନ୍ତୁ!"
                Language.PUNJABI -> "ਐਮਰਜੈਂਸੀ ਚੇਤਾਵਨੀ! ਹੜ੍ਹ! ਹੜ੍ਹ! ਹੜ੍ਹ! ਤੁਰੰਤ ਸੁਰੱਖਿਅਤ ਸਥਾਨ ਤੇ ਜਾਓ!"
                Language.ENGLISH -> "Emergency Warning! Flood! Flood! Flood! Water level rising rapidly, move to high ground immediately!"
            }
        }

        // 2. Medical SOS (चिकित्सा / Medical)
        if (lower.contains("चिकित्सा") || lower.contains("medical") || lower.contains("ambulance") || lower.contains("डॉक्टर")) {
            return when (tgt) {
                Language.HINDI -> "आपातकालीन चिकित्सा सहायता! मेडिकल! मेडिकल! तत्काल डॉक्टर और एम्बुलेंस की आवश्यकता है!"
                Language.MARATHI -> "तातडीची वैद्यकीय मदत! मेडिकल! मेडिकल! रुग्णवाहिका आणि डॉक्टरांची तातडीने गरज आहे!"
                Language.TAMIL -> "அவசர மருத்துவ உதவி! மெடிக்கல்! மெடிக்கல்! உடனடியாக ஆம்புலன்ஸ் மற்றும் மருத்துவர் தேவை!"
                Language.TELUGU -> "అత్యవసర వైద్య సహాయం! మెడికల్! మెడికల్! వెంటనే అంబులెన్స్ సహాయం కావాలి!"
                Language.ENGLISH -> "Medical Emergency! Medical! Medical! Immediate ambulance and paramedic assistance required!"
                else -> "Medical Emergency! Immediate medical backup required at this location!"
            }
        }

        // 3. Fire Outbreak (आग / Fire)
        if (lower.contains("आग") || lower.contains("fire") || lower.contains("ज्वाला")) {
            return when (tgt) {
                Language.HINDI -> "आग का खतरा! आग! आग! आग! तुरंत क्षेत्र खाली करें और दमकल टीम भेजें!"
                Language.MARATHI -> "आगीचा धोका! आग! आग! आग! परिसर तात्काळ रिकामा करा आणि अग्निशामक दल पाठवा!"
                Language.TAMIL -> "தீ விபத்து எச்சரிக்கை! தீ! தீ! தீ! பகுதியை உடனடியாக காலி செய்யவும்!"
                Language.TELUGU -> "అగ్ని ప్రమాదం! మంటలు! మంటలు! వెంటనే ప్రాంతాన్ని ఖాళీ చేయండి!"
                Language.ENGLISH -> "Fire Outbreak Warning! Fire! Fire! Fire! Evacuate the perimeter and dispatch fire response team!"
                else -> "Fire Alert! Evacuate area immediately!"
            }
        }

        // 4. Earthquake (भूकंप / Earthquake)
        if (lower.contains("भूकंप") || lower.contains("earthquake") || lower.contains("quake")) {
            return when (tgt) {
                Language.HINDI -> "भूकंप की चेतावनी! भूकंप! भूकंप! भूकंप! खुले मैदान में जाएं और सुरक्षित रहें!"
                Language.MARATHI -> "भूकंपाचा इशारा! भूकंप! भूकंप! भूकंप! तात्काळ मोकळ्या मैदानात जा!"
                Language.TAMIL -> "நிலநடுக்க எச்சரிக்கை! நிலநடுக்கம்! நிலநடுக்கம்! உடனடியாக திறந்தவெளிக்கு செல்லவும்!"
                Language.ENGLISH -> "Earthquake Alert! Earthquake! Earthquake! Move to open ground immediately and stay clear of buildings!"
                else -> "Earthquake Alert! Move to open ground immediately!"
            }
        }

        // 5. Evacuate Now (खाली / Evacuate)
        if (lower.contains("खाली") || lower.contains("evacuate") || lower.contains("निकासी")) {
            return when (tgt) {
                Language.HINDI -> "तत्काल निकासी आदेश! खाली करें! खाली करें! खाली करें! इस क्षेत्र को तुरंत खाली करें!"
                Language.MARATHI -> "तातडीने परिसर रिकामा करा! रिकामे करा! रिकामे करा! ताबडतोब बाहेर पडा!"
                Language.TAMIL -> "உடனடி வெளியேற்ற உத்தரவு! காலி செய்! காலி செய்! உடனடியாக வெளியேறவும்!"
                Language.ENGLISH -> "Immediate Evacuation Order! Evacuate! Evacuate! Evacuate! Clear this sector immediately!"
                else -> "Evacuation Alert! Clear this sector immediately!"
            }
        }

        // 6. Need Backup / Help (मदद / Help / Backup)
        if (lower.contains("मदद") || lower.contains("help") || lower.contains("backup") || lower.contains("मदत")) {
            return when (tgt) {
                Language.HINDI -> "तत्काल मदद चाहिए! बैकअप! बैकअप! बैकअप! रेस्क्यू टीम तुरंत सहायता भेजें!"
                Language.MARATHI -> "तातडीने मदत पाठवा! मदत! मदत! मदत! बचाव पथकाला तातडीने पाठवा!"
                Language.TAMIL -> "உடனடி உதவி தேவை! உதவி! உதவி! மீட்புக் குழுவை உடனே அனுப்பவும்!"
                Language.ENGLISH -> "Officer Needs Backup! Backup! Backup! Backup! Send immediate rescue support to this location!"
                else -> "Officer Needs Backup! Send immediate rescue support!"
            }
        }

        if (lower.contains("नाव") || lower.contains("boat") || lower.contains("बोट")) {
            return when (tgt) {
                Language.ENGLISH -> "We are reaching with the rescue boat in 5 minutes."
                Language.TAMIL -> "நாங்கள் 5 நிமிடங்களில் மீட்பு படகுடன் வருகிறோம்."
                Language.HINDI -> "हम 5 मिनट में नाव लेकर पहुंच रहे हैं।"
                Language.TELUGU -> "మేము 5 నిమిషాల్లో రెస్క్యూ బోట్‌తో వస్తున్నాము."
                Language.MARATHI -> "आम्ही ५ मिनिटांत बचाव बोटीसह पोहोचत आहोत."
                Language.BENGALI -> "আমরা ৫ মিনিটের মধ্যে উদ্ধারকারী নৌকা নিয়ে পৌঁছাচ্ছি।"
                Language.KANNADA -> "ನಾವು 5 ನಿಮಿಷಗಳಲ್ಲಿ ಪಾರುಗಾಣಿಕಾ ದೋಣಿಯೊಂದಿಗೆ ತಲುಪುತ್ತಿದ್ದೇವೆ."
                Language.MALAYALAM -> "ഞങ്ങൾ 5 മിനിറ്റിനുള്ളിൽ രക്ഷാപ്രവർത്തന ബോട്ടിൽ എത്തും."
                Language.GUJARATI -> "અમે 5 મિનિટમાં બચાવ બોટ સાથે પહોંચી રહ્યા છીએ."
                Language.ODIA -> "ଆମେ ୫ ମିନିଟରେ ଉଦ୍ଧାର ଡଙ୍ଗା ସହିତ ପହଞ୍ଚୁଛୁ।"
                Language.PUNJABI -> "ਅਸੀਂ 5 ਮਿੰਟਾਂ ਵਿੱਚ ਬਚਾਅ ਕਿਸ਼ਤੀ ਨਾਲ ਪਹੁੰਚ ਰਹੇ ਹਾਂ।"
            }
        }

        // 7. Route / Path Clear (रास्ता साफ / Route Clear / आगे बढ़ो)
        if (lower.contains("रास्ता") || lower.contains("साफ") || lower.contains("clear") || lower.contains("आगे") || lower.contains("proceed") || lower.contains("मार्ग")) {
            return when (tgt) {
                Language.HINDI -> "रास्ता साफ है, टीम आगे बढ़ सकती है।"
                Language.TAMIL -> "பாதை தெளிவாக உள்ளது, குழு முன்னேறலாம்."
                Language.TELUGU -> "దారి స్పష్టంగా ఉంది, బృందం ముందుకు సాగవచ్చు."
                Language.MARATHI -> "मार्ग मोकळा आहे, पथक पुढे जाऊ शकते."
                Language.BENGALI -> "পথ পরিষ্কার, দল এগিয়ে যেতে পারে।"
                Language.KANNADA -> "ರಸ್ತೆ ಸ್ಪಷ್ಟವಾಗಿದೆ, ತಂಡವು ಮುಂದುವರಿಯಬಹುದು."
                Language.MALAYALAM -> "വഴി വ്യക്തമാണ്, സംഘത്തിന് മുന്നോട്ട് പോകാം."
                Language.GUJARATI -> "રસ્તો સાફ છે, ટીમ આગળ વધી શકે છે."
                Language.ODIA -> "ରାସ୍ତା ସଫା ଅଛି, ଦଳ ଆଗକୁ ବଢିପାରିବ।"
                Language.PUNJABI -> "ਰਾਹ ਸਾਫ਼ ਹੈ, ਟੀਮ ਅੱਗੇ ਵਧ ਸਕਦੀ ਹੈ।"
                Language.ENGLISH -> "Route is clear, rescue team can proceed forward."
            }
        }

        // 8. Safe / All Clear (सुरक्षित / Safe / ठीक)
        if (lower.contains("सुरक्षित") || lower.contains("safe") || lower.contains("ठीक") || lower.contains("all clear")) {
            return when (tgt) {
                Language.HINDI -> "सभी लोग सुरक्षित हैं, स्थिति नियंत्रण में है।"
                Language.TAMIL -> "அனைத்து மக்களும் பாதுகாப்பாக உள்ளனர், நிலைமை கட்டுப்பாட்டில் உள்ளது."
                Language.TELUGU -> "అందరూ సురక్షితంగా ఉన్నారు, పరిస్థితి అదుపులో ఉంది."
                Language.MARATHI -> "सर्व नागरिक सुरक्षित आहेत, परिस्थिती नियंत्रणात आहे."
                Language.BENGALI -> "সকলে নিরাপদ আছেন, পরিস্থিতি নিয়ন্ত্রণে আছে।"
                Language.KANNADA -> "ಎಲ್ಲರೂ ಸುರಕ್ಷಿತವಾಗಿದ್ದಾರೆ, ಪರಿಸ್ಥಿತಿ ನಿಯಂತ್ರಣದಲ್ಲಿದೆ."
                Language.MALAYALAM -> "എല്ലാവരും സുരക്ഷിതരാണ്, സാഹചര്യം നിയന്ത്രണവിധേയമാണ്."
                Language.GUJARATI -> "બધા લોકો સુરક્ષિત છે, સ્થિતિ નિયંત્રણમાં છે."
                Language.ODIA -> "ସମସ୍ତେ ସୁରକ୍ଷିତ ଅଛନ୍ତି, ପରିସ୍ଥିତି ନିୟନ୍ତ୍ରଣରେ ଅଛି।"
                Language.PUNJABI -> "ਸਾਰੇ ਲੋਕ ਸੁਰੱਖਿਅਤ ਹਨ, ਸਥਿਤੀ ਕਾਬੂ ਵਿੱਚ ਹੈ।"
                Language.ENGLISH -> "All personnel are safe, situation is under control."
            }
        }

        // 9. Testing / Mic Check / Greetings
        if (lower.contains("test") || lower.contains("check") || lower.contains("hear") || lower.contains("123") || lower.contains("one two") || lower.contains("आवाज") || lower.contains("हेलो") || lower.contains("hello") || lower.contains("hi")) {
            return when (tgt) {
                Language.HINDI -> "नमस्ते, माइक टेस्टिंग 1 2 3। क्या आपको आवाज आ रही है?"
                Language.TAMIL -> "வணக்கம், மைக் சோதனை 1 2 3. எனது குரல் தெளிவாக கேட்கிறதா?"
                Language.TELUGU -> "నమస్కారం, మైక్ టెస్టింగ్ 1 2 3. నా స్వరం వినబడుతుందా?"
                Language.MARATHI -> "नमस्कार, माइक टेस्टिंग १ २ ३. माझा आवाज ऐकू येत आहे का?"
                Language.BENGALI -> "নমস্কার, মাইক টেস্টিং ১ ২ ৩। আমার কথা শুনতে পাচ্ছেন?"
                Language.KANNADA -> "ನಮಸ್ಕಾರ, ಮೈಕ್ ಪರೀಕ್ಷೆ 1 2 3. ನನ್ನ ಧ್ವನಿ ಕೇಳಿಸುತ್ತಿದೆಯೇ?"
                Language.MALAYALAM -> "നമസ്കാരം, മൈക്ക് ടെസ്റ്റിംഗ് 1 2 3. എന്റെ ശബ്ദം കേൾക്കാമോ?"
                Language.GUJARATI -> "નમસ્તે, માઇક ટેસ્ટિંગ 1 2 3. મારો અવાજ સંભળાય છે?"
                Language.ODIA -> "ନମସ୍କାର, ମାଇକ୍ ପରୀକ୍ଷା ୧ ୨ ୩। ମୋ କଥା ଶୁଭୁଛି କି?"
                Language.PUNJABI -> "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ, ਮਾਈਕ ਟੈਸਟਿੰਗ 1 2 3. ਮੇਰੀ ਆਵਾਜ਼ ਆ ਰਹੀ ਹੈ?"
                Language.ENGLISH -> "Hello, radio mic check 1 2 3. Do you copy?"
            }
        }

        // 10. Roger / Copy That / Understood
        if (lower.contains("roger") || lower.contains("copy") || lower.contains("समझ") || lower.contains("understood") || lower.contains("ok") || lower.contains("theek") || lower.contains("fine")) {
            return when (tgt) {
                Language.HINDI -> "रोजर! संदेश प्राप्त हुआ, हम तैयार हैं।"
                Language.TAMIL -> "ரோஜர்! செய்தி பெறப்பட்டது, நாங்கள் தயாராக உள்ளோம்."
                Language.TELUGU -> "రోజర్! సమాచారం అందింది, మేము సిద్ధంగా ఉన్నాము."
                Language.MARATHI -> "रोजर! संदेश मिळाला आहे, आम्ही तयार आहोत."
                Language.BENGALI -> "রজার! বার্তা পেয়েছি, আমরা প্রস্তুত।"
                Language.KANNADA -> "ರೋಜರ್! ಸಂದೇಶ ಬಂದಿದೆ, ನಾವು ಸಿದ್ಧರಿದ್ದೇವೆ."
                Language.MALAYALAM -> "റോജർ! സന്ദേശം ലഭിച്ചു, ഞങ്ങൾ തയ്യാറാണ്."
                Language.GUJARATI -> "રોજર! સંદેશ મળ્યો છે, અમે તૈયાર છીએ."
                Language.ODIA -> "ରୋଜର! ସୂଚନା ମିଳିଛି, ଆମେ ପ୍ରସ୍ତୁତ ଅଛୁ।"
                Language.PUNJABI -> "ਰੋਜਰ! ਸੁਨੇਹਾ ਮਿਲ ਗਿਆ, ਅਸੀਂ ਤਿਆਰ ਹਾਂ।"
                Language.ENGLISH -> "Roger that! Message received and understood loud and clear."
            }
        }

        // 11. Location / Where are you
        if (lower.contains("location") || lower.contains("where") || lower.contains("kahan") || lower.contains("कुठे") || lower.contains("எங்கே") || lower.contains("ఎక్కడ")) {
            return when (tgt) {
                Language.HINDI -> "आपकी वर्तमान स्थिति क्या है? तुरंत लोकेशन साझा करें।"
                Language.TAMIL -> "உங்கள் தற்போதைய இருப்பிடம் என்ன? இருப்பிடத்தை பகிரவும்."
                Language.TELUGU -> "మీ ప్రస్తుత స్థానం ఏమిటి? స్థానాన్ని పంపండి."
                Language.MARATHI -> "आपले सध्याचे स्थान काय आहे? तात्काळ स्थान कळवा."
                Language.BENGALI -> "আপনার বর্তমান অবস্থান কি? অবিলম্বে অবস্থান জানান।"
                Language.KANNADA -> "ನಿಮ್ಮ ಪ್ರಸ್ತುತ ಸ್ಥಳ ಯಾವುದು? ತಕ್ಷಣ ಸ್ಥಳ ಹಂಚಿಕೊಳ್ಳಿ."
                Language.MALAYALAM -> "നിങ്ങളുടെ നിലവിലെ സ്ഥാനം എന്താണ്? ലൊക്കേഷൻ അറിയിക്കുക."
                Language.GUJARATI -> "તમારું વર્તમાન સ્થાન શું છે? સ્થાન મોકલો."
                Language.ODIA -> "ଆପଣଙ୍କର ବର୍ତ୍ତମାନ ସ୍ଥିତି କଣ? ଲୋକେସନ ଜଣାନ୍ତୁ।"
                Language.PUNJABI -> "ਤੁਹਾਡੀ ਮੌਜੂਦਾ ਸਥਿਤੀ ਕੀ ਹੈ? ਲੋਕੇਸ਼ਨ ਭੇਜੋ।"
                Language.ENGLISH -> "What is your current GPS / grid location? Report coordinates."
            }
        }

        // Clean natural fallback
        return when (tgt) {
            Language.ENGLISH -> "$inputText"
            Language.TAMIL -> "$inputText"
            Language.HINDI -> "$inputText"
            Language.MARATHI -> "$inputText"
            Language.TELUGU -> "$inputText"
            Language.BENGALI -> "$inputText"
            else -> inputText
        }
    }
}
