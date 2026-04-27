import 'dart:math' as math;
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'accessibility_service.dart';
import 'wcag_theme.dart';

// ─────────────────────────────────────────────────────────────────────────────
// Cell constants — must match MapBuilder.kt
// ─────────────────────────────────────────────────────────────────────────────
const int cellUnknown  = 0;
const int cellFree     = 1;
const int cellObstacle = 2;
const int cellWall     = 3;
const int cellVisited  = 4;

// ─────────────────────────────────────────────────────────────────────────────
// Map painter palette — decorative cell colors only. NOT subject to WCAG
// text-contrast rules. UI chrome (top bar, chips, banners, etc.) pulls from
// [WcagPalette.of(context)] instead.
// ─────────────────────────────────────────────────────────────────────────────
class _T {
  // Architectural map colours (used only inside _MapPainter)
  static const mapBg       = Color(0xFFF8F6F0);
  static const mapGrid     = Color(0xFFD9D6CE);
  static const mapFloor    = Color(0xFFFFFFFF);
  static const mapVisited  = Color(0xFF60A5FA);
  static const mapWall     = Color(0xFF1F1F1F);
  static const mapObstacle = Color(0xFFB45309);
  static const mapPath     = Color(0xFF1D4ED8);
  static const mapNavPath  = Color(0xFF15803D);
  static const mapTrail    = Color(0xFF0E7490);
  static const mapRobot    = Color(0xFF1D4ED8);
}

