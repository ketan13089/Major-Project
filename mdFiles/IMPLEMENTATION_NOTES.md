# SLAM Safety & Localization Improvements - Implementation Session Notes

**Date:** March 19, 2026
**Status:** ✅ Complete - All 5 features implemented and integrated

## Overview
Implemented comprehensive safety, map quality, and localization improvements for the indoor SLAM navigation system. Features focus on reliability during development testing and real-world blind user scenarios.

---

## Feature 1: Enhanced Map Building

### Key Changes (MapBuilder.kt)

**Observation Tracking**
- Added `observationCounts: ConcurrentHashMap<GridCell, Int>` — incremented in `updateLogOdds()`, never reset
- Exposed via `observationCountSnapshot(): Map<GridCell, Int>` for path safety scoring
- Provides ground truth on cell mapping confidence

**Ray Casting Expansion**
- Forward rays: 7 rays (±45°) → 13 rays (±90°, 15° step intervals)
- NEW: Backward rays via `castBackwardRayFan()` — 5 rays (±30° from reverse), 2.0m range, called in `incrementalUpdate()`
- Ray distance: 3.0m → 3.5m

**Confidence-Weighted Decay** (in `rebuild()`)
- obs ≥ 10: decay 0.04 (well-observed walls persist)
- obs ≥ 5: decay 0.08 (moderate confidence)
- obs < 5: decay 0.15 (poorly-observed cells fade faster)

**Wall Continuity Improvements** (in `enforceConsistency()`)
- L-shaped corner detection: reinforces cells in corner patterns (+0.3 log-odds)
- 2-cell gap filling: `isWallGap2()` detects gaps separated by 2 cells on same axis
- Single-cell gap filling unchanged

**Depth Grid Resolution** (ArActivity.kt: `extractWallsFromDepth()`)
- Changed: 8×6 grid (48 points) → 10×8 grid (80 points)
- Loop: `for (col in 0..7)` → `for (col in 0 until 10)`
- Provides denser wall sampling

### Integration Points
- Called by `NavigationManager` for path safety scoring
- Decay thresholds calibrated for 2-second rebuild intervals

---

## Feature 2: Tracking Loss Recovery

### Key Changes (ArActivity.kt)

**New Fields**
```kotlin
private var lastTrackingState: TrackingState = TrackingState.STOPPED
@Volatile private var frozenPose: com.google.ar.core.Pose? = null
private var trackingLostTimestamp = 0L
private var announcer: NavigationGuide? = null  // Non-nav TTS
```

**Detection Flow** (in `onDrawFrame()` before `updateSlam()`)
1. **TRACKING → Lost**: Freeze pose, TTS "Tracking lost. Please hold the phone steady.", red HUD tint (0xCCCC0000)
2. **Lost → TRACKING**: Compute drift from frozen pose
   - If drift > 0.3m: TTS "Tracking recovered. Position shifted X metres. Rebuilding map." + force rebuild
   - If drift ≤ 0.3m: TTS "Tracking recovered." only
   - Clear red HUD (restore 0xCC000000)
3. Update `lastTrackingState` for next frame

**HUD Indicator**
- Show "⚠ TRACKING LOST ⚠" when `camera.trackingState != TRACKING`

**Navigation Integration** (NavigationManager.kt)
- `tick()` now accepts `isTracking: Boolean = true` parameter
- When false: skip all nav logic but preserve session state (no cancellation)
- Called from ArActivity: `nm.tick(..., isTracking = lastTrackingState == TrackingState.TRACKING)`

### Important Notes
- Announcer initialized in `onCreate()`, cleaned in `onDestroy()`
- Drift rebuild uses existing `rebuildExecutor` (no new threads)
- 0.3m threshold chosen for typical indoor 1-2 meter movements

---

## Feature 3: Enhanced Object Localization

### New Class: LocalizationSmoother.kt

**Buffering Strategy**
- Per-detection buffers keyed by `(label, gridX, gridZ)`
- Collects raw 3D positions with timestamps and method metadata
- Requires 2+ consistent positions within 0.8m over 5-second window

**EMA Smoothing**
```kotlin
ALPHA_HIT_TEST = 0.3f   // Fast response
ALPHA_FALLBACK = 0.15f  // Slower, more stable
```
- Hit-test results weighted 3x when mixed with fallback
- Effective alpha: `(alpha * 3f).coerceAtMost(0.8f)`

**Output**
- `feed(label, result): Point3D?` — returns smoothed position or null
- `removeStale(maxAgeMs)` — periodic cleanup (called every frame in `updateSlam()` with 5000L)

### ObjectLocalizer.kt Changes

**New Method**
```kotlin
fun estimate3DWithMethod(..., label: String): LocalizationSmoother.LocalizationResult?
```
- Returns: `LocalizationResult(position, method, confidence)`
- Hit-test: confidence=0.9, method="hit_test"
- Fallback: confidence=0.4, method="fallback"

