# CHANGELOG — Indoor Navigation System

This document covers all architectural changes made across two sessions.
Each change explains what was wrong before, what was changed, and why.

---

## Session 1: Mapping Accuracy & Architecture Overhaul

**Commit:** `c0d8c44` — "Improve mapping accuracy: keyframe rebuilds, drift detection, fixed 3D localization"

**10 files changed, 1060 insertions, 350 deletions**

### Problem Statement

The system generated an occupancy map, but it was unreliable for navigation. Walls were misaligned, free-space was noisy, objects drifted in position, and navigation paths occasionally passed through walls. The map became increasingly inconsistent as the user explored more space.

---

### 1. NEW FILE — MapBuilder.kt (374 lines)

**What it does:**
Owns the occupancy grid (log-odds probabilistic model). Provides two modes: fast incremental per-frame updates for low latency, and full periodic rebuilds from stored keyframes for global consistency.

**What was happening before:**
All occupancy grid logic lived directly inside `ArActivity.kt` (~920 lines). Every frame, planes and rays were rasterized directly into the grid. Once a wall was placed at the wrong position due to ARCore drift, that error was permanent — no mechanism existed to correct it.

**What changed:**
- Grid logic extracted from ArActivity into a dedicated module
- **Keyframe-based rebuild**: every ~2 seconds (or on drift detection), the grid is cleared and rebuilt from all stored keyframe observations. Since ARCore's plane poses are updated when it internally corrects its map, the re-projected planes land at corrected positions — fixing drift-induced wall misalignments
- **Temporal decay**: occupied cells that aren't re-observed lose 0.05 log-odds per rebuild. This prevents permanent phantom walls from stale observations
- **Spatial consistency enforcement**: after each rebuild, a two-pass cleanup runs — (1) isolated wall cells with fewer than 2 wall neighbors are removed, (2) single-cell gaps in walls (free cell with wall on opposing sides) are filled. This fixes the ragged/Swiss-cheese wall problem
- **Log-odds tuning**: `L_MIN` changed from -2.0 to -3.0 (cells can become more strongly free), `L_MAX` changed from 3.5 to 2.5 (occupied cells don't over-commit, making them correctable when drift is detected)
- **Ray-cast range reduced** from 4.0m to 2.5m. At 4m, even small quaternion noise (±5°) swings a ray ±35cm — enough to paint free cells into actual walls. 2.5m is safer

**Why it matters:**
This is the single most impactful change. The old system burned drift errors permanently into the grid. Now the grid self-corrects every 2 seconds.

---

### 2. NEW FILE — ObservationStore.kt (40 lines)

**What it does:**
Thread-safe ring buffer holding up to 500 Keyframe snapshots. Provides versioned access so MapBuilder only rebuilds when new data arrives.

**What was happening before:**
No observation history existed. Each frame's data was consumed immediately and discarded. There was no way to replay or re-project observations.

**What changed:**
- Keyframes are appended from the GL thread on every significant pose change
- MapBuilder reads snapshots from a background thread for rebuilds
- Versioned with a monotonic counter to avoid redundant rebuilds

**Why it matters:**
Without stored observations, the system had no material to rebuild from. This is the foundation that enables drift correction.

---

### 3. NEW FILE — PoseTracker.kt (148 lines)

**What it does:**
Manages pose history, keyframe selection criteria, and reference anchors for drift detection.

**What was happening before:**
- `SlamEngine.kt` tracked pose history but only for distance/statistics
- No keyframe selection — every frame was treated equally
- No drift detection at all — the system blindly trusted every ARCore pose

**What changed:**
- **Keyframe selection**: new keyframes are captured only when the pose changes significantly (>15cm translation or >10° rotation, minimum 200ms apart). This prevents redundant data and keeps the observation store compact
- **Reference anchor management**: ARCore Anchors are placed every 5m of movement (up to 3 anchors maintained). When ARCore internally corrects its coordinate frame (e.g., recognizing a revisited area), anchors shift. The system detects this shift (>5cm threshold) and triggers an immediate grid rebuild
- **Drift detection**: `checkDrift()` compares each anchor's current pose to its creation pose. `hasDriftExceededThreshold()` returns true when any anchor has shifted >5cm

**Why it matters:**
ARCore doesn't notify you of loop closures. This is a lightweight proxy that detects when the coordinate frame has been adjusted, enabling the grid to self-correct at exactly the right moment.

---

### 4. NEW FILE — Keyframe.kt (49 lines)

**What it does:**
Data classes for keyframe-based observation storage: `Keyframe`, `PlaneSnapshot`, `PlaneType`, `ObjectSighting`.

**What was happening before:**
No concept of keyframes. Plane data was consumed and discarded immediately from ARCore's `Plane` objects.

**What changed:**
- `PlaneSnapshot` stores world-space polygon vertices so planes can be re-rasterized without needing the original ARCore Plane handle
- `Keyframe` bundles pose, heading, forward direction, plane snapshots, and object sightings at a moment in time

**Why it matters:**
Decouples observation capture from grid generation. Observations can now be replayed at corrected positions.

---

### 5. NEW FILE — ObjectLocalizer.kt (157 lines)

**What it does:**
Estimates 3D world position of detected objects using ARCore hit-test or depth fallback.

**What was happening before:**
The `estimate3D` method was embedded in `ArActivity.kt` and had a critical coordinate mapping bug. YOLO operates on a 640x640 padded image (640x480 → rotated 90° CW → padded), but the bbox coordinates were naively mapped to surface dimensions without accounting for the rotation and padding. This caused hit-tests to land on wrong pixels, systematically misplacing objects.

The depth fallback used a coarse 4-bucket system:
- area > 30% → 1.0m
- area > 10% → 2.0m
- area > 3% → 3.5m
- else → 5.0m

A chair at 2.5m and a door at 2.5m both got placed at 3.5m.

**What changed:**
- **Fixed coordinate mapping**: properly accounts for YOLO's 90° rotation (640x480 → 480x640) and 80px side padding (→ 640x640). The padX is subtracted, coordinates are normalized to the actual 480px content width, then mapped to screen space
- **Continuous depth model**: replaced 4-bucket system with `depth = 0.5 / sqrt(area)` clamped to [0.8m, 6.0m]. This gives a smooth, physically motivated estimate instead of discrete jumps
- **Extracted as standalone module**: clean separation from ArActivity

**Why it matters:**
Object positions were systematically wrong before. Every detection placed via the depth fallback was off by the difference between the bucket value and the real distance. Hit-test detections were landing on wrong pixels. Both are now fixed.

---

### 6. MODIFIED — ArActivity.kt (463 insertions, rewritten)

**What was happening before:**
ArActivity was ~920 lines handling everything: ARCore lifecycle, GL rendering, occupancy grid logic, plane rasterization, ray casting, object localization, semantic object management, navigation ticking, and Flutter bridging. This made it fragile, hard to test, and impossible to correct drift-affected grid cells.

**What changed:**
- Delegates mapping to `MapBuilder` (no more grid/logOdds fields in ArActivity)
- Delegates pose tracking to `PoseTracker`
- Delegates observations to `ObservationStore`
- Delegates 3D localization to `ObjectLocalizer`
- Captures keyframes on significant pose changes
- Manages reference anchors for drift detection
- Schedules background grid rebuilds every 2s (or immediately on drift)
- Wires up stale-object footprint cleanup via `SemanticMapManager.onObjectRemoved`
- **Drift-weighted object merging**: old code used fixed weight `a = 0.2` for position updates, meaning old (possibly drifted) positions dominated. New code uses `weight = 1/sqrt(n)` which gives recent observations more influence as the count grows, helping correct drifted positions

ArActivity is now ~450 lines of pure orchestration.

**Why it matters:**
The monolithic design was the root cause of many problems. Separating concerns means each module can be reasoned about, tested, and improved independently.

---

### 7. MODIFIED — PathPlanner.kt (46 insertions)

**What was happening before:**
- Safety inflation was only 1 cell (20cm). A walking human needs ~40cm clearance minimum
- If the user's current cell was in the inflated zone (near a wall), A* immediately failed with an empty path
- After string-pulling smoothing, the smoothed path was returned without checking if the shortcuts actually avoided obstacles. String-pulling can create diagonal shortcuts that pass through occupied cells

**What changed:**
- **Safety inflation increased** from 1 cell to 2 cells (40cm clearance — realistic for walking)
- **Start cell adjustment**: if the start cell is blocked (user standing near wall), the planner searches a 3-cell radius for the nearest walkable cell before running A*
- **Goal reachability pre-check**: verifies at least one walkable cell exists near the goal before running A*. Prevents A* from exploring the entire grid only to fail
- **Post-smoothing validation**: after string-pulling produces a smoothed path, each segment is verified via line-of-sight check. If any segment crosses a blocked cell, the raw A* path is returned instead

**Why it matters:**
Paths through walls were the most dangerous navigation failure. The combination of tighter inflation, start adjustment, and smoothing validation eliminates the common cases.

---

### 8. MODIFIED — NavigationManager.kt (10 insertions)

**What was happening before:**
The navigation system only checked for deviation (user strays >2m from path) to trigger re-planning. If the grid changed after planning (new wall detected, object moved), the path could cross newly-blocked cells and the system wouldn't notice.

**What changed:**
- **Path validity monitoring**: on every tick, each waypoint in the active path is checked against the current grid. If any waypoint now sits on a WALL or OBSTACLE cell, re-planning is triggered immediately

**Why it matters:**
The map is dynamic — new walls and obstacles appear as the user scans more area. The path must adapt.

---

### 9. MODIFIED — SemanticMapManager.kt (15 insertions)

**What was happening before:**
When stale objects were removed (unseen >30s with <3 observations), only the semantic object record was deleted. The obstacle footprint cells that were painted into the occupancy grid when the object was first detected remained permanently.

**What changed:**
- Added `onObjectRemoved` callback field
- When a stale object is cleaned up, the callback fires
- ArActivity wires this to `MapBuilder.clearObstacleFootprint()`, which resets the log-odds of cells in the object's footprint area back to unknown

**Why it matters:**
Phantom obstacles from removed objects caused path planning to route around objects that no longer existed.

---

### 10. UPDATED — ARCHITECTURE_SUMMARY.txt

Updated to reflect the new module structure, data flow, rebuild process, and all tuning constants.

---

## Session 2: OCR Text Recognition Feature

**Uncommitted changes — 10 files changed, 330 insertions**

### Problem Statement

The system could detect physical objects (doors, chairs, etc.) but couldn't read text in the environment. Room numbers, directional signs (EXIT, WASHROOM), and notice board content were invisible. Users couldn't navigate by room number ("take me to room 203") or by facility ("find the washroom").

---

### 1. NEW FILE — TextRecognizer.kt (~250 lines)

**What it does:**
On-device text recognition using Google ML Kit Text Recognition v2. Processes the same YUV camera frames as YOLO, converts to bitmap with NV21 encoding, runs ML Kit synchronously on the detection executor thread.

**Key features:**
- **Text classification**: each detected text block is classified into one of 4 categories:
  - `ROOM_NUMBER` — "203", "Room 305", "Lab 4" (matched via regex)
  - `SIGN` — "EXIT", "WASHROOM", "STAIRS" (matched against 25+ keywords)
  - `NOTICE` — longer text (>20 chars) on notice boards
  - `GENERAL` — other text
- **Room number extraction**: regex pattern `(?:room|lab|rm|class|hall|office|cabin)?\s*(\d{1,4}[A-Za-z]?)` captures room identifiers
- **Landmark type mapping**: sign keywords map to `TextLandmarkType` enum (EXIT_SIGN, WASHROOM_SIGN, STAIRS_SIGN, LIFT_SIGN, FACILITY_SIGN, WARNING_SIGN)
- **Confidence estimation**: ML Kit v2 doesn't expose per-block confidence, so a heuristic combines alphanumeric ratio (50%), text length (30%), and line count (20%)
- **YUV→Bitmap conversion**: builds NV21 byte array from separate Y/U/V planes, compresses to JPEG, decodes to Bitmap with 90° rotation for portrait orientation

**Why it matters:**
Adds an entirely new perception modality — the system can now understand text in the environment.

---

### 2. MODIFIED — build.gradle.kts (+3 lines)

**What changed:**
Added `com.google.mlkit:text-recognition:16.0.1` dependency.

**Why ML Kit:**
- Runs fully on-device (no cloud processing — matches project constraint)
- Downloads text recognition model automatically via Google Play Services
- Optimized for mobile — lightweight compared to Tesseract
- Handles multiple orientations, varying fonts, and real-world conditions
- Well-maintained by Google, compatible with ARCore's Android requirements

---

### 3. MODIFIED — SemanticObject.kt (+34 lines)

**What was happening before:**
The ObjectType enum only had 8 YOLO classes + UNKNOWN. SemanticObject had a `label` field that was unused. There was no way to store text content or room numbers.

**What changed:**
- **7 new ObjectType values**: EXIT_SIGN, WASHROOM_SIGN, STAIRS_SIGN, ROOM_LABEL, FACILITY_SIGN, WARNING_SIGN, TEXT_SIGN
- **`fromTextLandmark()` converter**: maps TextLandmarkType enum to ObjectType
- **`textContent` field** on SemanticObject: stores the actual OCR text (e.g., "LAB CLOSED AFTER 6 PM")
- **`roomNumber` field** on SemanticObject: stores extracted room number (e.g., "203")

**Why it matters:**
The data model needed to represent text-based landmarks alongside physical objects. The new types allow the navigation system to treat signs as first-class destinations.

---

### 4. MODIFIED — ArActivity.kt (+154 lines)

**What was happening before:**
Only YOLO ran on camera frames. No text was detected from the environment.

**What changed:**

**Initialization:**
- TextRecognizer created in `onCreate()`, closed in `onDestroy()`
- OCR interval set to 3000ms (vs YOLO's 900ms) to minimize CPU impact

**Detection pipeline:**
- OCR runs inside the same `detectionExecutor` as YOLO, every 3rd YOLO cycle
- After YOLO completes, if 3 seconds have elapsed, OCR runs on the same YUV frame
- Both share the same pose/frame references for 3D localization

**New method — `processTextDetection()`:**
Routes each OCR detection based on its classification:

1. **ROOM_NUMBER**: looks for a door object within 2m. If found, attaches `roomNumber` and `textContent` to the door. If no nearby door, creates a standalone ROOM_LABEL object. This is the key association logic — a "203" sign above a door gets linked to that door, so "take me to room 203" navigates to the correct door.

2. **SIGN**: creates a semantic object of the appropriate type (EXIT_SIGN, WASHROOM_SIGN, etc.) with the detected text as `textContent`.

3. **NOTICE**: looks for a nearby NOTICE_BOARD object (YOLO-detected). If found, attaches the text content. Otherwise creates a standalone TEXT_SIGN.

4. **GENERAL**: only stored if confidence >= 0.5 to avoid noise.

**New method — `mergeOrAddText()`:**
Similar to `mergeOrAdd` for YOLO detections, but uses text content and room number for identity matching instead of just label+distance. Same drift-weighted position update (`1/sqrt(n)` weighting).

**HUD update:**
Now shows OCR inference time and text landmark count.

**Map payload update:**
`textContent` and `roomNumber` are now included in the object data sent to Flutter (only when non-null, to avoid bloating payloads for YOLO-only objects).

**Why it matters:**
This is the integration point where OCR results enter the perception pipeline. The door-room association logic is the critical piece — it enables room-number-based navigation.

---

### 5. MODIFIED — VoiceCommandProcessor.kt (+39 lines)

**What was happening before:**
Voice commands could only target the 8 YOLO object types. "Take me to room 203" would fail because no keyword matched. "Find the washroom" would fail because "washroom" wasn't in the keyword map.

**What changed:**

**NavigationIntent data class:**
- Added `roomNumber: String?` — carries the extracted room number for room-based navigation
- Added `textQuery: String?` — carries a free-text search term for text-landmark matching

**parseIntent() now has 3-tier matching:**
1. **Room number detection** (checked first): regex `(?:room|lab|class|hall|office|cabin)\s+(\d{1,4}[A-Za-z]?)` captures "room 203" → NavigationIntent(ROOM_LABEL, roomNumber="203")
2. **OCR sign keywords** (checked second): new `OCR_KEYWORD_MAP` maps "washroom/toilet/restroom/bathroom" → WASHROOM_SIGN, "exit" → EXIT_SIGN, "stairs" → STAIRS_SIGN, "canteen/cafeteria/library" → FACILITY_SIGN
3. **YOLO object keywords** (original, checked last): unchanged

**Why it matters:**
Without this, voice commands like "take me to room 203" or "find the washroom" would fail with "Could not understand". Now they produce proper intents that the navigation system can resolve.

---

### 6. MODIFIED — NavigationManager.kt (+63 lines)

**What was happening before:**
`selectDestination()` only matched by ObjectType + observations >= 2. There was no way to search by room number or text content.

**What changed:**

**selectDestination() now has 3 selection modes:**

1. **Room number search** (when `intent.roomNumber != null`):
   - First searches ROOM_LABEL objects whose `roomNumber` matches
   - Then searches DOOR objects whose `roomNumber` matches (doors with linked room numbers from OCR)
   - Only needs 1 observation (not 2) since room numbers are high-confidence identifiers
   - Returns nearest match

2. **Text content search** (when `intent.textQuery != null`):
   - First searches objects matching the intent's destination type (e.g., WASHROOM_SIGN)
   - Falls back to searching all objects whose `textContent` contains the query string
   - Only needs 1 observation since OCR text is distinctive
   - Applies the qualifier (nearest/farthest/left/right)

3. **Standard type-based search** (original behavior, unchanged):
   - Filters by ObjectType + observations >= 2
   - Applies qualifier

**Extracted `applyQualifier()` helper** to avoid code duplication across the 3 modes.

**Why it matters:**
Without the 3-mode search, OCR data would be stored in the semantic map but never used for navigation. This connects voice commands to OCR-detected landmarks.

---

### 7. MODIFIED — ObjectLocalizer.kt (+8 lines)

**What was happening before:**
`footprintHalfMetres()` only had entries for the 8 YOLO object types. New OCR types would fall through to the default (0.30m), which is too large for signs that are flat on walls.

**What changed:**
Added footprint sizes for all 7 OCR types:
- EXIT_SIGN, WASHROOM_SIGN, STAIRS_SIGN, FACILITY_SIGN, WARNING_SIGN: 0.15m (signs are flat, small footprint)
- ROOM_LABEL, TEXT_SIGN: 0.10m (very small markers)

**Why it matters:**
Oversized footprints for wall-mounted signs would create unnecessary obstacles in the occupancy grid, blocking navigation paths near signs.

---

### 8. MODIFIED — PathPlanner.kt (+6 lines)

**What was happening before:**
`LANDMARK_TYPES` only included DOOR, LIFT_GATE, FIRE_EXTINGUISHER, WINDOW. The A* semantic cost modifier (0.8x, meaning "prefer paths near landmarks") didn't apply to OCR-detected signs.

**What changed:**
Added EXIT_SIGN, WASHROOM_SIGN, STAIRS_SIGN, ROOM_LABEL, FACILITY_SIGN to `LANDMARK_TYPES`.

**Why it matters:**
OCR-detected signs are stable reference points (they don't move). Making the path planner prefer routes near these landmarks improves path reliability — the system routes past known signs rather than through unmapped corridors.

---

### 9. MODIFIED — indoor_map_viewer.dart (+33 lines)

**What was happening before:**
MapObject only had fields from YOLO. The map UI couldn't display text content, room numbers, or OCR landmark types.

**What changed:**

**MapObject data class:**
- Added `textContent` (nullable String) and `roomNumber` (nullable String)
- Parsed from the updated map payload

**New `_displayLabel()` function:**
Returns the best label for display:
- If `roomNumber` is set: "Room 203"
- If `textContent` is set and short (<= 20 chars): the text itself ("EXIT", "WASHROOM")
- Otherwise: the type label with underscores replaced ("exit sign")

Used in both the map painter (object labels on canvas) and the object rail (scrollable chips at bottom).

**`_typeColor()` — 7 new entries:**
- EXIT_SIGN: red (0xFFDC2626) — emergency
- WASHROOM_SIGN: purple (0xFF7C3AED) — facility
- STAIRS_SIGN: amber (0xFFD97706) — navigation
- ROOM_LABEL: emerald (0xFF059669) — identification
- FACILITY_SIGN: blue (0xFF2563EB) — facility
- WARNING_SIGN: orange (0xFFEA580C) — caution
- TEXT_SIGN: gray (0xFF6B7280) — generic

**`_emoji()` — 7 new entries:**
- EXIT_SIGN: 🚪, WASHROOM_SIGN: 🚻, STAIRS_SIGN: 🪜
- ROOM_LABEL: 🔢, FACILITY_SIGN: 🏢, WARNING_SIGN: ⚠️, TEXT_SIGN: 📝

**Why it matters:**
Without UI support, OCR landmarks would appear as blank gray circles with no label. Now they're visually distinct and informative.

---

## Summary of All Changes

### Session 1 — Mapping Overhaul
| Area | Before | After |
|------|--------|-------|
| Grid management | Monolithic in ArActivity, fire-and-forget | MapBuilder with keyframe rebuilds every 2s |
| Drift handling | None — errors permanent | Reference anchors detect drift, trigger rebuild |
| Observation storage | Discarded per-frame | 500-keyframe ring buffer for replay |
| Wall quality | Ragged, gaps, phantom walls | Consistency enforcement (isolated removal, gap fill) |
| Stale cells | Permanent phantom walls | Temporal decay (0.05 log-odds/rebuild) |
| Object footprints | Permanent after removal | Cleaned up via onObjectRemoved callback |
| 3D localization | Wrong coordinate mapping, 4-bucket depth | Fixed rotation/padding mapping, continuous depth |
| Path safety | 20cm margin, no start adjustment | 40cm margin, start/goal pre-checks, smoothing validation |
| Path monitoring | Only deviation check | Also checks if path crosses new obstacles |
| Object merging | Fixed weight (0.2) | Drift-weighted (1/sqrt(n)) |
| Ray-cast range | 4.0m (false positives) | 2.5m (safer) |
| ArActivity size | ~920 lines | ~450 lines (rest extracted) |

### Session 2 — OCR Feature
| Area | Before | After |
|------|--------|-------|
| Text detection | None | ML Kit v2 OCR every 3s |
| Room numbers | Not possible | Extracted from text, linked to nearby doors |
| Sign recognition | Not possible | EXIT, WASHROOM, STAIRS, etc. as landmarks |
| Notice boards | YOLO detection only | OCR reads and stores text content |
| Voice commands | 8 object types only | Room numbers + sign keywords + original types |
| Navigation search | Type-based only | Room number, text content, and type-based |
| Map display | 8 object types | 15 types with text labels and distinct icons |

### New Files Created (6 total)
1. `MapBuilder.kt` — occupancy grid engine
2. `ObservationStore.kt` — keyframe ring buffer
3. `PoseTracker.kt` — keyframe selection + drift detection
4. `Keyframe.kt` — observation data classes
5. `ObjectLocalizer.kt` — 3D position estimation
6. `TextRecognizer.kt` — ML Kit OCR wrapper

### Files Modified (9 total)
1. `ArActivity.kt` — rewritten as thin orchestrator + OCR integration
2. `PathPlanner.kt` — safety inflation, pre-checks, smoothing validation, OCR landmarks
3. `NavigationManager.kt` — path validity monitoring, 3-mode destination search
4. `SemanticMapManager.kt` — stale object footprint cleanup callback
5. `SemanticObject.kt` — 7 new OCR types, textContent/roomNumber fields
6. `VoiceCommandProcessor.kt` — room number + sign keyword intents
7. `ObjectLocalizer.kt` — fixed coordinate mapping, OCR footprint sizes
8. `indoor_map_viewer.dart` — OCR types UI, display labels, new colors/emojis
9. `build.gradle.kts` — ML Kit dependency
