import 'dart:math' as math;
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// ─────────────────────────────────────────────────────────────────────────────
// Cell constants — mirror ArActivity.kt
// ─────────────────────────────────────────────────────────────────────────────
const int cellUnknown  = 0;
const int cellFree     = 1;
const int cellObstacle = 2;
const int cellWall     = 3;
const int cellVisited  = 4;

// ─────────────────────────────────────────────────────────────────────────────
// Light minimal theme
// ─────────────────────────────────────────────────────────────────────────────
class _T {
  // Backgrounds
  static const bg        = Color(0xFFF5F7FA);
  static const surface   = Color(0xFFFFFFFF);
  static const surfaceLo = Color(0xFFF0F2F5);

  // Borders & dividers
  static const border    = Color(0xFFE2E6ED);
  static const divider   = Color(0xFFEBEEF2);

  // Accent — single confident blue
  static const blue      = Color(0xFF2563EB);
  static const blueSoft  = Color(0xFFEFF4FF);
  static const blueLight = Color(0xFFBFD4FE);

  // Semantic
  static const green     = Color(0xFF16A34A);
  static const greenSoft = Color(0xFFDCFCE7);
  static const amber     = Color(0xFFD97706);
  static const amberSoft = Color(0xFFFEF3C7);
  static const red       = Color(0xFFDC2626);
  static const redSoft   = Color(0xFFFEE2E2);

  // Text
  static const textPri   = Color(0xFF111827);
  static const textSec   = Color(0xFF6B7280);
  static const textDim   = Color(0xFFD1D5DB);
  static const textBlue  = Color(0xFF1D4ED8);

  // Map
  static const mapBg       = Color(0xFFF8F9FC);
  static const mapGrid     = Color(0xFFEEF1F6);
  static const mapFree     = Color(0xFFE3EAF8);
  static const mapVisited  = Color(0xFFBFD4FE);
  static const mapWall     = Color(0xFF6B7280);
  static const mapObstacle = Color(0xFFFCA5A5);
  static const mapPath     = Color(0xFF2563EB);
  static const mapRobot    = Color(0xFF2563EB);
}

