import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'accessibility_service.dart';
import 'global_voice.dart';
import 'performance_dashboard.dart';
import 'wcag_theme.dart';

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

class _SavedMapsScreenState extends State<SavedMapsScreen>
    with VolumeButtonNavigationMixin, GlobalVoiceCommandsMixin {
  static const _ch = MethodChannel('com.ketan.slam/map_store');
  final _accessibility = AccessibilityService();

  @override
  List<GlobalVoiceCommand> buildVoiceCommands(BuildContext context) {
    final commands = <GlobalVoiceCommand>[];
    commands.add(GlobalVoiceCommand(
      phrases: const ['refresh', 'reload', 'reload maps'],
      description: 'Reload the saved maps list',
      onMatch: _loadMaps,
    ));
    if (_isSelectionMode) {
      commands.add(GlobalVoiceCommand(
        phrases: const ['delete selected', 'delete maps', 'remove selected'],
        description: 'Delete the selected maps',
        onMatch: _deleteSelectedMaps,
      ));
      commands.add(GlobalVoiceCommand(
        phrases: const ['cancel selection', 'clear selection'],
        description: 'Exit selection mode',
        onMatch: () => setState(() {
          _isSelectionMode = false;
          _selectedMaps.clear();
        }),
      ));
    }
    if (_maps != null && _maps!.isNotEmpty) {
      commands.add(GlobalVoiceCommand(
        phrases: const ['open first', 'open latest', 'open most recent'],
        description: 'Open the most recently saved map',
        onMatch: () => _openMap(_maps!.first),
      ));
      commands.add(GlobalVoiceCommand(
        phrases: const ['list maps', 'how many maps'],
        description: 'Announce the number of saved maps',
        onMatch: () => _accessibility.speak(
            '${_maps!.length} saved map${_maps!.length == 1 ? '' : 's'}.'),
      ));
    }
    return commands;
  }

  List<SavedMapInfo>? _maps;
  bool _loading = true;
  String? _error;

  bool _isSelectionMode = false;
  final Set<String> _selectedMaps = {};

  @override
  void initState() {
    super.initState();
    _loadMaps();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _accessibility.announceScreen('Saved Maps');
    });
  }

  void _registerFocusables() {
    if (_maps == null || _maps!.isEmpty) {
      _accessibility.registerFocusables([
        FocusableElement(
          id: 'back',
          label: 'Go back',
          hint: 'Return to home screen',
          onActivate: () => Navigator.pop(context),
        ),
        FocusableElement(
          id: 'empty',
          label: 'No saved maps yet',
          hint: 'Start an AR scan to create your first map',
          type: FocusableElementType.header,
        ),
      ], onFocusChanged: () => setState(() {}));
      return;
    }

    final focusables = <FocusableElement>[
      FocusableElement(
        id: 'back',
        label: 'Go back',
        hint: 'Return to home screen',
        onActivate: () => Navigator.pop(context),
      ),
    ];

    for (int i = 0; i < _maps!.length; i++) {
      final map = _maps![i];
      focusables.add(FocusableElement(
        id: 'map_$i',
        label:
            '${map.name.replaceAll('_', ' ')}. ${map.formattedTimestamp}',
        hint:
            'Area ${map.areaM2.toStringAsFixed(0)} square meters, ${map.objectCount} objects. Double tap to open, long press to select.',
        onActivate: () => _openMap(map),
      ));
      focusables.add(FocusableElement(
        id: 'perf_$i',
        label: 'Performance for ${map.name.replaceAll('_', ' ')}',
        hint:
            'Double tap to view performance metrics for this scan.',
        onActivate: () => _openPerformance(map),
      ));
    }

    _accessibility.registerFocusables(focusables,
        onFocusChanged: () => setState(() {}));
  }

  @override
  void dispose() {
    _accessibility.clearFocusables();
    super.dispose();
  }

  Future<void> _loadMaps() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await _ch.invokeMethod('listSavedMaps');
      if (result is List && mounted) {
        final maps = result
            .whereType<Map>()
            .map((m) => SavedMapInfo.fromMap(m))
            .toList();
        maps.sort((a, b) => b.timestamp.compareTo(a.timestamp));
        setState(() {
          _maps = maps;
          _loading = false;
        });
        _registerFocusables();
        _accessibility.speak('${maps.length} saved maps found.');
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e.toString();
          _loading = false;
        });
        _accessibility.speak('Failed to load maps.');
      }
    }
  }

  Future<void> _deleteSelectedMaps() async {
    if (_selectedMaps.isEmpty) return;

    final count = _selectedMaps.length;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) {
        final p = WcagPalette.of(ctx);
        return AlertDialog(
          title: WcagText('Delete Maps',
              size: WcagType.headline, weight: WcagType.bold),
          content: WcagText(
            'Delete $count map${count == 1 ? '' : 's'}? This cannot be undone.',
            size: WcagType.body,
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: WcagText('Cancel',
                  size: WcagType.label,
                  weight: WcagType.semibold,
                  color: p.textPrimary),
            ),
            TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: WcagText('Delete',
                  size: WcagType.label,
                  weight: WcagType.semibold,
                  color: p.accentDanger),
            ),
          ],
        );
      },
    );
    if (confirmed != true) return;

    setState(() {
      _loading = true;
    });

    int deleted = 0;
    for (final mapName in _selectedMaps) {
      try {
        await _ch.invokeMethod('deleteMap', {'name': mapName});
        deleted++;
      } catch (e) {
        debugPrint('Failed to delete $mapName: $e');
      }
    }

    _isSelectionMode = false;
    _selectedMaps.clear();
    _loadMaps();
    refreshVoiceCommands();

    if (mounted) {
      final msg = 'Deleted $deleted map${deleted == 1 ? '' : 's'}';
      _accessibility.speak(msg);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: WcagText(msg,
              size: WcagType.body, color: Colors.white),
          duration: const Duration(seconds: 3),
        ),
      );
    }
  }

  void _openMap(SavedMapInfo map) {
    Navigator.pushNamed(context, '/map', arguments: map.name);
  }

  void _openPerformance(SavedMapInfo map) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => PerformanceDashboard(mapName: map.name),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final p = WcagPalette.of(context);

    return Scaffold(
      backgroundColor: p.background,
      appBar: AppBar(
        backgroundColor: p.surface,
        elevation: 0,
        leading: _isSelectionMode
            ? IconButton(
                iconSize: 28,
                icon: Icon(Icons.close_rounded, color: p.textPrimary),
                tooltip: 'Cancel selection',
                onPressed: () => setState(() {
                  _isSelectionMode = false;
                  _selectedMaps.clear();
                }),
              )
            : IconButton(
                iconSize: 28,
                icon: Icon(Icons.arrow_back_rounded, color: p.textPrimary),
                tooltip: 'Go back',
                onPressed: () => Navigator.pop(context),
              ),
        title: WcagText(
          _isSelectionMode
              ? '${_selectedMaps.length} Selected'
              : 'Saved Maps',
          size: WcagType.headline,
          weight: WcagType.bold,
        ),
        centerTitle: false,
        actions: _isSelectionMode
            ? [
                IconButton(
                  iconSize: 28,
                  icon: Icon(Icons.delete_outline_rounded,
                      color: p.accentDanger),
                  tooltip: 'Delete selected maps',
                  onPressed:
                      _selectedMaps.isEmpty ? null : _deleteSelectedMaps,
                ),
              ]
            : null,
      ),
      body: WcagScaffoldFrame(
        child: Stack(children: [
          _loading
              ? const Center(child: CircularProgressIndicator())
              : _error != null
                  ? _errorState(p)
                  : (_maps == null || _maps!.isEmpty)
                      ? _emptyState(p)
                      : _mapList(p),
          const GlobalVoiceFab(),
        ]),
      ),
    );
  }

  Widget _emptyState(WcagPalette p) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(40),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.map_outlined, size: 64, color: p.textTertiary),
          const SizedBox(height: 20),
          WcagText('No saved maps yet',
              size: WcagType.headline, weight: WcagType.semibold),
          const SizedBox(height: 8),
          WcagText('Start an AR scan to create your first map',
              align: TextAlign.center,
              size: WcagType.body,
              emphasis: 'secondary',
              height: 1.4),
        ]),
      ),
    );
  }

  Widget _errorState(WcagPalette p) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(40),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.error_outline_rounded,
              size: 56, color: p.accentDanger),
          const SizedBox(height: 16),
          WcagText('Failed to load maps',
              size: WcagType.headline, weight: WcagType.semibold),
          const SizedBox(height: 8),
          WcagText(_error ?? '',
              align: TextAlign.center,
              size: WcagType.body,
              emphasis: 'secondary'),
          const SizedBox(height: 20),
          SizedBox(
            height: WcagSize.minTouch,
            child: ElevatedButton(
              onPressed: _loadMaps,
              style: ElevatedButton.styleFrom(
                backgroundColor: p.accentPrimary,
                foregroundColor: p.textOnAccent,
                padding: const EdgeInsets.symmetric(horizontal: 24),
              ),
              child: WcagText('Retry',
                  size: WcagType.label,
                  weight: WcagType.semibold,
                  color: p.textOnAccent),
            ),
          ),
        ]),
      ),
    );
  }

  Widget _mapList(WcagPalette p) {
    return RefreshIndicator(
      onRefresh: _loadMaps,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        itemCount: _maps!.length,
        separatorBuilder: (_, __) => const SizedBox(height: 12),
        itemBuilder: (ctx, i) => _mapCard(_maps![i], p),
      ),
    );
  }

  Widget _mapCard(SavedMapInfo map, WcagPalette p) {
    final isSelected = _selectedMaps.contains(map.name);

    return Semantics(
      button: true,
      label:
          '${map.name.replaceAll('_', ' ')}. ${map.formattedTimestamp}. '
          'Area ${map.areaM2.toStringAsFixed(0)} square meters, '
          '${map.objectCount} objects, duration ${map.formattedDuration}. '
          'Tap to view, long press to select.',
      excludeSemantics: true,
      child: Material(
        color: isSelected ? p.accentPrimary.withOpacity(0.10) : p.surface,
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: () {
            if (_isSelectionMode) {
              setState(() {
                if (isSelected) {
                  _selectedMaps.remove(map.name);
                  if (_selectedMaps.isEmpty) _isSelectionMode = false;
                } else {
                  _selectedMaps.add(map.name);
                }
              });
            } else {
              _openMap(map);
            }
          },
          onLongPress: () {
            if (!_isSelectionMode) {
              HapticFeedback.lightImpact();
              setState(() {
                _isSelectionMode = true;
                _selectedMaps.add(map.name);
              });
            }
          },
          child: Container(
            constraints:
                const BoxConstraints(minHeight: WcagSize.minTouch + 32),
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              border: Border.all(
                color: isSelected ? p.accentPrimary : p.border,
                width: isSelected ? 2.5 : 1.5,
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [
                  if (_isSelectionMode)
                    Container(
                      margin: const EdgeInsets.only(right: 12),
                      width: 28,
                      height: 28,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: isSelected ? p.accentPrimary : p.surface,
                        border: Border.all(
                          color:
                              isSelected ? p.accentPrimary : p.borderStrong,
                          width: 2,
                        ),
                      ),
                      child: isSelected
                          ? Icon(Icons.check,
                              size: 18, color: p.textOnAccent)
                          : null,
                    )
                  else
                    Container(
                      padding: const EdgeInsets.all(10),
                      margin: const EdgeInsets.only(right: 12),
                      decoration: BoxDecoration(
                        color: p.accentSuccess,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Icon(Icons.map_rounded,
                          size: 22, color: p.textOnAccent),
                    ),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        WcagText(
                          map.name.replaceAll('_', ' '),
                          size: WcagType.label,
                          weight: WcagType.semibold,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: 4),
                        WcagText(
                          map.formattedTimestamp,
                          size: WcagType.caption,
                          emphasis: 'secondary',
                        ),
                      ],
                    ),
                  ),
                  if (!_isSelectionMode) ...[
                    SizedBox(
                      width: WcagSize.minTouch,
                      height: WcagSize.minTouch,
                      child: Semantics(
                        button: true,
                        label:
                            'View performance metrics for ${map.name.replaceAll('_', ' ')}',
                        excludeSemantics: true,
                        child: Material(
                          color: Colors.transparent,
                          shape: const CircleBorder(),
                          child: InkWell(
                            customBorder: const CircleBorder(),
                            onTap: () => _openPerformance(map),
                            child: Center(
                              child: Icon(Icons.analytics_rounded,
                                  size: 24, color: p.accentWarning),
                            ),
                          ),
                        ),
                      ),
                    ),
                    Icon(Icons.chevron_right_rounded,
                        size: 22, color: p.textSecondary),
                  ],
                ]),
                const SizedBox(height: 14),
                Wrap(
                  spacing: 12,
                  runSpacing: 8,
                  children: [
                    _statChip(Icons.square_foot_rounded,
                        '${map.areaM2.toStringAsFixed(0)} m²', p),
                    _statChip(Icons.category_rounded,
                        '${map.objectCount} objects', p),
                    _statChip(Icons.timer_outlined,
                        map.formattedDuration, p),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _statChip(IconData icon, String text, WcagPalette p) {
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Icon(icon, size: 16, color: p.textSecondary),
      const SizedBox(width: 6),
      WcagText(text,
          size: WcagType.caption,
          emphasis: 'secondary',
          weight: WcagType.medium),
    ]);
  }
}
