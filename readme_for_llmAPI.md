# LLM Assistant API — setup

The AR app has an LLM assistant with three flows:

| Flow            | Trigger                | What it does                                           |
|-----------------|------------------------|--------------------------------------------------------|
| Query           | "Ask" FAB (💬)          | Push-to-talk → LLM answers a question about the scene |
| Navigate        | "Guide me to" FAB (🧭) | Push-to-talk → LLM picks a target → turn-by-turn TTS   |
| Vision update   | Automatic (every ~12 s)| Sends a JPEG + map to LLM; observations merged back    |

All three hit the same OpenRouter endpoint with JSON `response_format`.

## 1. Fill in the API key and model id

Open (or create) the file:

```
/Users/omvarma/Desktop/ketan/Major-Project/android/local.properties
```

Add two lines (alongside any existing `sdk.dir=`, `openrouter.api.key=`, etc.):

```properties
llm.assistant.api.key=PUT_YOUR_OPENROUTER_KEY_HERE
llm.assistant.model=PUT_YOUR_MODEL_ID_HERE
```

Notes:
- The key is a standard OpenRouter key starting with `sk-or-...`.
- The model id is the OpenRouter string for GLM 4.7 — look it up at
  https://openrouter.ai/models and paste the exact id (e.g. something like
  `z-ai/glm-4.6` or `thudm/glm-4.7-plus`; OpenRouter's catalog is the source
  of truth).
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
| `android/app/build.gradle.kts`                                  | Adds `LLM_ASSISTANT_API_KEY` and `LLM_ASSISTANT_MODEL` `buildConfigField`s |

## 5. Tuning

Runtime constants (edit in `LlmAssistant.kt` → `LlmAssistantConfig`):

- `VISION_UPDATE_INTERVAL_MS` — how often vision updates fire (default 12 s)
- `CONTEXT_RADIUS_M` — how many metres of map are serialized per call (10 m)
- `MAX_CONTEXT_OBJECTS` — token budget for NEARBY_OBJECTS (40)
- `TEMPERATURE`, `MAX_TOKENS`, `TIMEOUT_MS` — standard knobs

## 6. Permissions already in the manifest

`RECORD_AUDIO`, `INTERNET`, `CAMERA` — all present, nothing to add.
