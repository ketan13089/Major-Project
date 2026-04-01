---
name: SLAM Indoor Navigation Project Context
description: Full architecture, module breakdown, data flows, constants, and key design decisions for the Flutter+Android ARCore SLAM indoor navigation app
type: project
---

# Project: Indoor SLAM Navigation App (Flutter + Android/Kotlin)

## Overview
A mobile indoor navigation system that builds a real-time occupancy grid map using ARCore, detects objects with a custom YOLO model, reads text signs via OCR (ML Kit), and provides voice-activated turn-by-turn navigation. Designed to assist blind/visually impaired users.

**Why:** Accessibility tool for indoor navigation in buildings (corridors, rooms, facilities).

## Tech Stack
- **Flutter** (Dart): UI layer only — `lib/main.dart`, `lib/indoor_map_viewer.dart`
- **Android/Kotlin**: All heavy lifting — ARCore, YOLO, OCR, SLAM, navigation
- **ARCore**: Camera tracking, plane detection, depth hit-testing
- **TFLite**: YOLOv8 model (`indoor_nav_best_float16.tflite`, 8 classes)
- **ML Kit Text Recognition v2**: OCR for room numbers and signs
- **Android TTS**: Voice navigation output
- **Android SpeechRecognizer**: Voice command input

---

## Flutter ↔ Android Method Channels

| Channel | Direction | Method | Purpose |
|---|---|---|---|
| `com.ketan.slam/ar` | Flutter→Native | `openAR` | Launch ArActivity |
| `com.ketan.slam/ar` | Native→Flutter | `onUpdate` | Pose + object count every 300ms |
| `com.ketan.slam/ar` | Native→Flutter | `updateMap` | Full map payload every 800ms |
| `com.ketan.slam/nav` | Flutter→Native | `startVoiceNav` | Start voice command listening |
| `com.ketan.slam/nav` | Flutter→Native | `stopNavigation` | Stop active navigation |
| `com.ketan.slam/nav` | Native→Flutter | `navStateChange` | Nav state (IDLE/LISTENING/PLANNING/NAVIGATING/ARRIVED/ERROR) |
| `com.ketan.slam/nav` | Native→Flutter | `navInstruction` | Turn-by-turn instruction text |

**Map payload fields:** `occupancyGrid` (ByteArray), `gridWidth`, `gridHeight`, `gridResolution` (0.20m), `originX`, `originZ`, `robotGridX`, `robotGridZ`, `objects` (list), `navPath` (list of {x,z})

---

## Architecture — Android Modules

### ArActivity.kt (Central Orchestrator)
- Manages ARCore session with **SHARED_CAMERA** feature
- Sets up GLSurfaceView renderer + detection overlay + HUD
- Camera: 640×480 YUV_420_888 via ImageReader (shared)
- YOLO inference every 900ms; OCR every 3000ms
- Calls `updateSlam()` every GL frame, `sendToFlutter()` every 300ms, map payload every 800ms
- Constants: `RES=0.20f`, `MERGE_DIST=1.2f`, `STALE_MS=30_000L`, `REBUILD_INTERVAL_MS=2000L`

### MapBuilder.kt (Occupancy Grid)
- Log-odds grid: `ConcurrentHashMap<GridCell, Byte>` for the output, `ConcurrentHashMap<GridCell, Float>` for log-odds
- Cell types: `0=UNKNOWN, 1=FREE, 2=OBSTACLE, 3=WALL, 4=VISITED`
- Grid resolution: 0.20m per cell
- **Log-odds params:** `L_FREE=-0.3f`, `L_OCCUPIED=0.9f`, `L_MIN=-4.0f`, `L_MAX=3.5f`
- **Thresholds:** `LO_THRESH_FREE=-0.6f`, `LO_THRESH_OCC=1.2f`
- **3 update paths:**
  1. `incrementalUpdate()` — every GL frame (fast, local)
  2. `integratePlane()` — per ARCore plane snapshot
  3. `rebuild(keyframes)` — full rebuild every 2s on background thread
- **Wall detection sources:**
  1. ARCore vertical planes → `rasterisePlaneAsWall()` (Bresenham line)
  2. Depth hit-test at torso height → `markHitOccupied()` (2× L_OCCUPIED, wallHint=true)
- **Free space sources:**
  1. Ray fan (±45° forward arc, 3m range, 7 rays)
  2. ARCore horizontal planes → `rasterisePlaneAsFree()`
  3. Depth hit-test at floor level → `markHitFree()` (3× L_FREE)
- `enforceConsistency()` runs 3 passes: (1) remove isolated occupied cells, (2) fill wall gaps, (3) dilate walls 1 cell
- `wallCells: HashSet<GridCell>` tracks which cells came from vertical planes → renders as `CELL_WALL` (dark) not `CELL_OBSTACLE` (brown)
- **Key fixes (8 total):** See MapBuilder.kt class doc for full list of FIX 1–8

### SlamEngine.kt
- Lightweight: only tracks pose history trail + edge count
- Actual grid is in MapBuilder
- Max 5000 pose history entries, 2cm min movement threshold

