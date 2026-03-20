import 'dart:math' as math;
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// ─────────────────────────────────────────────────────────────────────────────
// Cell constants — must match MapBuilder.kt
// ─────────────────────────────────────────────────────────────────────────────
const int cellUnknown  = 0;
const int cellFree     = 1;
const int cellObstacle = 2;
const int cellWall     = 3;
const int cellVisited  = 4;

// ─────────────────────────────────────────────────────────────────────────────
// Architectural floor-plan colour theme
// Matches the reference image: white floor, dark thick walls, clear rooms
// ─────────────────────────────────────────────────────────────────────────────
class _T {
  // App chrome
  static const bg        = Color(0xFFF5F7FA);
  static const surface   = Color(0xFFFFFFFF);
  static const surfaceLo = Color(0xFFF0F2F5);
  static const border    = Color(0xFFE2E6ED);
  static const divider   = Color(0xFFEBEEF2);

  // Accent
  static const blue      = Color(0xFF2563EB);
  static const blueSoft  = Color(0xFFEFF4FF);
  static const blueLight = Color(0xFFBFD4FE);
  static const green     = Color(0xFF16A34A);
  static const greenSoft = Color(0xFFDCFCE7);
  static const amber     = Color(0xFFD97706);
  static const amberSoft = Color(0xFFFEF3C7);
  static const red       = Color(0xFFDC2626);
  static const redSoft   = Color(0xFFFEE2E2);

  // Text
  static const textPri  = Color(0xFF111827);
  static const textSec  = Color(0xFF6B7280);
  static const textDim  = Color(0xFFD1D5DB);

  // ── Architectural map colours ──────────────────────────────────────────────
  // Background: light warm off-white (like paper/blueprint bg)
  static const mapBg      = Color(0xFFF8F6F0);

  // Grid lines: very subtle, doesn't compete with walls
  static const mapGrid    = Color(0xFFECEAE4);

  // Floor — clean white, clearly passable
  static const mapFloor   = Color(0xFFFFFFFF);

  // Visited path — very light blue tint (camera trajectory)
  static const mapVisited = Color(0xFFE8F0FE);

  // WALL — the most important colour. Dark gray like architectural drawings.
  // Must be very distinct from floor. Using a near-black warm gray.
  static const mapWall    = Color(0xFF2C2C2C);

  // Obstacle — object footprint. Muted orange-brown, like furniture in plans.
  static const mapObstacle = Color(0xFFB45309);

  // Path highlight (BFS route to selected object)
  static const mapPath    = Color(0xFF3B82F6);

  // Nav path (voice navigation route) — green
  static const mapNavPath = Color(0xFF10B981);

  // Robot position
  static const mapRobot   = Color(0xFF2563EB);
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
  const IndoorMapViewer({Key? key}) : super(key: key);
  @override
  State<IndoorMapViewer> createState() => _IndoorMapViewerState();
}

