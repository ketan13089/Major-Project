import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'indoor_map_viewer.dart';

void main() {
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
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF7C3AED)),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF7C3AED),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      themeMode: ThemeMode.system,
      initialRoute: '/',
      routes: {
        '/': (context) => const HomePage(),
        '/map': (context) => const IndoorMapViewer(),
      },
    );
  }
}

class HomePage extends StatelessWidget {
  static const platform = MethodChannel('com.ketan.slam/ar');
  const HomePage({super.key});

  Future<void> _openARCamera() async {
    try {
      await platform.invokeMethod("openAR");
    } on PlatformException catch (e) {
      debugPrint("Failed to open AR: '${e.message}'.");
    }
  }

  void _openMapViewer(BuildContext context) {
    Navigator.pushNamed(context, '/map');
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bg      = isDark ? const Color(0xFF0F0F17) : const Color(0xFFF8F8FC);
    final surface = isDark ? const Color(0xFF1A1A26) : Colors.white;
    final textPri = isDark ? const Color(0xFFF3F4F6) : const Color(0xFF111827);
    final textSec = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);
    final border  = isDark ? const Color(0xFF2A2A38) : const Color(0xFFE5E7EB);
    final chipBg  = isDark ? const Color(0xFF1E1E2C) : const Color(0xFFF3F4F6);

    return Scaffold(
      backgroundColor: bg,
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 36),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // ── Header ──
              Row(children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      colors: [Color(0xFF7C3AED), Color(0xFF6D28D9)],
                    ),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: const Icon(Icons.explore_rounded, size: 24,
                      color: Colors.white),
                ),
                const SizedBox(width: 12),
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('Indoor Navigator',
                      style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700,
                          color: textPri, letterSpacing: -0.3)),
                  Text('AR-powered wayfinding',
                      style: TextStyle(fontSize: 12, color: textSec)),
                ]),
              ]),

              const SizedBox(height: 28),

              // ── Hero Banner ──
              Container(
                width: double.infinity,
                padding: const EdgeInsets.fromLTRB(24, 32, 24, 28),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [Color(0xFF7C3AED), Color(0xFF4F46E5), Color(0xFF2563EB)],
                  ),
                  borderRadius: BorderRadius.circular(24),
                  boxShadow: [
                    BoxShadow(
                      color: const Color(0xFF7C3AED).withOpacity(isDark ? 0.15 : 0.25),
                      blurRadius: 24, offset: const Offset(0, 8),
                    ),
                  ],
                ),
                child: Column(children: [
                  Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: Colors.white.withOpacity(0.15),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: const Icon(Icons.accessibility_new_rounded,
                        size: 36, color: Colors.white),
                  ),
                  const SizedBox(height: 16),
                  const Text('Navigate indoors\nwith confidence',
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700,
                          color: Colors.white, height: 1.3)),
                  const SizedBox(height: 8),
                  Text(
                      'Real-time obstacle alerts, voice navigation,\n'
                      'and spatial audio feedback',
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 13,
                          color: Colors.white.withOpacity(0.8), height: 1.4)),
                ]),
              ),

              const SizedBox(height: 24),

              // ── Action Cards ──
              Semantics(
                button: true,
                label: 'Start AR Scan. Open camera for indoor navigation and obstacle detection.',
                child: _ActionCard(
                  title: 'Start AR Scan',
                  subtitle: 'Camera tracking & obstacle detection',
                  icon: Icons.camera_alt_rounded,
                  gradientColors: const [Color(0xFF7C3AED), Color(0xFF6D28D9)],
                  surface: surface, textPri: textPri,
                  textSec: textSec, border: border,
                  onTap: _openARCamera,
                ),
              ),

              const SizedBox(height: 12),

              Semantics(
                button: true,
                label: 'View Indoor Map. Explore the map built from your scan.',
                child: _ActionCard(
                  title: 'View Indoor Map',
                  subtitle: 'Explore your scanned floor plan',
                  icon: Icons.map_rounded,
                  gradientColors: const [Color(0xFF2563EB), Color(0xFF1D4ED8)],
                  surface: surface, textPri: textPri,
                  textSec: textSec, border: border,
                  onTap: () => _openMapViewer(context),
                ),
              ),

              const SizedBox(height: 28),

              // ── Capabilities ──
              Padding(
                padding: const EdgeInsets.only(left: 2),
                child: Text('Capabilities',
                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600,
                        color: textPri, letterSpacing: -0.2)),
              ),
              const SizedBox(height: 12),
              Wrap(spacing: 8, runSpacing: 8, children: [
                _CapChip(Icons.radar, 'Obstacle alerts', chipBg, textSec),
                _CapChip(Icons.hearing, 'Spatial audio', chipBg, textSec),
                _CapChip(Icons.mic, 'Voice commands', chipBg, textSec),
                _CapChip(Icons.stairs, 'Stair warnings', chipBg, textSec),
                _CapChip(Icons.sos, 'Emergency SOS', chipBg, textSec),
                _CapChip(Icons.undo, 'Guide me back', chipBg, textSec),
                _CapChip(Icons.save_alt, 'Save maps', chipBg, textSec),
                _CapChip(Icons.accessibility_new, 'TalkBack', chipBg, textSec),
              ]),
            ],
          ),
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Action Card — main navigation buttons
// ─────────────────────────────────────────────────────────────────────────────
class _ActionCard extends StatelessWidget {
  final String title, subtitle;
  final IconData icon;
  final List<Color> gradientColors;
  final Color surface, textPri, textSec, border;
  final VoidCallback onTap;

  const _ActionCard({
    required this.title, required this.subtitle, required this.icon,
    required this.gradientColors, required this.surface,
    required this.textPri, required this.textSec, required this.border,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: surface,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 20),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: border),
          ),
          child: Row(children: [
            Container(
              padding: const EdgeInsets.all(13),
              decoration: BoxDecoration(
                gradient: LinearGradient(colors: gradientColors),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(icon, size: 26, color: Colors.white),
            ),
            const SizedBox(width: 16),
            Expanded(child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: TextStyle(fontSize: 15,
                    fontWeight: FontWeight.w600, color: textPri)),
                const SizedBox(height: 3),
                Text(subtitle, style: TextStyle(fontSize: 12,
                    color: textSec, height: 1.3)),
              ],
            )),
            Icon(Icons.arrow_forward_ios_rounded, size: 15, color: textSec),
          ]),
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability Chip
// ─────────────────────────────────────────────────────────────────────────────
class _CapChip extends StatelessWidget {
  final IconData icon;
  final String text;
  final Color bg, textColor;

  const _CapChip(this.icon, this.text, this.bg, this.textColor);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 14, color: textColor),
        const SizedBox(width: 6),
        Text(text, style: TextStyle(fontSize: 12, color: textColor,
            fontWeight: FontWeight.w500)),
      ]),
    );
  }
}
