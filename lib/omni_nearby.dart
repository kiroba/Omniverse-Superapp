// "Find people nearby?": the plain-language ask before Android's own permission dialog, then the
// radios start with the node's key (OmniRadioDiscovery.startWithNodeIdentity).
//
// Human first: we explain in one sentence what it does and that it works without the internet,
// ask once on first launch, remember the answer, and leave a Nearby button in the app bar to
// change it later. Nothing starts until the person says yes.
import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'omni_radio_discovery.dart';

/// The person's answer, kept in one small file: "on", "off", or nothing yet.
class NearbyChoiceStore {
  NearbyChoiceStore(this.dir);
  final Directory dir;
  static const fileName = 'omni_nearby_choice';

  File get _file => File('${dir.path}${Platform.pathSeparator}$fileName');

  /// true = on, false = off, null = never asked.
  Future<bool?> read() async {
    try {
      if (!await _file.exists()) return null;
      final s = (await _file.readAsString()).trim();
      if (s == 'on') return true;
      if (s == 'off') return false;
    } catch (_) {}
    return null;
  }

  Future<void> write(bool on) async {
    await dir.create(recursive: true);
    await _file.writeAsString(on ? 'on' : 'off', flush: true);
  }
}

/// Native permission bridge (MainActivity, channel "omni/radio/permissions").
class RadioPermissions {
  static const MethodChannel _ch = MethodChannel('omni/radio/permissions');

  static Map<String, dynamic> _map(dynamic r) =>
      r is Map ? r.map((k, v) => MapEntry(k.toString(), v)) : <String, dynamic>{};

  static Future<Map<String, dynamic>> check() async {
    try {
      return _map(await _ch.invokeMethod('check'));
    } catch (e) {
      return {'allGranted': false, 'error': '$e'};
    }
  }

  static Future<Map<String, dynamic>> request() async {
    try {
      return _map(await _ch.invokeMethod('request'));
    } catch (e) {
      return {'allGranted': false, 'error': '$e'};
    }
  }

  /// Opens Omni Node. False when it isn't installed.
  static Future<bool> openNode() async {
    try {
      return await _ch.invokeMethod('openNode') == true;
    } catch (_) {
      return false;
    }
  }

  static Future<void> openSettings() async {
    try {
      await _ch.invokeMethod('openSettings');
    } catch (_) {}
  }
}

class OmniNearby extends ChangeNotifier {
  OmniNearby._();
  static final OmniNearby instance = OmniNearby._();

  bool running = false;
  String? identitySource; // "daemon" | "install"

  /// Omni Node owns the radios (Node 0.2.0+): this app doesn't start its own, so every Omni app
  /// shares one set of Bluetooth / Wi-Fi Aware peers through the node.
  bool byNode = false;

  /// Omni Node answers loopback node-health with 401 (it requires its local token, OMNI_LOCAL_TOKEN=1).
  static Future<bool> nodeIsRunning() async {
    final http = HttpClient()..connectionTimeout = const Duration(milliseconds: 1500);
    try {
      final req = await http.getUrl(Uri.parse('http://127.0.0.1:9100/api/v1/telemetry/node-health'));
      final res = await req.close().timeout(const Duration(seconds: 2));
      await res.drain<void>();
      return res.statusCode == 401;
    } catch (_) {
      return false;
    } finally {
      http.close(force: true);
    }
  }

  NearbyChoiceStore get _store => NearbyChoiceStore(OmniRadioDiscovery.defaultStateDir());

  bool get supported => Platform.isAndroid;

  /// Call once after the first frame. Asks on first launch; restarts quietly if already on.
  Future<void> onLaunch(BuildContext context) async {
    if (!supported) return;
    if (await nodeIsRunning()) {
      byNode = true;
      if (running) await stop();
      notifyListeners();
      return;
    }
    final choice = await _store.read();
    if (choice == false) return;
    if (choice == true) {
      final perms = await RadioPermissions.check();
      if (perms['allGranted'] == true) await _start();
      return; // permission was taken away in Settings: wait for them to tap Nearby
    }
    if (!context.mounted) return;
    await ask(context);
  }

  /// The explainer, then Android's dialog. Also what the app-bar Nearby button opens.
  Future<void> ask(BuildContext context) async {
    if (!supported) return;
    if (byNode || await nodeIsRunning()) {
      byNode = true;
      if (running) await stop();
      notifyListeners();
      if (!context.mounted) return;
      final open = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Nearby runs in Omni Node'),
          content: const Text('Omni Node handles Bluetooth and Wi-Fi for all your Omni apps, '
              'so they share the same nearby people. Turn it on or off there.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('OK')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Open Omni Node')),
          ],
        ),
      );
      if (open == true) await RadioPermissions.openNode();
      return;
    }
    final yes = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Find people nearby?'),
        content: Text(running
            ? 'Nearby is on. Your phone is connecting directly to people around you over '
                'Bluetooth and Wi-Fi.'
            : 'The Omniverse can connect straight to phones and computers around you over '
                'Bluetooth and Wi-Fi, even with no internet. Android will ask you to allow '
                '"Nearby devices" next. You can turn this off anytime.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(running ? 'Turn off' : 'Not now'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(running ? 'Keep on' : 'Allow'),
          ),
        ],
      ),
    );
    if (yes == null) return; // dismissed: ask again next time
    if (!yes) {
      await _store.write(false);
      await stop();
      return;
    }
    await _store.write(true);
    if (running) return;
    final perms = await RadioPermissions.request();
    if (!context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    if (perms['allGranted'] == true) {
      await _start();
      messenger.showSnackBar(const SnackBar(content: Text('Looking for people nearby')));
    } else {
      final blocked = (perms['blocked'] as List?)?.isNotEmpty == true;
      messenger.showSnackBar(SnackBar(
        content: const Text('Nearby needs the "Nearby devices" permission to work.'),
        action: blocked
            ? const SnackBarAction(label: 'Settings', onPressed: RadioPermissions.openSettings)
            : null,
      ));
    }
  }

  Future<void> _start() async {
    final r = await OmniRadioDiscovery.instance.startWithNodeIdentity();
    running = r['running'] == true;
    identitySource = r['identitySource']?.toString();
    notifyListeners();
  }

  Future<void> stop() async {
    await OmniRadioDiscovery.instance.stop();
    running = false;
    notifyListeners();
  }
}

/// App-bar button: lit when Nearby is on, tap to change it.
class NearbyButton extends StatelessWidget {
  const NearbyButton({super.key});

  @override
  Widget build(BuildContext context) {
    final nearby = OmniNearby.instance;
    if (!nearby.supported) return const SizedBox.shrink();
    return AnimatedBuilder(
      animation: nearby,
      builder: (context, _) => IconButton(
        icon: Icon(nearby.running || nearby.byNode ? Icons.wifi_tethering : Icons.wifi_tethering_off,
            color: nearby.running || nearby.byNode ? const Color(0xFF03DAC6) : Colors.grey),
        tooltip: nearby.byNode ? 'Nearby runs in Omni Node' : (nearby.running ? 'Nearby is on' : 'Find people nearby'),
        onPressed: () => nearby.ask(context),
      ),
    );
  }
}