class _IndoorMapViewerState extends State<IndoorMapViewer>
    with TickerProviderStateMixin {
  static const _ch    = MethodChannel('com.ketan.slam/ar');
  static const _navCh = MethodChannel('com.ketan.slam/nav');

  // ── Data ──────────────────────────────────────────────────────────────────
  Uint8List? grid;
  int gridW = 0, gridH = 0;
  double gridRes = 0.20;
  int originX = 0, originZ = 0;
  int robotGX = 0, robotGZ = 0;
  List<MapObject> objects = [];
  double posX = 0, posZ = 0, heading = 0;
  int totalObjects = 0;
  bool scanning = false;

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

  @override
  void initState() {
    super.initState();
    _ch.setMethodCallHandler(_onCall);
    _navCh.setMethodCallHandler(_onNavCall);
    _pulseCtrl = AnimationController(
        vsync: this, duration: const Duration(seconds: 2))
      ..repeat();
  }

  @override
  void dispose() { _pulseCtrl.dispose(); super.dispose(); }

  Future<void> _onCall(MethodCall call) async {
    switch (call.method) {
      case 'onUpdate':
        final a = call.arguments as Map;
        if (!mounted) return;
        setState(() {
          posX         = (a['position_x'] as num?)?.toDouble() ?? posX;
          posZ         = (a['position_z'] as num?)?.toDouble() ?? posZ;
          heading      = (a['heading']    as num?)?.toDouble() ?? heading;
          totalObjects = (a['total_objects'] as num?)?.toInt() ?? totalObjects;
          scanning     = true;
        });
        break;
      case 'updateMap':
        _handleMap(call.arguments as Map);
        break;
    }
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

      setState(() {
        grid = ng; gridW = newW; gridH = newH; gridRes = newRes;
        originX = newOX; originZ = newOZ;
        robotGX = newRGX; robotGZ = newRGZ;
        objects = newObjs; totalObjects = newObjs.length;
        _navPathCells = _parseNavPath(args['navPath'], newW, newH);
        _cachedAreaSqM = area;
        // Invalidate path cache when map or robot position changes
        _cachedPathRobotGX = newRGX;
        _cachedPathRobotGZ = newRGZ;
        _cachedPathCells = _recomputePath(ng, newW, newH, newRGX, newRGZ, _selObj, newObjs);
      });
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

  Future<void> _openAR() async {
    try {
      await _ch.invokeMethod('openAR');
      if (mounted) setState(() => scanning = true);
    } catch (_) {}
  }

  Future<void> _onNavCall(MethodCall call) async {
    if (!mounted) return;
    switch (call.method) {
      case 'navStateChange':
        final a = call.arguments as Map;
        setState(() {
          _navState   = a['state']   as String? ?? _navState;
          _navMessage = a['message'] as String? ?? _navMessage;
          if (_navState == 'IDLE' || _navState == 'ARRIVED') _navInstruction = '';
        });
        break;
      case 'navInstruction':
        final a = call.arguments as Map;
        setState(() {
          _navInstruction = a['text'] as String? ?? _navInstruction;
        });
        break;
    }
  }

  Future<void> _onNavButtonTap() async {
    try {
      if (_navState == 'NAVIGATING') {
        await _navCh.invokeMethod('stopNavigation');
      } else {
        await _navCh.invokeMethod('startVoiceNav');
      }
    } catch (_) {}
  }

  Color _navStateColor() {
    switch (_navState) {
      case 'LISTENING':  return const Color(0xFF7C3AED);
      case 'PLANNING':   return _T.blue;
      case 'NAVIGATING': return const Color(0xFF1D4ED8);
      case 'ARRIVED':    return _T.green;
      case 'ERROR':      return _T.red;
      default:           return _T.textSec;
    }
  }

  double get _areaSqM => _cachedAreaSqM;

  // ─────────────────────────────────────────────────────────────────────────
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _T.bg,
      body: SafeArea(
        child: Stack(children: [
          Column(children: [
            _topBar(),
            Expanded(child: _mapArea()),
            if (objects.isNotEmpty) _objectRail(),
            _bottomBar(),
          ]),
          if (_showLegend) _legendSheet(),
        ]),
      ),
    );
  }

  // ── Top bar ───────────────────────────────────────────────────────────────
  Widget _topBar() {
    return Container(
      color: _T.surface,
      padding: const EdgeInsets.fromLTRB(16, 0, 12, 0),
      child: Column(children: [
        SizedBox(
          height: 52,
          child: Row(children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
              decoration: BoxDecoration(
                color: scanning ? _T.greenSoft : _T.surfaceLo,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                AnimatedBuilder(
                  animation: _pulseCtrl,
                  builder: (_, __) {
                    final t = _pulseCtrl.value;
                    final pulse = t < 0.5 ? t * 2 : 2 - t * 2;
                    return Container(
                      width: 7, height: 7,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: (scanning ? _T.green : _T.textDim)
                            .withOpacity(scanning ? 0.4 + 0.6 * pulse : 1.0),
                      ),
                    );
                  },
                ),
                const SizedBox(width: 6),
                Text(
                  scanning ? 'Scanning' : 'Idle',
                  style: TextStyle(
                    color: scanning ? _T.green : _T.textSec,
                    fontSize: 12, fontWeight: FontWeight.w600,
                  ),
                ),
              ]),
            ),
            const SizedBox(width: 10),
            _inlineStatChip(
              '${posX.toStringAsFixed(1)}, ${posZ.toStringAsFixed(1)} m',
              Icons.navigation_rounded, _T.blue,
            ),
            const SizedBox(width: 6),
            _inlineStatChip(
              '${_areaSqM.toStringAsFixed(1)} m²',
              Icons.square_foot_rounded, _T.amber,
            ),
            const Spacer(),
            _tinyBtn(Icons.info_outline_rounded,
                    () => setState(() => _showLegend = !_showLegend),
                active: _showLegend),
            _tinyBtn(Icons.crop_free_rounded,
                    () => setState(() { pan = Offset.zero; scale = 28; })),
          ]),
        ),
        Divider(height: 1, color: _T.border),
        if (_navState != 'IDLE')
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            color: _navStateColor().withOpacity(0.08),
            child: Row(children: [
              Icon(Icons.assistant_navigation, size: 13, color: _navStateColor()),
              const SizedBox(width: 6),
              Expanded(child: Text(
                _navMessage,
                style: TextStyle(color: _navStateColor(), fontSize: 12,
                    fontWeight: FontWeight.w500),
                overflow: TextOverflow.ellipsis,
              )),
            ]),
          ),
      ]),
    );
  }

  Widget _inlineStatChip(String value, IconData icon, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: _T.surfaceLo, borderRadius: BorderRadius.circular(8),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 13, color: color),
        const SizedBox(width: 5),
        Text(value, style: const TextStyle(
            color: _T.textPri, fontSize: 12, fontWeight: FontWeight.w500)),
      ]),
    );
  }

  Widget _tinyBtn(IconData icon, VoidCallback cb, {bool active = false}) {
    return GestureDetector(
      onTap: cb,
      child: Container(
        width: 36, height: 36,
        margin: const EdgeInsets.only(left: 4),
        decoration: BoxDecoration(
          color: active ? _T.blueSoft : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Icon(icon, size: 18, color: active ? _T.blue : _T.textSec),
      ),
    );
  }

  // ── Map canvas ────────────────────────────────────────────────────────────
  Widget _mapArea() {
    // Use cached path — BFS is never run inside build()
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
            child: AnimatedBuilder(
            animation: _pulseCtrl,
            builder: (_, __) => CustomPaint(
              painter: _MapPainter(
                grid: grid, gridW: gridW, gridH: gridH, gridRes: gridRes,
                objects: objects, pathCells: pathCells,
                navPathCells: _navPathCells,
                selectedObj: _selObj,
                robotGX: robotGX, robotGZ: robotGZ, heading: heading,
                scale: scale, pan: pan,
                pulse: _pulseCtrl.value,
              ),
              child: const SizedBox.expand(),
            ),
          ),
          ),
          // Empty state
          if (grid == null || gridW == 0)
            Center(
              child: Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: _T.surface, borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: _T.border),
                ),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.map_outlined, size: 48, color: _T.textDim),
                  const SizedBox(height: 12),
                  const Text('No map yet',
                      style: TextStyle(color: _T.textPri, fontSize: 15,
                          fontWeight: FontWeight.w600)),
                  const SizedBox(height: 4),
                  const Text('Start a scan to build the floor map',
                      style: TextStyle(color: _T.textSec, fontSize: 13)),
                ]),
              ),
            ),
          // Zoom controls
          Positioned(
            right: 12, top: 12,
            child: Column(children: [
              _mapBtn(Icons.add_rounded, () => setState(() => scale = (scale + 6).clamp(6.0, 300.0))),
              const SizedBox(height: 4),
              _mapBtn(Icons.remove_rounded, () => setState(() => scale = (scale - 6).clamp(6.0, 300.0))),
              const SizedBox(height: 8),
              _mapBtn(Icons.my_location_rounded, () => setState(() => pan = Offset.zero)),
            ]),
          ),
          // Scale bar bottom-left
          Positioned(
            left: 12, bottom: 12,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: _T.surface.withOpacity(0.9),
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: _T.border),
              ),
              child: Text(
                '${(5 * gridRes).toStringAsFixed(1)} m / 5 cells',
                style: const TextStyle(color: _T.textSec, fontSize: 10),
              ),
            ),
          ),
          // Object count badge
          if (totalObjects > 0)
            Positioned(
              left: 12, top: 12,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: _T.surface.withOpacity(0.9),
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: _T.border),
                ),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.category_rounded, size: 12, color: _T.blue),
                  const SizedBox(width: 4),
                  Text('$totalObjects object${totalObjects > 1 ? 's' : ''} found',
                      style: const TextStyle(color: _T.textPri, fontSize: 10,
                          fontWeight: FontWeight.w500)),
                ]),
              ),
            ),
          // Nav instruction banner
          if (_navInstruction.isNotEmpty)
            Positioned(
              bottom: 60, left: 12, right: 64,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                decoration: BoxDecoration(
                  color: const Color(0xFF1D4ED8),
                  borderRadius: BorderRadius.circular(10),
                  boxShadow: [BoxShadow(
                      color: Colors.black.withOpacity(0.18),
                      blurRadius: 10, offset: const Offset(0, 3))],
                ),
                child: Row(children: [
                  const Text('🧭', style: TextStyle(fontSize: 15)),
                  const SizedBox(width: 8),
                  Expanded(child: Text(
                    _navInstruction,
                    style: const TextStyle(color: Colors.white, fontSize: 13,
                        fontWeight: FontWeight.w600),
                  )),
                ]),
              ),
            ),
          // Voice nav button
          Positioned(
            right: 12,
            bottom: _navInstruction.isNotEmpty ? 60 : 12,
            child: Semantics(
              button: true,
              label: _navState == 'NAVIGATING'
                  ? 'Stop navigation'
                  : _navState == 'LISTENING'
                      ? 'Listening for voice command'
                      : 'Start voice command. Say things like: take me to the nearest door.',
              child: GestureDetector(
                onTap: _onNavButtonTap,
                child: Container(
                  width: 48, height: 48,
                  decoration: BoxDecoration(
                    color: _navState == 'NAVIGATING' ? _T.red : _T.blue,
                    shape: BoxShape.circle,
                    boxShadow: [BoxShadow(
                        color: Colors.black.withOpacity(0.18),
                        blurRadius: 8, offset: const Offset(0, 2))],
                  ),
                  child: Icon(
                    _navState == 'LISTENING'  ? Icons.mic_none_rounded :
                    _navState == 'NAVIGATING' ? Icons.stop_rounded     : Icons.mic_rounded,
                    color: Colors.white, size: 22,
                  ),
                ),
              ),
            ),
          ),
        ]),
      ),
    );
  }

  Widget _mapBtn(IconData icon, VoidCallback cb) => GestureDetector(
    onTap: cb,
    child: Container(
      width: 36, height: 36,
      decoration: BoxDecoration(
        color: _T.surface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: _T.border),
        boxShadow: [BoxShadow(
            color: Colors.black.withOpacity(0.06),
            blurRadius: 6, offset: const Offset(0, 2))],
      ),
      child: Icon(icon, size: 18, color: _T.textSec),
    ),
  );

  // ── Object rail ───────────────────────────────────────────────────────────
  Widget _objectRail() {
    return Container(
      decoration: BoxDecoration(
        color: _T.surface,
        border: Border(
            top: BorderSide(color: _T.border),
            bottom: BorderSide(color: _T.border)),
      ),
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Text('Objects', style: TextStyle(color: _T.textPri,
              fontSize: 12, fontWeight: FontWeight.w600)),
          const SizedBox(width: 6),
          Text('· tap to show path',
              style: TextStyle(color: _T.textSec, fontSize: 11)),
        ]),
        const SizedBox(height: 8),
        SizedBox(
          height: 40,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: objects.length,
            separatorBuilder: (_, __) => const SizedBox(width: 6),
            itemBuilder: (ctx, i) {
              final o = objects[i];
              final col = _typeColor(o.type);
              final selected = _selObj == i;
              return Semantics(
                button: true,
                label: '${_displayLabel(o)}, ${(o.confidence * 100).toStringAsFixed(0)} percent confidence. Tap to show path.',
                child: GestureDetector(
                onTap: () => setState(() {
                  _selObj = selected ? null : i;
                  _cachedPathCells = _recomputePath(
                      grid, gridW, gridH, robotGX, robotGZ, _selObj, objects);
                }),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  decoration: BoxDecoration(
                    color: selected ? col.withOpacity(0.1) : _T.surfaceLo,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(
                        color: selected ? col : _T.border,
                        width: selected ? 1.5 : 1),
                  ),
                  child: Row(children: [
                    Text(_emoji(o.type), style: const TextStyle(fontSize: 14)),
                    const SizedBox(width: 6),
                    Text(_displayLabel(o),
                        style: TextStyle(
                            color: selected ? col : _T.textPri,
                            fontSize: 12, fontWeight: FontWeight.w500)),
                    const SizedBox(width: 5),
                    Text('${(o.confidence * 100).toStringAsFixed(0)}%',
                        style: TextStyle(color: _T.textSec, fontSize: 11)),
                  ]),
                ),
              ));
            },
          ),
        ),
      ]),
    );
  }

  // ── Bottom bar ────────────────────────────────────────────────────────────
  Widget _bottomBar() {
    return Container(
      color: _T.surface,
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
      child: Semantics(
        button: true,
        label: 'Open AR Camera for indoor navigation and scanning',
        child: GestureDetector(
        onTap: _openAR,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 300),
          height: 50,
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: scanning
                  ? [_T.green, const Color(0xFF15803D)]
                  : [_T.blue, const Color(0xFF1D4ED8)],
            ),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(
              scanning ? Icons.stop_circle_outlined : Icons.videocam_rounded,
              color: Colors.white, size: 20,
            ),
            const SizedBox(width: 10),
            Text(
              scanning ? 'Scanning in progress' : 'Start AR Scan',
              style: const TextStyle(
                color: Colors.white, fontSize: 14,
                fontWeight: FontWeight.w600, letterSpacing: 0.2,
              ),
            ),
          ]),
        ),
      ),
      ),
    );
  }

  // ── Legend ────────────────────────────────────────────────────────────────
  Widget _legendSheet() {
    return Positioned(
      top: 60, right: 12,
      child: Container(
        width: 230,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: _T.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: _T.border),
          boxShadow: [BoxShadow(
              color: Colors.black.withOpacity(0.10),
              blurRadius: 20, offset: const Offset(0, 4))],
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Text('Legend', style: TextStyle(color: _T.textPri,
                fontSize: 13, fontWeight: FontWeight.w700)),
            const Spacer(),
            GestureDetector(
              onTap: () => setState(() => _showLegend = false),
              child: Icon(Icons.close_rounded, size: 16, color: _T.textSec),
            ),
          ]),
          const SizedBox(height: 12),
          _legendRow(_T.mapFloor,    'Floor (passable)',    'White — open walkable area'),
          _legendRow(_T.mapVisited,  'Camera path',         'Light blue — scanned trajectory'),
          _legendRow(_T.mapWall,     'Wall',                'Dark — detected vertical surface'),
          _legendRow(_T.mapObstacle, 'Obstacle',            'Brown — object footprint'),
          _legendRow(_T.mapPath,     'Route to object',     'Blue — selected object path'),
          _legendRow(_T.mapNavPath,  'Navigation route',    'Green — voice nav path'),
          Divider(height: 16, color: _T.divider),
          Row(children: [
            Container(width: 12, height: 12,
                decoration: const BoxDecoration(
                    color: _T.mapRobot, shape: BoxShape.circle)),
            const SizedBox(width: 10),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('You', style: TextStyle(color: _T.textPri,
                  fontSize: 11, fontWeight: FontWeight.w600)),
              Text('Arrow shows heading',
                  style: TextStyle(color: _T.textSec, fontSize: 10)),
            ])),
          ]),
        ]),
      ),
    );
  }

  Widget _legendRow(Color color, String title, String desc) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 9),
      child: Row(children: [
        Container(
          width: 14, height: 14,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(3),
            border: Border.all(color: Colors.black.withOpacity(0.12), width: 0.5),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: const TextStyle(color: _T.textPri,
              fontSize: 11, fontWeight: FontWeight.w600)),
          Text(desc, style: TextStyle(color: _T.textSec, fontSize: 10)),
        ])),
      ]),
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
  final int? selectedObj;
  final int robotGX, robotGZ;
  final double heading, scale, pulse;
  final Offset pan;

  const _MapPainter({
    required this.grid, required this.gridW, required this.gridH,
    required this.gridRes, required this.objects,
    required this.pathCells, required this.navPathCells,
    required this.selectedObj,
    required this.robotGX, required this.robotGZ,
    required this.heading, required this.scale, required this.pan,
    required this.pulse,
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

    _drawGrid(canvas, size, origin);
    _drawFloorAndVisited(canvas, origin);
    _drawNavPath(canvas, origin);
    _drawBfsPath(canvas, origin);
    _drawObstacles(canvas, origin);
    _drawWalls(canvas, origin);       // walls on top = crisp architectural lines
    _drawObjects(canvas, origin);
    _drawRobot(canvas, origin);
  }

  void _drawGrid(Canvas canvas, Size size, Offset origin) {
    if (scale < 2) return;
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
  void _drawFloorAndVisited(Canvas canvas, Offset origin) {
    final g = grid;
    if (g == null || gridW == 0) return;
    final cp = scale;

    final pFloor   = Paint()..color = _T.mapFloor;
    final pVisited = Paint()..color = _T.mapVisited;

    for (int cz = 0; cz < gridH; cz++) {
      for (int cx = 0; cx < gridW; cx++) {
        final idx = cz * gridW + cx;
        if (idx >= g.length) continue;
        final v = g[idx];
        if (v != cellFree && v != cellVisited) continue;
        final sx = origin.dx + cx * cp;
        final sz = origin.dy + cz * cp;
        final rect = Rect.fromLTWH(sx, sz, cp, cp);
        canvas.drawRect(rect, v == cellVisited ? pVisited : pFloor);
      }
    }
  }

  /// Draw walls as thick solid dark rectangles — architectural style.
  /// KEY CHANGE: walls are now visually dominant (dark/black) drawn AFTER
  /// floor so they clearly delineate room boundaries. Cell size = full scale
  /// with no gap, so adjacent wall cells form solid continuous lines.
  void _drawWalls(Canvas canvas, Offset origin) {
    final g = grid;
    if (g == null || gridW == 0) return;
    final cp = scale;

    // Wall paint: solid near-black. No anti-aliasing — sharp edges.
    final pWall = Paint()
      ..color = _T.mapWall
      ..style = PaintingStyle.fill;

    // At low zoom, use a slightly lighter shade so thin walls are still visible
    final pWallThin = Paint()
      ..color = const Color(0xFF444444)
      ..style = PaintingStyle.fill;

    final useLight = scale < 12;

    for (int cz = 0; cz < gridH; cz++) {
      for (int cx = 0; cx < gridW; cx++) {
        final idx = cz * gridW + cx;
        if (idx >= g.length) continue;
        if (g[idx] != cellWall) continue;
        final sx = origin.dx + cx * cp;
        final sz = origin.dy + cz * cp;
        // Draw wall cell at full cell size — no gap between adjacent walls
        canvas.drawRect(Rect.fromLTWH(sx, sz, cp, cp),
            useLight ? pWallThin : pWall);
      }
    }
  }

  /// Draw obstacle footprints (object bounding boxes) as muted brown.
  void _drawObstacles(Canvas canvas, Offset origin) {
    final g = grid;
    if (g == null || gridW == 0) return;
    final cp = scale;
    final pObs = Paint()..color = _T.mapObstacle.withOpacity(0.5);

    for (int cz = 0; cz < gridH; cz++) {
      for (int cx = 0; cx < gridW; cx++) {
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
    final opacity = 0.30 + 0.15 * math.sin(pulse * math.pi * 2);
    final paint = Paint()..color = _T.mapPath.withOpacity(opacity);
    final cp = scale;
    for (final id in pathCells) {
      final cx = id % gridW; final cz = id ~/ gridW;
      canvas.drawRect(
          Rect.fromLTWH(origin.dx + cx * cp, origin.dy + cz * cp, cp, cp),
          paint);
    }
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
        canvas.drawCircle(pos, 13 + 2 * math.sin(pulse * math.pi * 2),
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

    // Accuracy ring (pulsing)
    final r = 20.0 + 4 * math.sin(pulse * math.pi * 2);
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
          navPathCells != o.navPathCells ||
          robotGX != o.robotGX || robotGZ != o.robotGZ ||
          heading != o.heading || scale != o.scale ||
          pan != o.pan || pulse != o.pulse || selectedObj != o.selectedObj;
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