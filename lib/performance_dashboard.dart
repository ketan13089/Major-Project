import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Real-time performance metrics dashboard for benchmarking and evaluation.
/// Displays live stats from PerformanceTracker via method channel.
class PerformanceDashboard extends StatefulWidget {
  const PerformanceDashboard({super.key});

  @override
  State<PerformanceDashboard> createState() => _PerformanceDashboardState();
}

class _PerformanceDashboardState extends State<PerformanceDashboard> {
  static const _arChannel = MethodChannel('com.ketan.slam/ar');
  static const _mapChannel = MethodChannel('com.ketan.slam/map');

  Map<String, dynamic> _metrics = {};
  bool _isLive = true;

  @override
  void initState() {
    super.initState();
    _arChannel.setMethodCallHandler(_handleMethod);
    _fetchMetrics();
    // Retry periodically if no data yet
    Future.delayed(const Duration(seconds: 2), () {
      if (mounted && _metrics.isEmpty && _errorMessage == null) {
        _fetchMetrics();
      }
    });
  }

  Future<void> _handleMethod(MethodCall call) async {
    if (call.method == 'perfUpdate' && _isLive) {
      setState(() {
        _metrics = Map<String, dynamic>.from(call.arguments);
        _errorMessage = null;  // Clear error on successful update
      });
    }
  }

  String? _errorMessage;

  Future<void> _fetchMetrics() async {
    try {
      final result = await _mapChannel.invokeMethod('getPerformanceMetrics');
      if (result != null && mounted) {
        setState(() {
          _metrics = Map<String, dynamic>.from(result);
          _errorMessage = null;
        });
      } else if (mounted && _metrics.isEmpty) {
        // No result but also no exception - session might be starting
        setState(() {
          _errorMessage = null;  // Keep showing loading spinner
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _errorMessage = 'AR session not active. Start a scan to collect metrics.';
        });
      }
    }
  }

