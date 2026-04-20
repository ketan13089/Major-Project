# LLM Assistant API — setup

The AR app has an LLM assistant with three flows:

| Flow            | Trigger                | What it does                                           |
|-----------------|------------------------|--------------------------------------------------------|
| Query           | "Ask" FAB (💬)          | Push-to-talk → LLM answers a question about the scene |
| Navigate        | "Guide me to" FAB (🧭) | Push-to-talk → LLM picks a target → turn-by-turn TTS   |
| Vision update   | Automatic (every ~12 s)| Sends a JPEG + map to LLM; observations merged back    |

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
```

Notes:
- The key is a Google AI Studio key starting with `AIza...`.
- `gemini-2.0-flash` is the default multimodal model — it supports both
  text and image inputs which is needed for the vision-update flow.
- `local.properties` is already gitignored. Never commit the key.
- If either line is blank the assistant stays disabled at runtime and the
  FABs show a toast explaining that.

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
- Walk around — every ~12 seconds the app sends a frame to the LLM and
  merges any new objects it identifies into the semantic map.

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

Runtime constants (edit in `LlmAssistant.kt` → `LlmAssistantConfig`):

- `VISION_UPDATE_INTERVAL_MS` — how often vision updates fire (default 12 s)
- `CONTEXT_RADIUS_M` — how many metres of map are serialized per call (10 m)
- `MAX_CONTEXT_OBJECTS` — token budget for NEARBY_OBJECTS (40)
- `TEMPERATURE`, `MAX_TOKENS`, `TIMEOUT_MS` — standard knobs

## 6. Permissions already in the manifest

`RECORD_AUDIO`, `INTERNET`, `CAMERA` — all present, nothing to add.