// ─────────────────────────────────────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────────────────────────────────────
class MapObject {
  final String id, label, type;
  final double confidence, x, y, z;
  final int gridX, gridZ, observations;
  final String? textContent, roomNumber;
  const MapObject({
    required this.id, required this.label, required this.type,
    required this.confidence, required this.x, required this.y, required this.z,
    required this.gridX, required this.gridZ, required this.observations,
    this.textContent, this.roomNumber,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// BFS shortest path
// ─────────────────────────────────────────────────────────────────────────────
Set<int> _bfsPath(Uint8List grid, int w, int h, int sx, int sz, int gx, int gz) {
  if (w == 0 || h == 0) return {};
  idx(int x, int z) => z * w + x;
  bool walkable(int x, int z) {
    if (x < 0 || x >= w || z < 0 || z >= h) return false;
    final v = grid[idx(x, z)];
    return v == cellFree || v == cellVisited;
  }
  final visited = <int, int>{};
  final queue   = <List<int>>[];
  final start   = idx(sx.clamp(0, w - 1), sz.clamp(0, h - 1));
  queue.add([sx.clamp(0, w - 1), sz.clamp(0, h - 1)]);
  visited[start] = -1;
  const dx = [1, -1, 0, 0, 1, 1, -1, -1];
  const dz = [0, 0, 1, -1, 1, -1, 1, -1];
  int? found;
  while (queue.isNotEmpty) {
    final cur = queue.removeAt(0);
    final cx = cur[0]; final cz = cur[1];
    if (cx == gx.clamp(0, w - 1) && cz == gz.clamp(0, h - 1)) {
      found = idx(cx, cz); break;
    }
    for (int d = 0; d < 8; d++) {
      final nx = cx + dx[d]; final nz = cz + dz[d];
      if (!walkable(nx, nz)) continue;
      final nid = idx(nx, nz);
      if (!visited.containsKey(nid)) {
        visited[nid] = idx(cx, cz);
        queue.add([nx, nz]);
      }
    }
  }
  if (found == null) return {};
  final path = <int>{};
  int cur = found;
  while (cur != -1) { path.add(cur); cur = visited[cur] ?? -1; }
  return path;
}

// ─────────────────────────────────────────────────────────────────────────────
// Root widget
// ─────────────────────────────────────────────────────────────────────────────
class IndoorMapViewer extends StatefulWidget {
  final String? savedMapName;
  const IndoorMapViewer({Key? key, this.savedMapName}) : super(key: key);
  @override
  State<IndoorMapViewer> createState() => _IndoorMapViewerState();
}

class _IndoorMapViewerState extends State<IndoorMapViewer>
    with TickerProviderStateMixin, VolumeButtonNavigationMixin {
  static const _ch    = MethodChannel('com.ketan.slam/ar');
  static const _navCh = MethodChannel('com.ketan.slam/nav');
  static const _mapStoreCh = MethodChannel('com.ketan.slam/map_store');

  final _accessibility = AccessibilityService();

  // ── Data ──────────────────────────────────────────────────────────────────
  Uint8List? grid;
  int gridW = 0, gridH = 0;
  double gridRes = 0.20;
  int originX = 0, originZ = 0;
  int robotGX = 0, robotGZ = 0;
  List<MapObject> objects = [];
  List<({int x, int z})> cameraTrail = [];
  double posX = 0, posZ = 0, heading = 0;
  double compassBearing = 0;   // true-north bearing in degrees from device sensors
  int totalObjects = 0;
  bool scanning = false;
  String? _lastSavedMap;

  // ── View ──────────────────────────────────────────────────────────────────
  double scale = 28.0;
  Offset pan   = Offset.zero;
  double _scaleStart = 28.0;
  int? _selObj;
  bool _showLegend = false;

  // ── Navigation ────────────────────────────────────────────────────────────
  String _navState       = 'IDLE';
  String _navMessage     = '';
  String _navInstruction = '';
  Set<int> _navPathCells = {};

  // ── Cached computations (avoid doing work inside build()) ────────────────
  double _cachedAreaSqM = 0;
  Set<int> _cachedPathCells = {};
  int? _cachedPathSelObj;
  int _cachedPathRobotGX = -1;
  int _cachedPathRobotGZ = -1;

  // ── Animations ────────────────────────────────────────────────────────────
  late AnimationController _pulseCtrl;

  bool get _isReadOnly => widget.savedMapName != null;

  @override
  void initState() {
    super.initState();
    if (!_isReadOnly) {
      _ch.setMethodCallHandler(_onCall);
      _navCh.setMethodCallHandler(_onNavCall);
    }
    _pulseCtrl = AnimationController(
        vsync: this, duration: const Duration(seconds: 2));
    if (_isReadOnly) {
      _loadSavedMap(widget.savedMapName!);
    } else {
      _loadLastMap();
    }

    // Register accessibility focusables after first frame
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _registerFocusables();
      _accessibility.announceScreen('Map Viewer');
    });
  }

  void _registerFocusables() {
    final focusables = <FocusableElement>[
      FocusableElement(
        id: 'back',
        label: 'Go back',
        hint: 'Return to home screen',
        onActivate: () => Navigator.pop(context),
      ),
      if (!_isReadOnly) FocusableElement(
        id: 'start_scan',
        label: scanning ? 'Scanning in progress' : 'Start AR Scan',
        hint: scanning ? 'AR camera is currently scanning' : 'Open AR camera to scan the environment',
        onActivate: _openAR,
      ),
      if (!_isReadOnly) FocusableElement(
        id: 'voice_nav',
        label: _navState == 'NAVIGATING' ? 'Stop navigation' : 'Start voice navigation',
        hint: _navState == 'NAVIGATING' 
            ? 'Tap to stop current navigation'
            : 'Say where you want to go, like: take me to the nearest door',
        onActivate: _onNavButtonTap,
      ),
      FocusableElement(
        id: 'position_info',
        label: 'Current position: ${posX.toStringAsFixed(1)} by ${posZ.toStringAsFixed(1)} meters. Facing ${_compassDirection()}. Area mapped: ${_areaSqM.toStringAsFixed(1)} square meters.',
        hint: 'Your current location and compass direction',
        type: FocusableElementType.header,
      ),
    ];

    // Add detected objects as focusables
    for (int i = 0; i < objects.length; i++) {
      final o = objects[i];
      focusables.add(FocusableElement(
        id: 'object_$i',
        label: '${_displayLabel(o)}, ${(o.confidence * 100).toStringAsFixed(0)} percent confidence',
        hint: 'Tap to show path to this object',
        onActivate: () => _selectObject(i),
      ));
    }

    if (_navInstruction.isNotEmpty) {
      focusables.insert(3, FocusableElement(
        id: 'nav_instruction',
        label: 'Navigation: $_navInstruction',
        hint: 'Current navigation instruction',
        type: FocusableElementType.header,
      ));
    }

    _accessibility.registerFocusables(focusables, onFocusChanged: () => setState(() {}));
  }

  void _selectObject(int index) {
    setState(() {
      _selObj = _selObj == index ? null : index;
      _cachedPathCells = _recomputePath(grid, gridW, gridH, robotGX, robotGZ, _selObj, objects);
    });
    if (_selObj != null && _selObj! < objects.length) {
      final o = objects[_selObj!];
      _accessibility.speak('Selected ${_displayLabel(o)}. Showing path.');
    } else {
      _accessibility.speak('Selection cleared.');
    }
  }

  Future<void> _loadLastMap() async {
    try {
      final maps = await _mapStoreCh.invokeMethod('listMaps') as List?;
      if (maps == null || maps.isEmpty) return;
      // Prefer 'last_session' if it exists, otherwise take the most recent
      final name = maps.contains('last_session') ? 'last_session' : maps.first as String;
      final payload = await _mapStoreCh.invokeMethod('loadMapPayload', {'name': name});
      if (payload is Map && mounted) {
        _handleMap(payload);
        _lastSavedMap = name;
        debugPrint('Loaded saved map: $name');
      }
    } catch (e) { debugPrint('Load map: $e'); }
  }

  Future<void> _loadSavedMap(String name) async {
    try {
      final payload = await _mapStoreCh.invokeMethod('loadMapPayload', {'name': name});
      if (payload is Map && mounted) {
        _handleMap(payload);
        debugPrint('Loaded saved map: $name');
      }
    } catch (e) { debugPrint('Load saved map: $e'); }
  }

  @override
  void dispose() {
    if (!_isReadOnly) {
      _ch.setMethodCallHandler(null);
      _navCh.setMethodCallHandler(null);
    }
    _accessibility.clearFocusables();
    _pulseCtrl.dispose();
    super.dispose();
  }

  Future<void> _onCall(MethodCall call) async {
    if (!mounted) return;
    try {
      switch (call.method) {
        case 'onUpdate':
          final a = call.arguments as Map;
          setState(() {
            posX            = (a['position_x']      as num?)?.toDouble() ?? posX;
            posZ            = (a['position_z']      as num?)?.toDouble() ?? posZ;
            heading         = (a['heading']         as num?)?.toDouble() ?? heading;
            compassBearing  = (a['compass_bearing'] as num?)?.toDouble() ?? compassBearing;
            totalObjects    = (a['total_objects']    as num?)?.toInt()    ?? totalObjects;
            if (!scanning) {
              scanning = true;
              _pulseCtrl.repeat();
            }
          });
          break;
        case 'updateMap':
          _handleMap(call.arguments as Map);
          break;
        case 'onARClosed':
          // AR activity closed, resume Flutter accessibility
          _accessibility.resume();
          if (mounted) {
            setState(() {
              scanning = false;
              _pulseCtrl.stop();
              _pulseCtrl.reset();
            });
            _accessibility.speak('AR camera closed. Returned to map viewer.');
          }
          break;
      }
    } catch (e) { debugPrint('onCall error: $e'); }
  }

  void _handleMap(Map args) {
    // FIX: guard against setState after dispose
    if (!mounted) return;
    try {
      Uint8List? ng;
      final raw = args['occupancyGrid'];
      if (raw is Uint8List) ng = raw;
      else if (raw is List) ng = Uint8List.fromList(raw.cast<int>());

      final newW   = (args['gridWidth']      as num?)?.toInt()    ?? 0;
      final newH   = (args['gridHeight']     as num?)?.toInt()    ?? 0;
      final newRes = (args['gridResolution'] as num?)?.toDouble() ?? gridRes;
      final newOX  = (args['originX']        as num?)?.toInt()    ?? 0;
      final newOZ  = (args['originZ']        as num?)?.toInt()    ?? 0;
      final newRGX = (args['robotGridX']     as num?)?.toInt()    ?? 0;
      final newRGZ = (args['robotGridZ']     as num?)?.toInt()    ?? 0;

      List<MapObject> newObjs = [];
      final rawObj = args['objects'];
      if (rawObj is List) {
        for (final o in rawObj) {
          if (o is! Map) continue;
          newObjs.add(MapObject(
            id:           o['id']?.toString()    ?? '',
            label:        o['label']?.toString() ?? '',
            type:         o['type']?.toString()  ?? '',
            confidence:   (o['confidence']   as num?)?.toDouble() ?? 0,
            x:            (o['x']            as num?)?.toDouble() ?? 0,
            y:            (o['y']            as num?)?.toDouble() ?? 0,
            z:            (o['z']            as num?)?.toDouble() ?? 0,
            gridX:        (o['gridX']        as num?)?.toInt()    ?? 0,
            gridZ:        (o['gridZ']        as num?)?.toInt()    ?? 0,
            observations: (o['observations'] as num?)?.toInt()    ?? 0,
            textContent:  o['textContent']?.toString(),
            roomNumber:   o['roomNumber']?.toString(),
          ));
        }
      }

      // Compute area outside setState to avoid blocking UI
      double area = 0;
      if (ng != null && newW > 0) {
        int free = 0;
        for (final v in ng!) { if (v == cellFree || v == cellVisited) free++; }
        area = free * newRes * newRes;
      }

      // Only recompute BFS when robot moved or selection changed
      final robotMoved = (newRGX - _cachedPathRobotGX).abs() > 1 ||
                         (newRGZ - _cachedPathRobotGZ).abs() > 1;
      final needBfs = robotMoved || _cachedPathSelObj != _selObj;

      // Track if objects changed to avoid expensive focusable re-registration
      final oldObjectCount = objects.length;

      final newTrail = _parseTrail(args['cameraTrail']);

      setState(() {
        grid = ng; gridW = newW; gridH = newH; gridRes = newRes;
        originX = newOX; originZ = newOZ;
        robotGX = newRGX; robotGZ = newRGZ;
        objects = newObjs; totalObjects = newObjs.length;
        cameraTrail = newTrail;
        _navPathCells = _parseNavPath(args['navPath'], newW, newH);
        _cachedAreaSqM = area;
        if (needBfs) {
          _cachedPathRobotGX = newRGX;
          _cachedPathRobotGZ = newRGZ;
          _cachedPathSelObj = _selObj;
          _cachedPathCells = _recomputePath(ng, newW, newH, newRGX, newRGZ, _selObj, newObjs);
        }
      });

      // Only re-register focusables when object count changes (not every frame)
      if (newObjs.length != oldObjectCount) {
        _registerFocusables();
      }

    } catch (e, st) { debugPrint('map error: $e\n$st'); }
  }

  /// Recomputes BFS path from robot to selected object.
  /// Called only when map data, robot position, or selection changes —
  /// never inside build() itself.
  Set<int> _recomputePath(Uint8List? g, int w, int h, int rgx, int rgz,
      int? selObj, List<MapObject> objs) {
    if (g == null || w == 0 || selObj == null || selObj >= objs.length) return {};
    final o = objs[selObj];
    return _bfsPath(g, w, h, rgx, rgz, o.gridX, o.gridZ);
  }

  Set<int> _parseNavPath(dynamic raw, int w, int h) {
    if (raw is! List || w == 0) return {};
    final out = <int>{};
    for (final e in raw) {
      if (e is! Map) continue;
      final nx = (e['x'] as num?)?.toInt() ?? -1;
      final nz = (e['z'] as num?)?.toInt() ?? -1;
      if (nx >= 0 && nx < w && nz >= 0 && nz < h) out.add(nz * w + nx);
    }
    return out;
  }

  List<({int x, int z})> _parseTrail(dynamic raw) {
    if (raw is! List) return [];
    final out = <({int x, int z})>[];
    for (final e in raw) {
      if (e is! Map) continue;
      final nx = (e['x'] as num?)?.toInt();
      final nz = (e['z'] as num?)?.toInt();
      if (nx != null && nz != null) out.add((x: nx, z: nz));
    }
    return out;
  }

  Future<void> _openAR() async {
    try {
      _accessibility.speak('Opening AR camera for scanning');
      // Pause Flutter accessibility - AR has its own TTS
      _accessibility.pause();
      await _ch.invokeMethod('openAR');
      if (mounted) setState(() {
        scanning = true;
        _pulseCtrl.repeat();
      });
    } catch (_) {}
  }

  Future<void> _onNavCall(MethodCall call) async {
    if (!mounted) return;
    try {
      switch (call.method) {
        case 'navStateChange':
          final a = call.arguments as Map;
          final newState = a['state'] as String? ?? _navState;
          final newMessage = a['message'] as String? ?? _navMessage;
          
          // Announce state changes
          if (newState != _navState) {
            _announceNavState(newState, newMessage);
          }
          
          setState(() {
            _navState   = newState;
            _navMessage = newMessage;
            if (_navState == 'IDLE' || _navState == 'ARRIVED') _navInstruction = '';
          });
          _registerFocusables(); // Update focusables with new nav state
          break;
        case 'navInstruction':
          final a = call.arguments as Map;
          final text = a['text'] as String? ?? _navInstruction;
          setState(() {
            _navInstruction = text;
          });
          // Navigation instructions are already spoken by native TTS, no need to duplicate
          break;
      }
    } catch (e) { debugPrint('onNavCall error: $e'); }
  }

  void _announceNavState(String state, String message) {
    switch (state) {
      case 'LISTENING':
        _accessibility.speak('Listening for your command. Say where you want to go.');
        break;
      case 'PLANNING':
        _accessibility.speak('Planning route. $message');
        break;
      case 'NAVIGATING':
        _accessibility.speak('Navigation started. $message');
        break;
      case 'ARRIVED':
        _accessibility.speak('You have arrived at your destination.');
        _accessibility.hapticConfirm();
        break;
      case 'ERROR':
        _accessibility.speak('Navigation error. $message');
        _accessibility.hapticError();
        break;
      case 'IDLE':
        if (_navState == 'NAVIGATING') {
          _accessibility.speak('Navigation stopped.');
        }
        break;
    }
  }

  Future<void> _onNavButtonTap() async {
    try {
      if (_navState == 'NAVIGATING') {
        _accessibility.speak('Stopping navigation');
        await _navCh.invokeMethod('stopNavigation');
      } else {
        _accessibility.speak('Starting voice navigation');
        await _navCh.invokeMethod('startVoiceNav');
      }
    } catch (_) {}
  }

  Color _navStateColor(WcagPalette p) {
    switch (_navState) {
      case 'LISTENING':  return const Color(0xFF6D28D9); // 4.85:1 on white
      case 'PLANNING':   return p.accentPrimary;
      case 'NAVIGATING': return p.accentPrimary;
      case 'ARRIVED':    return p.accentSuccess;
      case 'ERROR':      return p.accentDanger;
      default:           return p.textSecondary;
    }
  }

  double get _areaSqM => _cachedAreaSqM;

  String _compassDirection() {
    if (compassBearing == 0 && !scanning) return 'unknown';
    final d = (compassBearing.round() % 360 + 360) % 360;
    if (d < 23)  return 'North';
    if (d < 68)  return 'Northeast';
    if (d < 113) return 'East';
    if (d < 158) return 'Southeast';
    if (d < 203) return 'South';
    if (d < 248) return 'Southwest';
    if (d < 293) return 'West';
    if (d < 338) return 'Northwest';
    return 'North';
  }

  // ─────────────────────────────────────────────────────────────────────────
  @override
  Widget build(BuildContext context) {
    final p = WcagPalette.of(context);
    return Scaffold(
      backgroundColor: p.background,
      body: WcagScaffoldFrame(
        child: SafeArea(
          child: Stack(children: [
            Column(children: [
              _topBar(p),
              Expanded(child: _mapArea(p)),
              if (objects.isNotEmpty) _objectRail(p),
              if (!_isReadOnly) _bottomBar(p),
            ]),
            if (_showLegend) _legendSheet(p),
          ]),
        ),
      ),
    );
  }

  // ── Top bar ───────────────────────────────────────────────────────────────
  Widget _topBar(WcagPalette p) {
    return Container(
      color: p.surface,
      padding: const EdgeInsets.fromLTRB(8, 0, 4, 0),
      child: Column(children: [
        if (_isReadOnly)
          SizedBox(
            height: WcagSize.minTouch + 8,
            child: Row(children: [
              IconButton(
                iconSize: 28,
                tooltip: 'Go back',
                icon: Icon(Icons.arrow_back_rounded, color: p.textPrimary),
                onPressed: () => Navigator.pop(context),
              ),
              const SizedBox(width: 4),
              Expanded(
                child: WcagText(
                  widget.savedMapName!.replaceAll('_', ' '),
                  size: WcagType.label,
                  weight: WcagType.semibold,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 10, vertical: 6),
                decoration: BoxDecoration(
                  color: p.accentWarning,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: WcagText('Saved',
                    size: WcagType.caption,
                    weight: WcagType.bold,
                    color: p.textOnAccent),
              ),
              const SizedBox(width: 4),
              _tinyBtn(p, Icons.info_outline_rounded,
                  () => setState(() => _showLegend = !_showLegend),
                  active: _showLegend, tooltip: 'Toggle legend'),
              _tinyBtn(p, Icons.crop_free_rounded,
                  () => setState(() { pan = Offset.zero; scale = 28; }),
                  tooltip: 'Reset zoom'),
            ]),
          ),
        if (!_isReadOnly)
        SizedBox(
          height: WcagSize.minTouch + 8,
          child: Row(children: [
            Container(
              padding: const EdgeInsets.symmetric(
                  horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: scanning
                    ? p.accentSuccess.withOpacity(0.15)
                    : p.surfaceRecessed,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(
                  color: scanning ? p.accentSuccess : p.border,
                  width: scanning ? 1.5 : 1,
                ),
              ),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                AnimatedBuilder(
                  animation: _pulseCtrl,
                  builder: (_, __) {
                    final t = _pulseCtrl.value;
                    final pulse = t < 0.5 ? t * 2 : 2 - t * 2;
                    return Container(
                      width: 10, height: 10,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: scanning
                            ? p.accentSuccess.withOpacity(0.4 + 0.6 * pulse)
                            : p.textTertiary,
                      ),
                    );
                  },
                ),
                const SizedBox(width: 8),
                WcagText(
                  scanning ? 'Scanning' : 'Idle',
                  size: WcagType.caption,
                  weight: WcagType.bold,
                  color: scanning ? p.accentSuccess : p.textSecondary,
                ),
              ]),
            ),
            const SizedBox(width: 8),
            _inlineStatChip(p,
              '${posX.toStringAsFixed(1)}, ${posZ.toStringAsFixed(1)} m',
              Icons.navigation_rounded, p.accentPrimary,
            ),
            const SizedBox(width: 6),
            _inlineStatChip(p,
              '${_areaSqM.toStringAsFixed(1)} m²',
              Icons.square_foot_rounded, p.accentWarning,
            ),
            const Spacer(),
            _tinyBtn(p, Icons.info_outline_rounded,
                () => setState(() => _showLegend = !_showLegend),
                active: _showLegend, tooltip: 'Toggle legend'),
            _tinyBtn(p, Icons.crop_free_rounded,
                () => setState(() { pan = Offset.zero; scale = 28; }),
                tooltip: 'Reset zoom'),
          ]),
        ),
        Divider(height: 1, color: p.border),
        if (!_isReadOnly && _navState != 'IDLE')
          Semantics(
            liveRegion: true,
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(
                  horizontal: 16, vertical: 10),
              color: _navStateColor(p).withOpacity(0.12),
              child: Row(children: [
                Icon(Icons.assistant_navigation,
                    size: 18, color: _navStateColor(p)),
                const SizedBox(width: 8),
                Expanded(
                  child: WcagText(
                    _navMessage,
                    size: WcagType.caption,
                    weight: WcagType.semibold,
                    color: _navStateColor(p),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ]),
            ),
          ),
      ]),
    );
  }

