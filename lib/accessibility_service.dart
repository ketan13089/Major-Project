import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Accessibility service for blind users.
/// Provides TTS announcements, focus management, and haptic feedback.
class AccessibilityService extends ChangeNotifier {
  static final AccessibilityService _instance = AccessibilityService._internal();
  factory AccessibilityService() => _instance;
  AccessibilityService._internal();

  static const _ttsChannel = MethodChannel('com.ketan.slam/tts');

  bool _enabled = true;
  bool get enabled => _enabled;

  // Pause state - when true, TTS and focus navigation are suspended
  // Used when AR activity is active (has its own TTS)
  bool _paused = false;
  bool get paused => _paused;

  // Focus tracking for volume button navigation
  int _currentFocusIndex = 0;
  List<FocusableElement> _focusableElements = [];
  VoidCallback? _onFocusChanged;

  /// Pause accessibility (when AR is active)
  void pause() {
    _paused = true;
    notifyListeners();
  }

  /// Resume accessibility (when AR is closed)
  void resume() {
    _paused = false;
    notifyListeners();
  }

  /// Toggle accessibility mode
  void toggle() {
    _enabled = !_enabled;
    notifyListeners();
    if (_enabled) {
      speak('Accessibility mode enabled. Use volume buttons to navigate.');
    } else {
      speak('Accessibility mode disabled.');
    }
  }

  /// Speak text using native TTS
  Future<void> speak(String text, {bool interrupt = true}) async {
    if (!_enabled || _paused || text.isEmpty) return;
    try {
      await _ttsChannel.invokeMethod('speak', {
        'text': text,
        'interrupt': interrupt,
      });
    } catch (e) {
      // Fallback: use platform semantics announcement
      debugPrint('TTS fallback: $text');
    }
  }

  /// Stop any ongoing speech
  Future<void> stopSpeaking() async {
    try {
      await _ttsChannel.invokeMethod('stop');
    } catch (_) {}
  }

  /// Haptic feedback patterns
  Future<void> hapticTick() async {
    await HapticFeedback.lightImpact();
  }

  Future<void> hapticConfirm() async {
    await HapticFeedback.mediumImpact();
  }

  Future<void> hapticError() async {
    await HapticFeedback.heavyImpact();
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Focus management for volume button navigation
  // ─────────────────────────────────────────────────────────────────────────

  /// Register focusable elements for the current screen
  void registerFocusables(List<FocusableElement> elements, {VoidCallback? onFocusChanged}) {
    _focusableElements = elements;
    _onFocusChanged = onFocusChanged;
    _currentFocusIndex = 0;
    if (elements.isNotEmpty && _enabled && !_paused) {
      _announceFocusedElement();
    }
  }

  /// Clear registered focusables (call on screen dispose)
  void clearFocusables() {
    _focusableElements = [];
    _currentFocusIndex = 0;
    _onFocusChanged = null;
  }

  /// Get current focus index
  int get currentFocusIndex => _currentFocusIndex;

  /// Get total focusable elements count
  int get focusableCount => _focusableElements.length;

  /// Get current focused element
  FocusableElement? get currentFocusedElement {
    if (_focusableElements.isEmpty) return null;
    if (_currentFocusIndex < 0 || _currentFocusIndex >= _focusableElements.length) return null;
    return _focusableElements[_currentFocusIndex];
  }

  /// Move focus to next element (Volume Down)
  void focusNext() {
    if (_focusableElements.isEmpty || _paused) return;
    _currentFocusIndex = (_currentFocusIndex + 1) % _focusableElements.length;
    hapticTick();
    _announceFocusedElement();
    _onFocusChanged?.call();
  }

  /// Move focus to previous element (Volume Up)
  void focusPrevious() {
    if (_focusableElements.isEmpty || _paused) return;
    _currentFocusIndex = (_currentFocusIndex - 1 + _focusableElements.length) % _focusableElements.length;
    hapticTick();
    _announceFocusedElement();
    _onFocusChanged?.call();
  }

  /// Activate the currently focused element
  void activateFocused() {
    if (_paused) return;
    final element = currentFocusedElement;
    if (element != null) {
      hapticConfirm();
      speak('Activating ${element.label}');
      element.onActivate?.call();
    }
  }

  /// Set focus to a specific index
  void setFocusIndex(int index) {
    if (_paused) return;
    if (index >= 0 && index < _focusableElements.length) {
      _currentFocusIndex = index;
      _announceFocusedElement();
      _onFocusChanged?.call();
    }
  }

  void _announceFocusedElement() {
    if (_paused) return;
    final element = currentFocusedElement;
    if (element != null) {
      final position = '${_currentFocusIndex + 1} of ${_focusableElements.length}';
      speak('${element.label}. $position. ${element.hint ?? ""}');
    }
  }

  /// Announce screen change
  void announceScreen(String screenName) {
    speak('$screenName screen. ${_focusableElements.length} items.');
  }
}

/// Represents a focusable UI element
class FocusableElement {
  final String id;
  final String label;
  final String? hint;
  final VoidCallback? onActivate;
  final FocusableElementType type;

