// Stable radio identity for BLE / Wi-Fi Aware beacons.
//
// Before this, nothing tied a beacon to a node: whatever hash the caller passed went out, and the
// app had no stable value to pass. The beacon now carries the same identity the daemon signs events
// with, so a peer seen over BLE and the events it later syncs can be matched to one key:
//
//   1. "daemon": the local daemon's node_id, its Ed25519 public key (64 hex chars), read from
//      GET http://127.0.0.1:9100/api/v1/telemetry/node-health.
//   2. "install": when no daemon answers, a random 32-byte id created once and kept in the app's
//      support dir, so it stays the same across launches (it was random per launch before).
//      It is NOT a signing key; peers should treat it as unverified.
//
// Pure dart:io so it unit-tests without Flutter; the caller supplies the directory.
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

const String kNodeHealthUrl = 'http://127.0.0.1:9100/api/v1/telemetry/node-health';
const String kInstallIdFile = 'omni_radio_install_id';

final RegExp _hex64 = RegExp(r'^[0-9a-f]{64}$');

/// Fetches a URL and returns its body, or null on any failure. Injectable for tests.
typedef BodyFetcher = Future<String?> Function(Uri url);

class OmniNodeIdentity {
  /// 64 lowercase hex chars: the daemon's Ed25519 public key, or the per-install id.
  final String hex;

  /// "daemon" or "install".
  final String source;

  const OmniNodeIdentity(this.hex, this.source);

  bool get isDaemonKey => source == 'daemon';

  /// First 16 bytes, the part that fits in a BLE advert's service data.
  String get blePrefix => hex.substring(0, 32);

  @override
  String toString() => 'OmniNodeIdentity($source, ${hex.substring(0, 16)}...)';

  static bool isValidHex(String s) => _hex64.hasMatch(s);

  /// Daemon key when the daemon answers within [timeout], else the stable per-install id.
  static Future<OmniNodeIdentity> resolve({
    required Directory stateDir,
    String healthUrl = kNodeHealthUrl,
    BodyFetcher? fetch,
    Duration timeout = const Duration(seconds: 2),
  }) async {
    final fromDaemon = await daemonNodeId(healthUrl, fetch: fetch, timeout: timeout);
    if (fromDaemon != null) return OmniNodeIdentity(fromDaemon, 'daemon');
    return OmniNodeIdentity(await installId(stateDir), 'install');
  }

  /// The daemon's node_id, lowercased, or null when unreachable or malformed.
  static Future<String?> daemonNodeId(String healthUrl,
      {BodyFetcher? fetch, Duration timeout = const Duration(seconds: 2)}) async {
    try {
      final body = await (fetch ?? _httpGet)(Uri.parse(healthUrl)).timeout(timeout);
      if (body == null) return null;
      final decoded = jsonDecode(body);
      if (decoded is! Map) return null;
      final id = (decoded['node_id'] ?? '').toString().toLowerCase();
      return isValidHex(id) ? id : null;
    } catch (_) {
      return null;
    }
  }

  /// Reads the per-install id, creating it once. A damaged file is replaced.
  static Future<String> installId(Directory stateDir) async {
    final f = File('${stateDir.path}${Platform.pathSeparator}$kInstallIdFile');
    try {
      if (await f.exists()) {
        final s = (await f.readAsString()).trim().toLowerCase();
        if (isValidHex(s)) return s;
      }
    } catch (_) {}
    final rng = Random.secure();
    final id = List<int>.generate(32, (_) => rng.nextInt(256))
        .map((b) => b.toRadixString(16).padLeft(2, '0'))
        .join();
    await stateDir.create(recursive: true);
    await f.writeAsString(id, flush: true);
    return id;
  }

  static Future<String?> _httpGet(Uri url) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 2);
    try {
      final req = await client.getUrl(url);
      final resp = await req.close();
      final body = await resp.transform(utf8.decoder).join();
      return resp.statusCode == 200 ? body : null;
    } catch (_) {
      return null;
    } finally {
      client.close(force: true);
    }
  }
}
