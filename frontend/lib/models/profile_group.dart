// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import '../l10n/app_locale.dart';
import 'server_profile.dart';

const String _subscriptionPrefix = 'Sub:';

/// Имя узла сгенерировано импортом подписки и может быть перезаписано.
bool isAutoSubscriptionName(String? name) =>
    name == null || name.isEmpty || name.startsWith(_subscriptionPrefix);

/// Название подписки, извлечённое из имени профиля, созданного импортом.
///
/// Импорт пишет имена как `Sub:<название> - <узел>` (группа) или
/// `Sub:<название>` (один узел). Для обычных профилей возвращает null.
String? subscriptionTitleOf(ServerProfile profile) {
  final url = profile.subscriptionUrl;
  if (url == null || url.isEmpty) return null;
  final name = profile.name;
  if (name == null || !name.startsWith(_subscriptionPrefix)) return null;
  final rest = name.substring(_subscriptionPrefix.length);
  final separator = rest.indexOf(' - ');
  final title = (separator < 0 ? rest : rest.substring(0, separator)).trim();
  return title.isEmpty ? null : title;
}

/// Имя узла без служебного префикса `Sub:<название> - `.
String profileNodeName(ServerProfile profile) {
  final name = profile.name;
  if (name == null || name.isEmpty) return S.unnamedProfile;
  final title = subscriptionTitleOf(profile);
  if (title == null) return name;
  final prefix = '$_subscriptionPrefix$title - ';
  if (name.startsWith(prefix)) return name.substring(prefix.length);
  if (name == '$_subscriptionPrefix$title') {
    final ip = profile.ip;
    final port = profile.port;
    if (ip != null && ip.isNotEmpty && port != null) return '$ip:$port';
    return title;
  }
  return name;
}

/// Имя профиля после переименования подписки с [oldTitle] на [newTitle].
String renamedProfileName(
  ServerProfile profile,
  String oldTitle,
  String newTitle,
) {
  final name = profile.name;
  if (name == null || name.isEmpty) return '$_subscriptionPrefix$newTitle';
  final prefix = '$_subscriptionPrefix$oldTitle';
  if (name == prefix) return '$_subscriptionPrefix$newTitle';
  if (name.startsWith('$prefix - ')) {
    return '$_subscriptionPrefix$newTitle - '
        '${name.substring(prefix.length + 3)}';
  }
  return name;
}

/// Подпись выбранного профиля: для подписки — «название — узел».
String profileDisplayTitle(ServerProfile profile) {
  final title = subscriptionTitleOf(profile);
  if (title == null) return profile.name ?? S.unnamedProfile;
  final node = profileNodeName(profile);
  return node == title ? title : '$title — $node';
}

class ProfileGroup {
  ProfileGroup({required this.profiles, this.subscriptionUrl});

  final List<ServerProfile> profiles;
  String? subscriptionUrl;

  bool get isSubscription =>
      (subscriptionUrl != null && subscriptionUrl!.isNotEmpty) ||
      profiles.any((p) => subscriptionTitleOf(p) != null);

  /// У подписки заголовок есть всегда, даже с одним узлом: с него доступны
  /// обновление, переименование и удаление подписки.
  bool get showHeader => isSubscription;

  String get title {
    for (final profile in profiles) {
      final t = subscriptionTitleOf(profile);
      if (t != null) return t;
    }
    if (subscriptionUrl != null && subscriptionUrl!.isNotEmpty) {
      return _fallbackTitle(subscriptionUrl!);
    }
    return profiles.first.name ?? S.unnamedProfile;
  }
}

String _fallbackTitle(String url) {
  final host = Uri.tryParse(url)?.host ?? '';
  return host.isEmpty ? url : host;
}

/// Группирует профили по адресу подписки или общему имени, сохраняя порядок.
List<ProfileGroup> buildProfileGroups(List<ServerProfile> profiles) {
  final groups = <ProfileGroup>[];
  final byKey = <String, ProfileGroup>{};

  for (final profile in profiles) {
    final url = profile.subscriptionUrl?.trim();
    final title = subscriptionTitleOf(profile);

    final key = (url != null && url.isNotEmpty)
        ? 'url:$url'
        : (title != null && title.isNotEmpty)
        ? 'sub:$title'
        : null;

    if (key == null) {
      groups.add(ProfileGroup(profiles: [profile]));
      continue;
    }

    final existing = byKey[key];
    if (existing != null) {
      existing.profiles.add(profile);
      if ((existing.subscriptionUrl == null ||
              existing.subscriptionUrl!.isEmpty) &&
          url != null &&
          url.isNotEmpty) {
        existing.subscriptionUrl = url;
      }
      continue;
    }

    final group = ProfileGroup(
      profiles: [profile],
      subscriptionUrl: (url != null && url.isNotEmpty) ? url : null,
    );
    byKey[key] = group;
    groups.add(group);
  }

  return groups;
}
