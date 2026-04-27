import 'package:flutter/material.dart';
import 'indoor_map_viewer.dart';
import 'saved_maps_screen.dart';
import 'accessibility_service.dart';
import 'global_voice.dart';
import 'wcag_theme.dart';

/// Global navigator key — used by global voice commands ("home", "back",
/// "open map") so they can navigate without a BuildContext.
final GlobalKey<NavigatorState> appNavigatorKey = GlobalKey<NavigatorState>();

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  _installBuiltInVoiceCommands();
  runApp(const MyApp());
}

/// Install the always-available voice commands. These work on every screen
/// in addition to whatever each screen registers via [GlobalVoiceCommandsMixin].
void _installBuiltInVoiceCommands() {
  final acc = AccessibilityService();
  GlobalVoiceController.instance.setBuiltIns([
    GlobalVoiceCommand(
      phrases: const ['home', 'go home', 'main menu'],
      description: 'Return to the home screen',
      onMatch: () {
        appNavigatorKey.currentState
            ?.popUntil((route) => route.isFirst);
      },
    ),
    GlobalVoiceCommand(
      phrases: const ['back', 'go back', 'previous'],
      description: 'Go back to the previous screen',
      onMatch: () {
        appNavigatorKey.currentState?.maybePop();
      },
    ),
    GlobalVoiceCommand(
      phrases: const ['help', 'what can I say', 'commands', 'voice help'],
      description: 'List available voice commands on this screen',
      onMatch: () {
        acc.speak(
          GlobalVoiceController.instance.availableCommandsSummary(),
          interrupt: true,
        );
      },
    ),
    GlobalVoiceCommand(
      phrases: const ["what's here", 'where am I', 'current item'],
      description: 'Read the currently focused item again',
      onMatch: () {
        final el = acc.currentFocusedElement;
        if (el != null) {
          final pos =
              '${acc.currentFocusIndex + 1} of ${acc.focusableCount}';
          acc.speak(
            'Currently on ${el.label}. Position $pos. ${el.hint ?? ''}',
          );
        } else {
          acc.speak('No item focused.');
        }
      },
    ),
    GlobalVoiceCommand(
      phrases: const ['repeat', 'say that again'],
      description: 'Repeat the last announcement',
      onMatch: () {
        final el = acc.currentFocusedElement;
        if (el != null) {
          acc.speak('${el.label}. ${el.hint ?? ''}', interrupt: true);
        }
      },
    ),
  ]);
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Indoor Navigator',
      navigatorKey: appNavigatorKey,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: WcagPalette.light.accentPrimary,
        ),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: WcagPalette.dark.accentPrimary,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      themeMode: ThemeMode.system,
      initialRoute: '/',
      onGenerateRoute: (settings) {
        switch (settings.name) {
          case '/':
            return MaterialPageRoute(builder: (_) => const HomePage());
          case '/map':
            final savedMapName = settings.arguments as String?;
            return MaterialPageRoute(
              builder: (_) => IndoorMapViewer(savedMapName: savedMapName),
            );
          case '/saved-maps':
            return MaterialPageRoute(builder: (_) => const SavedMapsScreen());
          default:
            return MaterialPageRoute(builder: (_) => const HomePage());
        }
      },
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage>
    with VolumeButtonNavigationMixin, GlobalVoiceCommandsMixin {
  final _accessibility = AccessibilityService();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _registerFocusables();
      _accessibility.announceScreen('Home');
    });
  }

  void _registerFocusables() {
    _accessibility.registerFocusables([
      FocusableElement(
        id: 'view_map',
        label: 'View Indoor Map',
        hint: 'Opens the map viewer to see your scanned floor plan',
        onActivate: () => _openMapViewer(context),
      ),
      FocusableElement(
        id: 'saved_maps',
        label: 'Saved Maps',
        hint:
            'View and manage your previously saved maps. Each map carries its own performance metrics.',
        onActivate: () => Navigator.pushNamed(context, '/saved-maps'),
      ),
      FocusableElement(
        id: 'accessibility_toggle',
        label: _accessibility.enabled
            ? 'Disable Accessibility Mode'
            : 'Enable Accessibility Mode',
        hint: 'Toggle voice announcements and volume button navigation',
        onActivate: () {
          _accessibility.toggle();
          setState(() {});
          _registerFocusables();
          refreshVoiceCommands();
        },
        type: FocusableElementType.toggle,
      ),
    ], onFocusChanged: () => setState(() {}));
  }

  @override
  List<GlobalVoiceCommand> buildVoiceCommands(BuildContext context) {
    return [
      GlobalVoiceCommand(
        phrases: const ['open map', 'view map', 'show map', 'indoor map'],
        description: 'Open the indoor map viewer',
        onMatch: () => _openMapViewer(context),
      ),
      GlobalVoiceCommand(
        phrases: const [
          'saved maps',
          'open saved maps',
          'my maps',
          'show saved'
        ],
        description: 'Open the saved maps list',
        onMatch: () => Navigator.pushNamed(context, '/saved-maps'),
      ),
      GlobalVoiceCommand(
        phrases: const [
          'toggle accessibility',
          'turn off accessibility',
          'turn on accessibility',
          'accessibility mode'
        ],
        description: 'Toggle accessibility mode on or off',
        onMatch: () {
          _accessibility.toggle();
          setState(() {});
          _registerFocusables();
          refreshVoiceCommands();
        },
      ),
    ];
  }

  @override
  void dispose() {
    _accessibility.clearFocusables();
    super.dispose();
  }

  void _openMapViewer(BuildContext context) {
    _accessibility.speak('Opening map viewer');
    Navigator.pushNamed(context, '/map');
  }

  @override
  Widget build(BuildContext context) {
    final p = WcagPalette.of(context);

    return Scaffold(
      backgroundColor: p.background,
      body: WcagScaffoldFrame(
        // Stack lives OUTSIDE the SafeArea so the voice command bar can
        // extend all the way to the device bottom edge. The bar handles
        // its own gesture-inset padding internally.
        child: Stack(children: [
          SafeArea(
            child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _Header(p: p),
                const SizedBox(height: 28),
                _HeroBanner(p: p),
                const SizedBox(height: 24),
                AccessibleFocusable(
                  index: 0,
                  borderRadius: BorderRadius.circular(16),
                  child: Semantics(
                    button: true,
                    label:
                        'View Indoor Map. Explore the map built from your scan.',
                    excludeSemantics: true,
                    child: _ActionCard(
                      title: 'View Indoor Map',
                      subtitle: 'Explore your scanned floor plan',
                      icon: Icons.map_rounded,
                      iconColor: p.accentPrimary,
                      p: p,
                      onTap: () => _openMapViewer(context),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                AccessibleFocusable(
                  index: 1,
                  borderRadius: BorderRadius.circular(16),
                  child: Semantics(
                    button: true,
                    label: 'Saved Maps. View and manage your scanned maps.',
                    excludeSemantics: true,
                    child: _ActionCard(
                      title: 'Saved Maps',
                      subtitle: 'View and manage your scanned maps',
                      icon: Icons.folder_rounded,
                      iconColor: p.accentSuccess,
                      p: p,
                      onTap: () =>
                          Navigator.pushNamed(context, '/saved-maps'),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                AccessibleFocusable(
                  index: 2,
                  borderRadius: BorderRadius.circular(16),
                  child: Semantics(
                    button: true,
                    toggled: _accessibility.enabled,
                    label: _accessibility.enabled
                        ? 'Accessibility mode is on. Tap to disable.'
                        : 'Accessibility mode is off. Tap to enable.',
                    excludeSemantics: true,
                    child: _ActionCard(
                      title: _accessibility.enabled
                          ? 'Accessibility: ON'
                          : 'Accessibility: OFF',
                      subtitle:
                          'Volume buttons navigate, long-press to toggle',
                      icon: Icons.accessibility_new_rounded,
                      iconColor: _accessibility.enabled
                          ? p.accentPrimary
                          : p.textSecondary,
                      p: p,
                      onTap: () {
                        _accessibility.toggle();
                        setState(() {});
                        _registerFocusables();
                      },
                    ),
                  ),
                ),
                const SizedBox(height: 96),
              ],
            ),
          ),
        ),
        const GlobalVoiceFab(),
        ]),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  final WcagPalette p;
  const _Header({required this.p});

  @override
  Widget build(BuildContext context) {
    return Row(children: [
      Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: p.accentPrimary,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Icon(Icons.explore_rounded,
            size: 32, color: p.textOnAccent),
      ),
      const SizedBox(width: 12),
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: const [
        WcagText('Indoor Navigator',
            size: WcagType.title, weight: WcagType.bold, letterSpacing: -0.3),
        SizedBox(height: 2),
        WcagText('AR-powered wayfinding',
            size: WcagType.caption, emphasis: 'secondary'),
      ]),
    ]);
  }
}

class _HeroBanner extends StatelessWidget {
  final WcagPalette p;
  const _HeroBanner({required this.p});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(24, 32, 24, 28),
      decoration: BoxDecoration(
        color: p.accentPrimary,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(children: [
        Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            color: Colors.white.withOpacity(0.18),
            borderRadius: BorderRadius.circular(18),
          ),
          child: Icon(Icons.accessibility_new_rounded,
              size: 48, color: p.textOnAccent),
        ),
        const SizedBox(height: 16),
        WcagText(
          'Navigate indoors\nwith confidence',
          align: TextAlign.center,
          size: WcagType.headline,
          weight: WcagType.bold,
          color: p.textOnAccent,
          height: 1.3,
        ),
        const SizedBox(height: 8),
        WcagText(
          'Real-time obstacle alerts, voice navigation,\n'
          'and spatial audio feedback',
          align: TextAlign.center,
          size: WcagType.caption,
          color: p.textOnAccent.withOpacity(0.9),
          height: 1.4,
        ),
      ]),
    );
  }
}

class _ActionCard extends StatelessWidget {
  final String title, subtitle;
  final IconData icon;
  final Color iconColor;
  final WcagPalette p;
  final VoidCallback onTap;

  const _ActionCard({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.iconColor,
    required this.p,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: p.surface,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          constraints: const BoxConstraints(minHeight: WcagSize.minTouch + 24),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 18),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: p.border, width: 1.5),
          ),
          child: Row(children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: iconColor,
                borderRadius: BorderRadius.circular(16),
              ),
              child: Icon(icon, size: 34, color: p.textOnAccent),
            ),
            const SizedBox(width: 18),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  WcagText(title,
                      size: WcagType.label, weight: WcagType.semibold),
                  const SizedBox(height: 4),
                  WcagText(subtitle,
                      size: WcagType.caption,
                      emphasis: 'secondary',
                      height: 1.3),
                ],
              ),
            ),
            Icon(Icons.arrow_forward_ios_rounded,
                size: 20, color: p.textSecondary),
          ]),
        ),
      ),
    );
  }
}

