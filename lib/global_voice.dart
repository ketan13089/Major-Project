import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'accessibility_service.dart';
import 'wcag_theme.dart';

/// A single voice command. The user can say any of [phrases] (case-insensitive,
/// whole-word) to trigger [onMatch]. [description] appears in the in-app help.
class GlobalVoiceCommand {
  final List<String> phrases;
  final String description;
  final VoidCallback onMatch;

  const GlobalVoiceCommand({
    required this.phrases,
    required this.description,
    required this.onMatch,
  });
}

enum GlobalVoiceState { idle, listening, processing }

/// App-wide voice command dispatcher.
///
/// Each screen calls [setScreenCommands] in initState with the commands valid
/// on that screen. Built-in commands ("home", "back", "help", "what's here",
/// "repeat") are added automatically by [_buildAllCommands].
///
/// The transcript matcher prefers longer phrases first, so a screen that
/// registers "open performance dashboard" wins over a built-in "open" prefix.
/// Match is case-insensitive substring with word-boundary checks (so "back"
/// matches "go back" but not "background").
class GlobalVoiceController extends ChangeNotifier {
  GlobalVoiceController._();
  static final GlobalVoiceController instance = GlobalVoiceController._();

  static const _channel = MethodChannel('com.ketan.slam/global_voice');
  bool _wired = false;

  GlobalVoiceState _state = GlobalVoiceState.idle;
  GlobalVoiceState get state => _state;

  String? _lastError;
  String? get lastError => _lastError;

  /// Per-screen registered commands, replaced wholesale on each screen change.
  List<GlobalVoiceCommand> _screenCommands = const [];

  /// Built-in commands, populated by the host once Navigator is available.
  List<GlobalVoiceCommand> _builtInCommands = const [];

  /// Set the built-in commands once at app startup.
  void setBuiltIns(List<GlobalVoiceCommand> commands) {
    _builtInCommands = commands;
    _ensureWired();
  }

  /// Replace the screen's command vocabulary. Called from each screen's
  /// initState — and again from dispose with an empty list.
  void setScreenCommands(List<GlobalVoiceCommand> commands) {
    _screenCommands = commands;
    _ensureWired();
  }

  /// All commands the matcher will consider, longest phrases first so more
  /// specific commands shadow generic ones.
  List<GlobalVoiceCommand> _buildAllCommands() {
    final all = [..._screenCommands, ..._builtInCommands];
    return all;
  }

  void _ensureWired() {
    if (_wired) return;
    _wired = true;
    _channel.setMethodCallHandler(_onNativeCall);
  }

