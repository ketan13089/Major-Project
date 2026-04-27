import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'global_voice.dart';
import 'wcag_theme.dart';

/// Per-map performance dashboard. Displays the performance metrics that were
/// frozen into a saved map's JSON at save time.
class PerformanceDashboard extends StatefulWidget {
  final String mapName;
  const PerformanceDashboard({super.key, required this.mapName});

  @override
  State<PerformanceDashboard> createState() => _PerformanceDashboardState();
}

/// Severity levels — used so that color is never the sole signal. Each level
/// has a text label, an icon, and a color, satisfying WCAG 1.4.1 (Use of Color).
enum _Severity { good, warn, bad, neutral }

extension on _Severity {
  String get label => switch (this) {
        _Severity.good => 'Good',
        _Severity.warn => 'Warning',
        _Severity.bad => 'Critical',
        _Severity.neutral => '',
      };

  IconData get icon => switch (this) {
        _Severity.good => Icons.check_circle_rounded,
        _Severity.warn => Icons.warning_amber_rounded,
        _Severity.bad => Icons.error_rounded,
        _Severity.neutral => Icons.circle,
      };
}

class _PerformanceDashboardState extends State<PerformanceDashboard>
    with GlobalVoiceCommandsMixin {
  static const _mapStoreChannel = MethodChannel('com.ketan.slam/map_store');

  Map<String, dynamic> _metrics = {};
  bool _loading = true;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _fetchMetrics();
  }

  @override
  List<GlobalVoiceCommand> buildVoiceCommands(BuildContext context) {
    return [
      GlobalVoiceCommand(
        phrases: const ['refresh', 'reload', 'reload metrics'],
        description: 'Reload the performance metrics',
        onMatch: _fetchMetrics,
      ),
      GlobalVoiceCommand(
        phrases: const ['export', 'export report', 'save report'],
        description: 'Export the metrics as a JSON report',
        onMatch: _exportReport,
      ),
      GlobalVoiceCommand(
        phrases: const ['summary', 'read summary', 'overview'],
        description: 'Read a spoken summary of key metrics',
        onMatch: _speakSummary,
      ),
    ];
  }

  void _speakSummary() {
    if (_metrics.isEmpty) {
      return;
    }
    final fps = (_metrics['avgFps'] as num?)?.toDouble() ?? 0;
    final yolo = (_metrics['yoloAvgMs'] as num?)?.toDouble() ?? 0;
    final drift = (_metrics['avgDrift'] as num?)?.toDouble() ?? 0;
    final dur = (_metrics['sessionDurationSec'] as num?)?.toDouble() ?? 0;
    final mins = (dur / 60).floor();
    final secs = (dur % 60).floor();
    final summary =
        'Performance summary: average frame rate ${fps.toStringAsFixed(0)} '
        'frames per second, YOLO latency ${yolo.toStringAsFixed(0)} milliseconds, '
        'drift ${drift.toStringAsFixed(2)} meters, '
        'session ${mins} minutes ${secs} seconds.';
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
          content:
              WcagText(summary, size: WcagType.body, color: Colors.white),
          duration: const Duration(seconds: 6)),
    );
  }

  Future<void> _fetchMetrics() async {
    setState(() {
      _loading = true;
      _errorMessage = null;
    });
    try {
      final result = await _mapStoreChannel
          .invokeMethod('getMapPerformance', {'name': widget.mapName});
      if (!mounted) return;
      if (result is Map) {
        setState(() {
          _metrics = Map<String, dynamic>.from(result);
          _loading = false;
        });
      } else {
        setState(() {
          _metrics = {};
          _loading = false;
        });
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage = 'Failed to load metrics: $e';
        _loading = false;
      });
    }
  }

  Future<void> _exportReport() async {
    try {
      final result = await _mapStoreChannel
          .invokeMethod('exportMapPerformance', {'name': widget.mapName});
      if (!mounted) return;
      if (result is Map) {
        final path = result['path'] as String?;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: WcagText('Report saved: $path',
                size: WcagType.body, color: Colors.white),
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: WcagText('Export failed: $e',
              size: WcagType.body, color: Colors.white),
        ),
      );
    }
  }

  double _d(String key) => (_metrics[key] as num?)?.toDouble() ?? 0.0;
  int _i(String key) => (_metrics[key] as num?)?.toInt() ?? 0;
  String _f1(String key) => _d(key).toStringAsFixed(1);
  String _f2(String key) => _d(key).toStringAsFixed(2);
  String _f3(String key) => _d(key).toStringAsFixed(3);

  @override
  Widget build(BuildContext context) {
    final p = WcagPalette.of(context);
    final hasMetrics = _metrics.isNotEmpty;

    return Scaffold(
      backgroundColor: p.background,
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            WcagText('Performance Metrics',
                size: WcagType.headline, weight: WcagType.bold),
            WcagText(widget.mapName.replaceAll('_', ' '),
                size: WcagType.caption, emphasis: 'secondary'),
          ],
        ),
        backgroundColor: p.background,
        elevation: 0,
        iconTheme: IconThemeData(color: p.textPrimary),
        actions: [
          IconButton(
            iconSize: 26,
            icon: Icon(Icons.refresh, color: p.textSecondary),
            tooltip: 'Reload metrics',
            onPressed: _fetchMetrics,
          ),
          IconButton(
            iconSize: 26,
            icon: Icon(Icons.save_alt, color: p.accentPrimary),
            tooltip: 'Export JSON report',
            onPressed: hasMetrics ? _exportReport : null,
          ),
        ],
      ),
      body: WcagScaffoldFrame(
        child: Stack(children: [
          _loading
              ? const Center(child: CircularProgressIndicator())
              : _errorMessage != null
                  ? _errorState(p)
                  : !hasMetrics
                      ? _noMetricsState(p)
                      : _metricsList(p),
          const GlobalVoiceFab(),
        ]),
      ),
    );
  }

  Widget _errorState(WcagPalette p) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(Icons.error_outline_rounded,
                color: p.accentDanger, size: 56),
            const SizedBox(height: 16),
            WcagText(_errorMessage ?? '',
                emphasis: 'secondary',
                align: TextAlign.center,
                size: WcagType.body),
            const SizedBox(height: 24),
            SizedBox(
              height: WcagSize.minTouch,
              child: ElevatedButton.icon(
                onPressed: _fetchMetrics,
                style: ElevatedButton.styleFrom(
                  backgroundColor: p.accentPrimary,
                  foregroundColor: p.textOnAccent,
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                ),
                icon: const Icon(Icons.refresh, size: 20),
                label: WcagText('Retry',
                    size: WcagType.label,
                    weight: WcagType.semibold,
                    color: p.textOnAccent),
              ),
            ),
          ]),
        ),
      );

  Widget _noMetricsState(WcagPalette p) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(Icons.insert_chart_outlined_rounded,
                color: p.textTertiary, size: 64),
            const SizedBox(height: 20),
            WcagText('No metrics available',
                size: WcagType.headline, weight: WcagType.semibold),
            const SizedBox(height: 8),
            WcagText(
              'This map was saved before per-map performance tracking '
              'was added. Scan a new map to see its metrics.',
              align: TextAlign.center,
              size: WcagType.body,
              emphasis: 'secondary',
              height: 1.4,
            ),
          ]),
        ),
      );

  Widget _metricsList(WcagPalette p) {
    return ListView(
      // Bottom padding leaves room for the full-width voice command bar.
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 112),
      children: [
        _SectionHeader('Session Overview', Icons.timer, p),
        _MetricCard(p, [
          _MetricRow(
              'Duration', _formatDuration(_d('sessionDurationSec')), p),
          _MetricRow('Total Frames', '${_i('totalFrames')}', p),
        ]),
        _SectionHeader('Frame Rate', Icons.speed, p),
        _MetricCard(p, [
          _BigMetric(
            '${_f1('avgFps')} FPS',
            'Average',
            _fpsSeverity(_d('avgFps')),
            p,
          ),
          Row(children: [
            Expanded(child: _MetricRow('Min', '${_f1('minFps')} FPS', p)),
            Expanded(child: _MetricRow('Max', '${_f1('maxFps')} FPS', p)),
          ]),
        ]),
        _SectionHeader('YOLO Object Detection', Icons.visibility, p),
        _MetricCard(p, [
          Row(children: [
            Expanded(
                child: _BigMetric(
                    '${_f1('yoloAvgMs')} ms',
                    'Avg Latency',
                    _latencySeverity(_d('yoloAvgMs'), 200),
                    p)),
            Expanded(
                child: _BigMetric('${_i('totalDetections')}',
                    'Total Detections', _Severity.neutral, p)),
          ]),
          Row(children: [
            Expanded(
                child:
                    _MetricRow('P95 Latency', '${_f1('yoloP95Ms')} ms', p)),
            Expanded(
                child:
                    _MetricRow('Max Latency', '${_f1('yoloMaxMs')} ms', p)),
          ]),
          Row(children: [
            Expanded(
                child:
                    _MetricRow('Total Runs', '${_i('totalYoloRuns')}', p)),
            Expanded(
                child: _MetricRow(
                    'Avg/Run', _f2('avgDetectionsPerRun'), p)),
          ]),
          _MetricRow('Avg Confidence',
              '${(_d('avgConfidence') * 100).toStringAsFixed(1)}%', p),
        ]),
        _SectionHeader('OCR Text Recognition', Icons.text_fields, p),
        _MetricCard(p, [
          Row(children: [
            Expanded(
                child: _BigMetric(
                    '${_f1('ocrAvgMs')} ms',
                    'Avg Latency',
                    _latencySeverity(_d('ocrAvgMs'), 500),
                    p)),
            Expanded(
                child: _BigMetric('${_i('totalTextDetections')}',
                    'Text Found', _Severity.neutral, p)),
          ]),
          Row(children: [
            Expanded(
                child: _MetricRow('P95 Latency', '${_f1('ocrP95Ms')} ms', p)),
            Expanded(
                child: _MetricRow('Total Runs', '${_i('totalOcrRuns')}', p)),
          ]),
        ]),
        _SectionHeader('Map Building', Icons.grid_on, p),
        _MetricCard(p, [
          Row(children: [
            Expanded(
                child: _BigMetric('${_d('currentGridSize').toInt()}',
                    'Grid Cells', _Severity.neutral, p)),
            Expanded(
                child: _BigMetric('${_d('peakGridSize').toInt()}',
                    'Peak Cells', _Severity.neutral, p)),
          ]),
          _CellBreakdownBar(
            free: _d('currentFreeCells'),
            walls: _d('currentWallCells'),
            obstacles: _d('currentObstacleCells'),
            visited: _d('currentVisitedCells'),
            p: p,
          ),
          Row(children: [
            Expanded(
                child: _MetricRow(
                    'Free', '${_d('currentFreeCells').toInt()}', p)),
            Expanded(
                child: _MetricRow(
                    'Walls', '${_d('currentWallCells').toInt()}', p)),
          ]),
          Row(children: [
            Expanded(
                child: _MetricRow(
                    'Obstacles', '${_d('currentObstacleCells').toInt()}', p)),
            Expanded(
                child: _MetricRow(
                    'Visited', '${_d('currentVisitedCells').toInt()}', p)),
          ]),
          Divider(height: 16, color: p.border),
          Row(children: [
            Expanded(
                child: _MetricRow(
                    'Full Rebuilds', '${_i('totalRebuilds')}', p)),
            Expanded(
                child:
                    _MetricRow('Avg Time', '${_f1('rebuildAvgMs')} ms', p)),
          ]),
          Row(children: [
            Expanded(
                child: _MetricRow(
                    'Light Rebuilds', '${_i('totalLightRebuilds')}', p)),
            Expanded(
                child: _MetricRow(
                    'Avg Time', '${_f1('lightRebuildAvgMs')} ms', p)),
          ]),
          _MetricRow('Max Rebuild', '${_f1('rebuildMaxMs')} ms', p),
        ]),
        _SectionHeader('Localization', Icons.gps_fixed, p),
        _MetricCard(p, [
          Row(children: [
            Expanded(
                child: _BigMetric('${_f3('avgDrift')} m', 'Avg Drift',
                    _driftSeverity(_d('avgDrift')), p)),
            Expanded(
                child: _BigMetric('${_i('totalKeyframes')}', 'Keyframes',
                    _Severity.neutral, p)),
          ]),
          Row(children: [
            Expanded(
                child: _MetricRow('Max Drift', '${_f3('maxDrift')} m', p)),
            Expanded(
                child: _MetricRow(
                    'Drift Rebuilds', '${_i('totalDriftRebuilds')}', p)),
          ]),
        ]),
        _SectionHeader('Path Planning', Icons.route, p),
        _MetricCard(p, [
          Row(children: [
            Expanded(
                child: _BigMetric(
                    '${_f1('pathPlanAvgMs')} ms',
                    'Avg Latency',
                    _latencySeverity(_d('pathPlanAvgMs'), 100),
                    p)),
            Expanded(
                child: _BigMetric(
                    _i('totalPathPlans') > 0
                        ? '${((_i('totalPathPlans') - _i('failedPathPlans')) / _i('totalPathPlans') * 100).toStringAsFixed(0)}%'
                        : 'N/A',
                    'Success Rate',
                    _Severity.good,
                    p)),
          ]),
          Row(children: [
            Expanded(
                child:
                    _MetricRow('Total Plans', '${_i('totalPathPlans')}', p)),
            Expanded(
                child:
                    _MetricRow('Failed', '${_i('failedPathPlans')}', p)),
          ]),
          Row(children: [
            Expanded(
                child: _MetricRow('Avg Path Length',
                    '${_f1('avgPathLength')} cells', p)),
            Expanded(
                child: _MetricRow(
                    'Max Latency', '${_f1('pathPlanMaxMs')} ms', p)),
          ]),
        ]),
        _SectionHeader('Object Tracking', Icons.category, p),
        _MetricCard(p, [
          Row(children: [
            Expanded(
                child: _BigMetric('${_d('currentObjectCount').toInt()}',
                    'Current', _Severity.neutral, p)),
            Expanded(
                child: _BigMetric('${_i('peakObjectCount')}', 'Peak',
                    _Severity.neutral, p)),
          ]),
        ]),
        _SectionHeader('Navigation', Icons.navigation, p),
        _MetricCard(p, [
          Row(children: [
            Expanded(
                child: _BigMetric('${_i('totalNavSessions')}',
                    'Total Sessions', _Severity.neutral, p)),
            Expanded(
                child: _BigMetric('${_i('successfulNavSessions')}',
                    'Successful', _Severity.good, p)),
          ]),
          Row(children: [
            Expanded(
                child: _MetricRow('Avg Duration',
                    '${_f1('avgNavDurationSec')} s', p)),
            Expanded(
                child: _MetricRow(
                    'Avg Replans', _f1('avgReplansPerSession'), p)),
          ]),
        ]),
        _SectionHeader('Memory Usage', Icons.memory, p),
        _MetricCard(p, [
          Row(children: [
            Expanded(
                child: _BigMetric('${_f1('avgMemoryMb')} MB', 'Average',
                    _memorySeverity(_d('avgMemoryMb')), p)),
            Expanded(
                child: _BigMetric('${_f1('peakMemoryMb')} MB', 'Peak',
                    _memorySeverity(_d('peakMemoryMb')), p)),
          ]),
        ]),
        const SizedBox(height: 16),
      ],
    );
  }

  String _formatDuration(double seconds) {
    final m = (seconds / 60).floor();
    final s = (seconds % 60).floor();
    return m > 0 ? '${m}m ${s}s' : '${s}s';
  }

  _Severity _fpsSeverity(double fps) =>
      fps >= 25 ? _Severity.good : fps >= 15 ? _Severity.warn : _Severity.bad;

  _Severity _latencySeverity(double ms, double threshold) =>
      ms <= threshold * 0.5
          ? _Severity.good
          : ms <= threshold
              ? _Severity.warn
              : _Severity.bad;

  _Severity _driftSeverity(double drift) => drift <= 0.02
      ? _Severity.good
      : drift <= 0.05
          ? _Severity.warn
          : _Severity.bad;

  _Severity _memorySeverity(double mb) => mb <= 200
      ? _Severity.good
      : mb <= 400
          ? _Severity.warn
          : _Severity.bad;
}

