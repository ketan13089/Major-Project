import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class SavedMapInfo {
  final String name;
  final DateTime timestamp;
  final double areaM2;
  final int objectCount;
  final int durationSeconds;
  final int wallCount;
  final double resolution;

  const SavedMapInfo({
    required this.name,
    required this.timestamp,
    required this.areaM2,
    required this.objectCount,
    required this.durationSeconds,
    this.wallCount = 0,
    this.resolution = 0.20,
  });

  factory SavedMapInfo.fromMap(Map m) {
    return SavedMapInfo(
      name: m['name']?.toString() ?? '',
      timestamp: DateTime.fromMillisecondsSinceEpoch(
          (m['timestamp'] as num?)?.toInt() ?? 0),
      areaM2: (m['areaM2'] as num?)?.toDouble() ?? 0,
      objectCount: (m['objectCount'] as num?)?.toInt() ?? 0,
      durationSeconds: (m['durationSec'] as num?)?.toInt() ?? 0,
      wallCount: (m['wallCount'] as num?)?.toInt() ?? 0,
      resolution: (m['resolution'] as num?)?.toDouble() ?? 0.20,
    );
  }

  static const _months = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
  ];

  String get formattedTimestamp {
    final h = timestamp.hour;
    final amPm = h >= 12 ? 'PM' : 'AM';
    final h12 = h == 0 ? 12 : (h > 12 ? h - 12 : h);
    final min = timestamp.minute.toString().padLeft(2, '0');
    return '${_months[timestamp.month - 1]} ${timestamp.day}, ${timestamp.year} at $h12:$min $amPm';
  }

  String get formattedDuration {
    final m = durationSeconds ~/ 60;
    final s = durationSeconds % 60;
    if (m == 0) return '${s}s';
    return '${m}m ${s}s';
  }
}

class SavedMapsScreen extends StatefulWidget {
  const SavedMapsScreen({Key? key}) : super(key: key);
  @override
  State<SavedMapsScreen> createState() => _SavedMapsScreenState();
}

class _SavedMapsScreenState extends State<SavedMapsScreen> {
  static const _ch = MethodChannel('com.ketan.slam/map_store');

  List<SavedMapInfo>? _maps;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadMaps();
  }

  Future<void> _loadMaps() async {
    setState(() { _loading = true; _error = null; });
    try {
      final result = await _ch.invokeMethod('listSavedMaps');
      if (result is List && mounted) {
        final maps = result
            .whereType<Map>()
            .map((m) => SavedMapInfo.fromMap(m))
            .toList();
        maps.sort((a, b) => b.timestamp.compareTo(a.timestamp));
        setState(() { _maps = maps; _loading = false; });
      }
    } catch (e) {
      if (mounted) setState(() { _error = e.toString(); _loading = false; });
    }
  }

  Future<void> _deleteMap(SavedMapInfo map) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete Map'),
        content: Text('Delete "${map.name}"? This cannot be undone.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: const Color(0xFFDC2626)),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await _ch.invokeMethod('deleteMap', {'name': map.name});
      _loadMaps();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Deleted "${map.name}"'),
              duration: const Duration(seconds: 2)),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to delete: $e'),
              backgroundColor: const Color(0xFFDC2626)),
        );
      }
    }
  }

  void _openMap(SavedMapInfo map) {
    Navigator.pushNamed(context, '/map', arguments: map.name);
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bg      = isDark ? const Color(0xFF0F0F17) : const Color(0xFFF8F8FC);
    final surface = isDark ? const Color(0xFF1A1A26) : Colors.white;
    final textPri = isDark ? const Color(0xFFF3F4F6) : const Color(0xFF111827);
    final textSec = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);
    final border  = isDark ? const Color(0xFF2A2A38) : const Color(0xFFE5E7EB);

    return Scaffold(
      backgroundColor: bg,
      appBar: AppBar(
        backgroundColor: surface,
        elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_rounded, color: textPri),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text('Saved Maps',
            style: TextStyle(color: textPri, fontSize: 18,
                fontWeight: FontWeight.w700)),
        centerTitle: false,
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? _errorState(textPri, textSec)
              : (_maps == null || _maps!.isEmpty)
                  ? _emptyState(textPri, textSec)
                  : _mapList(surface, textPri, textSec, border),
    );
  }

  Widget _emptyState(Color textPri, Color textSec) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(40),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.map_outlined, size: 64,
              color: textSec.withOpacity(0.4)),
          const SizedBox(height: 20),
          Text('No saved maps yet',
              style: TextStyle(color: textPri, fontSize: 18,
                  fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          Text('Start an AR scan to create your first map',
              textAlign: TextAlign.center,
              style: TextStyle(color: textSec, fontSize: 14, height: 1.4)),
        ]),
      ),
    );
  }

  Widget _errorState(Color textPri, Color textSec) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(40),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.error_outline_rounded, size: 48,
              color: const Color(0xFFDC2626).withOpacity(0.6)),
          const SizedBox(height: 16),
          Text('Failed to load maps',
              style: TextStyle(color: textPri, fontSize: 16,
                  fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          Text(_error ?? '', textAlign: TextAlign.center,
              style: TextStyle(color: textSec, fontSize: 13)),
          const SizedBox(height: 20),
          ElevatedButton(onPressed: _loadMaps, child: const Text('Retry')),
        ]),
      ),
    );
  }

  Widget _mapList(Color surface, Color textPri, Color textSec, Color border) {
    return RefreshIndicator(
      onRefresh: _loadMaps,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        itemCount: _maps!.length,
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (ctx, i) => _mapCard(_maps![i], surface, textPri, textSec, border),
      ),
    );
  }

  Widget _mapCard(SavedMapInfo map, Color surface, Color textPri,
      Color textSec, Color border) {
    return Semantics(
      button: true,
      label: '${map.name}. ${map.formattedTimestamp}. '
          'Area ${map.areaM2.toStringAsFixed(0)} square meters, '
          '${map.objectCount} objects, duration ${map.formattedDuration}. '
          'Tap to view, long press to delete.',
      child: Material(
        color: surface,
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: () => _openMap(map),
          onLongPress: () => _deleteMap(map),
          child: Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: border),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: const Color(0xFF059669).withOpacity(0.1),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: const Icon(Icons.map_rounded, size: 20,
                        color: Color(0xFF059669)),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          map.name.replaceAll('_', ' '),
                          style: TextStyle(color: textPri, fontSize: 15,
                              fontWeight: FontWeight.w600),
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: 2),
                        Text(map.formattedTimestamp,
                            style: TextStyle(color: textSec, fontSize: 12)),
                      ],
                    ),
                  ),
                  Icon(Icons.chevron_right_rounded, size: 20, color: textSec),
                ]),
                const SizedBox(height: 12),
                Row(children: [
                  _statChip(Icons.square_foot_rounded,
                      '${map.areaM2.toStringAsFixed(0)} m\u00B2', textSec),
                  const SizedBox(width: 12),
                  _statChip(Icons.category_rounded,
                      '${map.objectCount} objects', textSec),
                  const SizedBox(width: 12),
                  _statChip(Icons.timer_outlined,
                      map.formattedDuration, textSec),
                ]),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _statChip(IconData icon, String text, Color color) {
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Icon(icon, size: 13, color: color),
      const SizedBox(width: 4),
      Text(text, style: TextStyle(color: color, fontSize: 12,
          fontWeight: FontWeight.w500)),
    ]);
  }
}