  /// Returns true if the device supports speech recognition.
  Future<bool> isAvailable() async {
    _ensureWired();
    try {
      final result = await _channel.invokeMethod<bool>('isAvailable');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Start a one-shot listen. Idempotent — re-entry is ignored.
  Future<void> startListening() async {
    if (_state != GlobalVoiceState.idle) return;
    _ensureWired();
    final available = await isAvailable();
    if (!available) {
      _emitError(
          'Voice commands are not available on this device.');
      return;
    }
    AccessibilityService().speak('Listening', interrupt: true);
    AccessibilityService().hapticConfirm();
    try {
      await _channel.invokeMethod('startListen');
    } catch (e) {
      _emitError('Could not start listening: $e');
    }
  }

  Future<void> stopListening() async {
    if (_state == GlobalVoiceState.idle) return;
    try {
      await _channel.invokeMethod('stopListen');
    } catch (_) {}
  }

  Future<void> _onNativeCall(MethodCall call) async {
    switch (call.method) {
      case 'onState':
        final s = (call.arguments as Map?)?['state'] as String? ?? 'IDLE';
        _state = switch (s) {
          'LISTENING' => GlobalVoiceState.listening,
          'PROCESSING' => GlobalVoiceState.processing,
          _ => GlobalVoiceState.idle,
        };
        notifyListeners();
        break;
      case 'onTranscript':
        final text = (call.arguments as Map?)?['text'] as String? ?? '';
        _state = GlobalVoiceState.idle;
        notifyListeners();
        _dispatch(text);
        break;
      case 'onError':
        final msg =
            (call.arguments as Map?)?['message'] as String? ?? 'Voice error';
        _emitError(msg);
        break;
    }
  }

  void _emitError(String message) {
    _lastError = message;
    _state = GlobalVoiceState.idle;
    notifyListeners();
    AccessibilityService().speak(message, interrupt: true);
    AccessibilityService().hapticError();
  }

  /// Match transcript to a command. Strategy:
  /// 1. Lowercase + trim the transcript.
  /// 2. For every phrase across every command, ranked longest-first, check
  ///    whether the transcript contains that phrase as a whole-word match.
  /// 3. First hit wins.
  void _dispatch(String transcript) {
    final t = ' ${transcript.toLowerCase().trim()} ';
    if (t.length <= 2) {
      AccessibilityService()
          .speak('No command recognized. Say help to hear available commands.');
      return;
    }

    final all = _buildAllCommands();
    // Build (phrase, command) pairs sorted by phrase length DESC.
    final pairs = <MapEntry<String, GlobalVoiceCommand>>[];
    for (final cmd in all) {
      for (final p in cmd.phrases) {
        pairs.add(MapEntry(p.toLowerCase(), cmd));
      }
    }
    pairs.sort((a, b) => b.key.length.compareTo(a.key.length));

    for (final entry in pairs) {
      final phrase = ' ${entry.key} ';
      if (t.contains(phrase)) {
        AccessibilityService()
            .speak('Heard: ${entry.key}', interrupt: true);
        AccessibilityService().hapticConfirm();
        try {
          entry.value.onMatch();
        } catch (e) {
          AccessibilityService().speak('Command failed: $e');
        }
        return;
      }
    }

    AccessibilityService().speak(
      'Sorry, I didn\'t catch a command. You said: $transcript. '
      'Say help to hear available commands.',
    );
  }

  /// Build a human-readable help string of the currently active commands.
  String availableCommandsSummary() {
    final all = _buildAllCommands();
    if (all.isEmpty) return 'No voice commands available on this screen.';
    final lines = <String>[];
    for (final c in all) {
      final example = c.phrases.first;
      lines.add('Say "$example" — ${c.description}.');
    }
    return lines.join(' ');
  }
}

/// 64dp WCAG-compliant push-to-talk FAB. Drop into a Stack on any screen
/// (typically `Positioned(right: 16, bottom: 16)`).
///
/// State:
///   idle       → mic icon, primary color
///   listening  → mic-on icon, danger color (matches the AR voice nav
///                pattern so the visual language is consistent)
///   processing → spinner, accent
class GlobalVoiceFab extends StatelessWidget {
  /// Optional override of the default bottom inset (e.g. when the screen
  /// already has a bottom bar that the FAB should sit above).
  final double? bottom;

  /// Right inset. Pass null *and* set [left] to position on the left edge.
  final double? right;

  /// Left inset. Mutually exclusive with [right].
  final double? left;

  const GlobalVoiceFab({super.key, this.bottom, this.right = 16, this.left});

  @override
  Widget build(BuildContext context) {
    final p = WcagPalette.of(context);
    final controller = GlobalVoiceController.instance;
    return Positioned(
      right: left == null ? right : null,
      left: left,
      bottom: bottom ?? 16,
      child: Semantics(
        button: true,
        label: 'Voice command. Tap and speak a command. '
            'Say help to hear available commands.',
        excludeSemantics: true,
        child: AnimatedBuilder(
          animation: controller,
          builder: (context, _) {
            final state = controller.state;
            final color = switch (state) {
              GlobalVoiceState.listening => p.accentDanger,
              GlobalVoiceState.processing => p.accentWarning,
              GlobalVoiceState.idle => p.accentPrimary,
            };
            final icon = switch (state) {
              GlobalVoiceState.listening => Icons.mic,
              GlobalVoiceState.processing => Icons.graphic_eq,
              GlobalVoiceState.idle => Icons.record_voice_over,
            };
            return Material(
              color: color,
              shape: const CircleBorder(),
              elevation: 6,
              child: InkWell(
                customBorder: const CircleBorder(),
                onTap: () {
                  if (state == GlobalVoiceState.listening) {
                    controller.stopListening();
                  } else {
                    controller.startListening();
                  }
                },
                child: Container(
                  width: 64,
                  height: 64,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: p.textOnAccent,
                      width: 2.5,
                    ),
                  ),
                  child: Icon(icon, color: p.textOnAccent, size: 30),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// Convenience: register a command set scoped to a screen's lifecycle.
/// Pair with [VoiceCommandsScope] in the build tree, OR call manually from
/// initState / dispose.
mixin GlobalVoiceCommandsMixin<T extends StatefulWidget> on State<T> {
  /// Override to declare the screen's commands. Built-ins are added
  /// automatically by the controller.
  List<GlobalVoiceCommand> buildVoiceCommands(BuildContext context);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      GlobalVoiceController.instance
          .setScreenCommands(buildVoiceCommands(context));
    });
  }

  /// Call from your own state-change paths to refresh the registered set
  /// (e.g. toggling Accessibility on/off changes the available commands).
  void refreshVoiceCommands() {
    if (!mounted) return;
    GlobalVoiceController.instance
        .setScreenCommands(buildVoiceCommands(context));
  }

  @override
  void dispose() {
    GlobalVoiceController.instance.setScreenCommands(const []);
    super.dispose();
  }
}