// ─────────────────────────────────────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────────────────────────────────────
class MapObject {
  final String id, label, type;
  final double confidence, x, y, z;
  final int gridX, gridZ, observations;
  const MapObject({
    required this.id, required this.label, required this.type,
    required this.confidence, required this.x, required this.y, required this.z,
    required this.gridX, required this.gridZ, required this.observations,
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
    if (cx == gx.clamp(0, w - 1) && cz == gz.clamp(0, h - 1)) { found = idx(cx, cz); break; }
    for (int d = 0; d < 8; d++) {
      final nx = cx + dx[d]; final nz = cz + dz[d];
      if (!walkable(nx, nz)) continue;
      final nid = idx(nx, nz);
      if (!visited.containsKey(nid)) { visited[nid] = idx(cx, cz); queue.add([nx, nz]); }
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
  static const _ch = MethodChannel('com.ketan.slam/ar');

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

  // ── Animations ────────────────────────────────────────────────────────────
  late AnimationController _pulseCtrl;

  @override
  void initState() {
    super.initState();
    _ch.setMethodCallHandler(_onCall);
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
            id: o['id']?.toString() ?? '',
            label: o['label']?.toString() ?? '',
            type: o['type']?.toString() ?? '',
            confidence: (o['confidence'] as num?)?.toDouble() ?? 0,
            x: (o['x'] as num?)?.toDouble() ?? 0,
            y: (o['y'] as num?)?.toDouble() ?? 0,
            z: (o['z'] as num?)?.toDouble() ?? 0,
            gridX:        (o['gridX']        as num?)?.toInt() ?? 0,
            gridZ:        (o['gridZ']        as num?)?.toInt() ?? 0,
            observations: (o['observations'] as num?)?.toInt() ?? 0,
          ));
        }
      }
      setState(() {
        grid = ng; gridW = newW; gridH = newH; gridRes = newRes;
        originX = newOX; originZ = newOZ;
        robotGX = newRGX; robotGZ = newRGZ;
        objects = newObjs; totalObjects = newObjs.length;
      });
    } catch (e, st) { debugPrint('map error: $e\n$st'); }
  }

  Future<void> _openAR() async {
    try { await _ch.invokeMethod('openAR'); setState(() => scanning = true); }
    catch (_) {}
  }

  double get _areaSqM {
    if (grid == null || gridW == 0) return 0;
    int free = 0;
    for (final v in grid!) { if (v == cellFree || v == cellVisited) free++; }
    return free * gridRes * gridRes;
  }

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
        // Main header row
        SizedBox(
          height: 52,
          child: Row(children: [
            // Status pill
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
              decoration: BoxDecoration(
                color: scanning ? _T.greenSoft : _T.surfaceLo,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                AnimatedBuilder(
                  animation: _pulseCtrl,
                  builder: (_, __) => Container(
                    width: 7, height: 7,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: scanning ? _T.green : _T.textDim,
                    ),
                  ),
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
            // Stats inline
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
            // Actions
            _tinyBtn(Icons.info_outline_rounded, () => setState(() => _showLegend = !_showLegend),
                active: _showLegend),
            _tinyBtn(Icons.crop_free_rounded,
                    () => setState(() { pan = Offset.zero; scale = 28; })),
          ]),
        ),
        Divider(height: 1, color: _T.border),
      ]),
    );
  }

  Widget _inlineStatChip(String value, IconData icon, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: _T.surfaceLo,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 13, color: color),
        const SizedBox(width: 5),
        Text(value, style: TextStyle(
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
        child: Icon(icon, size: 18,
            color: active ? _T.blue : _T.textSec),
      ),
    );
  }

  // ── Map canvas ────────────────────────────────────────────────────────────
  Widget _mapArea() {
    Set<int> pathCells = {};
    if (grid != null && gridW > 0) {
      if (_selObj != null && _selObj! < objects.length) {
        final o = objects[_selObj!];
        pathCells = _bfsPath(grid!, gridW, gridH, robotGX, robotGZ, o.gridX, o.gridZ);
      }
    }

    return Container(
      color: _T.mapBg,
      child: GestureDetector(
        onScaleStart:  (d) => _scaleStart = scale,
        onScaleUpdate: (d) => setState(() {
          pan += d.focalPointDelta;
          if (d.scale != 1.0) scale = (_scaleStart * d.scale).clamp(6.0, 300.0);
        }),
        child: Stack(children: [
          AnimatedBuilder(
            animation: _pulseCtrl,
            builder: (_, __) => CustomPaint(
              painter: _MapPainter(
                grid: grid, gridW: gridW, gridH: gridH,
                gridRes: gridRes,
                objects: objects, pathCells: pathCells,
                selectedObj: _selObj,
                robotGX: robotGX, robotGZ: robotGZ, heading: heading,
                scale: scale, pan: pan,
                pulse: _pulseCtrl.value,
              ),
              child: const SizedBox.expand(),
            ),
          ),
          // Empty state
          if (grid == null || gridW == 0)
            Center(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: _T.surface, borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: _T.border),
                  ),
                  child: Column(children: [
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
              ]),
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
          // Scale label — bottom left
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
          // Object count badge top-left
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
        boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.06), blurRadius: 6, offset: const Offset(0, 2))],
      ),
      child: Icon(icon, size: 18, color: _T.textSec),
    ),
  );

  // ── Object rail ───────────────────────────────────────────────────────────
  Widget _objectRail() {
    return Container(
      decoration: BoxDecoration(
        color: _T.surface,
        border: Border(top: BorderSide(color: _T.border), bottom: BorderSide(color: _T.border)),
      ),
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Text('Objects', style: TextStyle(color: _T.textPri, fontSize: 12,
              fontWeight: FontWeight.w600)),
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
              return GestureDetector(
                onTap: () => setState(() => _selObj = selected ? null : i),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  decoration: BoxDecoration(
                    color: selected ? col.withOpacity(0.1) : _T.surfaceLo,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(
                      color: selected ? col : _T.border,
                      width: selected ? 1.5 : 1,
                    ),
                  ),
                  child: Row(children: [
                    Text(_emoji(o.type), style: const TextStyle(fontSize: 14)),
                    const SizedBox(width: 6),
                    Text(
                      o.label.replaceAll('_', ' '),
                      style: TextStyle(
                        color: selected ? col : _T.textPri,
                        fontSize: 12, fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(width: 5),
                    Text(
                      '${(o.confidence * 100).toStringAsFixed(0)}%',
                      style: TextStyle(color: _T.textSec, fontSize: 11),
                    ),
                  ]),
                ),
              );
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
      child: GestureDetector(
        onTap: _openAR,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 300),
          height: 50,
          decoration: BoxDecoration(
            color: scanning ? _T.green : _T.blue,
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
    );
  }

  // ── Legend sheet ──────────────────────────────────────────────────────────
  Widget _legendSheet() {
    const items = <(Color, String, String)>[
      (_T.mapFree,     'Walkable floor',  'Open, passable area'),
      (_T.mapVisited,  'Camera path',     'Scanned by the camera'),
      (_T.mapWall,     'Wall',            'Detected vertical surface'),
      (_T.mapObstacle, 'Obstacle',        'Object footprint — avoid'),
      (_T.mapPath,     'Route',           'Shortest path to selected object'),
    ];
    return Positioned(
      top: 60, right: 12,
      child: Container(
        width: 220,
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
            Text('Legend', style: TextStyle(color: _T.textPri, fontSize: 13,
                fontWeight: FontWeight.w700)),
            const Spacer(),
            GestureDetector(
              onTap: () => setState(() => _showLegend = false),
              child: Icon(Icons.close_rounded, size: 16, color: _T.textSec),
            ),
          ]),
          const SizedBox(height: 12),
          ...items.map((item) => Padding(
            padding: const EdgeInsets.only(bottom: 9),
            child: Row(children: [
              Container(
                width: 12, height: 12,
                decoration: BoxDecoration(
                  color: item.$1,
                  borderRadius: BorderRadius.circular(3),
                  border: Border.all(color: Colors.black.withOpacity(0.08)),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(item.$2, style: TextStyle(color: _T.textPri, fontSize: 11,
                    fontWeight: FontWeight.w600)),
                Text(item.$3, style: TextStyle(color: _T.textSec, fontSize: 10)),
              ])),
            ]),
          )),
          Divider(height: 16, color: _T.divider),
          Row(children: [
            Container(width: 12, height: 12,
                decoration: const BoxDecoration(
                    color: _T.blue, shape: BoxShape.circle)),
            const SizedBox(width: 10),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('You', style: TextStyle(color: _T.textPri, fontSize: 11,
                  fontWeight: FontWeight.w600)),
              Text('Arrow shows your heading', style: TextStyle(color: _T.textSec, fontSize: 10)),
            ])),
          ]),
        ]),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Map Painter