  Future<void> _exportReport() async {
    try {
      final result = await _mapChannel.invokeMethod('exportPerformanceReport');
      if (result != null && mounted) {
        final path = result['path'] as String?;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Report saved: $path')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Export failed: $e')),
        );
      }
    }
  }

  double _d(String key) => (_metrics[key] as num?)?.toDouble() ?? 0.0;
  int _i(String key) => (_metrics[key] as num?)?.toInt() ?? 0;
  String _f1(String key) => _d(key).toStringAsFixed(1);
  String _f2(String key) => _d(key).toStringAsFixed(2);
  String _f3(String key) => _d(key).toStringAsFixed(3);

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bg = isDark ? const Color(0xFF0F0F17) : const Color(0xFFF8F8FC);
    final cardBg = isDark ? const Color(0xFF1A1A26) : Colors.white;
    final textPri = isDark ? const Color(0xFFF3F4F6) : const Color(0xFF111827);
    final textSec = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);
    final border = isDark ? const Color(0xFF2A2A38) : const Color(0xFFE5E7EB);

    return Scaffold(
      backgroundColor: bg,
      appBar: AppBar(
        title: Text('Performance Metrics',
            style: TextStyle(color: textPri, fontWeight: FontWeight.w700)),
        backgroundColor: bg,
        elevation: 0,
        iconTheme: IconThemeData(color: textPri),
        actions: [
          IconButton(
            icon: Icon(_isLive ? Icons.pause_circle : Icons.play_circle,
                color: _isLive ? const Color(0xFF10B981) : textSec),
            tooltip: _isLive ? 'Pause live updates' : 'Resume live updates',
            onPressed: () => setState(() => _isLive = !_isLive),
          ),
          IconButton(
            icon: Icon(Icons.refresh, color: textSec),
            tooltip: 'Refresh',
            onPressed: _fetchMetrics,
          ),
          IconButton(
            icon: const Icon(Icons.save_alt, color: Color(0xFF2563EB)),
            tooltip: 'Export JSON report',
            onPressed: _exportReport,
          ),
        ],
      ),
      body: _errorMessage != null
          ? Center(child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.info_outline, color: textSec, size: 48),
                const SizedBox(height: 16),
                Text(_errorMessage!,
                    style: TextStyle(color: textSec),
                    textAlign: TextAlign.center),
                const SizedBox(height: 24),
                ElevatedButton.icon(
                  onPressed: _fetchMetrics,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Retry'),
                ),
              ],
            ))
          : _metrics.isEmpty
          ? Center(child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                CircularProgressIndicator(color: textSec),
                const SizedBox(height: 16),
                Text('Waiting for AR session data...',
                    style: TextStyle(color: textSec)),
              ],
            ))
          : ListView(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
              children: [
                // Session overview
                _SectionHeader('Session Overview', Icons.timer, textPri),
                _MetricCard(cardBg, border, [
                  _MetricRow('Duration', _formatDuration(_d('sessionDurationSec')), textPri, textSec),
                  _MetricRow('Total Frames', '${_i('totalFrames')}', textPri, textSec),
                ]),

                // Frame rate
                _SectionHeader('Frame Rate', Icons.speed, textPri),
                _MetricCard(cardBg, border, [
                  _BigMetric('${_f1('avgFps')} FPS', 'Average', _fpsColor(_d('avgFps'))),
                  Row(children: [
                    Expanded(child: _MetricRow('Min', '${_f1('minFps')} FPS', textPri, textSec)),
                    Expanded(child: _MetricRow('Max', '${_f1('maxFps')} FPS', textPri, textSec)),
                  ]),
                ]),

                // YOLO Inference
                _SectionHeader('YOLO Object Detection', Icons.visibility, textPri),
                _MetricCard(cardBg, border, [
                  Row(children: [
                    Expanded(child: _BigMetric('${_f1('yoloAvgMs')} ms', 'Avg Latency',
                        _latencyColor(_d('yoloAvgMs'), 200))),
                    Expanded(child: _BigMetric('${_i('totalDetections')}', 'Total Detections',
                        const Color(0xFF7C3AED))),
                  ]),
                  Row(children: [
                    Expanded(child: _MetricRow('P95 Latency', '${_f1('yoloP95Ms')} ms', textPri, textSec)),
                    Expanded(child: _MetricRow('Max Latency', '${_f1('yoloMaxMs')} ms', textPri, textSec)),
                  ]),
                  Row(children: [
                    Expanded(child: _MetricRow('Total Runs', '${_i('totalYoloRuns')}', textPri, textSec)),
                    Expanded(child: _MetricRow('Avg/Run', _f2('avgDetectionsPerRun'), textPri, textSec)),
                  ]),
                  _MetricRow('Avg Confidence', '${(_d('avgConfidence') * 100).toStringAsFixed(1)}%',
                      textPri, textSec),
                ]),

                // OCR
                _SectionHeader('OCR Text Recognition', Icons.text_fields, textPri),
                _MetricCard(cardBg, border, [
                  Row(children: [
                    Expanded(child: _BigMetric('${_f1('ocrAvgMs')} ms', 'Avg Latency',
                        _latencyColor(_d('ocrAvgMs'), 500))),
                    Expanded(child: _BigMetric('${_i('totalTextDetections')}', 'Text Found',
                        const Color(0xFF059669))),
                  ]),
                  Row(children: [
                    Expanded(child: _MetricRow('P95 Latency', '${_f1('ocrP95Ms')} ms', textPri, textSec)),
                    Expanded(child: _MetricRow('Total Runs', '${_i('totalOcrRuns')}', textPri, textSec)),
                  ]),
                ]),

                // Map Building
                _SectionHeader('Map Building', Icons.grid_on, textPri),
                _MetricCard(cardBg, border, [
                  Row(children: [
                    Expanded(child: _BigMetric('${_d('currentGridSize').toInt()}', 'Grid Cells',
                        const Color(0xFF2563EB))),
                    Expanded(child: _BigMetric('${_d('peakGridSize').toInt()}', 'Peak Cells',
                        const Color(0xFF6366F1))),
                  ]),
                  _CellBreakdownBar(
                    free: _d('currentFreeCells'),
                    walls: _d('currentWallCells'),
                    obstacles: _d('currentObstacleCells'),
                    visited: _d('currentVisitedCells'),
                  ),
                  Row(children: [
                    Expanded(child: _MetricRow('Free', '${_d('currentFreeCells').toInt()}',
                        textPri, const Color(0xFF10B981))),
                    Expanded(child: _MetricRow('Walls', '${_d('currentWallCells').toInt()}',
                        textPri, const Color(0xFF374151))),
                  ]),
                  Row(children: [
                    Expanded(child: _MetricRow('Obstacles', '${_d('currentObstacleCells').toInt()}',
                        textPri, const Color(0xFFB45309))),
                    Expanded(child: _MetricRow('Visited', '${_d('currentVisitedCells').toInt()}',
                        textPri, const Color(0xFF2563EB))),
                  ]),
                  const Divider(height: 16),
                  Row(children: [
                    Expanded(child: _MetricRow('Full Rebuilds', '${_i('totalRebuilds')}', textPri, textSec)),
                    Expanded(child: _MetricRow('Avg Time', '${_f1('rebuildAvgMs')} ms', textPri, textSec)),
                  ]),
                  Row(children: [
                    Expanded(child: _MetricRow('Light Rebuilds', '${_i('totalLightRebuilds')}', textPri, textSec)),
                    Expanded(child: _MetricRow('Avg Time', '${_f1('lightRebuildAvgMs')} ms', textPri, textSec)),
                  ]),
                  _MetricRow('Max Rebuild', '${_f1('rebuildMaxMs')} ms', textPri, textSec),
                ]),

                // Localization & Drift
                _SectionHeader('Localization', Icons.gps_fixed, textPri),
                _MetricCard(cardBg, border, [
                  Row(children: [
                    Expanded(child: _BigMetric('${_f3('avgDrift')} m', 'Avg Drift',
                        _driftColor(_d('avgDrift')))),
                    Expanded(child: _BigMetric('${_i('totalKeyframes')}', 'Keyframes',
                        const Color(0xFF6366F1))),
                  ]),
                  Row(children: [
                    Expanded(child: _MetricRow('Max Drift', '${_f3('maxDrift')} m', textPri, textSec)),
                    Expanded(child: _MetricRow('Drift Rebuilds', '${_i('totalDriftRebuilds')}', textPri, textSec)),
                  ]),
                ]),

                // Path Planning
                _SectionHeader('Path Planning', Icons.route, textPri),
                _MetricCard(cardBg, border, [
                  Row(children: [
                    Expanded(child: _BigMetric('${_f1('pathPlanAvgMs')} ms', 'Avg Latency',
                        _latencyColor(_d('pathPlanAvgMs'), 100))),
                    Expanded(child: _BigMetric(
                        _i('totalPathPlans') > 0
                            ? '${((_i('totalPathPlans') - _i('failedPathPlans')) / _i('totalPathPlans') * 100).toStringAsFixed(0)}%'
                            : 'N/A',
                        'Success Rate',
                        const Color(0xFF10B981))),
                  ]),
                  Row(children: [
                    Expanded(child: _MetricRow('Total Plans', '${_i('totalPathPlans')}', textPri, textSec)),
                    Expanded(child: _MetricRow('Failed', '${_i('failedPathPlans')}', textPri, textSec)),
                  ]),
                  Row(children: [
                    Expanded(child: _MetricRow('Avg Path Length', '${_f1('avgPathLength')} cells', textPri, textSec)),
                    Expanded(child: _MetricRow('Max Latency', '${_f1('pathPlanMaxMs')} ms', textPri, textSec)),
                  ]),
                ]),

                // Object Tracking
                _SectionHeader('Object Tracking', Icons.category, textPri),
                _MetricCard(cardBg, border, [
                  Row(children: [
                    Expanded(child: _BigMetric('${_d('currentObjectCount').toInt()}', 'Current',
                        const Color(0xFF7C3AED))),
                    Expanded(child: _BigMetric('${_i('peakObjectCount')}', 'Peak',
                        const Color(0xFF6366F1))),
                  ]),
                ]),

                // Navigation
                _SectionHeader('Navigation', Icons.navigation, textPri),
                _MetricCard(cardBg, border, [
                  Row(children: [
                    Expanded(child: _BigMetric('${_i('totalNavSessions')}', 'Total Sessions',
                        const Color(0xFF2563EB))),
                    Expanded(child: _BigMetric('${_i('successfulNavSessions')}', 'Successful',
                        const Color(0xFF10B981))),
                  ]),
                  Row(children: [
                    Expanded(child: _MetricRow('Avg Duration', '${_f1('avgNavDurationSec')} s', textPri, textSec)),
                    Expanded(child: _MetricRow('Avg Replans', _f1('avgReplansPerSession'), textPri, textSec)),
                  ]),
                ]),

                // Memory
                _SectionHeader('Memory Usage', Icons.memory, textPri),
                _MetricCard(cardBg, border, [
                  Row(children: [
                    Expanded(child: _BigMetric('${_f1('avgMemoryMb')} MB', 'Average',
                        _memoryColor(_d('avgMemoryMb')))),
                    Expanded(child: _BigMetric('${_f1('peakMemoryMb')} MB', 'Peak',
                        _memoryColor(_d('peakMemoryMb')))),
                  ]),
                ]),

                const SizedBox(height: 16),
              ],
            ),
    );
  }

  String _formatDuration(double seconds) {
    final m = (seconds / 60).floor();
    final s = (seconds % 60).floor();
    return m > 0 ? '${m}m ${s}s' : '${s}s';
  }

  Color _fpsColor(double fps) =>
      fps >= 25 ? const Color(0xFF10B981) :
      fps >= 15 ? const Color(0xFFF59E0B) :
      const Color(0xFFEF4444);

  Color _latencyColor(double ms, double threshold) =>
      ms <= threshold * 0.5 ? const Color(0xFF10B981) :
      ms <= threshold ? const Color(0xFFF59E0B) :
      const Color(0xFFEF4444);

  Color _driftColor(double drift) =>
      drift <= 0.02 ? const Color(0xFF10B981) :
      drift <= 0.05 ? const Color(0xFFF59E0B) :
      const Color(0xFFEF4444);

  Color _memoryColor(double mb) =>
      mb <= 200 ? const Color(0xFF10B981) :
      mb <= 400 ? const Color(0xFFF59E0B) :
      const Color(0xFFEF4444);
}

