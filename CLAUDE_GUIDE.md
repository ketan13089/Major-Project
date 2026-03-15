# Using Claude Code with SLAM Project

This project uses a unique architecture where most of the complex logic (SLAM, Computer Vision, AR) resides in the Android/Kotlin layer, while Flutter serves primarily as the UI.

To get the best results from Claude Code Pro, follow these guidelines.

## 1. Context Strategy (The "Iceberg" Architecture)
m
The codebase is split: 10% Dart (UI), 90% Kotlin (Logic).

*   **UI Tasks:** Focus on `lib/` (Dart).
*   **Logic Tasks:** Focus on `android/app/src/main/kotlin/com/ketan/slam/` (Kotlin).

**Best Practice:** Explicitly state the layer you are working on.
*   *Bad:* "Fix the pathfinding."
*   *Good:* "In `PathPlanner.kt`, update the A* algorithm to increase cost near walls."

## 2. Key Context Sets

When asking Claude to help, load these specific file groups to give it the right context without overloading it.

### Set A: The Bridge (UI <-> Engine)
*   **Use when:** Working on data visualization, commands, or adding new features that need UI control.
*   **Files:** `lib/main.dart`, `lib/indoor_map_viewer.dart`, `android/.../ArActivity.kt`.
*   **Key Insight:** Ensure MethodChannel names (`com.ketan.slam/ar`) match exactly.

### Set B: The Brain (SLAM & Logic)
*   **Use when:** Working on algorithms, navigation, or map management.
*   **Files:** `SlamEngine.kt`, `PathPlanner.kt`, `SemanticMapManager.kt`.
*   **Key Insight:** These classes are tightly coupled. Modifying one often requires checking the others.

### Set C: The Eyes (AR & Vision)
*   **Use when:** Debugging rendering, detection accuracy, or coordinate systems.
*   **Files:** `ArActivity.kt`, `YoloDetector.kt`, `BackgroundRenderer.kt`.

## 3. Workflow & Verification

Since this project relies heavily on JNI/Platform Channels, **Flutter Hot Reload will NOT work** for logic changes.

*   **Instruction:** Tell Claude: *"I am modifying Native Kotlin code. Do not rely on Hot Reload. Verify compilation with `flutter build apk`."*
*   **Testing:** Ask Claude to write **Kotlin Unit Tests (JUnit)** for complex logic (e.g., `GridCell` math) instead of trying to debug everything through the UI.

## 4. Specific "Power User" Tips

*   **Math & Geometry:** The project uses raw OpenGL and Matrix math.
    *   *Tip:* Before asking for code changes, ask Claude to *"Explain the coordinate system transformation from Screen Space to World Space currently implemented in `estimate3D`"* to prime its context.

*   **Concurrency:** You use `ConcurrentHashMap` and background threads.
    *   *Tip:* Always add the constraint: *"Ensure this runs off the UI thread and handles thread safety for the `grid` map."*

*   **Large Files:** `ArActivity.kt` is large (~800 lines).
    *   *Tip:* Use line ranges when possible (e.g., "Read lines 500-600 of ArActivity.kt") to save tokens and improve focus.

## Summary Checklist for Prompts

Copy-paste this into your "Custom Instructions" or system prompt:

> "This is a Flutter project with heavy Native Kotlin logic for SLAM and AR.
> 1. Always check `android/` directory for core logic.
> 2. Flutter is only for UI; Logic lies in `SlamEngine` and `ArActivity`.
> 3. Verify native changes by running a Gradle build, not just Dart analysis.
> 4. Be careful with MethodChannel names ('com.ketan.slam/ar')."
