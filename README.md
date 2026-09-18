# iTantra
# iTantra — Offline Disaster Mesh Walkie‑Talkie

iTantra is a native Android app that turns a cluster of phones into an **infrastructure‑free, multilingual walkie‑talkie mesh** for disaster response. When the cell network and internet are down, nearby phones running iTantra discover each other over **Wi‑Fi Direct** and **Bluetooth SPP**, relay short voice/text messages and SOS alerts across the group, and locally translate + speak each message in the receiver's own language — all **on‑device**, with a persistent store‑and‑forward queue so messages aren't lost if a peer is briefly out of range.

---

## 1. What the app does (user‑facing)

1. **Onboarding** — pick your spoken language (11 languages: English + 10 Indian languages) and a transport mode (Wi‑Fi Direct, Bluetooth SPP, or Dual Auto‑Mesh).
2. **Walkie‑Talkie screen** — press‑and‑hold a push‑to‑talk (PTT) button, speak, and release. Your speech is transcribed, packaged, and broadcast to every peer in the mesh (or to one selected peer).
3. **Receiving** — every other phone receives the packet, translates the text into *its own* preferred language, and speaks it aloud through TTS — automatically, no user action needed.
4. **Emergency Alerts screen** — one‑tap disaster alerts (Flood, Fire, Earthquake, Medical, Evacuate, Help) that jump the queue and trigger an alarm tone on every receiving device.
5. **Mesh Diagnostics screen** — see connected peer count, active transport, discovered Wi‑Fi Direct / Bluetooth devices, and the DTN (store‑and‑forward) message history.
6. **Background operation** — a foreground service keeps the mesh radios and audio pipeline alive even when the app is minimized.

---

## 2. Installation & Build

### 2.1 Prerequisites

| Requirement | Version / Notes |
|---|---|
| **Android Studio** | Koala (2024.1) or newer — bundles a compatible Gradle/AGP and the Android NDK/CMake components |
| **JDK** | 17 (set as Gradle JVM in Android Studio) |
| **Android SDK** | `compileSdk 34`, `minSdk 26` (Android 8.0+), `targetSdk 34` |
| **NDK + CMake** | Install via SDK Manager → SDK Tools → check "NDK (Side by side)" and "CMake" (project pins CMake `3.22.1`) — needed because the app has a native C++20 audio engine |
| **Physical Android devices** | **Two or more phones are required to test the mesh features.** An emulator has no real Bluetooth/Wi‑Fi Direct radio, so PTT/mesh/relay behavior cannot be exercised on it (STT/TTS/UI can still be smoke‑tested on one emulator) |

### 2.2 Clone / open the project

```bash
unzip iTantra-main.zip
cd iTantra-main
```

Open the folder in **Android Studio** ("Open an existing project") and let it sync Gradle, **or** build from the command line:

```bash
# macOS/Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

The first sync will download:
- The Android Gradle Plugin, Kotlin, Compose BOM, Room, Coroutines, Navigation (all resolved from Google/Maven Central).
- **ONNX Runtime for Android** (`com.microsoft.onnxruntime:onnxruntime-android:1.18.0`).
- **Google Oboe** (`com.google.oboe:oboe:1.8.0`) — the high‑performance native audio library.
- The bundled **sherpa‑onnx** AAR at `app/libs/sherpa-onnx-1.13.8.aar` (already included in the repo, not fetched from a remote repo).

> No API keys, backend servers, or `local.properties` secrets are required — everything the app needs at runtime ships inside the APK (`app/src/main/assets/sherpa/*.onnx` + `tokens.txt`, ~35 MB of speech‑recognition model weights).

### 2.3 Running it

1. Connect **two Android devices** (API 26+) over USB, enable Developer Options → USB Debugging on each.
2. In Android Studio, select each device and click **Run ▶** (or `./gradlew installDebug` twice, once per connected device via `adb -s <serial> install …`).
3. On first launch, grant the requested permissions (microphone, nearby devices / Bluetooth, location — location is required by Android for Wi‑Fi Direct/Bluetooth peer discovery, not for GPS tracking).
4. Walk through onboarding on both phones, choosing different spoken languages to see translation happen live.
5. Keep the two phones within Wi‑Fi Direct/Bluetooth range (a few meters to ~30 m depending on the phone), no Wi‑Fi router or SIM/data connection needed.
6. Press and hold the PTT button on phone A, speak, release — phone B should speak the translated message back within a couple of seconds.
7. Try **Alerts → Flood** on phone A; phone B should get an alarm tone + spoken translated alert.

### 2.4 Common build snags

| Symptom | Fix |
|---|---|
| `CMake` / NDK not found | SDK Manager → SDK Tools → install "NDK (Side by side)" + "CMake" matching the version in `app/build.gradle.kts`/`CMakeLists.txt` |
| `oboe::oboe` target not found | Make sure `buildFeatures.prefab = true` synced correctly and the Oboe AAR resolved (check the `google-oboe` dependency downloaded) |
| App runs but STT never returns real text | Expected on the emulator/without a working mic route — see §4.4; the app has a scripted "tactical phrase" fallback so PTT never appears to silently fail |
| Only one phone available | You can still test the UI, onboarding, TTS and the emergency alert screen; the actual mesh relay needs a second device |

---

## 3. High‑level architecture

```
┌─────────────────────────────── UI (Jetpack Compose) ───────────────────────────────┐
│  OnboardingScreen → WalkieTalkieScreen ⇄ EmergencyAlertScreen ⇄ MeshDiagnosticsScreen │
└──────────────────────────────────────┬───────────────────────────────────────────────┘
                                        │ StateFlow (WalkieTalkieViewModel)
┌───────────────────────────────────────┴───────────────────────────────────────────────┐
│                                  ITantraApp (Application)                              │
│   owns singletons: VAD · LID · STT · Translation · TTS · DtnMessageQueue · MeshCoord   │
└───────────────────────────────────────┬───────────────────────────────────────────────┘
                                        │
                 ┌──────────────────────┼───────────────────────────┐
                 ▼                      ▼                           ▼
      RealtimeSpeechRecognizer   MeshCoordinator            WalkieTalkieForegroundService
      (AudioRecord → sherpa‑onnx) (packet routing, heartbeat, dedupe) (keeps everything alive)
                                        │
                    ┌───────────────────┼────────────────────┐
                    ▼                   ▼                    ▼
           BluetoothMeshManager  WifiDirectMeshManager   DtnMessageQueue
           (RFCOMM SPP sockets)  (Wi‑Fi P2P + UDP/TCP)    (Room DB, retry watchdog)
                    │                   │
                    └─────────┬─────────┘
                               ▼
                     PacketProtocol (binary framing + CRC32)
```

### 3.1 Native layer (C++ / JNI)

`app/src/main/cpp/` builds a shared library (`itantra_native`) via CMake:

- **`oboe_audio_engine.cpp`** — wraps [Google Oboe](https://github.com/google/oboe) for low‑latency full‑duplex audio record/playback, exposes a pre‑roll ring buffer (captures audio *just before* PTT is pressed, so the very first syllable isn't clipped).
- **`rms_gate.cpp`** — a cheap RMS energy gate used as a first‑pass silence filter before running any heavier VAD logic, so idle mesh phones burn effectively no CPU.
- **`onnx_mmap_loader.cpp`** — memory‑maps model files instead of reading them fully into RAM, which matters on low‑end disaster‑relief hardware.
- **`jni_bridge.cpp`** — the `extern "C"` JNI entry points that `NativeAudioBridge.kt` calls into (`nativeInit`, `nativeStartRecording/Playback`, `nativeIsAudioActive`, `nativeLoadMmapModel`, …).

`ITantraApp.onCreate()` initializes this native engine first; if it fails to load (e.g. missing `.so` on an unsupported ABI), the app sets `isDemoMode = true` and continues running in a degraded software‑only mode rather than crashing.

### 3.2 Transport / Mesh layer

**`PacketProtocol`** (`transport/protocol/PacketProtocol.kt`) defines an 8‑byte binary header + UTF‑8 payload:

```
[0]     Packet type   (0x00 EMERGENCY, 0x01 VOICE_TEXT, 0x02 HANDSHAKE, 0x03 ACK, 0x04 HEARTBEAT_PING)
[1]     Source language ID (0x00–0x0A, one of the 11 supported languages)
[2..3]  Sequence number (uint16, big‑endian)
[4..7]  CRC32 checksum of the payload (int32, big‑endian)
[8..N]  UTF‑8 text payload
```
CRC32 protects against corruption over noisy radio links; a unit test (`PacketProtocolTest.kt`) round‑trips encode/decode and asserts CRC failure detection.

**`BluetoothMeshManager`** — classic Bluetooth RFCOMM/SPP. Runs a server socket listening on a fixed UUID (`00001101‑…`), auto‑connects to already‑bonded devices every 4 s, and fans out every outgoing packet to all open sockets.

**`WifiDirectMeshManager`** — combines Android's `WifiP2pManager` (peer discovery/connection) with a raw **UDP broadcast** on port `8988` (works even without a formal Wi‑Fi Direct group — e.g. over a phone hotspot) plus a **TCP server/client fallback** once a P2P group is formed. It walks common hotspot subnet ranges (`192.168.43.x`, `192.168.49.x`) to cope with carriers/kernels that block true broadcast on tethered interfaces.

**`MeshCoordinator`** ties both radios together:
- Sends a **heartbeat ping** every 2 s so peers/UI can tell the mesh is alive.
- **Dual‑radio redundancy**: every outgoing packet is sent over *both* Bluetooth and Wi‑Fi simultaneously for resilience.
- **De‑duplication**: tracks a hash of every packet it has sent (to ignore its own broadcast loopbacks) and a key of every packet it has received (`type_lang_seq_textHash`) so a message arriving via both radios isn't processed twice.
- Routes each decoded packet by type: `EMERGENCY_ALERT` → alarm tone + immediate translate‑and‑speak; `VOICE_TEXT` → translate, log to DTN history, send an `ACK` back, then speak; `ACK` → marks the matching outgoing message acknowledged in the DTN queue; `HEARTBEAT_PING` → just logged.

**`DtnMessageQueue`** (Delay‑Tolerant Networking) — a Room‑backed persistent queue (`DtnMessageEntity`/`DtnDao`/`DtnDatabase`) that records every outgoing and incoming message with a status (`PENDING_OUTGOING`, `DELIVERED`, `ACKNOWLEDGED`, `RECEIVED_INCOMING`). A background watchdog checks every 3 s for messages still awaiting an ACK, so the app has the scaffolding for "message survives a temporary loss of link and gets flagged/retried" rather than firing into the void.

### 3.3 AI pipeline

`ITantraApp` wires up five AI engines in a straight line:

```
mic → VAD (speech detection) → LID (which language?) → STT (speech→text)
    → PacketProtocol → mesh → receiver's phone
    → Translation (source lang → receiver's preferred lang) → TTS (text→speech)
```

- **`SileroVadEngine`** — two‑stage voice activity detection: a fast native RMS energy gate first, then a "neural" probability score. Frames are classified speech/silence with hysteresis (`speechStartConfidence`/`speechEndConfidence`) so a single dip mid‑sentence doesn't cut you off.
- **`LanguageIdentificationEngine`** — meant to auto‑detect which of the 11 languages is being spoken from the first ~1 s of audio.
- **`IndicConformerSttEngine`** + **`RealtimeSpeechRecognizer`** — **this is the one fully real neural component.** It captures 16 kHz mono PCM straight from `AudioRecord` and streams it into a **[sherpa‑onnx](https://github.com/k2-fsa/sherpa-onnx) online transducer** (Conformer encoder/decoder/joiner, INT8‑quantized ONNX) bundled in `app/src/main/assets/sherpa/`, giving genuine 100%‑offline, on‑device speech‑to‑text with no cloud round trip.
- **`IndicTranslationEngine`** — translates the recognized text from the source language into the recipient's chosen language before it's spoken back.
- **`ChunkedTtsEngine`** — splits the translated sentence into clauses on punctuation and speaks each clause as soon as it's ready (lower perceived latency than waiting for the whole sentence), bumps stream volume so alerts aren't missed, and plays a short chime before speaking so a listener knows a message is incoming.

### 3.4 UI layer

Jetpack Compose + Material 3, single‑Activity, manual screen‑enum navigation (`MainActivity` switches between `OnboardingScreen`, `WalkieTalkieScreen`, `EmergencyAlertScreen`, `MeshDiagnosticsScreen` — no `NavHost`, even though the Navigation‑Compose dependency is present). `WalkieTalkieViewModel` exposes a single `StateFlow<WalkieTalkieUiState>` (PTT state, connected‑peer count, active transport, spoken/receiver language, discovered peers, live waveform bars driven off mic RMS, last sent/translated text) that the screens collect and render reactively.

`WalkieTalkieForegroundService` is a plain foreground `Service` (declared with `foregroundServiceType="microphone|connectedDevice"`) that keeps the process (and therefore the mesh sockets + audio engine) alive while the app is backgrounded, as required for a walkie‑talkie to keep receiving.

---

## 4. Honest status of the AI components

The class and file names in this codebase (`IndicConformerSttEngine`, `IndicTranslationEngine`/"IndicTrans2‑dist‑200M", `SileroVadEngine`, `ChunkedTtsEngine`/"FastPitch + HiFi‑GAN") describe the **target production architecture** the team designed toward for the hackathon. Reading the actual implementation, here's what each one does *today*:

| Component | Claimed | What actually runs right now |
|---|---|---|
| **Speech‑to‑Text (STT)** | On‑device sherpa‑onnx Conformer model | ✅ **Real.** Genuine ONNX transducer inference via the bundled `sherpa-onnx` AAR and the three model files in `assets/sherpa/`. If inference returns nothing (e.g. models fail to load, or no speech is decodable), it falls back to a small set of hardcoded "tactical phrases" per language so the demo never dead‑ends on empty text. |
| **Voice Activity Detection (VAD)** | Silero VAD v5 ONNX neural network | ⚠️ **Simulated.** `SileroVadEngine.calculateSpeechProbability()` computes RMS energy and pushes it through a hand‑tuned sigmoid — there is no Silero ONNX model loaded or run. |
| **Language ID (LID)** | Acoustic classifier over the first 1s of audio | ⚠️ **Simulated.** `runAcousticLidInference()` computes a zero‑crossing rate but doesn't use it for anything — it always returns the caller's `fallbackLanguage` (i.e. whatever the user picked at onboarding). |
| **Translation** | IndicTrans2‑dist‑200M offline neural MT | ⚠️ **Rule‑based phrasebook.** `IndicTranslationEngine.runIndicTrans2Inference()` keyword‑matches the input against ~11 disaster‑relevant phrase categories (flood, fire, medical, evacuate, help, "route clear", "all safe", mic‑check, roger, location request) in a big `when` block and returns a pre‑written translation for each of the 11 languages. Anything that doesn't match one of those keyword buckets is returned **untranslated**. |
| **Text‑to‑Speech (TTS)** | FastPitch + HiFi‑GAN neural vocoder, chunked for low latency | ⚠️ **Uses Android's built‑in `TextToSpeech`** engine (whatever voice/locale packs are installed on the phone), not a custom neural vocoder. If the device has no voice pack for the target language, it falls back to a small set of hardcoded English phonetic sentences that approximate common alert phrases. |
| **Mesh networking, packet protocol, DTN queue, native audio (Oboe), foreground service, permissions** | — | ✅ **Fully implemented and functional**, and independent of the AI simulation status above. |

**Practical implication:** the app will faithfully record→transcribe→broadcast→receive→speak a message end‑to‑end today, and free‑form speech that lands in one of the eleven scripted categories above will be translated correctly. Anything outside those categories currently passes through translation unchanged, and VAD/LID always behave as their simple heuristics dictate rather than as trained classifiers. Swapping in the real Silero VAD, a language‑ID classifier, and IndicTrans2 ONNX models (following the same asset‑bundling + sherpa‑onnx/ONNX‑Runtime pattern already used for STT) is the natural next step to make the naming match the behavior.

---

## 5. Project layout reference

```
iTantra-main/
├── app/
│   ├── CMakeLists.txt              # native build config
│   ├── build.gradle.kts            # app module Gradle config
│   ├── libs/sherpa-onnx-1.13.8.aar # bundled offline STT engine
│   └── src/
│       ├── main/
│       │   ├── cpp/                # Oboe audio engine, RMS gate, mmap loader, JNI bridge
│       │   ├── assets/sherpa/      # ONNX STT model weights + tokens.txt
│       │   ├── java/com/itantra/
│       │   │   ├── ITantraApp.kt               # Application: wires up all engines
│       │   │   ├── ai/{vad,lid,stt,translation,tts,model}/
│       │   │   ├── audio/NativeAudioBridge.kt  # Kotlin↔JNI bridge
│       │   │   ├── service/WalkieTalkieForegroundService.kt
│       │   │   ├── transport/
│       │   │   │   ├── MeshCoordinator.kt
│       │   │   │   ├── protocol/PacketProtocol.kt
│       │   │   │   ├── bluetooth/BluetoothMeshManager.kt
│       │   │   │   ├── wifidirect/WifiDirectMeshManager.kt
│       │   │   │   └── dtn/{DtnDao,DtnDatabase,DtnMessageEntity,DtnMessageQueue}.kt
│       │   │   └── ui/{MainActivity, alerts, mesh, onboarding, walkietalkie, theme, components}
│       │   └── res/                # icons, strings, themes
│       └── test/java/com/itantra/PacketProtocolTest.kt
├── gradle/libs.versions.toml       # version catalog
├── settings.gradle.kts
├── prototype.html                  # standalone HTML/JS UI prototype (not part of the Android app)
└── presentation_board.html         # hackathon presentation board
```

---

## 6. Suggested next steps for contributors

1. Replace the heuristic **VAD** with an actual Silero VAD v5 ONNX model bundled and run through ONNX Runtime (the dependency is already in `build.gradle.kts`).
2. Replace the **LID** stub with a small acoustic classifier, or at minimum use it to *override* the user's manually chosen language rather than being a no‑op.
3. Bundle a real **IndicTrans2** (or similar) distilled ONNX translation model to replace the phrasebook, so arbitrary free‑form speech translates correctly.
4. Consider a neural **TTS** voice bundle (e.g. VITS/FastPitch ONNX export) for languages where the phone's built‑in TTS has no installed voice pack, instead of the English‑phonetic fallback.
5. Add instrumented tests around `MeshCoordinator`'s de‑duplication and `DtnMessageQueue` retry logic, mirroring the existing `PacketProtocolTest`.