  Widget _inlineStatChip(WcagPalette p, String value, IconData icon, Color iconColor) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: p.surfaceRecessed,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: p.border),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 16, color: iconColor),
        const SizedBox(width: 6),
        WcagText(value,
            size: WcagType.caption, weight: WcagType.semibold),
      ]),
    );
  }

  Widget _tinyBtn(WcagPalette p, IconData icon, VoidCallback cb,
      {bool active = false, String? tooltip}) {
    return Tooltip(
      message: tooltip ?? '',
      child: Material(
        color: active ? p.accentPrimary.withOpacity(0.12) : Colors.transparent,
        borderRadius: BorderRadius.circular(10),
        child: InkWell(
          onTap: cb,
          borderRadius: BorderRadius.circular(10),
          child: Container(
            width: WcagSize.minTouch,
            height: WcagSize.minTouch,
            margin: const EdgeInsets.only(left: 2),
            child: Icon(icon,
                size: 24,
                color: active ? p.accentPrimary : p.textSecondary),
          ),
        ),
      ),
    );
  }

  // ── Map canvas ────────────────────────────────────────────────────────────
  Widget _mapArea(WcagPalette p) {
    final pathCells = _cachedPathCells;

    return Container(
      color: _T.mapBg,
      child: GestureDetector(
        onScaleStart:  (d) => _scaleStart = scale,
        onScaleUpdate: (d) => setState(() {
          pan += d.focalPointDelta;
          if (d.scale != 1.0) scale = (_scaleStart * d.scale).clamp(6.0, 300.0);
        }),
        child: Stack(children: [
          Semantics(
            label: 'Indoor map showing ${objects.length} detected objects. '
                'Pinch to zoom, drag to pan.',
            excludeSemantics: true,
            child: RepaintBoundary(
              child: CustomPaint(
                painter: _MapPainter(
                  grid: grid, gridW: gridW, gridH: gridH, gridRes: gridRes,
                  objects: objects, pathCells: pathCells,
                  navPathCells: _navPathCells,
                  cameraTrail: cameraTrail,
                  selectedObj: _selObj,
                  robotGX: robotGX, robotGZ: robotGZ, heading: heading,
                  compassBearing: compassBearing,
                  scale: scale, pan: pan,
                  scanning: scanning,
                ),
                child: const SizedBox.expand(),
              ),
            ),
          ),
          // Empty state
          if (grid == null || gridW == 0)
            Center(
              child: Container(
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  color: p.surface,
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: p.border, width: 1.5),
                ),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.map_outlined, size: 56, color: p.textTertiary),
                  const SizedBox(height: 14),
                  WcagText('No map yet',
                      size: WcagType.headline,
                      weight: WcagType.semibold),
                  const SizedBox(height: 6),
                  WcagText('Start a scan to build the floor map',
                      size: WcagType.body, emphasis: 'secondary'),
                ]),
              ),
            ),
          // Zoom controls
          Positioned(
            right: 12, top: 12,
            child: Column(children: [
              _mapBtn(p, Icons.add_rounded,
                  () => setState(() => scale = (scale + 6).clamp(6.0, 300.0)),
                  tooltip: 'Zoom in'),
              const SizedBox(height: 6),
              _mapBtn(p, Icons.remove_rounded,
                  () => setState(() => scale = (scale - 6).clamp(6.0, 300.0)),
                  tooltip: 'Zoom out'),
              const SizedBox(height: 10),
              _mapBtn(p, Icons.my_location_rounded,
                  () => setState(() => pan = Offset.zero),
                  tooltip: 'Center on me'),
            ]),
          ),
          // Scale bar bottom-left
          Positioned(
            left: 12, bottom: 12,
            child: Container(
              padding: const EdgeInsets.symmetric(
                  horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: p.surface,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: p.borderStrong),
              ),
              child: WcagText(
                '${(5 * gridRes).toStringAsFixed(1)} m / 5 cells',
                size: WcagType.caption,
                emphasis: 'secondary',
                weight: WcagType.medium,
              ),
            ),
          ),
          // Object count badge
          if (totalObjects > 0)
            Positioned(
              left: 12, top: 12,
              child: Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 10, vertical: 8),
                decoration: BoxDecoration(
                  color: p.surface,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: p.borderStrong),
                ),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.category_rounded,
                      size: 16, color: p.accentPrimary),
                  const SizedBox(width: 6),
                  WcagText(
                    '$totalObjects object${totalObjects > 1 ? 's' : ''} found',
                    size: WcagType.caption,
                    weight: WcagType.semibold,
                  ),
                ]),
              ),
            ),
          // Nav instruction banner — high-contrast & live region for TTS
          if (!_isReadOnly && _navInstruction.isNotEmpty)
            Positioned(
              bottom: 110, left: 12, right: 76,
              child: Semantics(
                liveRegion: true,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 14, vertical: 12),
                  decoration: BoxDecoration(
                    color: p.accentPrimary,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                        color: p.textOnAccent, width: 1.5),
                  ),
                  child: Row(children: [
                    Icon(Icons.assistant_navigation,
                        size: 22, color: p.textOnAccent),
                    const SizedBox(width: 10),
                    Expanded(
                      child: WcagText(_navInstruction,
                          size: WcagType.body,
                          weight: WcagType.bold,
                          color: p.textOnAccent),
                    ),
                  ]),
                ),
              ),
            ),
          // Voice nav button — large 64dp circle, well above the FAB minimum
          if (!_isReadOnly) Positioned(
            right: 12,
            bottom: _navInstruction.isNotEmpty ? 130 : 80,
            child: Semantics(
              button: true,
              label: _navState == 'NAVIGATING'
                  ? 'Stop navigation'
                  : _navState == 'LISTENING'
                      ? 'Listening for voice command'
                      : 'Start voice command. Say things like: take me to the nearest door.',
              excludeSemantics: true,
              child: Material(
                color: _navState == 'NAVIGATING'
                    ? p.accentDanger
                    : p.accentPrimary,
                shape: const CircleBorder(),
                elevation: 4,
                child: InkWell(
                  customBorder: const CircleBorder(),
                  onTap: _onNavButtonTap,
                  child: Container(
                    width: 64,
                    height: 64,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(
                          color: p.textOnAccent, width: 2),
                    ),
                    child: Icon(
                      _navState == 'LISTENING'
                          ? Icons.mic_none_rounded
                          : _navState == 'NAVIGATING'
                              ? Icons.stop_rounded
                              : Icons.mic_rounded,
                      color: p.textOnAccent,
                      size: 30,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ]),
      ),
    );
  }

  Widget _mapBtn(WcagPalette p, IconData icon, VoidCallback cb,
      {String? tooltip}) {
    return Tooltip(
      message: tooltip ?? '',
      child: Material(
        color: p.surface,
        borderRadius: BorderRadius.circular(10),
        elevation: 1,
        child: InkWell(
          onTap: cb,
          borderRadius: BorderRadius.circular(10),
          child: Container(
            width: WcagSize.minTouch,
            height: WcagSize.minTouch,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: p.borderStrong),
            ),
            child: Icon(icon, size: 24, color: p.textSecondary),
          ),
        ),
      ),
    );
  }

  // ── Object rail ───────────────────────────────────────────────────────────
  Widget _objectRail(WcagPalette p) {
    return Container(
      decoration: BoxDecoration(
        color: p.surface,
        border: Border(
          top: BorderSide(color: p.border),
          bottom: BorderSide(color: p.border),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          WcagText('Objects',
              size: WcagType.caption,
              weight: WcagType.bold),
          const SizedBox(width: 8),
          WcagText('· tap to show path',
              size: WcagType.caption, emphasis: 'secondary'),
        ]),
        const SizedBox(height: 10),
        SizedBox(
          height: WcagSize.minTouch,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: objects.length,
            separatorBuilder: (_, __) => const SizedBox(width: 8),
            itemBuilder: (ctx, i) {
              final o = objects[i];
              final col = _typeColor(o.type);
              final selected = _selObj == i;
              return Semantics(
                button: true,
                label:
                    '${_displayLabel(o)}, ${(o.confidence * 100).toStringAsFixed(0)} percent confidence. Tap to show path.',
                excludeSemantics: true,
                child: Material(
                  color: selected
                      ? col.withOpacity(0.15)
                      : p.surfaceRecessed,
                  borderRadius: BorderRadius.circular(10),
                  child: InkWell(
                    onTap: () => setState(() {
                      _selObj = selected ? null : i;
                      _cachedPathCells = _recomputePath(grid, gridW, gridH,
                          robotGX, robotGZ, _selObj, objects);
                    }),
                    borderRadius: BorderRadius.circular(10),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 150),
                      padding: const EdgeInsets.symmetric(horizontal: 14),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: selected ? col : p.border,
                          width: selected ? 2 : 1.2,
                        ),
                      ),
                      child: Row(children: [
                        Text(_emoji(o.type),
                            style: const TextStyle(fontSize: 18)),
                        const SizedBox(width: 8),
                        WcagText(
                          _displayLabel(o),
                          size: WcagType.caption,
                          weight: WcagType.semibold,
                          color: selected ? col : p.textPrimary,
                        ),
                        const SizedBox(width: 6),
                        WcagText(
                          '${(o.confidence * 100).toStringAsFixed(0)}%',
                          size: WcagType.caption,
                          emphasis: 'secondary',
                        ),
                      ]),
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ]),
    );
  }

  // ── Bottom bar ────────────────────────────────────────────────────────────
  Widget _bottomBar(WcagPalette p) {
    return Container(
      color: p.surface,
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 14),
      child: Semantics(
        button: true,
        label: scanning
            ? 'Scanning in progress. Tap to open AR camera.'
            : 'Start AR scan. Opens the AR camera for indoor navigation and scanning.',
        excludeSemantics: true,
        child: Material(
          color: scanning ? p.accentSuccess : p.accentPrimary,
          borderRadius: BorderRadius.circular(12),
          child: InkWell(
            onTap: _openAR,
            borderRadius: BorderRadius.circular(12),
            child: Container(
              height: WcagSize.minTouch + 8,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                border:
                    Border.all(color: p.textOnAccent.withOpacity(0.4)),
              ),
              child: Row(mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                Icon(
                  scanning
                      ? Icons.stop_circle_outlined
                      : Icons.videocam_rounded,
                  color: p.textOnAccent,
                  size: 24,
                ),
                const SizedBox(width: 12),
                WcagText(
                  scanning ? 'Scanning in progress' : 'Start AR Scan',
                  size: WcagType.label,
                  weight: WcagType.bold,
                  color: p.textOnAccent,
                  letterSpacing: 0.2,
                ),
              ]),
            ),
          ),
        ),
      ),
    );
  }

  // ── Legend ────────────────────────────────────────────────────────────────
  Widget _legendSheet(WcagPalette p) {
    return Positioned(
      top: 60,
      right: 12,
      child: Container(
        width: 280,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: p.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: p.borderStrong, width: 1.5),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withOpacity(0.18),
              blurRadius: 24,
              offset: const Offset(0, 6),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              WcagText('Legend',
                  size: WcagType.label, weight: WcagType.bold),
              const Spacer(),
              SizedBox(
                width: WcagSize.minTouch,
                height: WcagSize.minTouch,
                child: IconButton(
                  iconSize: 24,
                  tooltip: 'Close legend',
                  icon: Icon(Icons.close_rounded, color: p.textSecondary),
                  onPressed: () => setState(() => _showLegend = false),
                ),
              ),
            ]),
            const SizedBox(height: 8),
            _legendRow(p, _T.mapFloor, 'Floor (passable)',
                'Open walkable area'),
            _legendRow(p, _T.mapVisited, 'Visited cells',
                'Scanned floor area'),
            _legendRow(p, _T.mapTrail, 'Camera trail',
                'Exact path taken'),
            _legendRow(p, _T.mapWall, 'Wall',
                'Detected vertical surface'),
            _legendRow(p, _T.mapObstacle, 'Obstacle',
                'Object footprint'),
            _legendRow(p, _T.mapPath, 'Route to object',
                'Selected object path'),
            _legendRow(p, _T.mapNavPath, 'Navigation route',
                'Voice nav path'),
            Divider(height: 20, color: p.border),
            Row(children: [
              Container(
                width: 16,
                height: 16,
                decoration: BoxDecoration(
                  color: _T.mapRobot,
                  shape: BoxShape.circle,
                  border: Border.all(color: p.textOnAccent, width: 1.5),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    WcagText('You',
                        size: WcagType.caption,
                        weight: WcagType.bold),
                    WcagText('Arrow shows heading',
                        size: WcagType.caption,
                        emphasis: 'secondary'),
                  ],
                ),
              ),
            ]),
          ],
        ),
      ),
    );
  }

  Widget _legendRow(WcagPalette p, Color swatch, String title, String desc) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 18,
            height: 18,
            decoration: BoxDecoration(
              color: swatch,
              borderRadius: BorderRadius.circular(4),
              border: Border.all(color: p.borderStrong, width: 1),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                WcagText(title,
                    size: WcagType.caption,
                    weight: WcagType.semibold),
                WcagText(desc,
                    size: WcagType.caption,
                    emphasis: 'secondary'),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Map Painter — architectural floor-plan style
// ─────────────────────────────────────────────────────────────────────────────
class _MapPainter extends CustomPainter {
  final Uint8List? grid;
  final int gridW, gridH;
  final double gridRes;
  final List<MapObject> objects;
  final Set<int> pathCells;
  final Set<int> navPathCells;
  final List<({int x, int z})> cameraTrail;
  final int? selectedObj;
  final int robotGX, robotGZ;
  final double heading, compassBearing, scale;
  final bool scanning;
  final Offset pan;

  const _MapPainter({
    required this.grid, required this.gridW, required this.gridH,
    required this.gridRes, required this.objects,
    required this.pathCells, required this.navPathCells,
    required this.cameraTrail,
    required this.selectedObj,
    required this.robotGX, required this.robotGZ,
    required this.heading, required this.compassBearing,
    required this.scale, required this.pan,
    required this.scanning,
  });

  @override
  void paint(Canvas canvas, Size size) {
    // Origin: centre of screen + pan offset, anchored at robot position
    final ox = size.width  / 2 + pan.dx - robotGX * scale;
    final oz = size.height / 2 + pan.dy - robotGZ * scale;
    final origin = Offset(ox, oz);

    // Draw in architectural layer order:
    // 1. Background fill (warm off-white)
    // 2. Subtle grid
    // 3. Floor cells (white)
    // 4. Visited cells (light blue tint)
    // 5. Nav path (green overlay)
    // 6. BFS path (blue overlay)
    // 7. Walls (solid dark — drawn LAST over free space so walls are crisp)
    // 8. Obstacle footprints
    // 9. Objects (pins)
    // 10. Robot

    canvas.drawRect(Rect.fromLTWH(0, 0, size.width, size.height),
        Paint()..color = _T.mapBg);

    // Empty grid — paint only the background and compass, skip cell iteration.
    // This guards against the brief init window where the Android side hasn't
    // pushed a payload yet (gridW/H == 0 would make .clamp(0, -1) throw).
    if (grid == null || gridW <= 0 || gridH <= 0) {
      _drawGrid(canvas, size, origin);
      _drawRobot(canvas, origin);
      return;
    }

    // Viewport culling — compute visible cell range to skip off-screen cells
    final int visCXMin = ((0 - origin.dx) / scale).floor().clamp(0, gridW - 1);
    final int visCXMax = ((size.width - origin.dx) / scale).ceil().clamp(0, gridW - 1);
    final int visCZMin = ((0 - origin.dy) / scale).floor().clamp(0, gridH - 1);
    final int visCZMax = ((size.height - origin.dy) / scale).ceil().clamp(0, gridH - 1);

    _drawGrid(canvas, size, origin);
    _drawFloorAndVisited(canvas, origin, visCXMin, visCXMax, visCZMin, visCZMax);
    _drawNavPath(canvas, origin);
    _drawBfsPath(canvas, origin);
    _drawCameraTrail(canvas, origin);
    _drawObstacles(canvas, origin, visCXMin, visCXMax, visCZMin, visCZMax);
    _drawWalls(canvas, origin, visCXMin, visCXMax, visCZMin, visCZMax);
    _drawObjects(canvas, origin);
    _drawRobot(canvas, origin);
    _drawCompass(canvas, size);
  }

  void _drawGrid(Canvas canvas, Size size, Offset origin) {
    // Skip grid lines at low zoom (too many lines) and very low zoom (invisible)
    if (scale < 8) return;
    final paint = Paint()..color = _T.mapGrid..strokeWidth = 0.4;
    final step  = scale;
    for (double x = origin.dx % step; x < size.width;  x += step)
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), paint);
    for (double z = origin.dy % step; z < size.height; z += step)
      canvas.drawLine(Offset(0, z), Offset(size.width, z), paint);
  }

  /// Draw floor and visited cells in a single pass.
  /// KEY CHANGE: floor is pure WHITE (architectural style), visited is very
  /// light blue. Previously both used similar blue shades making floor/visited
  /// indistinguishable from walls at a glance.
  void _drawFloorAndVisited(Canvas canvas, Offset origin,
      int cxMin, int cxMax, int czMin, int czMax) {
    final g = grid;
    if (g == null || gridW == 0) return;
    final cp = scale;

    final pFloor = Paint()..color = _T.mapFloor;
    final pVisitedFill = Paint()..color = _T.mapVisited.withOpacity(0.65);
    final pVisitedStroke = Paint()
      ..color = _T.mapVisited
      ..style = PaintingStyle.stroke
      ..strokeWidth = math.max(0.8, cp * 0.08);
    final inset = (cp * 0.18).clamp(0.35, 2.0).toDouble();

    for (int cz = czMin; cz <= czMax; cz++) {
      for (int cx = cxMin; cx <= cxMax; cx++) {
        final idx = cz * gridW + cx;
        if (idx >= g.length) continue;
        final v = g[idx];
        if (v != cellFree && v != cellVisited) continue;
        final sx = origin.dx + cx * cp;
        final sz = origin.dy + cz * cp;
        final rect = Rect.fromLTWH(sx, sz, cp, cp);
        canvas.drawRect(rect, pFloor);
        if (v == cellVisited) {
          final trailRect = Rect.fromLTWH(
            sx + inset,
            sz + inset,
            math.max(1.0, cp - inset * 2),
            math.max(1.0, cp - inset * 2),
          );
          canvas.drawRect(trailRect, pVisitedFill);
          canvas.drawRect(trailRect, pVisitedStroke);
        }
      }
    }
  }

  /// Draw walls as thick solid dark rectangles — architectural style.
  /// KEY CHANGE: walls are now visually dominant (dark/black) drawn AFTER
  /// floor so they clearly delineate room boundaries. Cell size = full scale
  /// with no gap, so adjacent wall cells form solid continuous lines.
  void _drawWalls(Canvas canvas, Offset origin,
      int cxMin, int cxMax, int czMin, int czMax) {
    final g = grid;
    if (g == null || gridW == 0) return;
    final cp = scale;

    final pWall = Paint()
      ..color = _T.mapWall
      ..style = PaintingStyle.fill;

    final pWallThin = Paint()
      ..color = const Color(0xFF444444)
      ..style = PaintingStyle.fill;

    final useLight = scale < 12;

    for (int cz = czMin; cz <= czMax; cz++) {
      for (int cx = cxMin; cx <= cxMax; cx++) {
        final idx = cz * gridW + cx;
        if (idx >= g.length) continue;
        if (g[idx] != cellWall) continue;
        final sx = origin.dx + cx * cp;
        final sz = origin.dy + cz * cp;
        canvas.drawRect(Rect.fromLTWH(sx, sz, cp, cp),
            useLight ? pWallThin : pWall);
      }
    }
  }

  /// Draw obstacle footprints (object bounding boxes) as muted brown.
  void _drawObstacles(Canvas canvas, Offset origin,
      int cxMin, int cxMax, int czMin, int czMax) {
    final g = grid;
    if (g == null || gridW == 0) return;
    final cp = scale;
    final pObs = Paint()..color = _T.mapObstacle.withOpacity(0.5);

    for (int cz = czMin; cz <= czMax; cz++) {
      for (int cx = cxMin; cx <= cxMax; cx++) {
        final idx = cz * gridW + cx;
        if (idx >= g.length) continue;
        if (g[idx] != cellObstacle) continue;
        canvas.drawRect(
            Rect.fromLTWH(origin.dx + cx * cp, origin.dy + cz * cp, cp, cp),
            pObs);
      }
    }
  }

  void _drawNavPath(Canvas canvas, Offset origin) {
    if (navPathCells.isEmpty || gridW == 0) return;
    final paint = Paint()..color = _T.mapNavPath.withOpacity(0.55);
    final cp = scale;
    for (final id in navPathCells) {
      final cx = id % gridW; final cz = id ~/ gridW;
      canvas.drawRect(
          Rect.fromLTWH(origin.dx + cx * cp, origin.dy + cz * cp, cp, cp),
          paint);
    }
  }

  void _drawBfsPath(Canvas canvas, Offset origin) {
    if (pathCells.isEmpty || gridW == 0) return;
    final paint = Paint()..color = _T.mapPath.withOpacity(0.40);
    final cp = scale;
    for (final id in pathCells) {
      final cx = id % gridW; final cz = id ~/ gridW;
      canvas.drawRect(
          Rect.fromLTWH(origin.dx + cx * cp, origin.dy + cz * cp, cp, cp),
          paint);
    }
  }

  void _drawCameraTrail(Canvas canvas, Offset origin) {
    if (cameraTrail.length < 2) return;
    final half = scale / 2;
    // Stroke width scales with zoom but stays readable at all levels
    final sw = (scale * 0.18).clamp(1.5, 5.0);
    final paint = Paint()
      ..color = _T.mapTrail.withOpacity(0.75)
      ..strokeWidth = sw
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;

    final path = Path();
    final first = cameraTrail.first;
    path.moveTo(origin.dx + first.x * scale + half,
                origin.dy + first.z * scale + half);
    for (int i = 1; i < cameraTrail.length; i++) {
      final pt = cameraTrail[i];
      path.lineTo(origin.dx + pt.x * scale + half,
                  origin.dy + pt.z * scale + half);
    }
    canvas.drawPath(path, paint);

    // Start dot — marks where the session began
    final startPt = cameraTrail.first;
    canvas.drawCircle(
      Offset(origin.dx + startPt.x * scale + half,
             origin.dy + startPt.z * scale + half),
      (sw * 1.5).clamp(2.5, 6.0),
      Paint()..color = _T.mapTrail.withOpacity(0.9),
    );
  }

  void _drawObjects(Canvas canvas, Offset origin) {
    for (int i = 0; i < objects.length; i++) {
      final obj = objects[i];
      final sx = origin.dx + obj.gridX * scale + scale / 2;
      final sz = origin.dy + obj.gridZ * scale + scale / 2;
      final pos = Offset(sx, sz);
      final col = _typeColor(obj.type);
      final isSelected = selectedObj == i;

      // Drop shadow
      canvas.drawCircle(pos, 14,
          Paint()
            ..color = Colors.black.withOpacity(0.10)
            ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 5));

      // White background
      canvas.drawCircle(pos, 13, Paint()..color = Colors.white);

      // Colour ring — thicker when selected
      if (isSelected) {
        canvas.drawCircle(pos, 15,
            Paint()
              ..color = col.withOpacity(0.35)
              ..style = PaintingStyle.stroke
              ..strokeWidth = 3.0);
      }
      canvas.drawCircle(pos, 13,
          Paint()
            ..color = col.withOpacity(isSelected ? 1.0 : 0.75)
            ..style = PaintingStyle.stroke
            ..strokeWidth = isSelected ? 2.5 : 1.5);

      // Emoji icon
      _txt(canvas, _emoji(obj.type), pos + const Offset(0, -5), 13, Colors.black);

      // Label when zoomed in enough
      if (scale > 20) {
        _txt(canvas, _displayLabel(obj),
            pos + Offset(0, scale * 0.6 + 6), 9, col);
      }
    }
  }

  void _drawRobot(Canvas canvas, Offset origin) {
    final rx = origin.dx + robotGX * scale + scale / 2;
    final rz = origin.dy + robotGZ * scale + scale / 2;
    final pos = Offset(rx, rz);

    // Accuracy ring (static — no pulse animation to save GPU)
    final r = scanning ? 22.0 : 20.0;
    canvas.drawCircle(pos, r, Paint()..color = _T.mapRobot.withOpacity(0.08));
    canvas.drawCircle(pos, r,
        Paint()
          ..color = _T.mapRobot.withOpacity(0.20)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1);

    // Heading arrow
    canvas.save();
    canvas.translate(rx, rz);
    canvas.rotate(heading);
    final arrow = Path()
      ..moveTo(0, -16) ..lineTo(-7, 8) ..lineTo(0, 4) ..lineTo(7, 8) ..close();
    canvas.drawPath(arrow, Paint()..color = _T.mapRobot.withOpacity(0.20));
    canvas.drawPath(arrow,
        Paint()
          ..color = _T.mapRobot
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.5);
    canvas.restore();

    // Robot dot
    canvas.drawCircle(pos, 5, Paint()..color = _T.mapRobot);
    canvas.drawCircle(pos, 5,
        Paint()
          ..color = Colors.white
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2);
  }

  /// Draw a compass rose in the bottom-right corner of the map.
  /// The compass rotates based on the user's heading so that N always
  /// points toward the ARCore world -Z direction (the initial forward).
  void _drawCompass(Canvas canvas, Size size) {
    final cx = size.width - 52;
    final cy = size.height - 58;
    final center = Offset(cx, cy);
    const radius = 32.0;

    // Background circle
    canvas.drawCircle(center, radius + 5,
        Paint()..color = const Color(0xFFF8F8FC).withOpacity(0.94));
    canvas.drawCircle(center, radius + 5,
        Paint()
          ..color = const Color(0xFFD1D5DB)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1);

    // Use true-north bearing from device sensors when available,
    // fall back to ARCore heading if compass data hasn't arrived yet.
    // compassBearing is in degrees (0=N, 90=E); convert to radians
    // and negate so the needle rotates to point north.
    final useCompass = compassBearing != 0;
    final rotAngle = useCompass
        ? -compassBearing * math.pi / 180.0
        : -heading;

    canvas.save();
    canvas.translate(cx, cy);
    canvas.rotate(rotAngle);

    // Tick marks every 45 degrees
    for (int i = 0; i < 8; i++) {
      canvas.save();
      canvas.rotate(i * math.pi / 4);
      final len = i % 2 == 0 ? 5.0 : 3.0;
      canvas.drawLine(
        Offset(0, -radius + 1),
        Offset(0, -radius + 1 + len),
        Paint()..color = const Color(0xFFD1D5DB)..strokeWidth = 1,
      );
      canvas.restore();
    }

    // North needle (red)
    final northPath = Path()
      ..moveTo(0, -radius + 5)
      ..lineTo(-6, 4)
      ..lineTo(0, -2)
      ..lineTo(6, 4)
      ..close();
    canvas.drawPath(northPath, Paint()..color = const Color(0xFFDC2626));

    // South needle (gray)
    final southPath = Path()
      ..moveTo(0, radius - 5)
      ..lineTo(-6, -4)
      ..lineTo(0, 2)
      ..lineTo(6, -4)
      ..close();
    canvas.drawPath(southPath, Paint()..color = const Color(0xFF9CA3AF));

    // Direction labels
    _compassLabel(canvas, 'N', Offset(0, -radius + 12), const Color(0xFFDC2626));
    _compassLabel(canvas, 'S', Offset(0, radius - 12), const Color(0xFF6B7280));
    _compassLabel(canvas, 'E', Offset(radius - 11, 0), const Color(0xFF6B7280));
    _compassLabel(canvas, 'W', Offset(-radius + 11, 0), const Color(0xFF6B7280));

    canvas.restore();

    // Center dot
    canvas.drawCircle(center, 3, Paint()..color = const Color(0xFF374151));

    // Bearing text below compass
    if (useCompass) {
      final deg = compassBearing.round() % 360;
      final dir = _cardinalName(deg);
      final text = '$deg° $dir';
      final tp = TextPainter(
        text: TextSpan(
          text: text,
          style: const TextStyle(
            color: Color(0xFF374151), fontSize: 9,
            fontWeight: FontWeight.w600,
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      tp.paint(canvas, Offset(cx - tp.width / 2, cy + radius + 10));
    }
  }

  static String _cardinalName(int deg) {
    final d = ((deg % 360) + 360) % 360;
    if (d < 23)  return 'N';
    if (d < 68)  return 'NE';
    if (d < 113) return 'E';
    if (d < 158) return 'SE';
    if (d < 203) return 'S';
    if (d < 248) return 'SW';
    if (d < 293) return 'W';
    if (d < 338) return 'NW';
    return 'N';
  }

  void _compassLabel(Canvas canvas, String label, Offset pos, Color color) {
    final tp = TextPainter(
      text: TextSpan(
        text: label,
        style: TextStyle(
          color: color, fontSize: 9, fontWeight: FontWeight.w700,
          letterSpacing: 0.5,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, pos - Offset(tp.width / 2, tp.height / 2));
  }

  void _txt(Canvas canvas, String text, Offset pos, double fs, Color col) {
    final tp = TextPainter(
      text: TextSpan(text: text,
          style: TextStyle(color: col, fontSize: fs, fontWeight: FontWeight.w500)),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, pos - Offset(tp.width / 2, tp.height / 2));
  }

  @override
  bool shouldRepaint(_MapPainter o) =>
      grid != o.grid || objects != o.objects || pathCells != o.pathCells ||
          navPathCells != o.navPathCells || cameraTrail != o.cameraTrail ||
          robotGX != o.robotGX || robotGZ != o.robotGZ ||
          heading != o.heading || compassBearing != o.compassBearing ||
          scale != o.scale ||
          pan != o.pan || scanning != o.scanning || selectedObj != o.selectedObj;
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
Color _typeColor(String type) {
  switch (type.toUpperCase()) {
    case 'CHAIR':             return const Color(0xFF16A34A);
    case 'DOOR':              return const Color(0xFFD97706);
    case 'FIRE_EXTINGUISHER': return const Color(0xFFDC2626);
    case 'LIFT_GATE':         return const Color(0xFF9333EA);
    case 'NOTICE_BOARD':      return const Color(0xFF2563EB);
    case 'TRASH_CAN':         return const Color(0xFF78716C);
    case 'WATER_PURIFIER':    return const Color(0xFF0891B2);
    case 'WINDOW':            return const Color(0xFF0E7490);
    case 'EXIT_SIGN':         return const Color(0xFFDC2626);
    case 'WASHROOM_SIGN':     return const Color(0xFF7C3AED);
    case 'STAIRS_SIGN':       return const Color(0xFFD97706);
    case 'ROOM_LABEL':        return const Color(0xFF059669);
    case 'FACILITY_SIGN':     return const Color(0xFF2563EB);
    case 'WARNING_SIGN':      return const Color(0xFFEA580C);
    case 'TEXT_SIGN':         return const Color(0xFF6B7280);
    default:                  return const Color(0xFF6B7280);
  }
}

String _emoji(String type) {
  switch (type.toUpperCase()) {
    case 'CHAIR':             return '🪑';
    case 'DOOR':              return '🚪';
    case 'FIRE_EXTINGUISHER': return '🧯';
    case 'LIFT_GATE':         return '🛗';
    case 'NOTICE_BOARD':      return '📋';
    case 'TRASH_CAN':         return '🗑';
    case 'WATER_PURIFIER':    return '💧';
    case 'WINDOW':            return '🪟';
    case 'EXIT_SIGN':         return '🚪';
    case 'WASHROOM_SIGN':     return '🚻';
    case 'STAIRS_SIGN':       return '🪜';
    case 'ROOM_LABEL':        return '🔢';
    case 'FACILITY_SIGN':     return '🏢';
    case 'WARNING_SIGN':      return '⚠️';
    case 'TEXT_SIGN':         return '📝';
    default:                  return '📍';
  }
}

String _displayLabel(MapObject obj) {
  if (obj.roomNumber != null) return 'Room ${obj.roomNumber}';
  if (obj.textContent != null && obj.textContent!.length <= 20) return obj.textContent!;
  return obj.label.replaceAll('_', ' ');
}
