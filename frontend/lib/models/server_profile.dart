// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'package:isar/isar.dart';

part 'server_profile.g.dart';

@collection
class ServerProfile {
  ServerProfile({
    this.id = Isar.autoIncrement,
    this.name,
    this.ip,
    this.port,
    this.password,
    this.power,
    this.subUuid,
    this.subscriptionUrl,
    this.threadsLimit,
  });

  Id id;
  String? name;
  String? ip;
  int? port;

  String? password;

  int? power;

  String? subUuid;

  /// Адрес подписки хранится в открытом виде (как и пароль): в URL может
  /// быть токен подписки, поэтому БД нельзя считать защищённым хранилищем.
  String? subscriptionUrl;

  /// Лимит потоков, полученный из подписки. Отличается от [power]
  /// (текущее значение), чтобы пользователь мог уменьшить мощность,
  /// не теряя максимально разрешённую.
  int? threadsLimit;
}
