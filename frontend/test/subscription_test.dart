import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/models/server_profile.dart';
import 'package:frontend/service/db.dart';
import 'package:frontend/service/subscription.dart';

import 'support/test_db.dart';

String _link(int n, {int? power, String? hashes, String? password}) {
  final buffer = StringBuffer(
    'csqtt://connect?v=2&host=203.0.113.$n'
    '&peer=${40000 + n}&password=${password ?? 'pw$n'}',
  );
  if (power != null) buffer.write('&power=$power');
  if (hashes != null) buffer.write('&hashes=${Uri.encodeComponent(hashes)}');
  return buffer.toString();
}

Map<String, dynamic> _node(
  String name,
  String link, {
  String? uuid,
  int? power,
}) {
  return {'name': name, 'link': link, 'sub_uuid': ?uuid, 'power': ?power};
}

Map<String, dynamic> _group(String name, List<Map<String, dynamic>> items) {
  return {
    'ok': true,
    'group': {'name': name, 'items': items},
  };
}

String _htmlPage(String title, String link) {
  return '<html><head><title>Подписка: $title</title></head>'
      '<body><h2>Подписка: $title</h2><a href="$link">node</a></body></html>';
}

Future<void> _json(HttpRequest request, Object body) async {
  request.response.headers.contentType = ContentType.json;
  request.response.write(jsonEncode(body));
  await request.response.close();
}

Future<void> _html(HttpRequest request, String body) async {
  request.response.headers.contentType = ContentType.html;
  request.response.write(body);
  await request.response.close();
}

Future<void> _status(HttpRequest request, int code) async {
  request.response.statusCode = code;
  request.response.write('status $code');
  await request.response.close();
}

Future<void> _notFound(HttpRequest request) => _status(request, 404);

class _TestServer {
  _TestServer._(this._server);

  final HttpServer _server;

  static Future<_TestServer> start(
    FutureOr<void> Function(HttpRequest request) handler,
  ) async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      try {
        await handler(request);
      } catch (_) {
        try {
          request.response.statusCode = HttpStatus.internalServerError;
          await request.response.close();
        } catch (_) {
          // Клиент уже закрыл соединение.
        }
      }
    });
    return _TestServer._(server);
  }

  Uri url(String path) =>
      Uri.parse('http://${_server.address.address}:${_server.port}$path');

  Future<void> close() => _server.close(force: true);
}

Future<void> _withServer(
  FutureOr<void> Function(HttpRequest request) handler,
  Future<void> Function(_TestServer server) body,
) async {
  final server = await _TestServer.start(handler);
  try {
    await body(server);
  } finally {
    await server.close();
  }
}

Future<List<ServerProfile>> _profiles() => DbService.getProfiles();

