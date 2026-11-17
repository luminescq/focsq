import 'package:flutter_test/flutter_test.dart';
import 'package:frontend/models/profile_group.dart';
import 'package:frontend/models/server_profile.dart';

ServerProfile _profile(String name, {String? url, int id = 1}) {
  return ServerProfile(id: id, name: name, subscriptionUrl: url);
}

void main() {
  test('служебное имя подписки определяется по префиксу', () {
    expect(isAutoSubscriptionName(null), isTrue);
    expect(isAutoSubscriptionName(''), isTrue);
    expect(isAutoSubscriptionName('Sub:Demo - A'), isTrue);
    expect(isAutoSubscriptionName('Мой узел'), isFalse);
  });

  test('группирует узлы по адресу подписки и прячет префикс', () {
    final groups = buildProfileGroups([
      _profile('Sub:Demo demo - Node 0', url: 'https://s.test/feed', id: 1),
      _profile('Sub:Demo demo - Node 1', url: 'https://s.test/feed', id: 2),
      _profile('мой сервер', id: 3),
      _profile('Sub:Other - A', url: 'https://other.test/x', id: 4),
      _profile('Sub:Other - B', url: 'https://other.test/x', id: 5),
    ]);

    expect(groups, hasLength(3));
    expect(groups[0].subscriptionUrl, 'https://s.test/feed');
    expect(groups[0].title, 'Demo demo');
    expect(groups[0].showHeader, isTrue);
    expect(groups[0].profiles.map(profileNodeName), ['Node 0', 'Node 1']);
    expect(groups[1].isSubscription, isFalse);
    expect(groups[1].title, 'мой сервер');
    expect(groups[1].showHeader, isFalse);
    expect(groups[2].title, 'Other');
    expect(groups[2].profiles.map(profileNodeName), ['A', 'B']);
  });

  test('одиночная подписка получает заголовок для управления и обновления', () {
    final node = _profile('Sub:Demo demo', url: 'https://s.test/feed');

    expect(subscriptionTitleOf(node), 'Demo demo');
    expect(profileNodeName(node), 'Demo demo');
    expect(profileDisplayTitle(node), 'Demo demo');

    final groups = buildProfileGroups([node]);
    expect(groups.single.showHeader, isTrue);
  });

  test('одиночный узел подписки показывается как ip:port', () {
    final node = ServerProfile(
      id: 1,
      name: 'Sub:Demo demo',
      ip: '203.0.113.7',
      port: 40400,
      subscriptionUrl: 'https://s.test/feed',
    );

    expect(profileNodeName(node), '203.0.113.7:40400');
    expect(profileDisplayTitle(node), 'Demo demo — 203.0.113.7:40400');
  });

  test('переименование подписки обновляет имена узлов', () {
    final single = _profile('Sub:Old', url: 'https://s.test/feed');
    final grouped = _profile('Sub:Old - Node 1', url: 'https://s.test/feed');

    expect(renamedProfileName(single, 'Old', 'New'), 'Sub:New');
    expect(renamedProfileName(grouped, 'Old', 'New'), 'Sub:New - Node 1');
    expect(
      renamedProfileName(_profile('Своё имя', url: 'u'), 'Old', 'New'),
      'Своё имя',
    );
  });

  test('подпись выбранного узла содержит название подписки', () {
    final node = _profile('Sub:Demo demo - Node 7', url: 'https://s.test/feed');

    expect(profileDisplayTitle(node), 'Demo demo — Node 7');
  });

  test('переименованный узел не ломает группу', () {
    final nodes = [
      _profile('Мой быстрый узел', url: 'https://s.test/feed', id: 1),
      _profile('Мой второй узел', url: 'https://s.test/feed', id: 2),
    ];

    final group = buildProfileGroups(nodes).single;
    expect(group.showHeader, isTrue);
    expect(group.title, 's.test');
    expect(group.profiles.map(profileNodeName), [
      'Мой быстрый узел',
      'Мой второй узел',
    ]);
  });

  test('профиль с именем Sub:, но без подписки, остаётся обычным', () {
    final profile = _profile('Sub:ручной профиль');

    expect(subscriptionTitleOf(profile), isNull);
    expect(profileNodeName(profile), 'Sub:ручной профиль');
    expect(profileDisplayTitle(profile), 'Sub:ручной профиль');

    final group = buildProfileGroups([profile]).single;
    expect(group.isSubscription, isFalse);
    expect(group.showHeader, isFalse);
  });

  test('несколько подписок не смешиваются при одинаковых именах узлов', () {
    final groups = buildProfileGroups([
      _profile('Sub:Same - Node', url: 'https://a.test/feed', id: 1),
      _profile('Sub:Same - Node', url: 'https://b.test/feed', id: 2),
    ]);

    expect(groups, hasLength(2));
    expect(groups[0].subscriptionUrl, 'https://a.test/feed');
    expect(groups[1].subscriptionUrl, 'https://b.test/feed');
    expect(groups[0].showHeader, isTrue);
    expect(groups[1].showHeader, isTrue);
  });
}