**Class-Specific Depth Scaling**
```kotlin
fun depthScaleFactor(label: String): Float = when {
    "DOOR" -> 0.65f; "CHAIR" -> 0.40f; "FIRE_EXTINGUISHER" -> 0.35f
    "LIFT_GATE" -> 0.70f; "WINDOW" -> 0.55f; "NOTICE_BOARD" -> 0.45f
    "TRASH_CAN" -> 0.35f; "WATER_PURIFIER" -> 0.40f
    else -> 0.50f  // default
}
```
- Applied in `areaBasedFallback()`: `depth = (baseDepth * scaleFactor).coerceIn(0.8f, 6.0f)`

### SemanticObject.kt Changes
- Added optional field: `localizationMethod: String? = null`

### ArActivity.kt Integration

**Detection Pipeline** (in camera callback)
```
estimate3DWithMethod() → LocalizationSmoother.feed() →
  (if accepted) mergeOrAdd() → markObstacleFootprint()
```
- Only calls `mergeOrAdd()` when smoother returns non-null
- Passes localization method through to SemanticObject

**Lifecycle**
- Initialized in `onCreate()`: `localizationSmoother = LocalizationSmoother(RES)`
- Cleanup in `updateSlam()`: `localizationSmoother.removeStale(5000L)`

### Key Insights
- Smoother requires consistent positioning before accepting — prevents hallucinations
- Method metadata enables future confidence-based filtering
- Class-specific scaling compensates for YOLO bbox size variations

---

## Feature 4: Real-Time Hazard Warning System

### New Class: HazardWarningSystem.kt

**Always-On Operation**
- Independent of navigation state
- Analyzes wall-level depth hits for obstacles in walking path

**Forward Cone Filter**
```kotlin
FORWARD_CONE_DOT = 0.866f  // cos(30°)
```
- Only considers hits where `dot(hitDir, forwardDir) > 0.866`
- Excludes side obstacles, focuses on path threats

**Alert Levels**

| Distance | Vibration | TTS | Cooldown | Notes |
|----------|-----------|-----|----------|-------|
| < 0.8m (DANGER) | 2x pulse (200+100+200ms) | "Obstacle ahead, stop." | 2s | Always speaks |
| < 1.5m (WARNING) | 1x pulse (100ms) | "Obstacle ahead." | 5s | Suppressed during nav |
| Haptic | — | — | 1s global | Shared across both levels |

**Integration** (ArActivity.kt)

In `extractWallsFromDepth()`:
```kotlin
// Collect wall-level hits (relY in -0.5f..0.8f)
depthHits.add(HazardWarningSystem.DepthHit(hx, hz, dist))

// After loop, process hits
hazardWarningSystem?.processDepthHits(
    depthHits, userX, userZ, fwdX, fwdZ,
    isNavigating = (navigationManager?.state == NavigationState.NAVIGATING)
)
```

**Haptic Implementation**
```kotlin
// Android 8+: VibrationEffect.createWaveform()
// Older: deprecated Vibrator.vibrate(pattern)
```

### AndroidManifest.xml
- Added: `<uses-permission android:name="android.permission.VIBRATE" />`

### Important Notes
- Forward cone prevents "phantom obstacles" from side walls
- TTS suppression during nav prevents guidance interference
- Haptic cooldown (1s) prevents overwhelming user with vibration
- Announcer service reused from feature 2

---

## Feature 5: Path Safety Scoring

### PathPlanner.kt Changes

**Signature Update**
```kotlin
fun planPath(
    grid: Map<GridCell, Byte>,
    startGX: Int, startGZ: Int,
    goalGX: Int, goalGZ: Int,
    semanticObjects: List<SemanticObject> = emptyList(),
    observationCounts: Map<GridCell, Int>? = null  // NEW
): List<NavWaypoint>
```

**Cost Computation** (in A* loop)
```kotlin
val widthCost = corridorWidthCost(nc, d, grid)
val confidenceCost = mappingConfidenceCost(nc, grid, observationCounts)
val moveCost = baseCost * semanticMod * widthCost * confidenceCost
```

**Corridor Width Scoring**
- Counts perpendicular free cells (max 5 each side)
- Perpendicular direction = 90° rotation of movement direction
- Cost multipliers:
  - ≥ 6 cells: 0.85x (prefer wide corridors)
  - ≥ 3 cells: 1.0x (neutral)
  - ≥ 1 cell: 1.2x (narrow penalty)
  - 0 cells: 1.5x (strong penalty, dead-end)

**Mapping Confidence Scoring**
- obs ≥ 5 & no unknown neighbors: 0.9x (prefer)
- obs ≥ 2: 1.0x (neutral)
- near unknown cells: 1.3x (penalty)
- else: 1.15x (slight penalty)

**Helper Methods**
```kotlin
fun countPerpFree(cell, dx, dz, grid, limit): Int
  // Count consecutive free cells in direction up to limit

fun hasUnknownNeighbor(cell, grid): Boolean
  // Check all 8 neighbors for unknown or null cells

fun corridorWidthCost(cell, dir, grid): Float

fun mappingConfidenceCost(cell, grid, observationCounts): Float
```

### NavigationManager.kt Changes

**Caching**
```kotlin
@Volatile private var cachedObservationCounts: Map<GridCell, Int>? = null
```

