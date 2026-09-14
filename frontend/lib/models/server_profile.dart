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
  });

  Id id;
  String? name;
  String? ip;
  int? port;

  String? password;

  int? power;
}