// ── Reusable widgets ────────────────────────────────────────────────────────

class _SectionHeader extends StatelessWidget {
  final String title;
  final IconData icon;
  final Color textColor;
  const _SectionHeader(this.title, this.icon, this.textColor);

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 20, bottom: 8, left: 2),
    child: Row(children: [
      Icon(icon, size: 16, color: textColor.withOpacity(0.6)),
      const SizedBox(width: 6),
      Text(title, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600,
          color: textColor, letterSpacing: -0.2)),
    ]),
  );
}

class _MetricCard extends StatelessWidget {
  final Color bg, border;
  final List<Widget> children;
  const _MetricCard(this.bg, this.border, this.children);

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: bg,
      borderRadius: BorderRadius.circular(14),
      border: Border.all(color: border),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: children,
    ),
  );
}

class _MetricRow extends StatelessWidget {
  final String label, value;
  final Color labelColor, valueColor;
  const _MetricRow(this.label, this.value, this.labelColor, this.valueColor);

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 3),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: TextStyle(fontSize: 12, color: valueColor)),
        Text(value, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600,
            color: labelColor)),
      ],
    ),
  );
}

class _BigMetric extends StatelessWidget {
  final String value, label;
  final Color color;
  const _BigMetric(this.value, this.label, this.color);

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(value, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700,
            color: color, letterSpacing: -0.5)),
        const SizedBox(height: 2),
        Text(label, style: TextStyle(fontSize: 11,
            color: color.withOpacity(0.7))),
      ],
    ),
  );
}

class _CellBreakdownBar extends StatelessWidget {
  final double free, walls, obstacles, visited;
  const _CellBreakdownBar({
    required this.free, required this.walls,
    required this.obstacles, required this.visited,
  });

  @override
  Widget build(BuildContext context) {
    final total = free + walls + obstacles + visited;
    if (total == 0) return const SizedBox(height: 8);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(4),
        child: SizedBox(
          height: 8,
          child: Row(children: [
            _bar(free / total, const Color(0xFF10B981)),
            _bar(visited / total, const Color(0xFF3B82F6)),
            _bar(obstacles / total, const Color(0xFFB45309)),
            _bar(walls / total, const Color(0xFF374151)),
          ]),
        ),
      ),
    );
  }

  Widget _bar(double fraction, Color color) => fraction > 0
      ? Expanded(flex: (fraction * 1000).toInt().clamp(1, 1000),
          child: Container(color: color))
      : const SizedBox.shrink();
}
