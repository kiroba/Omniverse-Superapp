import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:omniverse_superapp/omni_nearby.dart';

void main() {
  late Directory dir;
  setUp(() async => dir = await Directory.systemTemp.createTemp('omni_nearby_'));
  tearDown(() async => dir.delete(recursive: true));

  test('never asked, then remembers on and off', () async {
    final store = NearbyChoiceStore(dir);
    expect(await store.read(), isNull);
    await store.write(true);
    expect(await NearbyChoiceStore(dir).read(), isTrue);
    await store.write(false);
    expect(await NearbyChoiceStore(dir).read(), isFalse);
  });

  test('a damaged file counts as never asked', () async {
    File('${dir.path}/${NearbyChoiceStore.fileName}').writeAsStringSync('maybe');
    expect(await NearbyChoiceStore(dir).read(), isNull);
  });
}
