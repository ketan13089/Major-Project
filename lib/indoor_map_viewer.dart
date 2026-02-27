import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────

class MapEdge {
  final Offset start;
  final Offset end;
  final double confidence;
  const MapEdge({required this.start, required this.end, required this.confidence});
}

class MapObject {
  final String id;
  final String label;
  final String type;
  final double confidence;
  final double x, y, z;
  final int gridX, gridZ;

  const MapObject({
    required this.id, required this.label, required this.type,
    required this.confidence,
    required this.x, required this.y, required this.z,
    required this.gridX, required this.gridZ,
  });
}

enum MapViewMode { floorPlan, technical }

// ─────────────────────────────────────────────────────────────────────────────
// Widget
// ─────────────────────────────────────────────────────────────────────────────

class IndoorMapViewer extends StatefulWidget {
  const IndoorMapViewer({Key? key}) : super(key: key);

  @override
  State<IndoorMapViewer> createState() => _IndoorMapViewerState();
}

class _IndoorMapViewerState extends State<IndoorMapViewer> {
  static const _channel = MethodChannel('com.ketan.slam/ar');

  // ── Map data ──────────────────────────────────────────────────────────────
  List<MapEdge> edges = [];
  Uint8List? occupancyGrid;
  int gridWidth  = 0;
  int gridHeight = 0;
  double gridResolution = 0.25;
  int originX = 0;
  int originZ = 0;
  List<MapObject> objects = [];

  // ── Pose ──────────────────────────────────────────────────────────────────
  double posX = 0, posY = 0, posZ = 0;

  // ── Stats ─────────────────────────────────────────────────────────────────
  int totalObjects = 0;
  int edgesCount   = 0;
  int cellsCount   = 0;

  // ── View ──────────────────────────────────────────────────────────────────
  MapViewMode viewMode  = MapViewMode.floorPlan;
  double scale          = 40.0;
  Offset panOffset      = Offset.zero;
  double _scaleAtGestureStart = 40.0;