// ── Reusable widgets ────────────────────────────────────────────────────────

class _SectionHeader extends StatelessWidget {
  final String title;
  final IconData icon;
  final WcagPalette p;
  const _SectionHeader(this.title, this.icon, this.p);

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 24, bottom: 10, left: 2),
        child: Row(children: [
          Icon(icon, size: 18, color: p.textSecondary),
          const SizedBox(width: 8),
          WcagText(title,
              size: WcagType.label,
              weight: WcagType.bold,
              letterSpacing: -0.2),
        ]),
      );
}

class _MetricCard extends StatelessWidget {
  final WcagPalette p;
  final List<Widget> children;
  const _MetricCard(this.p, this.children);

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: p.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: p.border, width: 1.5),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: children,
        ),
      );
}

class _MetricRow extends StatelessWidget {
  final String label, value;
  final WcagPalette p;
  const _MetricRow(this.label, this.value, this.p);

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Flexible(
              child: WcagText(label,
                  size: WcagType.caption,
                  emphasis: 'secondary',
                  weight: WcagType.medium),
            ),
            const SizedBox(width: 12),
            WcagText(value,
                size: WcagType.body, weight: WcagType.semibold),
          ],
        ),
      );
}

class _BigMetric extends StatelessWidget {
  final String value, label;
  final _Severity severity;
  final WcagPalette p;
  const _BigMetric(this.value, this.label, this.severity, this.p);