### PoseTracker.kt
- Keyframe gating: 0.15m translation OR 0.17rad (~10°) rotation OR 200ms minimum interval
- Drift detection via ARCore Anchors placed every 5m, up to 3 anchors
- Drift threshold: 0.05m triggers grid rebuild flag

### ObservationStore.kt
- Ring buffer of max 2000 Keyframes
- Thread-safe, monotonic version counter
- Keyframes include: pose, heading, forward direction, PlaneSnapshots, ObjectSightings

### Keyframe.kt / PlaneSnapshot.kt / ObjectSighting.kt
- `Keyframe`: timestamp, poseX/Y/Z, headingRad, forwardX/Z, planes list, objectSightings list
- `PlaneSnapshot`: type (HORIZONTAL_FREE or VERTICAL_WALL), world-space polygon vertices, planeId
- `ObjectSighting`: label, confidence, worldPosition, footprintHalfMetres

### ObjectLocalizer.kt (3D Position Estimation)
- Two strategies in order:
  1. **ARCore hit-test** at bbox center (only if frame age < 67ms = 2 frames at 30fps)
  2. **Area-based fallback**: `depth = 0.5/sqrt(area)` clamped to [0.8, 6.0]m
- **CRITICAL coordinate mapping:** YOLO input = 640×480 landscape → rotate 90° CW → 480×640 → pad 80px each side → 640×640
  - `padX = (640-480)/2 = 80`
  - `normX = (bboxCX - 80) / 480`
  - `normY = bboxCY / 640`
  - Map to screen: `screenX = normX * surfaceWidth`, `screenY = normY * surfaceHeight`
- Object footprint sizes: DOOR=0.45m, LIFT_GATE=0.60m, CHAIR=0.25m, WINDOW=0.50m, etc.

### SemanticMapManager.kt
- `ConcurrentHashMap<GridCell, MutableList<SemanticObject>>` spatial grid (1m cells)
- `ConcurrentHashMap<String, SemanticObject>` by ID
- Merge radius: 1.2m same type
- Stale removal: >30s unseen AND <3 observations
- Callback `onObjectRemoved` → MapBuilder clears footprint
- Contains `ObjectRelationGraph` (internal, not exposed to user)

### SemanticObject.kt / ObjectType enum
- **YOLO classes (8):** CHAIR, DOOR, FIRE_EXTINGUISHER, LIFT_GATE, NOTICE_BOARD, TRASH_CAN, WATER_PURIFIER, WINDOW
- **OCR text landmarks (7):** EXIT_SIGN, WASHROOM_SIGN, STAIRS_SIGN, ROOM_LABEL, FACILITY_SIGN, WARNING_SIGN, TEXT_SIGN
- SemanticObject fields: id, type, category, position (Point3D), boundingBox (BoundingBox2D), confidence, firstSeen, lastSeen, observations, label, textContent, roomNumber

### YoloDetector.kt
- Model: `indoor_nav_best_float16.tflite` (assets)
- Input: 640×640 RGB float, 6 TFLite threads + NNAPI
- Confidence threshold: 0.45f; IoU (NMS): 0.45f
- YUV→RGB pipeline: rotate 90° CW + letterbox pad to 640×640
- Supports both channels-first `[1, 4+N, n]` and channels-last `[1, n, 4+N]` output formats
- DetectionConfirmationGate: requiredHits=1, windowMs=2000, minIoU=0.25f

### TextRecognizer.kt (OCR)
- Google ML Kit Text Recognition v2 (Latin)
- Input: same YUV frame → NV21 → JPEG → Bitmap, 90° rotation
- Runs synchronously via CountDownLatch (timeout 3s)
- 4 text classifications: ROOM_NUMBER, SIGN, NOTICE, GENERAL
- 6 text landmark types: EXIT_SIGN, WASHROOM_SIGN, STAIRS_SIGN, LIFT_SIGN, FACILITY_SIGN, WARNING_SIGN
- Room number pattern: `(?:room|lab|rm|class|hall|office|cabin)?\s*(\d{1,4}[A-Za-z]?)`
- Min text length: 2 chars, min confidence: 0.3f
- OCR text processing in ArActivity: links room numbers to nearby doors (<2m), links notices to notice boards

### NavigationManager.kt
- States: IDLE, LISTENING, PLANNING, NAVIGATING, ARRIVED, ERROR
- Pipeline: Voice → VoiceCommandProcessor → NavigationIntent → destination selection → PathPlanner → NavigationGuide
- `tick()` called every SLAM frame: resolves pending intents, checks arrival, checks path validity, detects deviation
- Deviation threshold: 2.0m triggers re-plan
- Instruction interval: 3000ms minimum between spoken instructions
- Waypoint trim: passed waypoints within 0.6m removed
- Destination selection: (1) room number match, (2) text content match, (3) type-based nearest/farthest

