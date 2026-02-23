import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class IndoorMapViewer extends StatefulWidget {
  const IndoorMapViewer({Key? key}) : super(key: key);

  @override
  State<IndoorMapViewer> createState() => _IndoorMapViewerState();
}

class _IndoorMapViewerState extends State<IndoorMapViewer> {
  static const platform = MethodChannel('com.ketan.slam/ar');

  List<MapEdge> edges = [];
  Uint8List? occupancyGrid;
  int gridWidth = 100;
  int gridHeight = 100;
  double gridResolution = 0.1;

  Offset currentPosition = Offset.zero;
  double currentX = 0.0;
  double currentY = 0.0;
  double currentZ = 0.0;

  double scale = 20.0; // pixels per meter
  Offset panOffset = Offset.zero;

  // NEW: View mode toggle
  MapViewMode viewMode = MapViewMode.floorPlan;

  @override
  void initState() {
    super.initState();
    _setupMethodChannel();
  }

  void _setupMethodChannel() {
    platform.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'updatePose':
          setState(() {
            currentX = call.arguments['x'] ?? 0.0;
            currentY = call.arguments['y'] ?? 0.0;
            currentZ = call.arguments['z'] ?? 0.0;
            currentPosition = Offset(currentX, currentZ);
          });
          break;

        case 'updateMap':
          _handleMapUpdate(call.arguments);
          break;
      }
    });
  }

  void _handleMapUpdate(dynamic arguments) {
    try {
      // Update edges
      if (arguments['edges'] != null) {
        final edgesList = List<Map<dynamic, dynamic>>.from(arguments['edges']);
        setState(() {
          edges = edgesList.map((e) => MapEdge(
            start: Offset(e['startX'] ?? 0.0, e['startZ'] ?? 0.0),
            end: Offset(e['endX'] ?? 0.0, e['endZ'] ?? 0.0),
            confidence: e['confidence'] ?? 0.5,
          )).toList();
        });
      }

      // Update occupancy grid
      if (arguments['occupancyGrid'] != null) {
        setState(() {
          occupancyGrid = arguments['occupancyGrid'];
          gridWidth = arguments['gridWidth'] ?? 100;
          gridHeight = arguments['gridHeight'] ?? 100;
          gridResolution = arguments['gridResolution'] ?? 0.1;
        });
      }
    } catch (e) {
      print('Error updating map: $e');
    }
  }

  Future<void> _openAR() async {
    try {
      await platform.invokeMethod('openAR');
    } catch (e) {
      print('Error opening AR: $e');
    }
  }

  void _resetView() {
    setState(() {
      panOffset = Offset.zero;
      scale = 20.0;
    });
  }

  void _clearMap() {
    setState(() {
      edges.clear();
      occupancyGrid = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Indoor Map'),
        backgroundColor: Colors.blue[900],
        actions: [
          // View mode toggle
          IconButton(
            icon: Icon(viewMode == MapViewMode.floorPlan ? Icons.grid_on : Icons.apartment),
            onPressed: () {
              setState(() {
                viewMode = viewMode == MapViewMode.floorPlan
                    ? MapViewMode.technical
                    : MapViewMode.floorPlan;
              });
            },
            tooltip: viewMode == MapViewMode.floorPlan
                ? 'Switch to Technical View'
                : 'Switch to Floor Plan',
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _resetView,
            tooltip: 'Reset View',
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline),
            onPressed: _clearMap,
            tooltip: 'Clear Map',
          ),
        ],
      ),
      body: Column(
        children: [
          // Info Panel
          Container(
            padding: const EdgeInsets.all(16),
            color: Colors.grey[900],
            child: Column(
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    _InfoCard(
                      label: 'Position',
                      value: '(${currentX.toStringAsFixed(2)}, ${currentZ.toStringAsFixed(2)})',
                      icon: Icons.location_on,
                    ),
                    _InfoCard(
                      label: 'Walls/Edges',
                      value: '${edges.length}',
                      icon: Icons.timeline,
                    ),
                    _InfoCard(
                      label: 'View Mode',
                      value: viewMode == MapViewMode.floorPlan ? 'Floor Plan' : 'Technical',
                      icon: viewMode == MapViewMode.floorPlan ? Icons.apartment : Icons.grid_on,
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                // View mode description
                Text(
                  viewMode == MapViewMode.floorPlan
                      ? 'Floor Plan: Clean 2D view showing walls, obstacles, and walkable paths'
                      : 'Technical: Detailed grid view with occupancy data',
                  style: TextStyle(
                    color: Colors.grey[400],
                    fontSize: 12,
                  ),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),

          // Map Viewer
          Expanded(
            child: GestureDetector(
              onScaleStart: (details) {
                // Store initial values
              },
              onScaleUpdate: (details) {
                setState(() {
                  // Pan
                  panOffset += details.focalPointDelta;

                  // Zoom
                  if (details.scale != 1.0) {
                    scale = (scale * details.scale).clamp(5.0, 100.0);
                  }
                });
              },
              child: Container(
                color: viewMode == MapViewMode.floorPlan ? Colors.grey[200] : Colors.black,
                child: CustomPaint(
                  painter: viewMode == MapViewMode.floorPlan
                      ? FloorPlanPainter(
                    edges: edges,
                    occupancyGrid: occupancyGrid,
                    gridWidth: gridWidth,
                    gridHeight: gridHeight,
                    gridResolution: gridResolution,
                    currentPosition: currentPosition,
                    scale: scale,
                    offset: panOffset,
                  )
                      : TechnicalMapPainter(
                    edges: edges,
                    occupancyGrid: occupancyGrid,
                    gridWidth: gridWidth,
                    gridHeight: gridHeight,
                    gridResolution: gridResolution,
                    currentPosition: currentPosition,
                    scale: scale,
                    offset: panOffset,
                  ),
                  child: Container(),
                ),
              ),
            ),
          ),

          // Control Panel
          Container(
            padding: const EdgeInsets.all(16),
            color: Colors.grey[900],
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton.icon(
                  onPressed: _openAR,
                  icon: const Icon(Icons.camera_alt),
                  label: const Text('Start AR Mapping'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.blue[700],
                    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.remove),
                  onPressed: () {
                    setState(() {
                      scale = (scale - 5).clamp(5.0, 100.0);
                    });
                  },
                  iconSize: 32,
                  color: Colors.white,
                ),
                IconButton(
                  icon: const Icon(Icons.add),
                  onPressed: () {
                    setState(() {
                      scale = (scale + 5).clamp(5.0, 100.0);
                    });
                  },
                  iconSize: 32,
                  color: Colors.white,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _InfoCard extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;

  const _InfoCard({
    required this.label,
    required this.value,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Icon(icon, color: Colors.blue[300], size: 20),
        const SizedBox(height: 4),
        Text(
          label,
          style: const TextStyle(color: Colors.grey, fontSize: 12),
        ),
        Text(
          value,
          style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold),
        ),
      ],
    );
  }
}

enum MapViewMode {
  floorPlan,    // Clean 2D architectural view
  technical,    // Technical grid view
}

class MapEdge {
  final Offset start;
  final Offset end;
  final double confidence;

  MapEdge({
    required this.start,
    required this.end,
    required this.confidence,
  });
}

// ============================================================================
// FLOOR PLAN PAINTER - Clean 2D Architectural View
// ============================================================================
class FloorPlanPainter extends CustomPainter {
  final List<MapEdge> edges;
  final Uint8List? occupancyGrid;
  final int gridWidth;
  final int gridHeight;
  final double gridResolution;
  final Offset currentPosition;
  final double scale;
  final Offset offset;

  FloorPlanPainter({
    required this.edges,
    required this.occupancyGrid,
    required this.gridWidth,
    required this.gridHeight,
    required this.gridResolution,
    required this.currentPosition,
    required this.scale,
    required this.offset,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2) + offset;

    // Draw clean background
    _drawCleanBackground(canvas, size);

    // Draw walkable areas (free space) in white/light
    _drawWalkablePaths(canvas, size, center);

    // Draw obstacles as solid shapes
    _drawObstacles(canvas, size, center);

    // Draw walls (edges) as thick black lines
    _drawWalls(canvas, center);

    // Draw measurement grid (subtle)
    _drawMeasurementGrid(canvas, size, center);

    // Draw current position with direction indicator
    _drawPositionMarker(canvas, center);

    // Draw compass
    _drawCompass(canvas, size);

    // Draw scale indicator
    _drawScaleBar(canvas, size);
  }

  void _drawCleanBackground(Canvas canvas, Size size) {
    final paint = Paint()..color = const Color(0xFFF5F5F5); // Light gray background
    canvas.drawRect(Rect.fromLTWH(0, 0, size.width, size.height), paint);
  }

  void _drawWalkablePaths(Canvas canvas, Size size, Offset center) {
    if (occupancyGrid == null) return;

    final cellSize = gridResolution * scale;

    for (int z = 0; z < gridHeight; z++) {
      for (int x = 0; x < gridWidth; x++) {
        final index = z * gridWidth + x;
        if (index >= occupancyGrid!.length) continue;

        final cellValue = occupancyGrid![index];

        if (cellValue == 1) {
          // Free space - white/walkable
          final worldX = (x - gridWidth / 2) * gridResolution;
          final worldZ = (z - gridHeight / 2) * gridResolution;

          final screenX = center.dx + worldX * scale;
          final screenZ = center.dy + worldZ * scale;

          final paint = Paint()..color = Colors.white;

          canvas.drawRect(
            Rect.fromLTWH(screenX, screenZ, cellSize, cellSize),
            paint,
          );
        }
      }
    }
  }

  void _drawObstacles(Canvas canvas, Size size, Offset center) {
    if (occupancyGrid == null) return;

    final cellSize = gridResolution * scale;

    for (int z = 0; z < gridHeight; z++) {
      for (int x = 0; x < gridWidth; x++) {
        final index = z * gridWidth + x;
        if (index >= occupancyGrid!.length) continue;

        final cellValue = occupancyGrid![index];

        if (cellValue == 2) {
          // Occupied space - furniture/obstacles in light brown/tan
          final worldX = (x - gridWidth / 2) * gridResolution;
          final worldZ = (z - gridHeight / 2) * gridResolution;

          final screenX = center.dx + worldX * scale;
          final screenZ = center.dy + worldZ * scale;

          final paint = Paint()..color = const Color(0xFFD3C5B0); // Light tan for obstacles

          canvas.drawRect(
            Rect.fromLTWH(screenX, screenZ, cellSize, cellSize),
            paint,
          );

          // Add subtle border
          final borderPaint = Paint()
            ..color = const Color(0xFFB8A890)
            ..style = PaintingStyle.stroke
            ..strokeWidth = 0.5;

          canvas.drawRect(
            Rect.fromLTWH(screenX, screenZ, cellSize, cellSize),
            borderPaint,
          );
        }
      }
    }
  }

  void _drawWalls(Canvas canvas, Offset center) {
    for (final edge in edges) {
      final startScreen = Offset(
        center.dx + edge.start.dx * scale,
        center.dy + edge.start.dy * scale,
      );

      final endScreen = Offset(
        center.dx + edge.end.dx * scale,
        center.dy + edge.end.dy * scale,
      );

      // Draw wall shadow for depth
      final shadowPaint = Paint()
        ..color = Colors.black.withOpacity(0.1)
        ..strokeWidth = 8
        ..strokeCap = StrokeCap.round;

      canvas.drawLine(
          startScreen + const Offset(2, 2),
          endScreen + const Offset(2, 2),
          shadowPaint
      );

      // Draw main wall
      final wallPaint = Paint()
        ..color = Colors.black.withOpacity(0.8)
        ..strokeWidth = 6
        ..strokeCap = StrokeCap.round;

      canvas.drawLine(startScreen, endScreen, wallPaint);

      // Draw wall outline for better definition
      final outlinePaint = Paint()
        ..color = Colors.black
        ..strokeWidth = 7
        ..strokeCap = StrokeCap.round
        ..style = PaintingStyle.stroke;

      canvas.drawLine(startScreen, endScreen, outlinePaint);
    }
  }

  void _drawMeasurementGrid(Canvas canvas, Size size, Offset center) {
    final paint = Paint()
      ..color = Colors.grey[300]!
      ..strokeWidth = 0.5;

    // Draw grid lines every meter (subtle)
    for (int i = -10; i <= 10; i++) {
      final pos = i * scale;

      // Vertical lines
      canvas.drawLine(
        Offset(center.dx + pos, 0),
        Offset(center.dx + pos, size.height),
        paint,
      );

      // Horizontal lines
      canvas.drawLine(
        Offset(0, center.dy + pos),
        Offset(size.width, center.dy + pos),
        paint,
      );

      // Draw meter labels
      if (i != 0 && i % 2 == 0) {
        final textPainter = TextPainter(
          text: TextSpan(
            text: '${i}m',
            style: const TextStyle(
              color: Colors.grey,
              fontSize: 10,
            ),
          ),
          textDirection: TextDirection.ltr,
        );
        textPainter.layout();

        // X-axis labels
        textPainter.paint(
          canvas,
          Offset(center.dx + pos - 10, center.dy + 5),
        );

        // Z-axis labels
        textPainter.paint(
          canvas,
          Offset(center.dx + 5, center.dy + pos - 10),
        );
      }
    }
  }

  void _drawPositionMarker(Canvas canvas, Offset center) {
    final posScreen = Offset(
      center.dx + currentPosition.dx * scale,
      center.dy + currentPosition.dy * scale,
    );

    // Draw position circle with glow effect
    final glowPaint = Paint()
      ..color = Colors.blue.withOpacity(0.2)
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 10);
    canvas.drawCircle(posScreen, 20, glowPaint);

    // Outer circle
    final outerPaint = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.fill;
    canvas.drawCircle(posScreen, 12, outerPaint);

    // Border
    final borderPaint = Paint()
      ..color = Colors.blue
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2;
    canvas.drawCircle(posScreen, 12, borderPaint);

    // Inner dot
    final innerPaint = Paint()
      ..color = Colors.blue
      ..style = PaintingStyle.fill;
    canvas.drawCircle(posScreen, 6, innerPaint);

    // Direction indicator (pointing up/north)
    final directionPath = Path();
    directionPath.moveTo(posScreen.dx, posScreen.dy - 20);
    directionPath.lineTo(posScreen.dx - 6, posScreen.dy - 12);
    directionPath.lineTo(posScreen.dx + 6, posScreen.dy - 12);
    directionPath.close();

    final directionPaint = Paint()
      ..color = Colors.blue
      ..style = PaintingStyle.fill;
    canvas.drawPath(directionPath, directionPaint);
  }

  void _drawCompass(Canvas canvas, Size size) {
    final compassCenter = Offset(size.width - 60, 60);
    final radius = 30.0;

    // Compass circle background
    final bgPaint = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.fill;
    canvas.drawCircle(compassCenter, radius, bgPaint);

    // Compass circle border
    final borderPaint = Paint()
      ..color = Colors.black
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2;
    canvas.drawCircle(compassCenter, radius, borderPaint);

    // North arrow (red)
    final northPath = Path();
    northPath.moveTo(compassCenter.dx, compassCenter.dy - radius + 5);
    northPath.lineTo(compassCenter.dx - 8, compassCenter.dy);
    northPath.lineTo(compassCenter.dx, compassCenter.dy - 5);
    northPath.lineTo(compassCenter.dx + 8, compassCenter.dy);
    northPath.close();

    final northPaint = Paint()
      ..color = Colors.red
      ..style = PaintingStyle.fill;
    canvas.drawPath(northPath, northPaint);

    // South arrow (white/gray)
    final southPath = Path();
    southPath.moveTo(compassCenter.dx, compassCenter.dy + radius - 5);
    southPath.lineTo(compassCenter.dx - 8, compassCenter.dy);
    southPath.lineTo(compassCenter.dx, compassCenter.dy + 5);
    southPath.lineTo(compassCenter.dx + 8, compassCenter.dy);
    southPath.close();

    final southPaint = Paint()
      ..color = Colors.grey
      ..style = PaintingStyle.fill;
    canvas.drawPath(southPath, southPaint);

    // "N" label
    final textPainter = TextPainter(
      text: const TextSpan(
        text: 'N',
        style: TextStyle(
          color: Colors.black,
          fontSize: 14,
          fontWeight: FontWeight.bold,
        ),
      ),
      textDirection: TextDirection.ltr,
    );
    textPainter.layout();
    textPainter.paint(
      canvas,
      Offset(compassCenter.dx - 5, compassCenter.dy - radius - 20),
    );
  }

  void _drawScaleBar(Canvas canvas, Size size) {
    final barStart = Offset(20, size.height - 40);
    final barLength = scale * 2; // Represents 2 meters
    final barEnd = Offset(barStart.dx + barLength, barStart.dy);

    // Scale bar background
    final bgPaint = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.fill;
    canvas.drawRect(
      Rect.fromLTWH(barStart.dx - 5, barStart.dy - 15, barLength + 10, 30),
      bgPaint,
    );

    // Scale bar line
    final linePaint = Paint()
      ..color = Colors.black
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.square;
    canvas.drawLine(barStart, barEnd, linePaint);

    // End caps
    canvas.drawLine(
      Offset(barStart.dx, barStart.dy - 5),
      Offset(barStart.dx, barStart.dy + 5),
      linePaint,
    );
    canvas.drawLine(
      Offset(barEnd.dx, barEnd.dy - 5),
      Offset(barEnd.dx, barEnd.dy + 5),
      linePaint,
    );

    // Label
    final textPainter = TextPainter(
      text: const TextSpan(
        text: '2m',
        style: TextStyle(
          color: Colors.black,
          fontSize: 12,
          fontWeight: FontWeight.bold,
        ),
      ),
      textDirection: TextDirection.ltr,
    );
    textPainter.layout();
    textPainter.paint(
      canvas,
      Offset(barStart.dx + barLength / 2 - 10, barStart.dy - 25),
    );
  }

  @override
  bool shouldRepaint(covariant FloorPlanPainter oldDelegate) {
    return edges != oldDelegate.edges ||
        occupancyGrid != oldDelegate.occupancyGrid ||
        currentPosition != oldDelegate.currentPosition ||
        scale != oldDelegate.scale ||
        offset != oldDelegate.offset;
  }
}

// ============================================================================
// TECHNICAL MAP PAINTER - Original Grid View
// ============================================================================
class TechnicalMapPainter extends CustomPainter {
  final List<MapEdge> edges;
  final Uint8List? occupancyGrid;
  final int gridWidth;
  final int gridHeight;
  final double gridResolution;
  final Offset currentPosition;
  final double scale;
  final Offset offset;

  TechnicalMapPainter({
    required this.edges,
    required this.occupancyGrid,
    required this.gridWidth,
    required this.gridHeight,
    required this.gridResolution,
    required this.currentPosition,
    required this.scale,
    required this.offset,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2) + offset;

    // Draw grid background
    _drawGrid(canvas, size, center);

    // Draw occupancy grid
    if (occupancyGrid != null) {
      _drawOccupancyGrid(canvas, size, center);
    }

    // Draw edges
    _drawEdges(canvas, center);

    // Draw current position
    _drawCurrentPosition(canvas, center);

    // Draw legend
    _drawLegend(canvas, size);
  }

  void _drawGrid(Canvas canvas, Size size, Offset center) {
    final paint = Paint()
      ..color = Colors.grey[800]!
      ..strokeWidth = 0.5;

    // Draw grid lines every meter
    for (int i = -10; i <= 10; i++) {
      final pos = i * scale;

      // Vertical lines
      canvas.drawLine(
        Offset(center.dx + pos, 0),
        Offset(center.dx + pos, size.height),
        paint,
      );

      // Horizontal lines
      canvas.drawLine(
        Offset(0, center.dy + pos),
        Offset(size.width, center.dy + pos),
        paint,
      );
    }

    // Draw axes
    final axisPaint = Paint()
      ..color = Colors.blue[700]!
      ..strokeWidth = 2;

    // X-axis
    canvas.drawLine(
      Offset(0, center.dy),
      Offset(size.width, center.dy),
      axisPaint,
    );

    // Z-axis
    canvas.drawLine(
      Offset(center.dx, 0),
      Offset(center.dx, size.height),
      axisPaint,
    );
  }

  void _drawOccupancyGrid(Canvas canvas, Size size, Offset center) {
    if (occupancyGrid == null) return;

    final cellSize = gridResolution * scale;

    for (int z = 0; z < gridHeight; z++) {
      for (int x = 0; x < gridWidth; x++) {
        final index = z * gridWidth + x;
        if (index >= occupancyGrid!.length) continue;

        final cellValue = occupancyGrid![index];

        Color? color;
        if (cellValue == 1) {
          // Free space
          color = Colors.green.withOpacity(0.1);
        } else if (cellValue == 2) {
          // Occupied space
          color = Colors.red.withOpacity(0.4);
        }

        if (color != null) {
          final worldX = (x - gridWidth / 2) * gridResolution;
          final worldZ = (z - gridHeight / 2) * gridResolution;

          final screenX = center.dx + worldX * scale;
          final screenZ = center.dy + worldZ * scale;

          final paint = Paint()..color = color;

          canvas.drawRect(
            Rect.fromLTWH(screenX, screenZ, cellSize, cellSize),
            paint,
          );
        }
      }
    }
  }

  void _drawEdges(Canvas canvas, Offset center) {
    for (final edge in edges) {
      final startScreen = Offset(
        center.dx + edge.start.dx * scale,
        center.dy + edge.start.dy * scale,
      );

      final endScreen = Offset(
        center.dx + edge.end.dx * scale,
        center.dy + edge.end.dy * scale,
      );

      final paint = Paint()
        ..color = Colors.yellow.withOpacity(0.3 + edge.confidence * 0.7)
        ..strokeWidth = 3
        ..strokeCap = StrokeCap.round;

      canvas.drawLine(startScreen, endScreen, paint);

      // Draw edge points
      final pointPaint = Paint()
        ..color = Colors.orange
        ..style = PaintingStyle.fill;

      canvas.drawCircle(startScreen, 4, pointPaint);
      canvas.drawCircle(endScreen, 4, pointPaint);
    }
  }

  void _drawCurrentPosition(Canvas canvas, Offset center) {
    final posScreen = Offset(
      center.dx + currentPosition.dx * scale,
      center.dy + currentPosition.dy * scale,
    );

    // Draw outer circle
    final outerPaint = Paint()
      ..color = Colors.blue.withOpacity(0.3)
      ..style = PaintingStyle.fill;
    canvas.drawCircle(posScreen, 15, outerPaint);

    // Draw inner circle
    final innerPaint = Paint()
      ..color = Colors.blue
      ..style = PaintingStyle.fill;
    canvas.drawCircle(posScreen, 8, innerPaint);

    // Draw direction indicator
    final dirPaint = Paint()
      ..color = Colors.blue
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(
      posScreen,
      posScreen + const Offset(0, -20),
      dirPaint,
    );
  }

  void _drawLegend(Canvas canvas, Size size) {
    final legendX = size.width - 150;
    final legendY = 20.0;

    final textStyle = const TextStyle(
      color: Colors.white,
      fontSize: 12,
    );

    final items = [
      (Colors.blue, 'Current Position'),
      (Colors.yellow, 'Detected Edges'),
      (Colors.red.withOpacity(0.4), 'Obstacles'),
      (Colors.green.withOpacity(0.1), 'Free Space'),
    ];

    for (int i = 0; i < items.length; i++) {
      final y = legendY + i * 25;

      // Draw color box
      final boxPaint = Paint()..color = items[i].$1;
      canvas.drawRect(
        Rect.fromLTWH(legendX, y, 15, 15),
        boxPaint,
      );

      // Draw text
      final textPainter = TextPainter(
        text: TextSpan(text: items[i].$2, style: textStyle),
        textDirection: TextDirection.ltr,
      );
      textPainter.layout();
      textPainter.paint(canvas, Offset(legendX + 20, y));
    }
  }

  @override
  bool shouldRepaint(covariant TechnicalMapPainter oldDelegate) {
    return edges != oldDelegate.edges ||
        occupancyGrid != oldDelegate.occupancyGrid ||
        currentPosition != oldDelegate.currentPosition ||
        scale != oldDelegate.scale ||
        offset != oldDelegate.offset;
  }
}