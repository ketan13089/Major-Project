import 'package:flutter/material.dart';
import 'indoor_map_viewer.dart';
import 'saved_maps_screen.dart';
import 'accessibility_service.dart';
import 'wcag_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Indoor Navigator',
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

class _HomePageState extends State<HomePage> with VolumeButtonNavigationMixin {
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
        hint: 'View and manage your previously saved maps. Each map carries its own performance metrics.',
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
        },
        type: FocusableElementType.toggle,
      ),
    ], onFocusChanged: () => setState(() {}));
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
        child: SafeArea(
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
                const SizedBox(height: 28),
                WcagText(
                  'Capabilities',
                  size: WcagType.label,
                  weight: WcagType.semibold,
                  letterSpacing: -0.2,
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: const [
                    _CapChip(Icons.radar, 'Obstacle alerts'),
                    _CapChip(Icons.hearing, 'Spatial audio'),
                    _CapChip(Icons.mic, 'Voice commands'),
                    _CapChip(Icons.stairs, 'Stair warnings'),
                    _CapChip(Icons.sos, 'Emergency SOS'),
                    _CapChip(Icons.undo, 'Guide me back'),
                    _CapChip(Icons.save_alt, 'Save maps'),
                    _CapChip(Icons.accessibility_new, 'TalkBack'),
                  ],
                ),
              ],
            ),
          ),
        ),
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
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: p.accentPrimary,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Icon(Icons.explore_rounded,
            size: 24, color: p.textOnAccent),
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
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: Colors.white.withOpacity(0.18),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Icon(Icons.accessibility_new_rounded,
              size: 36, color: p.textOnAccent),
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
              padding: const EdgeInsets.all(13),
              decoration: BoxDecoration(
                color: iconColor,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(icon, size: 26, color: p.textOnAccent),
            ),
            const SizedBox(width: 16),
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
                size: 16, color: p.textSecondary),
          ]),
        ),
      ),
    );
  }
}

class _CapChip extends StatelessWidget {
  final IconData icon;
  final String text;
  const _CapChip(this.icon, this.text);

  @override
  Widget build(BuildContext context) {
    final p = WcagPalette.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: p.surfaceRecessed,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: p.border),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 16, color: p.textSecondary),
        const SizedBox(width: 6),
        WcagText(text,
            size: WcagType.caption,
            emphasis: 'secondary',
            weight: WcagType.medium),
      ]),
    );
  }
}