**Updated tick() Signature**
```kotlin
fun tick(
    userX: Float, userZ: Float,
    headingRad: Float,
    grid: Map<GridCell, Byte>,
    semanticMap: SemanticMapManager,
    isTracking: Boolean = true,
    observationCounts: Map<GridCell, Int>? = null  // NEW
)
```

**Flow**
1. Cache observation counts at start of tick
2. Pass to `resolveIntent()` and `rePlan()`
3. Both call `planner.planPath(..., observationCounts = cachedObservationCounts)`

### ArActivity.kt Integration
```kotlin
nm.tick(cx, cz, latestHeading, HashMap(mapBuilder.grid), semanticMap,
    isTracking = lastTrackingState == TrackingState.TRACKING,
    observationCounts = mapBuilder.observationCountSnapshot())
```

### Design Rationale
- Wider corridors reduce collision risk
- Well-mapped areas enable confident navigation
- Combined scoring prevents paths through "fog of war" where mapping is uncertain

---

## Testing Checklist

- [ ] Build: `./gradlew assembleDebug`
- [ ] Tracking loss: cover camera → verify TTS + red HUD → uncover → verify recovery announcement
- [ ] Hazard warnings: walk toward wall → verify vibration at ~1.5m (WARNING) and ~0.8m (DANGER)
- [ ] Map quality: compare coverage before/after ray fan expansion
- [ ] Path safety: navigate through narrow vs. wide corridors → verify planner prefers wide passages
- [ ] Object localization: detect same object multiple times → verify position stabilizes faster than before

---

## Constants Summary

| Feature | Constant | Value | Notes |
|---------|----------|-------|-------|
| MapBuilder | DECAY_PER_REBUILD | 0.12f | base; confidence-weighted override |
| MapBuilder | RAY_MAX_DIST | 3.5m | forward rays |
| MapBuilder | RAY_BACK_MAX_DIST | 2.0m | backward rays |
| Tracking Loss | DRIFT_THRESHOLD | 0.3m | triggers rebuild |
| LocalizationSmoother | CONSISTENCY_DIST | 0.8m | max distance between positions |
| LocalizationSmoother | MIN_HITS | 2 | required consistent observations |
| LocalizationSmoother | BUFFER_WINDOW_MS | 5000L | time window for buffering |
| HazardWarning | DANGER_DIST | 0.8m | <80cm = obstacle ahead stop |
| HazardWarning | WARNING_DIST | 1.5m | <1.5m = obstacle ahead |
| HazardWarning | FORWARD_CONE_DOT | 0.866f | cos(30°) — forward direction threshold |
| HazardWarning | DANGER_TTS_COOLDOWN_MS | 2000L | minimum time between danger announcements |
| HazardWarning | WARNING_TTS_COOLDOWN_MS | 5000L | minimum time between warning announcements |
| HazardWarning | HAPTIC_COOLDOWN_MS | 1000L | minimum time between vibrations |

---

## Known Limitations & Future Work

1. **Observation Counter**: Never reset. Consider periodic decay or windowed statistics if memory becomes concern
2. **Backward Rays**: Fixed 2.0m range. Could adapt based on velocity
3. **Hazard System**: Uses current frame depth only. Could buffer over time for smoother alerts
4. **Localization Smoother**: Simple buffer-and-average. Could use Kalman filtering for better stability
5. **Path Safety**: Confidence scoring requires observation counts. Gracefully defaults to 1.0x if null

---

## Architecture Insights

**Thread Safety**
- `observationCounts` (ConcurrentHashMap) — incremented from GL thread
- `cachedObservationCounts` (@Volatile) — read from nav thread
- `hazardWarningSystem` — immutable input, independent state

**Lifecycle Management**
- Announcer: initialized once in onCreate, reused across features
- Localizationoother: initialized once, garbage collects stale buffers
- HazardSystem: initialized once, always-on

**Callback Pattern**
- Detection → LocalizationSmoother.feed() → mergeOrAdd()
- Depth hits → HazardWarningSystem.processDepthHits()
- Both are non-blocking, safe for GL thread

---

## Files Modified/Created

| File | Action | Features |
|------|--------|----------|
| MapBuilder.kt | Modified | 1, 5 |
| ArActivity.kt | Modified | 1, 2, 3, 4, 5 |
| ObjectLocalizer.kt | Modified | 3 |
| PathPlanner.kt | Modified | 5 |
| NavigationManager.kt | Modified | 2, 5 |
| SemanticObject.kt | Modified | 3 |
| AndroidManifest.xml | Modified | 4 |
| LocalizationSmoother.kt | Created | 3 |
| HazardWarningSystem.kt | Created | 4 |

---

## Next Steps for Future Sessions

1. Run full build and smoke test on device
2. Profile memory usage of `observationCounts` over long sessions
3. Tune decay thresholds (0.04, 0.08, 0.15) based on map quality observations
4. Adjust hazard alert distances based on blind user feedback
5. Consider Kalman filtering for object localization instead of simple EMA
6. Add telemetry: log when each safety feature triggers for post-session analysis

---

**Implementation completed:** March 19, 2026
**Status:** Ready for testing and integration validation
