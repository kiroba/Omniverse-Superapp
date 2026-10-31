import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:omniverse_superapp/omni_node_identity.dart';

void main() {
  late Directory dir;
  final key = 'AB' * 32;

  setUp(() async => dir = await Directory.systemTemp.createTemp('omni_identity_'));
  tearDown(() async => dir.delete(recursive: true));

  test('uses the daemon node key, lowercased, and writes no install id', () async {
    final id = await OmniNodeIdentity.resolve(
        stateDir: dir, fetch: (_) async => '{"status":"OK","node_id":"$key"}');
    expect(id.source, 'daemon');
    expect(id.hex, key.toLowerCase());
    expect(id.blePrefix.length, 32);
    expect(File('${dir.path}/$kInstallIdFile').existsSync(), isFalse);
  });

  test('falls back to a per-install id that stays the same across launches', () async {
    final a = await OmniNodeIdentity.resolve(stateDir: dir, fetch: (_) async => null);
    final b = await OmniNodeIdentity.resolve(stateDir: dir, fetch: (_) async => throw Exception('down'));
    expect(a.source, 'install');
    expect(OmniNodeIdentity.isValidHex(a.hex), isTrue);
    expect(b.hex, a.hex);
  });

  test('malformed, non-JSON or slow daemon answers fall back', () async {
    final base = await OmniNodeIdentity.resolve(stateDir: dir, fetch: (_) async => null);
    for (final body in ['{"node_id":"zz"}', 'not json', '[]']) {
      final id = await OmniNodeIdentity.resolve(stateDir: dir, fetch: (_) async => body);
      expect(id.source, 'install', reason: body);
      expect(id.hex, base.hex);
    }
    final slow = await OmniNodeIdentity.resolve(
        stateDir: dir,
        timeout: const Duration(milliseconds: 100),
        fetch: (_) => Future.delayed(const Duration(seconds: 2), () => '{"node_id":"$key"}'));
    expect(slow.source, 'install');
  });

  test('a damaged install id file is replaced', () async {
    final a = await OmniNodeIdentity.resolve(stateDir: dir, fetch: (_) async => null);
    File('${dir.path}/$kInstallIdFile').writeAsStringSync('garbage');
    final b = await OmniNodeIdentity.resolve(stateDir: dir, fetch: (_) async => null);
    expect(OmniNodeIdentity.isValidHex(b.hex), isTrue);
    expect(b.hex, isNot(a.hex));
  });
}
