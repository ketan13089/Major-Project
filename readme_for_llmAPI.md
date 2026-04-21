# LLM Assistant API — setup

The AR app has an LLM assistant with three flows:

| Flow            | Trigger                | What it does                                           |
|-----------------|------------------------|--------------------------------------------------------|
| Query           | "Ask" FAB (💬)          | Push-to-talk → LLM answers a question about the scene |
| Navigate        | "Guide me to" FAB (🧭) | Push-to-talk → LLM picks a target → turn-by-turn TTS   |
| Vision update   | Optional automatic loop | Sends a JPEG + map to LLM; observations merged back    |

All three hit the Google AI Studio (Gemini) endpoint with JSON `responseMimeType`.

## 1. Fill in the API key and model id

Get a free API key from [Google AI Studio](https://aistudio.google.com/apikey).

Open the file:

```
android/local.properties
```

Add two lines (alongside any existing `sdk.dir=`, etc.):

```properties
gemini.api.key=AIza...
gemini.model=gemini-2.0-flash
# Optional: only enable automatic vision updates if you want background image
# calls. The green/orange buttons can still attach images on demand.
gemini.vision.enabled=false
# Optional: only enable the background semantic corrector if you want it to
# spend the same Gemini quota as the assistant buttons.
gemini.semantic.enabled=false
```

Notes:
- The key is a Google AI Studio key starting with `AIza...`.
- `gemini-2.0-flash` is the default multimodal model — it supports both
  text and image inputs which is needed for the vision-update flow.
- `local.properties` is already gitignored. Never commit the key.
- If either line is blank the assistant stays disabled at runtime and the
  FABs show a toast explaining that.
- Automatic vision updates are opt-in because they run in the background and
  can use quota while you are trying to use the assistant buttons.
- The semantic corrector is opt-in because it runs in the background and can
  otherwise rate-limit the green/orange assistant calls on the same key.

## 2. Rebuild

```
flutter clean
flutter build apk --debug
```

The Gradle build reads `local.properties` and injects both values into
`BuildConfig` at compile time. No code changes needed after pasting the key.

## 3. Test quickly

- Tap the green 💬 FAB → say "What do you see around me?"
- Tap the orange 🧭 FAB → say "Guide me to the nearest door."
- If `gemini.vision.enabled=true`, walk around and the app periodically sends
  a frame to the LLM and merges any new objects into the semantic map.

## 4. Where the integration lives

| File | Responsibility |
|---|---|
| `android/app/src/main/kotlin/com/ketan/slam/LlmAssistant.kt`    | Config, HTTP client, context builder, three flows (`query`, `navigate`, `visionUpdate`), YUV → base64 JPEG encoder |
| `android/app/src/main/kotlin/com/ketan/slam/LlmVoiceInput.kt`   | Thin `SpeechRecognizer` wrapper that returns the raw transcript |
| `android/app/src/main/kotlin/com/ketan/slam/LlmAssistantUi.kt`  | Two FABs, loading spinner, reply card — all added to the existing AR root layout |
| `android/app/src/main/kotlin/com/ketan/slam/NavigationManager.kt` | Added `navigateToExplicit(dest, ...)` so the LLM can hand off a pre-chosen destination for turn-by-turn TTS guidance |
| `android/app/src/main/kotlin/com/ketan/slam/ArActivity.kt`      | Wires up the three pieces: initializes `LlmAssistantConfig` from BuildConfig, attaches UI, dispatches voice → LLM → TTS, and publishes camera YUV snapshots for the periodic vision update |
| `android/app/build.gradle.kts`                                  | Adds `GEMINI_API_KEY` and `GEMINI_MODEL` `buildConfigField`s |
| `android/app/src/main/kotlin/com/ketan/slam/SemanticCorrectionEngine.kt` | Semantic map correction also uses the same Gemini API key |

## 5. Tuning

Most cost/rate controls live in `LlmCallGuard.kt` (cooldowns, motion gates):

- `ASK_COOLDOWN_MS` (2.5 s), `NAVIGATE_COOLDOWN_MS` (3 s), `VISION_COOLDOWN_MS` (15 s)
- `VISION_MOVE_THRESHOLD_M` (1.2 m) — resend when user has walked this far
- `VISION_TURN_THRESHOLD_RAD` (~25°) — resend when user has rotated this much
- `VISION_IDLE_REFRESH_MS` (60 s) — force refresh if stationary this long
- `VISION_FRAME_SIMILARITY` (6%) — skip resend when scene looks unchanged

Prompt/shape controls live in `LlmAssistant.kt` → `LlmAssistantConfig`:

- `CONTEXT_RADIUS_M` (10 m) — map radius serialized per call
- `MAX_CONTEXT_OBJECTS` (30) — cap on NEARBY_OBJECTS token cost
- `VISION_JPEG_QUALITY` (55) — image compression, lower = cheaper
- `TEMPERATURE`, `MAX_TOKENS`, `TIMEOUT_MS` — standard knobs

The Ask button only attaches the camera image for *visual* questions
(keywords in `AskNeedsImageClassifier`). Map-only questions ("how far is
the nearest door") stay text-only and cost ~3–5× less. The Guide button
is text-only on the first attempt and only retries with an image if the
LLM returns no target on the text-only pass.

## 6. Permissions already in the manifest

`RECORD_AUDIO`, `INTERNET`, `CAMERA` — all present, nothing to add.
