import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/models/server_profile.dart';
import 'package:frontend/service/db.dart';
import 'package:frontend/service/subscription.dart';
import 'package:frontend/service/subscription_manager.dart';

import 'support/test_db.dart';

/// Сервер подписки, который держит ответ до [release].
class _HangingFeedServer {
  _HangingFeedServer._(this._server);

  final HttpServer _server;
  final Completer<void> _firstRequest = Completer<void>();
  final List<HttpRequest> _pending = [];
  String _body = '';

  static Future<_HangingFeedServer> start() async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final feed = _HangingFeedServer._(server);
    server.listen((request) {
      if (request.uri.path != '/feed/json') {
        request.response.statusCode = HttpStatus.notFound;
        request.response.close();
        return;
      }
      feed._pending.add(request);
      if (!feed._firstRequest.isCompleted) feed._firstRequest.complete();
    });
    return feed;
  }

  String get url => 'http://${_server.address.address}:${_server.port}/feed';

  Future<void> get firstRequest => _firstRequest.future;

  void setBody(Object body) {
    _body = jsonEncode(body);
  }

  Future<void> release() async {
    for (final request in _pending) {
      request.response.headers.contentType = ContentType.json;
      request.response.write(_body);
      await request.response.close();
    }
    _pending.clear();
  }

  Future<void> close() => _server.close(force: true);
}

Map<String, dynamic> _feedBody() => {
  'ok': true,
  'group': {
    'name': 'Demo',
    'items': [
      {
        'name': 'A',
        'link': 'csqtt://connect?v=2&host=203.0.113.9&peer=40009&password=pw',
        'sub_uuid': 'a',
      },
    ],
  },
};

void main() {
  late TestDatabase testDb;

  setUpAll(() async {
    testDb = await TestDatabase.open();
  });

  setUp(() async {
    await testDb.clear();
    await DbService.getSettings();
    await DbService.updateSettings(
      (settings) => settings..zAllowInsecureHttp = true,
    );
  });

  tearDownAll(() async {
    await testDb.close();
  });

  test('changeUrl переносит профили и метаданные на новый адрес', () async {
    const oldUrl = 'https://old.test/sub/token';
    const newUrl = 'https://new.test/sub/other';
    await DbService.saveProfiles([
      ServerProfile(
        name: 'Sub:Demo - A',
        subscriptionUrl: oldUrl,
        ip: '192.0.2.1',
        port: 40001,
      ),
      ServerProfile(
        name: 'Sub:Demo - B',
        subscriptionUrl: oldUrl,
        ip: '192.0.2.2',
        port: 40002,
      ),
    ]);

    final meta = SubscriptionManager.instance.getMeta(
      oldUrl,
      defaultTitle: 'Demo',
    )..autoUpdateMinutes = 2;
    await SubscriptionManager.instance.saveMeta(meta);

    await SubscriptionManager.instance.changeUrl(
      oldUrl: oldUrl,
      newUrl: newUrl,
      profiles: await DbService.getProfiles(),
      meta: meta,
    );

    final updated = await DbService.getProfiles();
    expect(updated, hasLength(2));
    expect(updated.every((p) => p.subscriptionUrl == newUrl), isTrue);

    final moved = SubscriptionManager.instance.getMeta(newUrl);
    expect(moved.title, 'Demo');
    expect(moved.autoUpdateMinutes, 2);

    // Старый ключ не остаётся в файле метаданных (токен не дублируется).
    final file = File('${testDb.directoryPath}/subscriptions_meta.json');
    final data = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
    expect(data.containsKey(oldUrl), isFalse);
    expect(data.containsKey(newUrl), isTrue);
  });

  test('удаление во время обновления не воскрешает подписку', () async {
    final server = await _HangingFeedServer.start();
    try {
      final url = server.url;
      final profile = ServerProfile(
        name: 'Sub:Demo',
        subscriptionUrl: url,
        ip: '203.0.113.1',
        port: 40001,
      );
      await DbService.saveProfile(profile);
      final meta = SubscriptionManager.instance.getMeta(
        url,
        defaultTitle: 'Demo',
      )..autoUpdateMinutes = 2;
      await SubscriptionManager.instance.saveMeta(meta);

      final updating = SubscriptionManager.instance.updateSubscription(url);
      await server.firstRequest;

      await SubscriptionManager.instance.deleteSubscription(
        url: url,
        profiles: [profile],
      );
      server.setBody(_feedBody());
      await server.release();

      final result = await updating;
      expect(result.problem, SubscriptionProblem.cancelled);
      expect(await DbService.getProfiles(), isEmpty);

      final file = File('${testDb.directoryPath}/subscriptions_meta.json');
      final data =
          jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      expect(data.containsKey(url), isFalse);
    } finally {
      await server.close();
    }
  });

  test('смена URL во время обновления не возвращает старый адрес', () async {
    final server = await _HangingFeedServer.start();
    try {
      final oldUrl = server.url;
      const newUrl = 'https://new.test/feed';
      final profile = ServerProfile(
        name: 'Sub:Demo',
        subscriptionUrl: oldUrl,
        ip: '203.0.113.1',
        port: 40001,
      );
      await DbService.saveProfile(profile);
      final meta = SubscriptionManager.instance.getMeta(
        oldUrl,
        defaultTitle: 'Demo',
      )..autoUpdateMinutes = 2;
      await SubscriptionManager.instance.saveMeta(meta);

      final updating = SubscriptionManager.instance.updateSubscription(oldUrl);
      await server.firstRequest;

      await SubscriptionManager.instance.changeUrl(
        oldUrl: oldUrl,
        newUrl: newUrl,
        profiles: [profile],
        meta: meta,
      );
      server.setBody(_feedBody());
      await server.release();

      final result = await updating;
      expect(result.problem, SubscriptionProblem.cancelled);

      final profiles = await DbService.getProfiles();
      expect(profiles, hasLength(1));
      expect(profiles.single.subscriptionUrl, newUrl);

      final file = File('${testDb.directoryPath}/subscriptions_meta.json');
      final data =
          jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      expect(data.containsKey(oldUrl), isFalse);
      expect(data.containsKey(newUrl), isTrue);
    } finally {
      await server.close();
    }
  });
}