### PathPlanner.kt (A* path planning)
- Operates on `Map<GridCell, Byte>` occupancy grid
- Obstacle inflation: 2 cells = 0.40m safety margin
- 8-directional A*, octile distance heuristic
- Diagonal corner-cutting prevented
- Semantic cost modifiers: LANDMARK_TYPES get 0.8× cost, HAZARD_TYPES (chairs) get 1.5× cost
- String-pulling (greedy LOS smoothing) post-processing
- Goal cell always walkable even if it has obstacle footprint

### NavigationGuide.kt (TTS + instructions)
- Android TextToSpeech, Locale.US
- Lookahead: 1.5m ahead of user for turn direction
- Arrival threshold: 1.0m
- Turn directions: STRAIGHT, SLIGHT_LEFT/RIGHT (15-40°), LEFT/RIGHT (40-90°), SHARP_LEFT/RIGHT (90-150°), U_TURN (>150°)

### VoiceCommandProcessor.kt
- Android SpeechRecognizer, FREE_FORM language model, 3 max results
- Trigger phrases: "take me to", "go to", "navigate to", "find the", "where is", etc.
- Qualifier detection: NEAREST (default), FARTHEST, LEFT_MOST, RIGHT_MOST
- Room pattern: `(?:room|lab|class|hall|office|cabin)\s+(\d{1,4}[A-Za-z]?)`
- OCR keywords checked BEFORE YOLO keywords (priority)

### ObjectRelationGraph.kt
- Internal only — NEVER exposed to blind user
- Relations: NEAR_TO (<2m), NEXT_TO (<1m + axis-aligned), ACROSS_FROM (2-5m)
- Rebuilt on every semantic object mutation

---

## Flutter UI (Dart)

### lib/main.dart
- `HomePage`: Two feature cards (AR Camera, Indoor Map)
- `_FeatureCard` widget
- Routes: `/` → HomePage, `/map` → IndoorMapViewer
- MethodChannel `com.ketan.slam/ar` to call `openAR`

### lib/indoor_map_viewer.dart
- Full map viewer with real-time updates
- **Cell color scheme (architectural floor-plan style):**
  - UNKNOWN: map background (warm off-white `#F8F6F0`)
  - FREE (white `#FFFFFF`)
  - VISITED (light blue `#E8F0FE`)
  - WALL (near-black `#2C2C2C`)
  - OBSTACLE (brown `#B45309`, 50% opacity)
  - Nav path (green `#10B981`, 55% opacity)
  - BFS path (blue `#3B82F6`, animated opacity)
- `_MapPainter` CustomPainter: draws layers in order: bg → grid → floor → visited → nav path → BFS path → obstacles → walls → objects → robot
- BFS path: computed when user taps an object chip (Dart-side BFS, not native)
- Object rail: horizontal scrollable chips showing detected objects + confidence
- Nav button: mic icon (blue) → stop icon (red) when navigating
- Sensors: position X/Z, heading, area (m²), scanning status
- Scale: default 28.0 px/cell, range [6, 300], pinch-to-zoom
- Robot render: pulsing accuracy ring + heading arrow + blue dot

---

## Depth Hit-Test Wall Extraction (ArActivity.extractWallsFromDepth)
- 8×6 grid sample (48 points) per 300ms
- Height classification (relative to camera Y):
  - `relY < -0.5m` → floor → `markHitFree()`
  - `-0.5m ≤ relY ≤ 0.8m` → wall/torso → `markHitOccupied()`
  - `relY > 0.8m` → ceiling/ignore
- Max hit distance: 5.0m
- This is the PRIMARY source of wall data (beats plane detection for indoor vertical walls)

---

## Object Merge Logic (ArActivity.mergeOrAdd)
- Same label + distance < 1.2m → merge
- Weight for position averaging: `weight = (1/sqrt(n)).coerceIn(0.1, 0.5)`
- New position = `old * (1-weight) + new * weight`
- Takes max confidence
- New objects: id = `"${label}_${gridX}_${gridZ}"`

---

## Key File Locations
- YOLO model: `android/app/src/main/assets/indoor_nav_best_float16.tflite`
- All Kotlin: `android/app/src/main/kotlin/com/ketan/slam/`
- Flutter: `lib/`

## Important Constants Summary
| Constant | Value | Location |
|---|---|---|
| Grid resolution | 0.20m | ArActivity (RES), MapBuilder param |
| Camera size | 640×480 | ArActivity (CAM_W/H) |
| YOLO input | 640×640 | YoloDetector |
| YOLO confidence | 0.45f | YoloDetector |
| Merge radius | 1.2m | ArActivity (MERGE_DIST) |
| Stale object timeout | 30s | ArActivity (STALE_MS) |
| Map rebuild interval | 2s | ArActivity |
| Obstacle inflation | 2 cells = 0.4m | PathPlanner |
| Keyframe min translation | 0.15m | PoseTracker |
| Keyframe min rotation | 0.17rad (~10°) | PoseTracker |
| Drift rebuild threshold | 0.05m | PoseTracker |
| Anchor spacing | 5.0m | PoseTracker |
| OCR interval | 3s | ArActivity |
| YOLO interval | 0.9s | ArActivity |
| Arrival distance | 1.0m | NavigationGuide |
| Deviation re-plan | 2.0m | NavigationManager |