void main() {
  // Без TestWidgetsFlutterBinding: он подменяет HttpClient и ломает локальный
  // HTTP-сервер (все запросы получают статус 400).
  late TestDatabase testDb;

  setUpAll(() async {
    testDb = await TestDatabase.open();
  });

  setUp(() async {
    await testDb.clear();
    await DbService.getSettings();
  });

  tearDownAll(() async {
    await testDb.close();
  });

  group('fetchSubscription / JSON', () {
    test('группа: заголовок, power, пропущенные узлы', () async {
      await _withServer(
        (request) async {
          if (request.uri.path != '/feed/json') return _notFound(request);
          await _json(
            request,
            _group('Demo', [
              _node('Alpha', _link(1, power: 150), uuid: 'a', power: 150),
              _node('Low', _link(2), uuid: 'b', power: 1),
              _node('Broken', 'csqtt://broken', uuid: 'c'),
              _node('Empty', '', uuid: 'd'),
            ]),
          );
        },
        (server) async {
          final result = await SubscriptionService.fetchSubscription(
            '${server.url('/feed')}',
            allowHttp: true,
          );

          expect(result.success, isTrue);
          expect(result.problem, isNull);
          expect(result.title, 'Demo');
          expect(result.items, hasLength(2));
          expect(result.skippedCount, 2);
          expect(result.isSingle, isFalse);
          expect(result.items.first.profile!.power, 100);
          expect(result.items.last.profile!.power, 10);
        },
      );
    });

    test('одиночная подписка', () async {
      await _withServer(
        (request) async {
          if (request.uri.path != '/single/json') return _notFound(request);
          await _json(request, {
            'ok': true,
            'name': 'Solo',
            'link': _link(5, power: 30),
            'sub_uuid': 'solo-1',
          });
        },
        (server) async {
          final result = await SubscriptionService.fetchSubscription(
            '${server.url('/single')}',
            allowHttp: true,
          );

          expect(result.success, isTrue);
          expect(result.title, 'Solo');
          expect(result.isSingle, isTrue);
          expect(result.items.single.subUuid, 'solo-1');
          expect(result.items.single.profile!.power, 30);
          expect(result.items.single.profile!.ip, '203.0.113.5');
        },
      );
    });

    test('HTML-подписка: заголовок и одна ссылка', () async {
      final link = _link(7, password: 'html-pass');
      await _withServer(
        (request) async {
          if (request.uri.path != '/page/sub') return _notFound(request);
          await _html(request, _htmlPage('Demo HTML', link));
        },
        (server) async {
          final result = await SubscriptionService.fetchSubscription(
            '${server.url('/page')}',
            allowHttp: true,
          );

          expect(result.success, isTrue);
          expect(result.title, 'Demo HTML');
          expect(result.isSingle, isTrue);
          expect(result.items, hasLength(1));
          expect(result.items.single.profile!.password, 'html-pass');
        },
      );
    });

    test('отказ ok:false не мешает fallback на HTML', () async {
      await _withServer(
        (request) async {
          if (request.uri.path == '/feed/json') {
            return _json(request, {'ok': false, 'error': 'token expired'});
          }
          if (request.uri.path == '/feed/sub') {
            return _html(request, _htmlPage('Fallback', _link(8)));
          }
          return _notFound(request);
        },
        (server) async {
          final result = await SubscriptionService.fetchSubscription(
            '${server.url('/feed')}',
            allowHttp: true,
          );

          expect(result.success, isTrue);
          expect(result.title, 'Fallback');
        },
      );
    });

    test('отказ сервера сохраняется в ошибке', () async {
      await _withServer(
        (request) async {
          if (request.uri.path == '/feed/json') {
            return _json(request, {'ok': false, 'error': 'token expired'});
          }
          return _notFound(request);
        },
        (server) async {
          final result = await SubscriptionService.fetchSubscription(
            '${server.url('/feed')}',
            allowHttp: true,
          );

          expect(result.success, isFalse);
          expect(result.problem, SubscriptionProblem.serverError);
          expect(result.errorMessage, 'token expired');
        },
      );
    });

    test('HTTP без разрешения отклоняется до запроса', () async {
      var hits = 0;
      await _withServer(
        (request) async {
          hits++;
          return _notFound(request);
        },
        (server) async {
          final result = await SubscriptionService.fetchSubscription(
            '${server.url('/feed')}',
            allowHttp: false,
          );

          expect(result.success, isFalse);
          expect(result.problem, SubscriptionProblem.invalidUrl);
          expect(hits, 0);
        },
      );
    });

    test('403 и пустой ответ дают понятные ошибки', () async {
      await _withServer((request) => _status(request, 403), (server) async {
        final result = await SubscriptionService.fetchSubscription(
          '${server.url('/feed')}',
          allowHttp: true,
        );

        expect(result.success, isFalse);
        expect(result.problem, SubscriptionProblem.serverError);
        expect(result.errorMessage, contains('403'));
      });

      await _withServer(
        (request) async {
          request.response.headers.contentType = ContentType.json;
          await request.response.close();
        },
        (server) async {
          final result = await SubscriptionService.fetchSubscription(
            '${server.url('/feed')}',
            allowHttp: true,
          );

          expect(result.success, isFalse);
          expect(result.problem, SubscriptionProblem.emptyResponse);
        },
      );
    });

    test('превышение лимита тела ответа не вешает клиент', () async {
      await _withServer(
        (request) async {
          request.response.headers.contentType = ContentType.json;
          request.response.write(
            'x' * (SubscriptionService.maxResponseBodyBytes + 4096),
          );
          await request.response.close();
        },
        (server) async {
          final result = await SubscriptionService.fetchSubscription(
            '${server.url('/feed')}',
            allowHttp: true,
          );

          expect(result.success, isFalse);
          expect(result.problem, SubscriptionProblem.networkError);
          expect(result.errorMessage, contains('2 МБ'));
        },
      );
    });

    test('цикл редиректов обрывается по лимиту', () async {
      await _withServer(
        (request) async {
          request.response.statusCode = HttpStatus.found;
          request.response.headers.set(
            HttpHeaders.locationHeader,
            request.uri.path,
          );
          await request.response.close();
        },
        (server) async {
          final result = await SubscriptionService.fetchSubscription(
            '${server.url('/loop')}',
            allowHttp: true,
          );

          expect(result.success, isFalse);
          expect(result.problem, SubscriptionProblem.networkError);
          expect(result.errorMessage, contains('перенаправлен'));
        },
      );
    });
  });

  group('importSubscription / база', () {
    test('импорт, повтор, prune, защита prune и чужая подписка', () async {
      var mode = 'v1';
      await _withServer(
        (request) async {
          if (request.uri.path == '/feed/json') {
            switch (mode) {
              case 'v1':
                return _json(
                  request,
                  _group('Demo', [
                    _node('A', _link(1), uuid: 'a', power: 150),
                    _node('B', _link(2), uuid: 'b', power: 60),
                    _node('C', _link(3), uuid: 'c', power: 30),
                  ]),
                );
              case 'v2':
                return _json(
                  request,
                  _group('Demo', [
                    _node('A2', _link(1), uuid: 'a', power: 120),
                    _node('D', _link(4), uuid: 'd', power: 45),
                  ]),
                );
              case 'v3':
                return _json(
                  request,
                  _group('Demo', [_node('A3', _link(1), power: 10)]),
                );
              default:
                return _json(
                  request,
                  _group('Demo', [_node('D2', _link(4), uuid: 'd2')]),
                );
            }
          }
          if (request.uri.path == '/other/json') {
            return _json(
              request,
              _group('Other', [_node('X', _link(4), uuid: 'x')]),
            );
          }
          return _notFound(request);
        },
        (server) async {
          final feedUrl = server.url('/feed').toString();

          final first = await SubscriptionService.importSubscription(
            feedUrl,
            allowHttp: true,
          );
          expect(first.success, isTrue);
          expect(first.importedCount, 3);
          expect(first.updatedCount, 0);

          var profiles = await _profiles();
          expect(profiles, hasLength(3));
          final a = profiles.singleWhere((p) => p.subUuid == 'a');
          expect(a.name, 'Sub:Demo - A');
          expect(a.threadsLimit, 100);
          expect(a.subscriptionUrl, feedUrl);

          final second = await SubscriptionService.importSubscription(
            feedUrl,
            allowHttp: true,
          );
          expect(second.importedCount, 0);
          expect(second.updatedCount, 3);
          expect(await _profiles(), hasLength(3));

          final b = (await _profiles()).singleWhere((p) => p.subUuid == 'b');
          await DbService.setActiveProfileId(b.id);

          mode = 'v2';
          final third = await SubscriptionService.importSubscription(
            feedUrl,
            allowHttp: true,
          );
          expect(third.importedCount, 1);
          expect(third.updatedCount, 1);

          profiles = await _profiles();
          expect(profiles.map((p) => p.subUuid).toSet(), {'a', 'd'});
          expect((await DbService.getSettings()).activeProfileId, isNull);

          // Лимит сервера 120, но ядро не умеет больше 100 → оба поля 100.
          final a2 = profiles.singleWhere((p) => p.subUuid == 'a');
          expect(a2.threadsLimit, 100);
          expect(a2.power, 100);

          mode = 'v3';
          final fourth = await SubscriptionService.importSubscription(
            feedUrl,
            allowHttp: true,
          );
          expect(fourth.updatedCount, 1);
          expect(
            await _profiles(),
            hasLength(2),
            reason: 'без sub_uuid prune запрещён',
          );

          mode = 'v4';
          final fifth = await SubscriptionService.importSubscription(
            feedUrl,
            allowHttp: true,
          );
          expect(fifth.importedCount, 0);
          expect(fifth.updatedCount, 1);

          profiles = await _profiles();
          expect(profiles, hasLength(1));
          expect(profiles.single.subUuid, 'd2');
          expect(profiles.single.port, 40004);

          final other = await SubscriptionService.importSubscription(
            server.url('/other').toString(),
            allowHttp: true,
          );
          expect(other.importedCount, 1);
          expect(await _profiles(), hasLength(2));
        },
      );
    });

    test(
      'одиночная подписка без sub_uuid обновляется по URL и не плодит дубли',
      () async {
        var mode = 'v1';
        await _withServer(
          (request) async {
            if (request.uri.path != '/solo/json') return _notFound(request);
            await _json(request, {
              'ok': true,
              'name': 'Solo',
              'link': mode == 'v1' ? _link(1) : _link(9),
            });
          },
          (server) async {
            final url = server.url('/solo').toString();

            final first = await SubscriptionService.importSubscription(
              url,
              allowHttp: true,
            );
            expect(first.isSingle, isTrue);
            expect(first.importedCount, 1);
            var profiles = await _profiles();
            expect(profiles, hasLength(1));
            expect(profiles.single.subUuid, isNull);

            final second = await SubscriptionService.importSubscription(
              url,
              allowHttp: true,
            );
            expect(second.updatedCount, 1);
            expect(await _profiles(), hasLength(1));

            // Дубль от прежнего импорта (например, до фикса) должен исчезнуть.
            await DbService.saveProfile(
              ServerProfile(
                name: 'Sub:Solo - old',
                ip: '198.51.100.1',
                port: 41000,
                subscriptionUrl: url,
              ),
            );
            expect(await _profiles(), hasLength(2));

            mode = 'v2';
            final third = await SubscriptionService.importSubscription(
              url,
              allowHttp: true,
            );
            expect(third.isSingle, isTrue);
            expect(third.updatedCount, 1);

            profiles = await _profiles();
            expect(profiles, hasLength(1));
            expect(profiles.single.port, 40009);
            expect(profiles.single.subscriptionUrl, url);
          },
        );
      },
    );

    test('пользовательское имя узла переживает обновление подписки', () async {
      var nodeName = 'A';
      var node = 1;
      await _withServer(
        (request) async {
          if (request.uri.path != '/feed/json') return _notFound(request);
          await _json(
            request,
            _group('Demo', [
              _node(nodeName, _link(node), uuid: 'a'),
              _node('B', _link(2), uuid: 'b'),
            ]),
          );
        },
        (server) async {
          final url = server.url('/feed').toString();
          await SubscriptionService.importSubscription(url, allowHttp: true);

          final renamed = (await _profiles()).singleWhere(
            (p) => p.subUuid == 'a',
          );
          renamed.name = 'Мой узел';
          await DbService.saveProfile(renamed);

          nodeName = 'A2';
          node = 9;
          await SubscriptionService.importSubscription(url, allowHttp: true);

          final profiles = await _profiles();
          final custom = profiles.singleWhere((p) => p.subUuid == 'a');
          expect(custom.name, 'Мой узел');
          expect(custom.port, 40009);
          expect(
            profiles.singleWhere((p) => p.subUuid == 'b').name,
            'Sub:Demo - B',
          );
        },
      );
    });

    test('power следует за лимитом сервера, пока не выбран вручную', () async {
      var power = 60;
      await _withServer(
        (request) async {
          if (request.uri.path != '/feed/json') return _notFound(request);
          await _json(
            request,
            _group('Demo', [
              _node(
                'A',
                _link(1, power: power),
                uuid: 'a',
                power: power,
              ),
            ]),
          );
        },
        (server) async {
          final url = server.url('/feed').toString();

          await SubscriptionService.importSubscription(url, allowHttp: true);
          var profile = (await _profiles()).single;
          expect(profile.power, 60);
          expect(profile.threadsLimit, 60);

          power = 90;
          await SubscriptionService.importSubscription(url, allowHttp: true);
          profile = (await _profiles()).single;
          expect(profile.power, 90);
          expect(profile.threadsLimit, 90);

          profile.power = 30;
          await DbService.saveProfile(profile);

          power = 120;
          await SubscriptionService.importSubscription(url, allowHttp: true);
          profile = (await _profiles()).single;
          expect(profile.power, 30, reason: 'ручной выбор не повышается');
          expect(profile.threadsLimit, 100);

          power = 20;
          await SubscriptionService.importSubscription(url, allowHttp: true);
          profile = (await _profiles()).single;
          expect(profile.power, 20, reason: 'лимит сервера ниже выбранного');
          expect(profile.threadsLimit, 20);
        },
      );
    });

    test(
      'power следует за лимитом сервера, ручной выбор сохраняется',
      () async {
        var power = 90;
        await _withServer(
          (request) async {
            if (request.uri.path == '/p/json') {
              return _json(
                request,
                _group('P', [_node('N1', _link(1), uuid: 'n', power: power)]),
              );
            }
            return _notFound(request);
          },
          (server) async {
            final url = server.url('/p').toString();

            await SubscriptionService.importSubscription(url, allowHttp: true);
            var profile = (await _profiles()).single;
            expect(profile.power, 90);
            expect(profile.threadsLimit, 90);

            power = 100;
            await SubscriptionService.importSubscription(url, allowHttp: true);
            profile = (await _profiles()).single;
            expect(profile.power, 100, reason: 'power следовал за лимитом');
            expect(profile.threadsLimit, 100);

            // Пользователь вручную понизил мощность.
            profile.power = 27;
            await DbService.saveProfile(profile);

            power = 90;
            await SubscriptionService.importSubscription(url, allowHttp: true);
            profile = (await _profiles()).single;
            expect(profile.power, 27, reason: 'ручной выбор сохраняется');
            expect(profile.threadsLimit, 90);

            power = 10;
            await SubscriptionService.importSubscription(url, allowHttp: true);
            profile = (await _profiles()).single;
            expect(profile.power, 10, reason: 'выбор обрезается лимитом');
            expect(profile.threadsLimit, 10);
          },
        );
      },
    );

    test('applyHashes управляет настройками звонков', () async {
      final link = _link(9, hashes: 'abc,def');
      await _withServer(
        (request) async {
          if (request.uri.path != '/feed/json') return _notFound(request);
          await _json(request, _group('Demo', [_node('A', link, uuid: 'a')]));
        },
        (server) async {
          final url = server.url('/feed').toString();

          await DbService.updateSettings(
            (settings) => settings
              ..customHashes = 'keep'
              ..hashes = 'Авто ВК'
              ..workMode = 'Авто ВК',
          );

          final ignored = await SubscriptionService.importSubscription(
            url,
            allowHttp: true,
            applyHashes: false,
          );
          expect(ignored.hashesApplied, isFalse);
          var settings = await DbService.getSettings();
          expect(settings.customHashes, 'keep');
          expect(settings.hashes, 'Авто ВК');
          expect(settings.workMode, 'Авто ВК');

          final applied = await SubscriptionService.importSubscription(
            url,
            allowHttp: true,
            applyHashes: true,
          );
          expect(applied.hashesApplied, isTrue);
          settings = await DbService.getSettings();
          expect(settings.customHashes, 'abc,def');
          expect(settings.hashes, 'Ручной');
          expect(settings.workMode, 'Звонки');

          final repeated = await SubscriptionService.importSubscription(
            url,
            allowHttp: true,
            applyHashes: true,
          );
          expect(
            repeated.hashesApplied,
            isFalse,
            reason: 'повторное применение тех же хешей не меняет настройки',
          );
        },
      );
    });
  });
}