  Color _severityColor() => switch (severity) {
        _Severity.good => p.accentSuccess,
        _Severity.warn => p.accentWarning,
        _Severity.bad => p.accentDanger,
        _Severity.neutral => p.accentPrimary,
      };

  @override
  Widget build(BuildContext context) {
    final color = _severityColor();
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (severity != _Severity.neutral) ...[
                Icon(severity.icon, size: 22, color: color),
                const SizedBox(width: 6),
              ],
              Flexible(
                child: WcagText(
                  value,
                  size: WcagType.display,
                  weight: WcagType.bold,
                  color: color,
                  letterSpacing: -0.5,
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Row(children: [
            WcagText(label,
                size: WcagType.caption,
                emphasis: 'secondary',
                weight: WcagType.medium),
            if (severity != _Severity.neutral) ...[
              const SizedBox(width: 6),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: color.withOpacity(0.15),
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: color, width: 1),
                ),
                child: WcagText(severity.label,
                    size: WcagType.caption - 2,
                    weight: WcagType.bold,
                    color: color),
              ),
            ],
          ]),
        ],
      ),
    );
  }
}

class _CellBreakdownBar extends StatelessWidget {
  final double free, walls, obstacles, visited;
  final WcagPalette p;
  const _CellBreakdownBar({
    required this.free,
    required this.walls,
    required this.obstacles,
    required this.visited,
    required this.p,
  });

  @override
  Widget build(BuildContext context) {
    final total = free + walls + obstacles + visited;
    if (total == 0) return const SizedBox(height: 8);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(4),
        child: SizedBox(
          height: 12,
          child: Row(children: [
            _bar(free / total, p.accentSuccess),
            _bar(visited / total, p.accentInfo),
            _bar(obstacles / total, p.accentWarning),
            _bar(walls / total, p.textPrimary),
          ]),
        ),
      ),
    );
  }

  Widget _bar(double fraction, Color color) => fraction > 0
      ? Expanded(
          flex: (fraction * 1000).toInt().clamp(1, 1000),
          child: Container(color: color))
      : const SizedBox.shrink();
}