// ─────────────────────────────────────────────────────────────────────────────
class _MapPainter extends CustomPainter {
  final Uint8List? grid;
  final int gridW, gridH;
  final double gridRes;
  final List<MapObject> objects;
  final Set<int> pathCells;
  final int? selectedObj;
  final int robotGX, robotGZ;
  final double heading, scale, pulse;
  final Offset pan;

  const _MapPainter({
    required this.grid, required this.gridW, required this.gridH,
    required this.gridRes, required this.objects, required this.pathCells,
    required this.selectedObj,
    required this.robotGX, required this.robotGZ,
    required this.heading, required this.scale, required this.pan, required this.pulse,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final ox = size.width  / 2 + pan.dx - robotGX * scale;
    final oz = size.height / 2 + pan.dy - robotGZ * scale;
    final origin = Offset(ox, oz);

    _drawGrid(canvas, size, origin);
    _drawCells(canvas, origin);
    _drawPath(canvas, origin);
    _drawObjects(canvas, origin);
    _drawRobot(canvas, origin);
  }

  void _drawGrid(Canvas canvas, Size size, Offset origin) {
    final paint = Paint()..color = _T.mapGrid..strokeWidth = 0.5;
    final step  = scale;
    for (double x = origin.dx % step; x < size.width;  x += step)
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), paint);
    for (double z = origin.dy % step; z < size.height; z += step)
      canvas.drawLine(Offset(0, z), Offset(size.width, z), paint);
  }

  void _drawCells(Canvas canvas, Offset origin) {
    final g = grid;
    if (g == null || gridW == 0) return;
    final cp = scale;

    final pFree     = Paint()..color = _T.mapFree;
    final pVisited  = Paint()..color = _T.mapVisited;
    final pObstacle = Paint()..color = _T.mapObstacle;

    // Wall paint with slight texture
    final pWall     = Paint()..color = _T.mapWall;

    for (int cz = 0; cz < gridH; cz++) {
      for (int cx = 0; cx < gridW; cx++) {
        final idx = cz * gridW + cx;
        if (idx >= g.length) continue;
        final v = g[idx];
        if (v == cellUnknown) continue;
        final sx = origin.dx + cx * cp;
        final sz = origin.dy + cz * cp;
        final rect = Rect.fromLTWH(sx, sz, cp - 0.5, cp - 0.5);
        switch (v) {
          case cellFree:     canvas.drawRect(rect, pFree);     break;
          case cellVisited:  canvas.drawRect(rect, pVisited);  break;
          case cellWall:     canvas.drawRect(rect, pWall);     break;
          case cellObstacle: canvas.drawRect(rect, pObstacle); break;
        }
      }
    }
  }

  void _drawPath(Canvas canvas, Offset origin) {
    if (pathCells.isEmpty || gridW == 0) return;
    final opacity = 0.25 + 0.15 * math.sin(pulse * math.pi * 2);
    final pathPaint = Paint()..color = _T.mapPath.withOpacity(opacity);
    final cp = scale;
    for (final id in pathCells) {
      final cx = id % gridW; final cz = id ~/ gridW;
      canvas.drawRect(
          Rect.fromLTWH(origin.dx + cx * cp, origin.dy + cz * cp, cp, cp),
          pathPaint);
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

      // Shadow
      canvas.drawCircle(pos, 14,
          Paint()..color = Colors.black.withOpacity(0.08)
            ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 6));

      // Background circle
      canvas.drawCircle(pos, 13, Paint()..color = Colors.white);

      // Border ring — bolder when selected
      if (isSelected) {
        canvas.drawCircle(pos, 13 + 2 * math.sin(pulse * math.pi * 2),
            Paint()..color = col.withOpacity(0.4)
              ..style = PaintingStyle.stroke..strokeWidth = 2.5);
      }
      canvas.drawCircle(pos, 13,
          Paint()..color = col.withOpacity(isSelected ? 1.0 : 0.7)
            ..style = PaintingStyle.stroke..strokeWidth = isSelected ? 2.5 : 1.5);

      // Emoji label
      _txt(canvas, _emoji(obj.type), pos + const Offset(0, -5), 13, Colors.black);

      // Text label when zoomed in enough
      if (scale > 20) {
        _txt(canvas, obj.label.replaceAll('_', ' '),
            pos + Offset(0, scale * 0.6 + 6), 9, col);
      }
    }
  }

  void _drawRobot(Canvas canvas, Offset origin) {
    final rx = origin.dx + robotGX * scale + scale / 2;
    final rz = origin.dy + robotGZ * scale + scale / 2;
    final pos = Offset(rx, rz);

    // Accuracy ring
    final r = 20.0 + 4 * math.sin(pulse * math.pi * 2);
    canvas.drawCircle(pos, r,
        Paint()..color = _T.mapRobot.withOpacity(0.08));
    canvas.drawCircle(pos, r,
        Paint()..color = _T.mapRobot.withOpacity(0.2)
          ..style = PaintingStyle.stroke..strokeWidth = 1);

    // Direction arrow
    canvas.save();
    canvas.translate(rx, rz);
    canvas.rotate(heading);
    final arrow = Path()
      ..moveTo(0, -16) ..lineTo(-7, 8) ..lineTo(0, 4) ..lineTo(7, 8) ..close();
    canvas.drawPath(arrow, Paint()..color = _T.mapRobot.withOpacity(0.25));
    canvas.drawPath(arrow, Paint()..color = _T.mapRobot
      ..style = PaintingStyle.stroke..strokeWidth = 1.5);
    canvas.restore();

    // Dot
    canvas.drawCircle(pos, 5, Paint()..color = _T.mapRobot);
    canvas.drawCircle(pos, 5,
        Paint()..color = Colors.white..style = PaintingStyle.stroke..strokeWidth = 2);
  }

  void _txt(Canvas canvas, String text, Offset pos, double fs, Color col) {
    final tp = TextPainter(
      text: TextSpan(text: text, style: TextStyle(color: col, fontSize: fs,
          fontWeight: FontWeight.w500)),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, pos - Offset(tp.width / 2, tp.height / 2));
  }

  @override
  bool shouldRepaint(_MapPainter o) =>
      grid != o.grid || objects != o.objects || pathCells != o.pathCells ||
          robotGX != o.robotGX || robotGZ != o.robotGZ || heading != o.heading ||
          scale != o.scale || pan != o.pan || pulse != o.pulse ||
          selectedObj != o.selectedObj;
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
    default:                  return '📍';
  }
}