  const FocusableElement({
    required this.id,
    required this.label,
    this.hint,
    this.onActivate,
    this.type = FocusableElementType.button,
  });
}

enum FocusableElementType {
  button,
  toggle,
  slider,
  listItem,
  input,
  header,
}

// ─────────────────────────────────────────────────────────────────────────────
// Accessibility wrapper widget with focus highlight
// ─────────────────────────────────────────────────────────────────────────────

/// Wraps a widget with accessibility focus highlight
class AccessibleFocusable extends StatelessWidget {
  final Widget child;
  final int index;
  final Color focusColor;
  final double focusBorderWidth;
  final BorderRadius? borderRadius;

  const AccessibleFocusable({
    Key? key,
    required this.child,
    required this.index,
    this.focusColor = const Color(0xFF2563EB),
    this.focusBorderWidth = 3.0,
    this.borderRadius,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final service = AccessibilityService();
    return ListenableBuilder(
      listenable: service,
      builder: (context, _) {
        final isFocused = service.enabled && service.currentFocusIndex == index;
        return AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          decoration: BoxDecoration(
            borderRadius: borderRadius ?? BorderRadius.circular(12),
            border: isFocused
                ? Border.all(color: focusColor, width: focusBorderWidth)
                : null,
            boxShadow: isFocused
                ? [
                    BoxShadow(
                      color: focusColor.withOpacity(0.3),
                      blurRadius: 8,
                      spreadRadius: 2,
                    ),
                  ]
                : null,
          ),
          child: child,
        );
      },
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Volume button handler mixin
// ─────────────────────────────────────────────────────────────────────────────

/// Mixin to handle volume button navigation.
/// 
/// Volume Button Controls:
/// - Single Volume Up: Move to previous item
/// - Single Volume Down: Move to next item  
/// - Hold Volume Up (0.5s): Activate/Enter selected item
/// - Hold Volume Down (0.5s): Go back to previous screen
/// - Double-tap Volume Up: Read current position
/// - Double-tap Volume Down: Open accessibility help guide
mixin VolumeButtonNavigationMixin<T extends StatefulWidget> on State<T> {
  static const _volumeChannel = MethodChannel('com.ketan.slam/volume_buttons');
  
  bool _volumeNavEnabled = true;
  bool _helpDialogShowing = false;

  @override
  void initState() {
    super.initState();
    _volumeChannel.setMethodCallHandler(_handleVolumeAction);
  }

  @override
  void dispose() {
    _volumeChannel.setMethodCallHandler(null);
    super.dispose();
  }

  /// Override to customize go back behavior
  void onVolumeGoBack() {
    Navigator.of(context).maybePop();
  }

  /// Override to add custom help content
  List<String> getAdditionalHelpItems() => [];

  Future<dynamic> _handleVolumeAction(MethodCall call) async {
    if (!_volumeNavEnabled) return;
    final accessibility = AccessibilityService();
    
    if (call.method == 'volumeAction') {
      final args = call.arguments as Map?;
      final action = args?['action'] as String?;
      
      switch (action) {
        case 'next':
          accessibility.focusNext();
          break;
          
        case 'previous':
          accessibility.focusPrevious();
          break;
          
        case 'activate':
          accessibility.activateFocused();
          break;
          
        case 'goBack':
          accessibility.speak('Going back');
          onVolumeGoBack();
          break;
          
        case 'readPosition':
          _announceCurrentPosition();
          break;
          
        case 'openHelp':
          _showHelpGuide();
          break;
      }
    }
  }

  void _announceCurrentPosition() {
    final accessibility = AccessibilityService();
    final element = accessibility.currentFocusedElement;
    if (element != null) {
      final position = '${accessibility.currentFocusIndex + 1} of ${accessibility.focusableCount}';
      accessibility.speak(
        'Currently on: ${element.label}. Position $position. ${element.hint ?? ""}',
      );
    } else {
      accessibility.speak('No item focused.');
    }
  }

  void _showHelpGuide() {
    if (_helpDialogShowing) return;
    _helpDialogShowing = true;
    
    final accessibility = AccessibilityService();
    accessibility.speak('Opening accessibility help guide.');
    
    showDialog(
      context: context,
      builder: (ctx) => AccessibilityHelpDialog(
        additionalItems: getAdditionalHelpItems(),
      ),
    ).then((_) {
      _helpDialogShowing = false;
      accessibility.speak('Help guide closed.');
    });
  }

  /// Enable/disable volume navigation
  void setVolumeNavEnabled(bool enabled) {
    _volumeNavEnabled = enabled;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Accessibility Help Dialog
// ─────────────────────────────────────────────────────────────────────────────

class AccessibilityHelpDialog extends StatefulWidget {
  final List<String> additionalItems;
  
  const AccessibilityHelpDialog({
    Key? key,
    this.additionalItems = const [],
  }) : super(key: key);

  @override
  State<AccessibilityHelpDialog> createState() => _AccessibilityHelpDialogState();
}

class _AccessibilityHelpDialogState extends State<AccessibilityHelpDialog> {
  int _currentIndex = 0;
  
  final List<_HelpItem> _helpItems = [
    _HelpItem(
      title: 'Volume Button Navigation',
      description: 'Use the physical volume buttons on your phone to navigate the app without touching the screen.',
    ),
    _HelpItem(
      title: 'Move to Next Item',
      description: 'Press Volume Down once to move to the next item on the screen.',
      gesture: 'Single tap Volume Down',
    ),
    _HelpItem(
      title: 'Move to Previous Item',
      description: 'Press Volume Up once to move to the previous item on the screen.',
      gesture: 'Single tap Volume Up',
    ),
    _HelpItem(
      title: 'Activate or Enter',
      description: 'Hold Volume Up for half a second to activate the selected item. This is like tapping on it.',
      gesture: 'Hold Volume Up',
    ),
    _HelpItem(
      title: 'Go Back',
      description: 'Hold Volume Down for half a second to go back to the previous screen.',
      gesture: 'Hold Volume Down',
    ),
    _HelpItem(
      title: 'Read Current Position',
      description: 'Double-tap Volume Up to hear your current position and the selected item again.',
      gesture: 'Double-tap Volume Up',
    ),
    _HelpItem(
      title: 'Open This Help Guide',
      description: 'Double-tap Volume Down to open this help guide at any time.',
      gesture: 'Double-tap Volume Down',
    ),
    _HelpItem(
      title: 'Voice Navigation',
      description: 'In the map viewer, hold Volume Up on the voice navigation button to start voice commands. Say things like "take me to the nearest door" or "navigate to room 101".',
    ),
    _HelpItem(
      title: 'Tips',
      description: 'The app will announce each item as you navigate. Listen for the position number to know where you are in the list. A short vibration confirms your action.',
    ),
  ];

  @override
  void initState() {
    super.initState();
    _announceCurrentItem();
  }

  void _announceCurrentItem() {
    final item = _helpItems[_currentIndex];
    final position = '${_currentIndex + 1} of ${_helpItems.length}';
    AccessibilityService().speak(
      '${item.title}. $position. ${item.description}',
    );
  }

  void _next() {
    setState(() {
      _currentIndex = (_currentIndex + 1) % _helpItems.length;
    });
    _announceCurrentItem();
  }

  void _previous() {
    setState(() {
      _currentIndex = (_currentIndex - 1 + _helpItems.length) % _helpItems.length;
    });
    _announceCurrentItem();
  }

  @override
  Widget build(BuildContext context) {
    final item = _helpItems[_currentIndex];
    final isDark = Theme.of(context).brightness == Brightness.dark;
    
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Header
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: const Color(0xFF7C3AED).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(
                    Icons.accessibility_new_rounded,
                    color: Color(0xFF7C3AED),
                    size: 24,
                  ),
                ),
                const SizedBox(width: 12),
                const Expanded(
                  child: Text(
                    'Accessibility Guide',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.close_rounded),
                ),
              ],
            ),
            
            const SizedBox(height: 20),
            
            // Progress indicator
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: List.generate(_helpItems.length, (i) => Container(
                width: i == _currentIndex ? 24 : 8,
                height: 8,
                margin: const EdgeInsets.symmetric(horizontal: 2),
                decoration: BoxDecoration(
                  color: i == _currentIndex 
                      ? const Color(0xFF7C3AED) 
                      : const Color(0xFF7C3AED).withOpacity(0.2),
                  borderRadius: BorderRadius.circular(4),
                ),
              )),
            ),
            
            const SizedBox(height: 20),
            
            // Content
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: isDark 
                    ? Colors.white.withOpacity(0.05) 
                    : Colors.grey.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.title,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (item.gesture != null) ...[
                    const SizedBox(height: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                      decoration: BoxDecoration(
                        color: const Color(0xFF7C3AED).withOpacity(0.1),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        item.gesture!,
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: Color(0xFF7C3AED),
                        ),
                      ),
                    ),
                  ],
                  const SizedBox(height: 12),
                  Text(
                    item.description,
                    style: TextStyle(
                      fontSize: 14,
                      height: 1.5,
                      color: isDark ? Colors.white70 : Colors.black87,
                    ),
                  ),
                ],
              ),
            ),
            
            const SizedBox(height: 20),
            
            // Navigation buttons
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _previous,
                    icon: const Icon(Icons.arrow_back_rounded, size: 18),
                    label: const Text('Previous'),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _next,
                    icon: const Icon(Icons.arrow_forward_rounded, size: 18),
                    label: const Text('Next'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFF7C3AED),
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                  ),
                ),
              ],
            ),
            
            const SizedBox(height: 12),
            
            Text(
              '${_currentIndex + 1} of ${_helpItems.length}',
              style: TextStyle(
                fontSize: 12,
                color: isDark ? Colors.white54 : Colors.black45,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _HelpItem {
  final String title;
  final String description;
  final String? gesture;
  
  const _HelpItem({
    required this.title,
    required this.description,
    this.gesture,
  });
}
