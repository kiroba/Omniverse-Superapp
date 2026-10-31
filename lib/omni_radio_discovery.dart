// Native BLE + Wi-Fi Aware peer discovery (Android only).
//
// The radios are driven by OmniRadioDiscovery.kt over MethodChannel "omni/radio"
// and EventChannel "omni/radio/peers". Any peer that comes with a host + port is
// handed to the local Omni daemon at http://127.0.0.1:5000/api/sync/peers.
//
// TODO(permissions): this app has no runtime-permission helper yet and we don't add
// packages here. Call status() and check `missingPermissions`; request them
// (BLUETOOTH_SCAN/ADVERTISE/CONNECT, NEARBY_WIFI_DEVICES, or ACCESS_FINE_LOCATION
// on older Android) before start(), e.g. with a small native request later.

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

class OmniRadioPeer {
  final String transport; // "ble" | "wifi_aware"
  final String nodeHash;
  final String? host;
  final int? port;
  final int? rssi;

  const OmniRadioPeer({
    required this.transport,
    required this.nodeHash,
    this.host,
    this.port,
    this.rssi,
  });

  factory OmniRadioPeer.fromMap(Map<dynamic, dynamic> m) => OmniRadioPeer(
        transport: (m['transport'] ?? '').toString(),
        nodeHash: (m['nodeHash'] ?? '').toString(),
        host: m['host']?.toString(),
        port: m['port'] is int ? m['port'] as int : null,
        rssi: m['rssi'] is int ? m['rssi'] as int : null,
      );

  bool get routable => host != null && host!.isNotEmpty && port != null;

  @override
  String toString() =>
      'OmniRadioPeer($transport, $nodeHash, host=$host, port=$port, rssi=$rssi)';
}

class OmniRadioDiscovery {
  OmniRadioDiscovery._();
  static final OmniRadioDiscovery instance = OmniRadioDiscovery._();

  static const MethodChannel _method = MethodChannel('omni/radio');
  static const EventChannel _events = EventChannel('omni/radio/peers');
  static const String daemonPeersUrl = 'http://127.0.0.1:5000/api/sync/peers';

  Stream<OmniRadioPeer>? _peers;
  StreamSubscription<OmniRadioPeer>? _handoff;
  final Map<String, DateTime> _lastPosted = {};

  bool get supported => Platform.isAndroid;

  /// Broadcast stream of discovered peers.
  Stream<OmniRadioPeer> get peers {
    if (!supported) return const Stream<OmniRadioPeer>.empty();
    return _peers ??= _events
        .receiveBroadcastStream()
        .where((e) => e is Map)
        .map((e) => OmniRadioPeer.fromMap(e as Map))
        .asBroadcastStream();
  }

  /// Starts advertising/scanning. Peers with host+port are posted to the daemon.
  Future<Map<String, dynamic>> start(String nodeHashHex, {int port = 5005}) async {
    if (!supported) return {'running': false, 'errors': ['not android']};
    _handoff ??= peers.listen(_handToDaemon, onError: (_) {});
    try {
      final r = await _method
          .invokeMethod('start', {'nodeHashHex': nodeHashHex, 'port': port});
      return _asMap(r);
    } catch (e) {
      return {'running': false, 'errors': ['$e']};
    }
  }

  Future<Map<String, dynamic>> stop() async {
    await _handoff?.cancel();
    _handoff = null;
    if (!supported) return {'running': false};
    try {
      return _asMap(await _method.invokeMethod('stop'));
    } catch (e) {
      return {'running': false, 'errors': ['$e']};
    }
  }

  /// Includes `missingPermissions` (List) and `errors` (List).
  Future<Map<String, dynamic>> status() async {
    if (!supported) return {'running': false, 'errors': ['not android']};
    try {
      return _asMap(await _method.invokeMethod('status'));
    } catch (e) {
      return {'running': false, 'errors': ['$e']};
    }
  }

  Map<String, dynamic> _asMap(dynamic r) =>
      r is Map ? r.map((k, v) => MapEntry(k.toString(), v)) : <String, dynamic>{};

  Future<void> _handToDaemon(OmniRadioPeer peer) async {
    if (!peer.routable) return;
    final key = '${peer.transport}|${peer.host}|${peer.port}';
    final now = DateTime.now();
    final last = _lastPosted[key];
    if (last != null && now.difference(last) < const Duration(seconds: 30)) return;
    _lastPosted[key] = now;
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    try {
      final req = await client.postUrl(Uri.parse(daemonPeersUrl));
      req.headers.contentType = ContentType.json;
      req.write(jsonEncode({
        'host': peer.host,
        'port': peer.port,
        'transport': peer.transport,
        'node_hash': peer.nodeHash,
      }));
      final resp = await req.close();
      await resp.drain<void>();
    } catch (_) {
      // Daemon not running or endpoint not there yet: ignore.
    } finally {
      client.close(force: true);
    }
  }
}