  // ─────────────────────────────────────────────────────────────────────────

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_onMethodCall);
  }

  Future<void> _onMethodCall(MethodCall call) async {
    switch (call.method) {

      case 'updatePose':
        final a = call.arguments as Map;
        setState(() {
          posX = (a['x'] as num?)?.toDouble() ?? posX;
          posY = (a['y'] as num?)?.toDouble() ?? posY;
          posZ = (a['z'] as num?)?.toDouble() ?? posZ;
        });
        break;

      case 'onUpdate':
        final a = call.arguments as Map;
        setState(() {
          posX         = (a['position_x'] as num?)?.toDouble() ?? posX;
          posY         = (a['position_y'] as num?)?.toDouble() ?? posY;
          posZ         = (a['position_z'] as num?)?.toDouble() ?? posZ;
          edgesCount   = (a['edges_count']   as num?)?.toInt() ?? edgesCount;
          cellsCount   = (a['cells_count']   as num?)?.toInt() ?? cellsCount;
          totalObjects = (a['total_objects'] as num?)?.toInt() ?? totalObjects;
          // Individual counts (available if you want to display per-class stats):
          // chairs            = (a['chairs']             as num?)?.toInt() ?? 0;
          // doors             = (a['doors']              as num?)?.toInt() ?? 0;
          // fireExtinguishers = (a['fire_extinguishers'] as num?)?.toInt() ?? 0;
          // liftGates         = (a['lift_gates']         as num?)?.toInt() ?? 0;
          // noticeBoards      = (a['notice_boards']      as num?)?.toInt() ?? 0;
          // trashCans         = (a['trash_cans']         as num?)?.toInt() ?? 0;
          // waterPurifiers    = (a['water_purifiers']    as num?)?.toInt() ?? 0;
          // windows           = (a['windows']            as num?)?.toInt() ?? 0;
        });
        break;

      case 'updateMap':
        _handleMapUpdate(call.arguments as Map);
        break;
    }
  }

  void _handleMapUpdate(Map args) {
    try {
      Uint8List? newGrid;
      final rawGrid = args['occupancyGrid'];
      if (rawGrid is Uint8List) {
        newGrid = rawGrid;
      } else if (rawGrid is List) {
        newGrid = Uint8List.fromList(rawGrid.cast<int>());
      }

      final newW       = (args['gridWidth']      as num?)?.toInt()    ?? 0;
      final newH       = (args['gridHeight']     as num?)?.toInt()    ?? 0;
      final newRes     = (args['gridResolution'] as num?)?.toDouble() ?? gridResolution;
      final newOriginX = (args['originX']        as num?)?.toInt()    ?? 0;
      final newOriginZ = (args['originZ']        as num?)?.toInt()    ?? 0;

      final rawObjects = args['objects'];
      List<MapObject> newObjects = [];
      if (rawObjects is List) {
        for (final o in rawObjects) {
          if (o is! Map) continue;
          newObjects.add(MapObject(
            id:         o['id']?.toString()    ?? '',
            label:      o['label']?.toString() ?? '',
            type:       o['type']?.toString()  ?? '',
            confidence: (o['confidence'] as num?)?.toDouble() ?? 0.0,
            x:          (o['x'] as num?)?.toDouble() ?? 0.0,
            y:          (o['y'] as num?)?.toDouble() ?? 0.0,
            z:          (o['z'] as num?)?.toDouble() ?? 0.0,
            gridX:      (o['gridX'] as num?)?.toInt() ?? 0,
            gridZ:      (o['gridZ'] as num?)?.toInt() ?? 0,
          ));
        }
      }

      final rawEdges = args['edges'];
      List<MapEdge> newEdges = edges;
      if (rawEdges is List) {
        newEdges = rawEdges.map((e) => MapEdge(
          start: Offset((e['startX'] as num?)?.toDouble() ?? 0,
              (e['startZ'] as num?)?.toDouble() ?? 0),
          end:   Offset((e['endX']   as num?)?.toDouble() ?? 0,
              (e['endZ']   as num?)?.toDouble() ?? 0),
          confidence: (e['confidence'] as num?)?.toDouble() ?? 0.5,
        )).toList();
      }

      setState(() {
        occupancyGrid  = newGrid;
        gridWidth      = newW;
        gridHeight     = newH;
        gridResolution = newRes;
        originX        = newOriginX;
        originZ        = newOriginZ;
        objects        = newObjects;
        edges          = newEdges;
        totalObjects   = newObjects.length;
      });
    } catch (e, st) {
      debugPrint('IndoorMapViewer._handleMapUpdate error: $e\n$st');
    }
  }

  Future<void> _openAR() async {
    try {
      await _channel.invokeMethod('openAR');
    } catch (e) {
      debugPrint('Error opening AR: $e');
    }
  }

  void _resetView() => setState(() { panOffset = Offset.zero; scale = 40.0; });
  void _clearMap()  => setState(() {
    edges.clear(); occupancyGrid = null; objects.clear();
    totalObjects = edgesCount = cellsCount = 0;
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Build
  // ─────────────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.grey[900],
      appBar: AppBar(
        title: const Text('Indoor Map'),
        backgroundColor: Colors.blue[900],
        actions: [
          IconButton(
            icon: Icon(viewMode == MapViewMode.floorPlan ? Icons.grid_on : Icons.apartment),
            tooltip: viewMode == MapViewMode.floorPlan ? 'Technical view' : 'Floor plan',
            onPressed: () => setState(() {
              viewMode = viewMode == MapViewMode.floorPlan
                  ? MapViewMode.technical : MapViewMode.floorPlan;
            }),
          ),
          IconButton(icon: const Icon(Icons.refresh),        onPressed: _resetView, tooltip: 'Reset view'),
          IconButton(icon: const Icon(Icons.delete_outline), onPressed: _clearMap,  tooltip: 'Clear map'),
        ],
      ),
      body: Column(children: [
        _buildInfoPanel(),
        Expanded(child: _buildMapArea()),
        _buildControlPanel(),
      ]),
    );
  }

  Widget _buildInfoPanel() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      color: Colors.grey[850],
      child: Column(children: [
        Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [
          _InfoCard(label: 'Position',
              value: '(${posX.toStringAsFixed(1)}, ${posZ.toStringAsFixed(1)})',
              icon: Icons.location_on),
          _InfoCard(label: 'Cells',    value: '$cellsCount',   icon: Icons.grid_view),
          _InfoCard(label: 'Objects',  value: '$totalObjects', icon: Icons.category),
          _InfoCard(label: 'Mode',
              value: viewMode == MapViewMode.floorPlan ? 'Plan' : 'Tech',
              icon: viewMode == MapViewMode.floorPlan ? Icons.apartment : Icons.grid_on),
        ]),
        if (objects.isNotEmpty) ...[
          const SizedBox(height: 8),
          SizedBox(
            height: 28,
            child: ListView(
              scrollDirection: Axis.horizontal,
              children: objects.map((o) => Container(
                margin: const EdgeInsets.only(right: 6),
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: _objectColor(o.type).withOpacity(0.25),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: _objectColor(o.type), width: 1),
                ),
                child: Text(
                  '${o.label} ${(o.confidence * 100).toStringAsFixed(0)}%',
                  style: TextStyle(color: _objectColor(o.type), fontSize: 11),
                ),
              )).toList(),
            ),
          ),
        ],
      ]),
    );
  }

  Widget _buildMapArea() {
    return GestureDetector(
      onScaleStart: (d) {
        _scaleAtGestureStart = scale;
      },
      onScaleUpdate: (d) {
        setState(() {
          panOffset += d.focalPointDelta;
          if (d.scale != 1.0) {
            scale = (_scaleAtGestureStart * d.scale).clamp(8.0, 200.0);
          }
        });
      },
      child: Container(
        color: viewMode == MapViewMode.floorPlan
            ? const Color(0xFFF0F0EC) : Colors.black,
        child: CustomPaint(
          painter: viewMode == MapViewMode.floorPlan
              ? FloorPlanPainter(
            occupancyGrid: occupancyGrid,
            gridWidth: gridWidth, gridHeight: gridHeight,
            gridResolution: gridResolution,
            originX: originX, originZ: originZ,
            edges: edges, objects: objects,
            posX: posX, posZ: posZ,
            scale: scale, panOffset: panOffset,
          )
              : TechnicalMapPainter(
            occupancyGrid: occupancyGrid,
            gridWidth: gridWidth, gridHeight: gridHeight,
            gridResolution: gridResolution,
            originX: originX, originZ: originZ,
            edges: edges, objects: objects,
            posX: posX, posZ: posZ,
            scale: scale, panOffset: panOffset,
          ),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }

  Widget _buildControlPanel() {
    return Container(
      padding: const EdgeInsets.all(12),
      color: Colors.grey[900],
      child: Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
        ElevatedButton.icon(
          onPressed: _openAR,
          icon: const Icon(Icons.camera_alt),
          label: const Text('Start AR'),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.blue[700],
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          ),
        ),
        IconButton(
          icon: const Icon(Icons.remove_circle_outline),
          onPressed: () => setState(() => scale = (scale - 8).clamp(8.0, 200.0)),
          iconSize: 32, color: Colors.white70,
        ),
        Text('${scale.toStringAsFixed(0)} px/m',
            style: const TextStyle(color: Colors.white54, fontSize: 12)),
        IconButton(
          icon: const Icon(Icons.add_circle_outline),
          onPressed: () => setState(() => scale = (scale + 8).clamp(8.0, 200.0)),
          iconSize: 32, color: Colors.white70,
        ),
      ]),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

// Updated for new 8-class model
Color _objectColor(String type) {
  switch (type.toUpperCase()) {
    case 'CHAIR':             return Colors.green;
    case 'DOOR':              return Colors.orange;
    case 'FIRE_EXTINGUISHER': return Colors.red;
    case 'LIFT_GATE':         return Colors.purple;
    case 'NOTICE_BOARD':      return Colors.cyan;
    case 'TRASH_CAN':         return Colors.brown;
    case 'WATER_PURIFIER':    return Colors.lightBlue;
    case 'WINDOW':            return Colors.blueGrey;
    default:                  return Colors.white70;
  }
}

class _InfoCard extends StatelessWidget {
  final String label, value;
  final IconData icon;
  const _InfoCard({required this.label, required this.value, required this.icon});

  @override
  Widget build(BuildContext context) => Column(children: [
    Icon(icon, color: Colors.blue[300], size: 18),
    const SizedBox(height: 2),
    Text(label, style: const TextStyle(color: Colors.grey, fontSize: 11)),
    Text(value,  style: const TextStyle(color: Colors.white, fontSize: 14,
        fontWeight: FontWeight.bold)),
  ]);
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared coordinate helper
// ─────────────────────────────────────────────────────────────────────────────

Offset _worldToScreen(
    double worldX, double worldZ, Offset centre, double scale) {
  return Offset(centre.dx + worldX * scale, centre.dy + worldZ * scale);
}

// ─────────────────────────────────────────────────────────────────────────────
// Floor Plan Painter
// ─────────────────────────────────────────────────────────────────────────────

class FloorPlanPainter extends CustomPainter {
  final Uint8List? occupancyGrid;
  final int gridWidth, gridHeight;
  final double gridResolution;
  final int originX, originZ;
  final List<MapEdge> edges;
  final List<MapObject> objects;
  final double posX, posZ;
  final double scale;
  final Offset panOffset;

  const FloorPlanPainter({
    required this.occupancyGrid,
    required this.gridWidth, required this.gridHeight,
    required this.gridResolution,
    required this.originX, required this.originZ,
    required this.edges, required this.objects,
    required this.posX, required this.posZ,
    required this.scale, required this.panOffset,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final centre = Offset(size.width / 2, size.height / 2) + panOffset;

    _drawBackground(canvas, size);
    _drawMeasurementGrid(canvas, size, centre);
    _drawOccupancyGrid(canvas, centre);
    _drawEdges(canvas, centre);
    _drawObjects(canvas, centre);
    _drawPositionMarker(canvas, centre);
    _drawCompass(canvas, size);
    _drawScaleBar(canvas, size);
  }

  void _drawBackground(Canvas canvas, Size size) {
    canvas.drawRect(Rect.fromLTWH(0, 0, size.width, size.height),
        Paint()..color = const Color(0xFFF0F0EC));
  }

  void _drawMeasurementGrid(Canvas canvas, Size size, Offset centre) {
    final paint = Paint()..color = Colors.grey.withOpacity(0.25)..strokeWidth = 0.5;
    for (int i = -20; i <= 20; i++) {
      final pos = i * scale;
      canvas.drawLine(Offset(centre.dx + pos, 0), Offset(centre.dx + pos, size.height), paint);
      canvas.drawLine(Offset(0, centre.dy + pos), Offset(size.width, centre.dy + pos), paint);
    }
    final axisPaint = Paint()..color = Colors.grey.withOpacity(0.5)..strokeWidth = 1;
    canvas.drawLine(Offset(0, centre.dy), Offset(size.width, centre.dy), axisPaint);
    canvas.drawLine(Offset(centre.dx, 0), Offset(centre.dx, size.height), axisPaint);
  }

  void _drawOccupancyGrid(Canvas canvas, Offset centre) {
    final grid = occupancyGrid;
    if (grid == null || gridWidth == 0 || gridHeight == 0) return;

    final cellPx  = gridResolution * scale;
    final freePaint = Paint()..color = Colors.white;
    final occPaint  = Paint()..color = const Color(0xFFD3C5B0);
    final bordPaint = Paint()
      ..color = const Color(0xFFB8A890)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.4;

    for (int cz = 0; cz < gridHeight; cz++) {
      for (int cx = 0; cx < gridWidth; cx++) {
        final idx = cz * gridWidth + cx;
        if (idx >= grid.length) continue;
        final val = grid[idx];
        if (val == 0) continue;

        final worldX = (cx + originX) * gridResolution;
        final worldZ = (cz + originZ) * gridResolution;
        final screen = _worldToScreen(worldX, worldZ, centre, scale);
        final rect = Rect.fromLTWH(screen.dx, screen.dy, cellPx, cellPx);

        canvas.drawRect(rect, val == 1 ? freePaint : occPaint);
        if (val == 2) canvas.drawRect(rect, bordPaint);
      }
    }
  }

  void _drawEdges(Canvas canvas, Offset centre) {
    final shadowPaint = Paint()
      ..color = Colors.black.withOpacity(0.08)
      ..strokeWidth = 7 ..strokeCap = StrokeCap.round;
    final wallPaint = Paint()
      ..color = const Color(0xFF1A1A1A)
      ..strokeWidth = 5 ..strokeCap = StrokeCap.round;

    for (final e in edges) {
      final s   = _worldToScreen(e.start.dx, e.start.dy, centre, scale);
      final end = _worldToScreen(e.end.dx,   e.end.dy,   centre, scale);
      canvas.drawLine(s + const Offset(1.5, 1.5), end + const Offset(1.5, 1.5), shadowPaint);
      canvas.drawLine(s, end, wallPaint);
    }
  }

  void _drawObjects(Canvas canvas, Offset centre) {
    for (final obj in objects) {
      final screen = _worldToScreen(obj.x, obj.z, centre, scale);
      final color  = _objectColor(obj.type);

      canvas.drawCircle(screen, 10,
          Paint()..color = color.withOpacity(0.85)..style = PaintingStyle.fill);
      canvas.drawCircle(screen, 10,
          Paint()..color = color..style = PaintingStyle.stroke..strokeWidth = 1.5);

      final tp = TextPainter(
        text: TextSpan(
          text: obj.label,
          style: const TextStyle(color: Colors.black87, fontSize: 9,
              fontWeight: FontWeight.bold),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      final labelRect = Rect.fromLTWH(
          screen.dx - tp.width / 2 - 3, screen.dy + 12, tp.width + 6, tp.height + 2);
      canvas.drawRRect(RRect.fromRectAndRadius(labelRect, const Radius.circular(3)),
          Paint()..color = Colors.white.withOpacity(0.85));
      tp.paint(canvas, Offset(screen.dx - tp.width / 2, screen.dy + 13));
    }
  }

  void _drawPositionMarker(Canvas canvas, Offset centre) {
    final pos = _worldToScreen(posX, posZ, centre, scale);

    canvas.drawCircle(pos, 18,
        Paint()..color = Colors.blue.withOpacity(0.15)
          ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 8));
    canvas.drawCircle(pos, 11, Paint()..color = Colors.white);
    canvas.drawCircle(pos, 11,
        Paint()..color = Colors.blue..style = PaintingStyle.stroke..strokeWidth = 2);
    canvas.drawCircle(pos, 5, Paint()..color = Colors.blue);
    final arrow = Path()
      ..moveTo(pos.dx, pos.dy - 20)
      ..lineTo(pos.dx - 5, pos.dy - 13)
      ..lineTo(pos.dx + 5, pos.dy - 13)
      ..close();
    canvas.drawPath(arrow, Paint()..color = Colors.blue);
  }

  void _drawCompass(Canvas canvas, Size size) {
    final c = Offset(size.width - 50, 50);
    canvas.drawCircle(c, 26, Paint()..color = Colors.white.withOpacity(0.9));
    canvas.drawCircle(c, 26,
        Paint()..color = Colors.black54..style = PaintingStyle.stroke..strokeWidth = 1.5);

    canvas.drawPath(
      Path()
        ..moveTo(c.dx, c.dy - 20) ..lineTo(c.dx - 7, c.dy) ..lineTo(c.dx, c.dy - 4)
        ..lineTo(c.dx + 7, c.dy) ..close(),
      Paint()..color = Colors.red,
    );
    canvas.drawPath(
      Path()
        ..moveTo(c.dx, c.dy + 20) ..lineTo(c.dx - 7, c.dy) ..lineTo(c.dx, c.dy + 4)
        ..lineTo(c.dx + 7, c.dy) ..close(),
      Paint()..color = Colors.grey,
    );
    final tp = TextPainter(
      text: const TextSpan(text: 'N',
          style: TextStyle(color: Colors.black, fontSize: 13, fontWeight: FontWeight.bold)),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, Offset(c.dx - tp.width / 2, c.dy - 40));
  }

  void _drawScaleBar(Canvas canvas, Size size) {
    final barLen = scale * 2;
    final left   = Offset(20, size.height - 36);
    final right  = Offset(20 + barLen, size.height - 36);

    canvas.drawRect(Rect.fromLTWH(left.dx - 4, left.dy - 12, barLen + 8, 24),
        Paint()..color = Colors.white.withOpacity(0.85));

    final lp = Paint()..color = Colors.black..strokeWidth = 2.5..strokeCap = StrokeCap.square;
    canvas.drawLine(left, right, lp);
    canvas.drawLine(left  - const Offset(0, 5), left  + const Offset(0, 5), lp);
    canvas.drawLine(right - const Offset(0, 5), right + const Offset(0, 5), lp);

    final tp = TextPainter(
      text: const TextSpan(text: '2 m',
          style: TextStyle(color: Colors.black, fontSize: 11, fontWeight: FontWeight.bold)),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, Offset(left.dx + barLen / 2 - tp.width / 2, left.dy - 24));
  }

  @override
  bool shouldRepaint(FloorPlanPainter old) =>
      occupancyGrid != old.occupancyGrid ||
          objects != old.objects || edges != old.edges ||
          posX != old.posX || posZ != old.posZ ||
          scale != old.scale || panOffset != old.panOffset;
}

// ─────────────────────────────────────────────────────────────────────────────
// Technical Map Painter
// ─────────────────────────────────────────────────────────────────────────────

class TechnicalMapPainter extends CustomPainter {
  final Uint8List? occupancyGrid;
  final int gridWidth, gridHeight;
  final double gridResolution;
  final int originX, originZ;
  final List<MapEdge> edges;
  final List<MapObject> objects;
  final double posX, posZ;
  final double scale;
  final Offset panOffset;

  const TechnicalMapPainter({
    required this.occupancyGrid,
    required this.gridWidth, required this.gridHeight,
    required this.gridResolution,
    required this.originX, required this.originZ,
    required this.edges, required this.objects,
    required this.posX, required this.posZ,
    required this.scale, required this.panOffset,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final centre = Offset(size.width / 2, size.height / 2) + panOffset;

    _drawGrid(canvas, size, centre);
    _drawOccupancyGrid(canvas, centre);
    _drawEdges(canvas, centre);
    _drawObjects(canvas, centre);
    _drawPositionMarker(canvas, centre);
    _drawLegend(canvas, size);
  }

  void _drawGrid(Canvas canvas, Size size, Offset centre) {
    final minor = Paint()..color = Colors.grey[800]!..strokeWidth = 0.4;
    final major = Paint()..color = Colors.grey[700]!..strokeWidth = 0.8;
    for (int i = -20; i <= 20; i++) {
      final p = Paint()..strokeWidth = (i % 5 == 0 ? major : minor).strokeWidth
        ..color = (i % 5 == 0 ? major : minor).color;
      final pos = i * scale;
      canvas.drawLine(Offset(centre.dx + pos, 0), Offset(centre.dx + pos, size.height), p);
      canvas.drawLine(Offset(0, centre.dy + pos), Offset(size.width, centre.dy + pos), p);
    }
    final axis = Paint()..color = Colors.blue[700]!..strokeWidth = 1.5;
    canvas.drawLine(Offset(0, centre.dy), Offset(size.width, centre.dy), axis);
    canvas.drawLine(Offset(centre.dx, 0), Offset(centre.dx, size.height), axis);
  }

  void _drawOccupancyGrid(Canvas canvas, Offset centre) {
    final grid = occupancyGrid;
    if (grid == null || gridWidth == 0) return;

    final cellPx    = gridResolution * scale;
    final freePaint = Paint()..color = Colors.green.withOpacity(0.15);
    final occPaint  = Paint()..color = Colors.red.withOpacity(0.45);

    for (int cz = 0; cz < gridHeight; cz++) {
      for (int cx = 0; cx < gridWidth; cx++) {
        final idx = cz * gridWidth + cx;
        if (idx >= grid.length) continue;
        final val = grid[idx];
        if (val == 0) continue;

        final worldX = (cx + originX) * gridResolution;
        final worldZ = (cz + originZ) * gridResolution;
        final screen = _worldToScreen(worldX, worldZ, centre, scale);

        canvas.drawRect(
          Rect.fromLTWH(screen.dx, screen.dy, cellPx, cellPx),
          val == 1 ? freePaint : occPaint,
        );
      }
    }
  }

  void _drawEdges(Canvas canvas, Offset centre) {
    for (final e in edges) {
      final s   = _worldToScreen(e.start.dx, e.start.dy, centre, scale);
      final end = _worldToScreen(e.end.dx,   e.end.dy,   centre, scale);
      canvas.drawLine(s, end,
          Paint()
            ..color = Colors.yellow.withOpacity(0.3 + e.confidence * 0.7)
            ..strokeWidth = 2.5 ..strokeCap = StrokeCap.round);
      canvas.drawCircle(s,   3, Paint()..color = Colors.orange);
      canvas.drawCircle(end, 3, Paint()..color = Colors.orange);
    }
  }

  void _drawObjects(Canvas canvas, Offset centre) {
    for (final obj in objects) {
      final screen = _worldToScreen(obj.x, obj.z, centre, scale);
      final color  = _objectColor(obj.type);

      const half = 7.0;
      final lp = Paint()..color = color..strokeWidth = 2;
      canvas.drawLine(screen - const Offset(half, half), screen + const Offset(half, half), lp);
      canvas.drawLine(screen - const Offset(half, -half), screen + const Offset(half, -half), lp);

      final tp = TextPainter(
        text: TextSpan(text: obj.label,
            style: TextStyle(color: color, fontSize: 10)),
        textDirection: TextDirection.ltr,
      )..layout();
      tp.paint(canvas, Offset(screen.dx + 10, screen.dy - 8));
    }
  }

  void _drawPositionMarker(Canvas canvas, Offset centre) {
    final pos = _worldToScreen(posX, posZ, centre, scale);
    canvas.drawCircle(pos, 13, Paint()..color = Colors.blue.withOpacity(0.25));
    canvas.drawCircle(pos, 7,  Paint()..color = Colors.blue);
    canvas.drawLine(pos, pos - const Offset(0, 18),
        Paint()..color = Colors.blue..strokeWidth = 2..strokeCap = StrokeCap.round);
  }

  void _drawLegend(Canvas canvas, Size size) {
    final x = size.width - 140;
    double y = 16;
    final items = <(Color, String)>[
      (Colors.blue,                        'Position'),
      (Colors.yellow,                      'Edges'),
      (Colors.red.withOpacity(0.45),       'Occupied'),
      (Colors.green.withOpacity(0.3),      'Free'),
      (Colors.orange,                      'Objects'),
    ];
    canvas.drawRRect(
      RRect.fromRectAndRadius(
          Rect.fromLTWH(x - 6, y - 4, 140, items.length * 24.0 + 8),
          const Radius.circular(6)),
      Paint()..color = Colors.black54,
    );
    for (final item in items) {
      canvas.drawRect(Rect.fromLTWH(x, y + 2, 13, 13), Paint()..color = item.$1);
      final tp = TextPainter(
        text: TextSpan(text: item.$2,
            style: const TextStyle(color: Colors.white, fontSize: 11)),
        textDirection: TextDirection.ltr,
      )..layout();
      tp.paint(canvas, Offset(x + 18, y));
      y += 24;
    }
  }

  @override
  bool shouldRepaint(TechnicalMapPainter old) =>
      occupancyGrid != old.occupancyGrid ||
          objects != old.objects || edges != old.edges ||
          posX != old.posX || posZ != old.posZ ||
          scale != old.scale || panOffset != old.panOffset;
}