// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import 'dart:io';

import 'package:flutter/foundation.dart';

enum AppLanguage { russian, english }

/// Текущий язык интерфейса. Значение хранится в файле, а не в Isar,
/// чтобы не менять схему БД ради UI-предпочтения.
class LocaleService extends ChangeNotifier {
  LocaleService._();

  static final LocaleService instance = LocaleService._();

  static AppLanguage _language = AppLanguage.russian;

  /// Тест-хук: подменяет каталог хранения выбора языка.
  static Directory? Function()? dirOverrideForTest;

  static AppLanguage get language => _language;

  static bool get isEnglish => _language == AppLanguage.english;

  static File get _file {
    final override = dirOverrideForTest?.call();
    if (override != null) {
      return File('${override.path}${Platform.pathSeparator}language');
    }
    final base = Platform.isLinux
        ? (Platform.environment['XDG_DATA_HOME'] ??
              '${Platform.environment['HOME']}/.local/share')
        : (Platform.environment['APPDATA'] ?? Directory.systemTemp.path);
    return File(
      '$base${Platform.pathSeparator}focsq'
      '${Platform.pathSeparator}language',
    );
  }

  static Future<void> init() async {
    try {
      final value = (await _file.readAsString()).trim();
      if (value == 'en') _language = AppLanguage.english;
    } catch (_) {}
  }

  static Future<void> setLanguage(AppLanguage value) async {
    if (_language == value) return;
    _language = value;
    try {
      final file = _file;
      await file.parent.create(recursive: true);
      await file.writeAsString(
        value == AppLanguage.english ? 'en' : 'ru',
        flush: true,
      );
    } catch (_) {}
    instance.notifyListeners();
  }
}

/// Строки интерфейса. Значения режимов в БД остаются русскими ключами
/// ('Ручной', 'Звонки', 'Авто ВК'...) — переводим только подписи.
abstract final class S {
  static bool get _en => LocaleService.isEnglish;

  // Навигация
  static String get navConnect => _en ? 'Connect' : 'Подключение';
  static String get navLogs => _en ? 'Logs' : 'Логи';
  static String get navInfo => _en ? 'Info' : 'Информация';
  static String get navSettings => _en ? 'Settings' : 'Настройки';

  // Настройки
  static String get settingsTitle => _en ? 'Settings' : 'Настройки';
  static String get accountVk => _en ? 'VK account' : 'Аккаунт ВКонтакте';
  static String get vkStatusAuthorized => _en ? 'Signed in' : 'Авторизован';
  static String get vkStatusGuest => _en ? 'Not signed in' : 'Нет авторизации';
  static String get workMode => _en ? 'Work mode' : 'Режим работы';
  static String get hashes => _en ? 'Hashes' : 'Хеши';
  static String get masking => _en ? 'Masking' : 'Маскировка';
  static String get fingerprint => _en ? 'Fingerprint' : 'Отпечаток';
  static String get extraThreads => _en ? 'Extra threads' : 'Экстра потоки';
  static String get tray => _en ? 'Tray' : 'Трей';
  static String get launchAtStart =>
      _en ? 'Launch at startup' : 'Запуск при старте';
  static String get developerMode =>
      _en ? 'Developer mode' : 'Режим разработчика';
  static String get allowInsecureHttp =>
      _en ? 'Allow HTTP for subscriptions' : 'Разрешить HTTP для подписок';
  static String get allowInsecureHttpWarn => _en
      ? 'HTTP transmits subscription data and passwords in plaintext without TLS encryption. It is vulnerable to interception on untrusted networks.'
      : 'HTTP передаёт данные подписки и пароли в открытом виде без шифрования TLS. Это небезопасно в ненадежных сетях.';
  static String get allowInsecureHttpConfirmTitle =>
      _en ? 'Security Warning' : 'Предупреждение безопасности';
  static String get allowInsecureHttpConfirmButton =>
      _en ? 'Enable anyway' : 'Всё равно включить';
  static String get language => _en ? 'Language' : 'Язык';
  static String get languageRu => 'Русский';
  static String get languageEn => 'English';

  // Маршрутизация
  static String get routingSection => _en ? 'Routing' : 'Маршрутизация';
  static String get routingDesc => _en
      ? 'Direct bypass of selected websites and IPs'
      : 'Прямой обход сайтов и IP без VPN';
  static String get domainRouting =>
      _en ? 'Domain routing' : 'Маршрутизация доменов';
  static String get domainsListButton => _en ? 'Domain list' : 'Список доменов';
  static String get routingDomainsTitle =>
      _en ? 'Domain routing' : 'Маршрутизация доменов';
  static String get ipRouting => _en ? 'IP routing' : 'Маршрутизация IP';
  static String get ipsListButton => _en ? 'IP list' : 'Список IP';
  static String get routingIpsTitle => _en ? 'IP routing' : 'Маршрутизация IP';

  // Пресет РФ
  static String get ruPresetBadge => _en ? 'RU' : 'РФ';
  static String get excludeRuPreset =>
      _en ? 'Bypass Russian services' : 'Исключать сервисы РФ';
  static String get excludeRuPresetHint => _en
      ? 'Zones .ru, .рф, .su, banks, state services and popular resources'
      : 'Зоны .ru, .рф, .su, банки, госуслуги и популярные сервисы';

  // Режим списков
  static String get listMode => _en ? 'List mode' : 'Режим списка';
  static String get modeBlacklist =>
      _en ? 'Bypass (Blacklist)' : 'Исключения (Чёрный список)';
  static String get modeWhitelist =>
      _en ? 'Tunnel only (Whitelist)' : 'Только этот список (Белый список)';

  // Пользовательские списки
  static String get customDomainsLabel =>
      _en ? 'Direct domains (bypass VPN)' : 'Домены напрямую (мимо VPN)';
  static String get customDomainsHint => _en
      ? 'One per line or space-separated, e.g.:\nyandex.ru\n*.company.com'
      : 'По одному на строку или через пробел, например:\nyandex.ru\n*.company.com';
  static String get customIpsLabel => _en
      ? 'Direct IPs and subnets (bypass VPN)'
      : 'IP и подсети напрямую (мимо VPN)';
  static String get customIpsHint => _en
      ? 'One per line or space-separated, e.g.:\n1.2.3.4\n10.0.0.0/8\n192.168.1.0/24'
      : 'По одному на строку или через пробел, например:\n1.2.3.4\n10.0.0.0/8\n192.168.1.0/24';

  static String get excludeRuZones =>
      _en ? 'Exclude RU zones' : 'Исключать RU-зоны';
  static String get templateDomains =>
      _en ? 'Template domains' : 'Шаблонные домены';
  static String get domainWhitelist =>
      _en ? 'Only listed domains' : 'Только список доменов';
  static String get directDomains => _en ? 'Direct domains' : 'Домены напрямую';
  static String get directDomainsHint => _en
      ? 'Direct domains: example.com, *.example.org'
      : 'Домены напрямую: example.com, *.example.org';
  static String get ipWhitelist => _en ? 'Only listed IPs' : 'Только список IP';
  static String get directIps => _en ? 'Direct IPs' : 'IP напрямую';
  static String get directIpsHint => _en
      ? 'Direct IPs: 1.2.3.4, 10.0.0.0/8'
      : 'IP напрямую: 1.2.3.4, 10.0.0.0/8';
  static String get settingsSectionGeneral => _en ? 'General' : 'Основные';
  static String get settingsSectionApp =>
      _en ? 'Application settings' : 'Настройки приложения';

  // Значения режимов (отображение; в БД хранятся русские ключи)
  static String get optCaptcha => _en ? 'Captcha' : 'Капча';
  static String get optCalls => _en ? 'Calls' : 'Звонки';
  static String get optAutoVk => _en ? 'Auto VK' : 'Авто ВК';
  static String get optManual => _en ? 'Manual' : 'Ручной';
  static String get optAutoApi => _en ? 'Auto API' : 'Авто API';
  static String get optMaskSimple => _en ? 'Simple' : 'Простая';
  static String get optMaskMedium => _en ? 'Medium' : 'Средняя';

  static String get warnAutoVkPair => _en
      ? '"Auto VK" work mode only works together with "Auto VK" hashes'
      : 'Режим работы "Авто ВК" работает только в паре с хешами "Авто ВК"';
  static String get warnVkAuthNeeded => _en
      ? '⚠️ This mode requires VK authorization'
      : '⚠️ Для этого режима требуется авторизация ВКонтакте';
  static String get warnHashesVkNeeded => _en
      ? '⚠️ Getting hashes this way requires VK authorization (eternal token)'
      : '⚠️ Для получения хешей этим способом требуется авторизация ВКонтакте (вечный токен)';
  static String get hashHint =>
      _en ? 'Hashes, comma-separated' : 'Хеши через запятую';

  // Логи
  static String get logsTitle => _en ? 'Logs' : 'Логи';
  static String get searchLogs => _en ? 'Search logs' : 'Поиск по логам';
  static String get copy => _en ? 'Copy' : 'Копировать';
  static String get clear => _en ? 'Clear' : 'Очистить';
  static String get logsEmptyTitle => _en ? 'Logs are empty' : 'Логи пусты';
  static String get logsEmptyMessage => _en
      ? 'Nothing to copy yet — the journal is empty'
      : 'Пока нечего копировать — журнал пуст';
  static String get successTitle => _en ? 'Success' : 'Успех';
  static String get logsCopiedMessage =>
      _en ? 'Logs copied!' : 'Логи скопированы!';

  // Импорт
  static String get errorTitle => _en ? 'Error' : 'Ошибка';
  static String get emptyClipboard =>
      _en ? 'Clipboard is empty.' : 'Буфер обмена пуст.';
  static String get readError => _en
      ? 'Failed to read the clipboard.'
      : 'Не удалось прочитать буфер обмена.';
  static String get invalidFormat =>
      _en ? 'No valid links found.' : 'Не найдено ни одной корректной ссылки.';
  static String get saveFailed => _en
      ? 'Links recognized, but an error occurred while saving.'
      : 'Ссылки распознаны, но произошла ошибка при сохранении.';
  static String importedCount(int count) =>
      _en ? 'Profiles imported: $count' : 'Импортировано профилей: $count';
  static String get duplicateHashesOne => _en
      ? 'Server is a duplicate, but hashes were updated'
      : 'Сервер дублируется, но хеши обновлены';
  static String duplicatesHashesMany(int count) => _en
      ? 'Servers are duplicates ($count), but hashes were updated'
      : 'Серверы дублируются ($count), но хеши обновлены';
  static String skippedDuplicates(int count) =>
      _en ? 'Duplicates skipped: $count' : 'Пропущено дубликатов: $count';
  static String get hashesUpdated => _en ? 'Hashes updated' : 'Хеши обновлены';
  static String unrecognizedCount(int count) =>
      _en ? 'Unrecognized links: $count' : 'Нераспознано ссылок: $count';
  static String get noNewTitle => _en ? 'Nothing new' : 'Новых профилей нет';
  static String profileAdded(String name) =>
      _en ? 'Profile "$name" added' : 'Профиль «$name» добавлен';
  static String profileUpdated(String name) =>
      _en ? 'Profile "$name" saved' : 'Профиль «$name» сохранён';
  static String get deletedTitle => _en ? 'Deleted' : 'Удалено';
  static String profileDeleted(String name) =>
      _en ? 'Profile "$name" deleted' : 'Профиль «$name» удалён';

  // Deep-link импорт
  static String get linkNotOpened =>
      _en ? 'Link not opened' : 'Ссылка не открыта';
  static String get linkNotRecognized => _en
      ? 'The link is not recognized as a FOCSQ profile.'
      : 'Ссылка не распознана как профиль FOCSQ.';
  static String get importProfileTitle =>
      _en ? 'Profile import' : 'Импорт профиля';
  static String get confirmImportTitle =>
      _en ? 'Confirm server import' : 'Подтвердите импорт сервера';
  static String get confirmImportDesc => _en
      ? 'The profile will be added from the link. Password and hashes are not shown.'
      : 'Профиль будет добавлен из ссылки. Пароль и хеши не показываются.';
  static String get serverData => _en ? 'Server data' : 'Данные сервера';
  static String get address => _en ? 'Address' : 'Адрес';
  static String get port => _en ? 'Port' : 'Порт';
  static String get hashesWillUpdate =>
      _en ? 'Hashes will be updated' : 'Хеши будут обновлены';
  static String get duplicateWarning => _en
      ? 'Server already added — profile will not be created'
      : 'Сервер уже добавлен — профиль не создастся';
  static String get importButton => _en ? 'Import' : 'Импортировать';
  static String get cancelButton => _en ? 'Cancel' : 'Отмена';
  static String get dismiss => _en ? 'Dismiss' : 'Закрыть';

  // Подписки
  static String get addFromSubscription =>
      _en ? 'From subscription (URL)' : 'Из подписки (URL)';
  static String get subscriptionImportTitle =>
      _en ? 'Subscription import' : 'Импорт подписки';
  static String get subscriptionUrlHint =>
      _en ? 'Subscription URL' : 'Адрес подписки';
  static String get subscriptionUrlRequired =>
      _en ? 'Enter subscription URL' : 'Укажите адрес подписки';
  static String get subscriptionImportHint => _en
      ? 'The server returns a list of nodes; existing nodes are updated.'
      : 'Сервер вернёт список узлов; существующие узлы обновятся.';
  static String subscriptionImportedCount(int count) =>
      _en ? 'Nodes added: $count' : 'Добавлено узлов: $count';
  static String subscriptionUpdatedCount(int count) =>
      _en ? 'Nodes updated: $count' : 'Обновлено узлов: $count';
  static String subscriptionSkippedCount(int count) =>
      _en ? 'Nodes skipped: $count' : 'Пропущено узлов: $count';
  static String get subscriptionNothingNew =>
      _en ? 'Subscription is up to date' : 'Подписка не изменилась';
  static String get subscriptionHashesApplied => _en
      ? 'Call hashes from the subscription applied to settings'
      : 'Хеши звонков из подписки применены в настройках';
  static String get subscriptionDefaultTitle =>
      _en ? 'Subscription' : 'Подписка';
  static String get subscriptionDefaultNodeName => _en ? 'Node' : 'Узел';
  static String get subscriptionDefaultServerName => _en ? 'Server' : 'Сервер';
  static String get subscriptionInvalidUrl =>
      _en ? 'Invalid subscription URL' : 'Некорректный адрес URL подписки';
  static String get subscriptionHttpDisabled => _en
      ? 'Insecure HTTP is disabled in settings for security'
      : 'Использование незащищенного протокола HTTP отключено в настройках для безопасности';
  static String subscriptionServerStatus(int code) =>
      _en ? 'Server returned HTTP $code' : 'Сервер вернул статус HTTP $code';
  static String get subscriptionEmptyResponse =>
      _en ? 'Server returned an empty response' : 'Сервер вернул пустой ответ';
  static String get subscriptionInsecureRedirect => _en
      ? 'The server redirected the subscription to insecure HTTP. '
            'Enable "Allow HTTP for subscriptions" if this is intentional'
      : 'Сервер перенаправил подписку на незащищенный HTTP. '
            'Включите «Разрешить HTTP для подписок», если это осознанное решение';
  static String subscriptionUntrustedCertificate(String details) => _en
      ? 'The server TLS certificate is not trusted by the system store. '
            'Check the subscription address and system certificates. ($details)'
      : 'TLS-сертификат сервера не доверен системным хранилищем. '
            'Проверьте адрес подписки и системные сертификаты. ($details)';
  static String subscriptionCertificateError(String details) => _en
      ? 'Certificate verification error: $details'
      : 'Ошибка проверки сертификата: $details';
  static String subscriptionNetworkError(String details) =>
      _en ? 'Network error: $details' : 'Ошибка сети: $details';
  static String get subscriptionTimeout => _en
      ? 'The server response timed out'
      : 'Превышено время ожидания ответа сервера';
  static String get subscriptionFetchFailed => _en
      ? 'Failed to fetch subscription data from the server'
      : 'Не удалось получить данные подписки от сервера';
  static String get subscriptionUnsupportedScheme => _en
      ? 'Unsupported subscription URL scheme'
      : 'Неподдерживаемая схема адреса подписки';
  static String get subscriptionRedirectWithoutLocation => _en
      ? 'The server returned a redirect without a Location header'
      : 'Сервер вернул перенаправление без заголовка Location';
  static String get subscriptionTooManyRedirects => _en
      ? 'Too many redirects while loading the subscription'
      : 'Слишком много перенаправлений при загрузке подписки';
  static String get subscriptionBodyTooLarge => _en
      ? 'The subscription response exceeded the 2 MB limit'
      : 'Размер ответа подписки превысил допустимый лимит (2 МБ)';
  static String get subscriptionRejected => _en
      ? 'The server rejected the subscription request'
      : 'Сервер отклонил запрос подписки';
  static String subscriptionNoNodesInGroup(String group) => _en
      ? 'The "$group" subscription has no available nodes'
      : 'В подписке "$group" отсутствуют доступные узлы';
  static String get subscriptionNoDecryptableNodes => _en
      ? 'None of the subscription nodes could be decrypted or parsed'
      : 'Ни один узел из подписки не удалось расшифровать или разобрать';
  static String get subscriptionNoLink => _en
      ? 'The subscription does not contain a server link'
      : 'В подписке отсутствует ссылка на сервер';
  static String get subscriptionNodeDecryptFailed => _en
      ? 'Failed to decrypt the subscription node link'
      : 'Не удалось расшифровать ссылку узла подписки';
  static String get subscriptionHtmlDecryptFailed => _en
      ? 'Failed to decrypt the subscription link from the HTML page'
      : 'Не удалось расшифровать ссылку подписки из HTML-страницы';
  static String get subscriptionBadNodeLink => _en
      ? 'The subscription link has an invalid node format'
      : 'Ссылка подписки имеет некорректный формат узла';
  static String get subscriptionReadFailed => _en
      ? 'Failed to read the current profiles from the database'
      : 'Не удалось прочитать текущие профили из базы данных';
  static String get subscriptionSaveFailed => _en
      ? 'Failed to save subscription profiles to the database'
      : 'Ошибка записи профилей подписки в базу данных';
  static String get subscriptionDeleteFailed =>
      _en ? 'Failed to delete the subscription' : 'Не удалось удалить подписку';

  static String get subscriptionNameLabel =>
      _en ? 'Subscription name' : 'Название подписки';
  static String get subscriptionNameRequired =>
      _en ? 'Enter a subscription name' : 'Введите название подписки';
  static String get renameSubscription =>
      _en ? 'Rename subscription' : 'Переименовать подписку';
  static String get refreshSubscription =>
      _en ? 'Refresh subscription' : 'Обновить подписку';
  static String get deleteSubscription =>
      _en ? 'Delete subscription' : 'Удалить подписку';
  static String subscriptionDeleteConfirm(String name) => _en
      ? 'Delete subscription "$name" and all of its profiles?'
      : 'Удалить подписку «$name» и все её профили?';
  static String subscriptionDeleted(String name) =>
      _en ? 'Subscription "$name" deleted' : 'Подписка «$name» удалена';

  static String get editSubscription =>
      _en ? 'Edit subscription' : 'Редактировать подписку';
  static String get subscriptionSettings =>
      _en ? 'Subscription settings' : 'Настройки подписки';
  static String get subscriptionDetailsTitle =>
      _en ? 'Subscription' : 'Подписка';
  static String get subscriptionNameSection => _en ? 'Name' : 'Название';
  static String get subscriptionUrlSection =>
      _en ? 'Subscription URL' : 'Ссылка на подписку';
  static String get copied => _en ? 'Copied!' : 'Скопировано!';
  static String get autoUpdateSection => _en ? 'Auto-update' : 'Автообновление';
  static String get autoUpdateOff => _en ? 'Off' : 'Отключено';
  static String get intervalLabel => _en ? 'Interval' : 'Интервал';
  static String get neverUpdated => _en ? 'Never updated' : 'Не обновлялось';
  static String get updatedJustNow => _en ? 'Just now' : 'Только что';
  static String updatedMinutesAgo(int minutes) =>
      _en ? '$minutes min ago' : '$minutes мин назад';
  static String updatedHoursAgo(int hours) =>
      _en ? '$hours h ago' : '$hours ч назад';
  static String updatedAt(String when) =>
      _en ? 'Updated: $when' : 'Обновлено: $when';
  static String serversCount(int count) =>
      _en ? 'Servers: $count' : 'Серверов: $count';
  static String get refreshButton => _en ? 'Refresh' : 'Обновить';
  static String get subscriptionDeleteTitle =>
      _en ? 'Delete subscription?' : 'Удалить подписку?';
  static String subscriptionDeleteBody(int count) => _en
      ? 'All $count subscription servers will be deleted.'
      : 'Все серверы подписки ($count шт.) будут удалены.';
  static String get subscriptionUrlInvalid => _en
      ? 'Enter a valid http(s) subscription URL'
      : 'Укажите корректную http(s)-ссылку подписки';
  static String subscriptionUpdatedLog(String title, int added, int updated) =>
      _en
      ? 'Subscription "$title" updated: $added new, $updated changed'
      : 'Подписка «$title» обновлена: новых $added, обновлено $updated';
  static String subscriptionUpdateFailedLog(String title) => _en
      ? 'Subscription "$title" update failed'
      : 'Ошибка обновления подписки «$title»';
  static String subscriptionHashesLog(String title) => _en
      ? 'Subscription "$title": call hashes applied'
      : 'Подписка «$title»: применены хеши звонков';
  static String get subscriptionUrlChangedLog =>
      _en ? 'Subscription URL changed' : 'Ссылка подписки изменена';

  static String subscriptionIntervalLabel(int minutes) {
    switch (minutes) {
      case 0:
        return autoUpdateOff;
      case 2:
        return _en ? '2 minutes' : '2 минуты';
      case 30:
        return _en ? '30 min' : '30 минут';
      case 60:
        return _en ? '1 hour' : '1 час';

      case 120:
        return _en ? '2 hours' : '2 часа';

      case 360:
        return _en ? '6 hours' : '6 часов';
      case 720:
        return _en ? '12 hours' : '12 часов';
      case 1440:
        return _en ? '24 hours' : '24 часа';
      default:
        return _en ? '$minutes min' : '$minutes мин';
    }
  }

  // Шаблоны разбора HTML подписки. Кириллица обязана жить в l10n (i18n-guard),
  // хотя это не UI-строки, а часть регулярных выражений.
  static final RegExp subscriptionTitlePattern = RegExp(
    r'<title>(?:Подписка|Subscription):\s*([^<]+)</title>',
    caseSensitive: false,
  );
  static final RegExp subscriptionHeadingPattern = RegExp(
    r'<h2[^>]*>(?:Подписка|Subscription):\s*([^<]+)</h2>',
    caseSensitive: false,
  );

  // Подключение
  static String get statusConnect => _en ? 'Connect' : 'Подключиться';
  static String get statusConnecting =>
      _en ? 'Connecting...' : 'Подключение...';
  static String get statusDisconnecting =>
      _en ? 'Disconnecting...' : 'Отключение...';
  static String get statusConnected => _en ? 'Connected' : 'Подключено';
  static String get unnamedProfile => _en ? 'Unnamed' : 'Без имени';
  static String get noProfiles => _en ? 'No profiles' : 'Нет профилей';
  static String get addManually => _en ? 'Manually' : 'Вручную';
  static String get addFromClipboard =>
      _en ? 'From clipboard (links)' : 'Из буфера (ссылки)';
  static String get clipboardReadFailed =>
      _en ? 'Failed to read the clipboard' : 'Не удалось прочитать буфер';

  // Профили
  static String get profileData => _en ? 'Profile data' : 'Данные профиля';
  static String get addProfile => _en ? 'Add profile' : 'Добавить профиль';
  static String get editProfile =>
      _en ? 'Edit profile' : 'Редактировать профиль';
  static String get profileNameHint => _en ? 'Profile name' : 'Имя профиля';
  static String get ipHint => _en ? 'IP address' : 'IP-адрес';
  static String get passwordHint => _en ? 'Profile password' : 'Пароль профиля';
  static String get power => _en ? 'Power' : 'Мощность';
  static String threadsCount(int n) => _en ? '$n threads' : '$n потоков';
  static String get save => _en ? 'Save' : 'Сохранить';
  static String get delete => _en ? 'Delete' : 'Удалить';
  static String get errIpRequired => _en ? 'Enter IP' : 'Укажите IP';
  static String get errPortRange => _en ? 'Port: 1–65535' : 'Порт: 1–65535';
  static String get errPassRequired =>
      _en ? 'Enter password' : 'Укажите пароль';
  static String defaultNodeName(int n) => _en ? 'Node #$n' : 'Узел #$n';

  // Обновление
  static String get updateAvailable =>
      _en ? 'Update available' : 'Доступно обновление';
  static String get version => _en ? 'Version' : 'Версия';
  static String get installed => _en ? 'Installed' : 'Установлена';
  static String get available => _en ? 'Available' : 'Доступна';
  static String get changelog => _en ? 'Changelog' : 'Список изменений';
  static String get downloadReleases =>
      _en ? 'Download on the releases page' : 'Скачать на странице релизов';
  static String get skipVersion =>
      _en ? "Don't remind about this version" : 'Не напоминать про эту версию';

  static String get devModeLogsOn => _en
      ? '── Developer mode: full logs ──'
      : '── Режим разработчика: полные логи ──';
  static String get normalLogsOn =>
      _en ? '── Normal logs mode ──' : '── Обычный режим логов ──';

  // Информация
  static String get browserOpenFailed =>
      _en ? 'Failed to open the browser' : 'Не удалось открыть браузер';
  static String get okTitle => _en ? 'Okay' : 'Хорошо';
  static String reminderHidden(String version) => _en
      ? 'Reminder about $version hidden'
      : 'Напоминание про $version скрыто';
  static String get noUpdatesTitle => _en ? 'No updates' : 'Обновлений нет';
  static String upToDateMessage(String version) => _en
      ? 'Up-to-date version installed ($version)'
      : 'Установлена актуальная версия ($version)';
  static String noReleasesMessage(String version) => _en
      ? 'Installed $version. No published releases on GitHub yet — check back later.'
      : 'Установлена $version. Публикованных релизов '
            'на GitHub ещё нет — загляни позже.';
  static String get checkFailedTitle =>
      _en ? 'Check failed' : 'Проверка не удалась';
  static String get infoTitle => _en ? 'Info' : 'Информация';
  static String get sourceCode =>
      _en ? 'Application source code' : 'Исходный код приложения';
  static String get report => _en ? 'Report' : 'Отчет';
  static String get reportSubtitle => _en ? 'Bug report' : 'Отчет о багах';
  static String get reportCopied =>
      _en ? 'Report copied to clipboard!' : 'Отчет скопирован в буфер обмена!';
  static String get reportIssue =>
      _en ? 'Report a problem' : 'Сообщить о проблеме';
  static String get updateSection => _en ? 'Update' : 'Обновление';
  static String updateStatusHidden(String installed, String? available) => _en
      ? 'Installed $installed · available $available (reminder hidden)'
      : 'Установлена $installed · доступна $available '
            '(напоминание скрыто)';
  static String updateStatusAvailable(String installed, String? available) =>
      _en
      ? 'Installed $installed · available $available'
      : 'Установлена $installed · доступна $available';
  static String updateStatusInstalled(String installed) =>
      _en ? 'Installed $installed' : 'Установлена $installed';

  // Логи и сервисные сообщения. Теги [СТАТУС]/[ЯДРО]/[КОННЕКТ]/[TUN]/[ВК] —
  // стабильные коды, по ним работает фильтр логов, поэтому не переводим.
  static String get logConnecting =>
      _en ? '[STATUS] Connecting...' : '[STATUS] Подключение...';
  static String logConnected(int workers) => _en
      ? '[STATUS] Connected · streams $workers'
      : '[STATUS] Подключено · потоков $workers';
  static String get logDisconnecting =>
      _en ? '[STATUS] Disconnecting...' : '[STATUS] Отключение...';
  static String get logDisconnected =>
      _en ? '[STATUS] Disconnected' : '[STATUS] Отключено';
  static String connectWatchdog(int sec) => _en
      ? 'Failed to connect in $sec s — check your internet '
            'or disable another VPN'
      : 'Не удалось подключиться за $sec с — проверьте интернет '
            'или отключите другой VPN';
  static String get logCoreNoCancel => _en
      ? '[CONNECT] Core did not respond to cancel — state reset'
      : '[CONNECT] Ядро не ответило на отмену — состояние сброшено';
  static String logCoreNoStop(int sec) => _en
      ? '[CONNECT] Core did not confirm shutdown in $sec s — state reset'
      : '[CONNECT] Ядро не подтвердило остановку за $sec с — состояние сброшено';
  static String logLogStreamError(Object e) =>
      _en ? '[CORE] Log stream error: $e' : '[CORE] Ошибка потока логов: $e';
  static String get logLogStreamClosed =>
      _en ? '[CORE] Log stream closed' : '[CORE] Поток логов закрыт';
  static String get logBridgeReady =>
      _en ? '[CORE] Bridge initialized' : '[CORE] Мост инициализирован';
  static String errStartFailed(Object e) =>
      _en ? 'Start failed: $e' : 'Ошибка запуска: $e';
  static String get errNoProfile => _en
      ? 'Add and select a server profile first'
      : 'Сначала добавьте и выберите профиль сервера';
  static String errProfileIncomplete(String? name) => _en
      ? 'Profile "$name" is missing address or port'
      : 'В профиле «$name» не заполнены адрес или порт';
  static String errNoPassword(String? name) => _en
      ? 'Profile "$name" has no password'
      : 'В профиле «$name» не указан пароль';
  static String get errAdminRequired => _en
      ? 'Run the app as administrator '
            '(TUN adapter creation requires elevation)'
      : 'Запустите приложение от имени администратора '
            '(нужны права на создание TUN-адаптера)';
  static String errVkTokenIssue(String issue) =>
      _en ? 'VK token unavailable: $issue' : 'Токен ВК недоступен: $issue';
  static String get errVkLoginRequired => _en
      ? 'VK login required (Settings → VK account)'
      : 'Требуется вход ВКонтакте (Настройки → Аккаунт ВКонтакте)';
  static String get errManualHashesRequired => _en
      ? 'Fill in hashes for manual mode (Settings → Hashes)'
      : 'Для ручного режима заполните хеши (Настройки → Хеши)';
  static String logCallCreated(int created, int total) => _en
      ? '[AUTO API] Call created: $created/$total'
      : '[AUTO API] Звонок создан: $created/$total';
  static String logWorkersCapped(int workers, int maxByHashes) => _en
      ? '[CLIENT] Workers capped by hashes: $workers → $maxByHashes'
      : '[КЛИЕНТ] Потоки ограничены хешами: $workers → $maxByHashes';
  static String get errVkTokenInvalid => _en
      ? 'VK token is invalid — log in again'
      : 'Токен ВК недействителен — выполните вход заново';
  static String get errVkCallsCreateFailed => _en
      ? 'Failed to create VK calls — check your internet and try again'
      : 'Не удалось создать звонки VK — проверь интернет и попробуй ещё раз';
  static String get clientStoppedReason =>
      _en ? 'client stopped' : 'клиент остановлен';
  static String get logCoreStopped =>
      _en ? '[CORE] Core stopped' : '[CORE] Ядро остановлено';
  static String logFinishingCalls(int n) => _en
      ? '[AUTO API] Finishing calls ($n)...'
      : '[AUTO API] Завершаю звонки ($n)...';
  static String logCoreError(bool fatal, int code, String message) => _en
      ? '[CORE] ${fatal ? 'FATAL' : 'Error'} ($code) $message'
      : '[CORE] ${fatal ? 'ФАТАЛЬНО' : 'Ошибка'} ($code) $message';
  static String get errCoreGeneric => _en ? 'Core error' : 'Ошибка ядра';
  static String tunConfig(String ip, String dns) => _en
      ? '[TUN] Network config received: $ip (DNS: $dns)'
      : '[TUN] Конфиг сети получен: $ip (DNS: $dns)';
  static String get logServerRestart => _en
      ? '[CORE] Server restarted by panel'
      : '[CORE] Сервер перезапущен панелью';

  static String get workerReadyLine =>
      _en ? '[WORKER] Worker ready ✓' : '[WORKER] Поток готов ✓';
  static String networkStatsLine(int active, String upMb, String downMb) => _en
      ? '[NET] Active streams: $active | Traffic: ↑$upMb ↓$downMb MB'
      : '[NET] Активных потоков: $active | Трафик: ↑$upMb ↓$downMb МБ';
  static String get wintunCreatedLine =>
      _en ? '[TUN] Wintun adapter created ✓' : '[TUN] Wintun-адаптер создан ✓';
  static String get tunAdapterReadyLine => _en
      ? '[TUN] Network adapter configured (IP/DNS/routes) ✓'
      : '[TUN] Сетевой адаптер настроен (IP/DNS/маршруты) ✓';
  static String get tunRoutesReadyLine => _en
      ? '[TUN] Routes and DNS applied ✓'
      : '[TUN] Маршрутизация и DNS применены ✓';
  static String get vkAuthPassedLine => _en
      ? '[CALLS] VK authorization passed ✓'
      : '[CALLS] Авторизация VK пройдена ✓';
  static String callsFailedLine(String tail) =>
      _en ? '[CALLS] Calls failed $tail' : '[CALLS] Звонки не прошли $tail';
  static String get captchaSolvedLine =>
      _en ? '[CAPTCHA] Captcha solved ✓' : '[CAPTCHA] Капча успешно решена ✓';
  static String get captchaSolvingLine =>
      _en ? '[CAPTCHA] Solving captcha...' : '[CAPTCHA] Решение капчи...';
  static String get serverRestartedByPanel => _en
      ? '[SERVER] Server restarted by panel'
      : '[SERVER] Сервер перезапущен панелью';

  static String get wintunExtracted =>
      _en ? '[TUN] wintun.dll extracted: ' : '[TUN] wintun.dll распакован: ';
  static String wintunExtractFailed(Object e) => _en
      ? '[TUN] Failed to extract wintun.dll: $e'
      : '[TUN] Не удалось распаковать wintun.dll: $e';

  static String get vkWebviewUnavailableLog => _en
      ? '[VK] WebView is not available on this machine'
      : '[VK] WebView недоступен на этой машине';
  static String get vkWebviewUnavailableMsg => _en
      ? 'WebView is not available on this machine '
            '(on Windows: install WebView2 Runtime)'
      : 'WebView недоступен на этой машине '
            '(на Windows: установи WebView2 Runtime)';
  static String get vkTokenSaved =>
      _en ? '[VK] Token saved' : '[VK] Токен сохранён';
  static String vkSecretFallback(String issue) => _en
      ? '[VK] Secret Service unavailable ($issue) — '
            'token saved to an unencrypted file'
      : '[VK] Secret Service недоступен ($issue) — '
            'токен сохранён в файл без шифрования';
  static String get vkTokenTimeout => _en
      ? '[VK] Timed out waiting for token'
      : '[VK] Тайм-аут ожидания токена';
  static String vkTokenSaveFailed(Object e) =>
      _en ? '[VK] Token not saved: $e' : '[VK] Токен не сохранён: $e';
  static String get vkTokenMoved => _en
      ? '[VK] Token moved from file to secure storage'
      : '[VK] Токен перенесён из файла в защищённое хранилище';
  static String vkTokenUnavailable(String issue) =>
      _en ? '[VK] Token unavailable: $issue' : '[VK] Токен недоступен: $issue';
  static String vkTokenDeleteFailed(String issue) => _en
      ? '[VK] Failed to delete token: $issue'
      : '[VK] Не удалось удалить токен: $issue';
  static String get vkKeyringLocked => _en
      ? 'storage is locked — unlock the keyring '
            '(the password is usually entered at system login)'
      : 'хранилище заблокировано — разблокируй keyring '
            '(пароль обычно вводится при входе в систему)';
  static String get vkNoSecretService => _en
      ? 'no Secret Service — install gnome-keyring '
            '(works in KDE too, unlike KWallet)'
      : 'нет Secret Service — установи gnome-keyring '
            '(работает и в KDE, в отличие от KWallet)';
  static String get vkSecretNotFound =>
      _en ? 'entry not found' : 'запись не найдена';
  static String get vkNoDescription => _en ? 'no description' : 'без описания';
  static String get vkFallbackNotice => _en
      ? 'Token saved to an unencrypted file: this system has no Secret '
            'Service (KDE without gnome-keyring or a Live session). '
            'Install gnome-keyring and log in again — the token will move '
            'to secure storage.'
      : 'Токен сохранён в файл без шифрования: на этой системе '
            'нет Secret Service (KDE без gnome-keyring или Live-сессия). '
            'Установи gnome-keyring, войди заново — токен переедет в '
            'защищённое хранилище.';

  static String windowSetupFailed(Object e) => _en
      ? '[WINDOW] Failed to configure window: $e'
      : '[WINDOW] Не удалось настроить окно: $e';
  static String windowCloseLog(int ms) => _en
      ? '[CONNECT] Window close: destroy (${ms}ms)'
      : '[CONNECT] Закрытие окна: destroy ($msмс)';

  // Трей
  static String get statusDisconnected => _en ? 'Disconnected' : 'Отключено';
  static String get trayOpen => _en ? 'Open FOCSQ' : 'Открыть FOCSQ';
  static String get trayDisconnect => _en ? 'Disconnect' : 'Отключиться';
  static String get trayExit => _en ? 'Exit' : 'Выход';
  static String trayIconError(Object e) => _en
      ? '[TRAY] Failed to set icon: $e'
      : '[TRAY] Ошибка установки иконки: $e';
  static String get trayMinimizedLog =>
      _en ? '[STATUS] Minimized to tray' : '[STATUS] Свёрнуто в трей';
  static String get trayExitStartLog =>
      _en ? '[CONNECT] Exit: start' : '[CONNECT] Выход: старт';
  static String trayExitHiddenLog(int ms) => _en
      ? '[CONNECT] Exit: UI hidden ($ms ms)'
      : '[CONNECT] Выход: интерфейс скрыт ($ms мс)';
  static String trayExitCoreStoppedLog(int ms) => _en
      ? '[CONNECT] Exit: core stopped ($ms ms)'
      : '[CONNECT] Выход: ядро остановлено ($ms мс)';
  static String trayExitTeardownLog(int ms) => _en
      ? '[CONNECT] Exit: teardown done ($ms ms)'
      : '[CONNECT] Выход: teardown завершён ($ms мс)';

  // Капча
  static String captchaBadRequest(String line) => _en
      ? '[CAPTCHA] Bad request from core: $line'
      : '[CAPTCHA] Некорректный запрос от ядра: $line';
  static String get captchaClientStoppedLog => _en
      ? '[CAPTCHA] Client stopped — not opening the window'
      : '[CAPTCHA] Клиент остановлен — окно не открываю';
  static String get captchaAlreadyOpenLog => _en
      ? '[CAPTCHA] Window already open — parallel request rejected'
      : '[CAPTCHA] Окно уже открыто, параллельный запрос отклонён';
  static String captchaOpeningLog(String mode, int sec) => _en
      ? '[CAPTCHA] WebView($mode): opening solve window (${sec}s)...'
      : '[CAPTCHA] WebView($mode): открываю окно решения (${sec}s)...';
  static String captchaAcceptedLog(String reason) => _en
      ? '[CAPTCHA] Result accepted by core ($reason)'
      : '[CAPTCHA] Результат передан в ядро ($reason)';
  static String captchaRejectedLog(String reason) => _en
      ? '[CAPTCHA] Core rejected the result ($reason) — stale or client stopped'
      : '[CAPTCHA] Ядро не приняло результат ($reason) — устарел или клиент остановлен';
  static String get captchaWindowTitle =>
      _en ? 'Confirm you are not a robot' : 'Подтвердите, что вы не робот';
  static String get captchaReasonClosed =>
      _en ? 'window closed by user' : 'окно закрыто пользователем';
  static String get captchaReasonCaptured =>
      _en ? 'success_token captured' : 'success_token перехвачен';
  static String captchaReasonTimeout(int sec) =>
      _en ? 'timeout $sec s' : 'таймаут ${sec}s';
  static String captchaOpenFailed(Object e) => _en
      ? '[CAPTCHA] Failed to open WebView: $e'
      : '[CAPTCHA] Не удалось открыть WebView: $e';
  static String get captchaReasonWebview =>
      _en ? 'webview error' : 'ошибка WebView';

  // Автозапуск
  static String autostartStateReadError(Object e) => _en
      ? '[AUTOSTART] Failed to read state: $e'
      : '[AUTOSTART] Ошибка чтения состояния: $e';
  static String get autostartUnsupportedLog => _en
      ? '[AUTOSTART] Autostart is not supported on this platform'
      : '[AUTOSTART] На этой платформе автозапуск не поддерживается';
  static String autostartTaskCreateFailed(int code) => _en
      ? '[AUTOSTART] Failed to create task (code $code)'
      : '[AUTOSTART] Не удалось создать задачу (код $code)';
  static String get autostartTaskCreatedLog => _en
      ? '[AUTOSTART] Task created (elevated run)'
      : '[AUTOSTART] Задача создана (запуск от админа)';
  static String autostartEnableError(Object e) => _en
      ? '[AUTOSTART] Failed to enable: $e'
      : '[AUTOSTART] Ошибка включения: $e';
  static String autostartTaskDeleteFailed(int code) => _en
      ? '[AUTOSTART] Failed to delete task (code $code)'
      : '[AUTOSTART] Не удалось удалить задачу (код $code)';
  static String get autostartTaskDeletedLog =>
      _en ? '[AUTOSTART] Task deleted' : '[AUTOSTART] Задача удалена';
  static String get autostartEntryDeletedLog =>
      _en ? '[AUTOSTART] Entry deleted' : '[AUTOSTART] Запись удалена';
  static String autostartDisableError(Object e) => _en
      ? '[AUTOSTART] Failed to disable: $e'
      : '[AUTOSTART] Ошибка выключения: $e';
  static String get autostartNoHomeLog => _en
      ? '[AUTOSTART] No HOME/XDG_CONFIG_HOME — nowhere to write the entry'
      : '[AUTOSTART] Нет HOME/XDG_CONFIG_HOME — некуда писать запись';
  static String autostartEntryCreated(String path) => _en
      ? '[AUTOSTART] Entry created: $path'
      : '[AUTOSTART] Запись создана: $path';
  static String get autostartGrantHintLog => _en
      ? '[AUTOSTART] Before the first autostart, run FOCSQ from the menu '
            'and confirm the TUN permission grant (once)'
      : '[AUTOSTART] Перед первым автозапуском запусти FOCSQ из меню '
            'и подтверди выдачу права туннеля (однократно)';
  static String autostartRunFailed(int code) => _en
      ? 'Failed to start the process (GetLastError: $code)'
      : 'Не удалось запустить процесс (GetLastError: $code)';
  static String autostartWaitTimeout(int timeoutMs, int wait) => _en
      ? 'Process did not exit within ${timeoutMs}ms (wait code $wait)'
      : 'Процесс не завершился за $timeoutMs мс (код ожидания $wait)';
  static String get autostartExitCodeFailed =>
      _en ? 'GetExitCodeProcess failed' : 'GetExitCodeProcess не удался';

  // Браузер
  static String browserOnlyHttp(String url) => _en
      ? 'only http/https with an explicit host: $url'
      : 'только http/https с указанным хостом: $url';
  static String browserPrivateHost(String url) => _en
      ? 'private/reserved host: $url'
      : 'приватный/зарезервированный хост: $url';
  static String get browserUnsupportedLog => _en
      ? '[SERVICE] Opening the browser is not supported on this OS'
      : '[SERVICE] Открытие браузера на этой ОС не поддержано';
  static String browserRejectedLog(String msg) => _en
      ? '[SERVICE] Link rejected: $msg'
      : '[SERVICE] Ссылка отклонена: $msg';
  static String browserOpenFailedLog(Object e) => _en
      ? '[SERVICE] Browser did not open: $e'
      : '[SERVICE] Браузер не открылся: $e';

  // Авто API (VK)
  static String get vkApiNoResponse =>
      _en ? 'no response from VK API' : 'нет ответа VK API';
  static String get vkNoResponseField => _en
      ? 'no response in the calls.start reply'
      : 'нет response в ответе calls.start';
  static String get vkEmptyCallId => _en
      ? 'empty call_id/hash in the calls.start reply'
      : 'пустой call_id/hash в ответе calls.start';
  static String callNotCreatedCode(int code, String msg) => _en
      ? '[AUTO API] Call not created · code=$code $msg'
      : '[AUTO API] Звонок не создан · код=$code $msg';
  static String callNotCreated(String reason) => _en
      ? '[AUTO API] Call not created · $reason'
      : '[AUTO API] Звонок не создан · $reason';
  static String get vkTokenInvalidLog => _en
      ? '[AUTO API] Token is invalid · log in again'
      : '[AUTO API] Токен недействителен · войдите снова';
  static String callsDistributed(int created, int count) => _en
      ? '[AUTO API] Calls $created/$count · workers distributed'
      : '[AUTO API] Звонки $created/$count · потоки распределены';
  static String callsFinished(int finished, int total) => _en
      ? '[AUTO API] Calls finished $finished/$total'
      : '[AUTO API] Звонки завершены $finished/$total';
  static String callUnavailableLine(int code) => _en
      ? '[CALLS] Error: call hash unavailable (code $code)'
      : '[ЗВОНКИ] Ошибка: хеш звонка недоступен (код $code)';

  static String get autostartVerifyPresentOk => _en
      ? '[AUTOSTART] Verified: task is present ✓'
      : '[AUTOSTART] Проверено: задача есть ✓';
  static String get autostartVerifyPresentFail => _en
      ? '[AUTOSTART] Verified: task is MISSING (expected present)'
      : '[AUTOSTART] Проверено: задачи НЕТ (ожидалась)';
  static String get autostartVerifyAbsentOk => _en
      ? '[AUTOSTART] Verified: task is absent ✓'
      : '[AUTOSTART] Проверено: задачи нет ✓';
  static String get autostartVerifyAbsentFail => _en
      ? '[AUTOSTART] Verified: task is STILL present (expected absent)'
      : '[AUTOSTART] Проверено: задача ВСЁ ЕЩЁ есть (ожидалось отсутствие)';
  static String get autostartVerifyUnknown => _en
      ? '[AUTOSTART] Verified: task state unknown (query failed)'
      : '[AUTOSTART] Проверено: состояние задачи неизвестно (ошибка запроса)';

  // Обновление
  static String get updateFromCache => _en ? 'cache' : 'кэш';
  static String get updateReleaseNoTag =>
      _en ? 'release without a version tag' : 'релиз без тега версии';
  static String updateAvailableLog(String tag, String current) => _en
      ? '[UPDATE] Version $tag available (installed $current)'
      : '[ОБНОВЛЕНИЕ] Доступна версия $tag (установлена $current)';
  static String updateBadJson(String detail) => _en
      ? 'server returned invalid JSON: $detail'
      : 'сервер вернул некорректный JSON: $detail';
  static String updateServerError(String msg) =>
      _en ? 'server replied: $msg' : 'сервер ответил: $msg';
  static String updateNoNetwork(String msg) =>
      _en ? 'no network: $msg' : 'нет сети: $msg';
  static String get updateTimeout => _en
      ? 'server did not respond within 15 seconds'
      : 'сервер не ответил за 15 секунд';
  static String updateCheckFailedMsg(Object e) =>
      _en ? 'check failed: $e' : 'проверка не удалась: $e';
  static String get updateNotJsonObject =>
      _en ? 'response is not a JSON object' : 'ответ не является JSON-объектом';

  // TUN-права (Linux)
  static String get tunNoWrapperLog => _en
      ? '[TUN] No permission, no focsq-tun wrapper nearby'
      : '[TUN] Права нет, обёртки focsq-tun нет рядом';
  static String get tunNoWrapperIssue => _en
      ? 'No tunnel permission (cap_net_admin) and no focsq-tun wrapper '
            'next to the app. Run the app via focsq.sh'
      : 'Нет права на туннель (cap_net_admin) и рядом с приложением '
            'нет обёртки focsq-tun. Запусти приложение через focsq.sh';
  static String get tunGrantDisabledLog => _en
      ? '[TUN] No permission, grant disabled (FOCSQ_NO_GRANT)'
      : '[TUN] Права нет, выдача отключена (FOCSQ_NO_GRANT)';
  static String get tunGrantDisabledIssue => _en
      ? 'No tunnel permission (cap_net_admin). Run the app via focsq.sh '
            'or without the FOCSQ_NO_GRANT variable'
      : 'Нет права на туннель (cap_net_admin). Запусти приложение '
            'через focsq.sh или без переменной FOCSQ_NO_GRANT';
  static String tunGrantErrorLog(Object e) =>
      _en ? '[TUN] Grant failed: $e' : '[TUN] Выдача права не удалась: $e';
  static String get tunGrantedLog => _en
      ? '[TUN] Permission granted (focsq-tun) — restarting the app'
      : '[TUN] Право выдано (focsq-tun) — перезапускаю приложение';
  static String tunPkexecCancelled(int code) => _en
      ? 'TUN permission grant cancelled or failed (pkexec, code $code)'
      : 'Выдача права TUN отменена или не удалась (pkexec, код $code)';
  static String tunPkexecFailed(String stderr) => _en
      ? 'TUN permission grant failed: $stderr'
      : 'Выдача права TUN не удалась: $stderr';
  static String tunPkexecUnavailable(String wrapper, Object e) => _en
      ? 'pkexec unavailable — grant manually in a terminal:\n'
            'sudo setcap cap_net_admin+ep "$wrapper"\n($e)'
      : 'pkexec недоступен — выдай право вручную в терминале:\n'
            'sudo setcap cap_net_admin+ep "$wrapper"\n($e)';
  static String tunRestartFailed(Object e) =>
      _en ? '[TUN] Restart failed: $e' : '[TUN] Перезапуск не удался: $e';

  // VK-токен и разное
  static String get vkResetConfirmTitle =>
      _en ? 'Reset authorization?' : 'Сбросить авторизацию?';
  static String get vkResetConfirmBody => _en
      ? 'Auto API and Auto VK modes will stop working until you log in again.'
      : 'Режимы «Авто API» и «Авто ВК» перестанут работать до повторного входа.';
  static String get cancelButton2 => _en ? 'Cancel' : 'Отмена';
  static String get resetButton => _en ? 'Reset' : 'Сбросить';
  static String get vkTokenDeleteFailedShort =>
      _en ? '[VK] Failed to delete the token' : '[VK] Не удалось удалить токен';
  static String vkLoginFailedLog(String error) => _en
      ? '[VK] Login not completed: $error'
      : '[VK] Вход не завершён: $error';
  static String get vkLoginFailedTitle =>
      _en ? 'Login not completed' : 'Вход не завершён';
  static String get vkLoginWithNoticeTitle => _en
      ? 'Authorization completed (with a caveat)'
      : 'Авторизация пройдена (с оговоркой)';
  static String get vkLoginOkMessage =>
      _en ? 'VK authorization completed' : 'Авторизация ВКонтакте пройдена';
  static String get vkResetButton => _en ? 'Reset VK' : 'Сброс ВК';
  static String get vkLoginButton => _en ? 'Log in' : 'Логин';

  // Дисклеймер об ответственности и условиях использования
  static String get banDisclaimerTitle => _en ? 'Disclaimer' : 'Дисклеймер';
  static String get banDisclaimerHeading => _en
      ? 'Terms of Use & Liability'
      : 'Условия использования и ответственность';
  static String get banDisclaimerAsIsTitle =>
      _en ? 'As-is provision' : 'Предоставление «как есть»';
  static String get banDisclaimerAsIsDesc => _en
      ? 'The program is distributed free of charge and without any warranties of stability, permanent availability, or complete compatibility with your system configuration.'
      : 'Программа распространяется бесплатно и без каких-либо гарантий стабильности, постоянной доступности или полной совместимости с вашей конфигурацией системы.';
  static String get banDisclaimerRisksTitle =>
      _en ? 'Third-party service risks' : 'Риски сторонних сервисов';
  static String get banDisclaimerRisksDesc => _en
      ? 'The protocol uses external platform infrastructure in a non-standard way. The developer does not control their security policies and is not responsible for any restrictions or account bans (e.g., when using Auto Api or Auto VK modes). It is strongly recommended not to use primary profiles.'
      : 'Протокол использует инфраструктуру внешних платформ нестандартным образом. Разработчик не контролирует их политику безопасности и не несет ответственности за любые ограничения или блокировки учетных записей (к примеру при использовании режимов Авто Api или Авто вк). Настоятельно рекомендуется не использовать основные профили.';
  static String get banDisclaimerTrafficTitle =>
      _en ? 'Traffic usage' : 'Использование трафика';
  static String get banDisclaimerTrafficDesc => _en
      ? 'You are solely responsible for routed traffic and compliance with third-party service rules and applicable legislation.'
      : 'Вы самостоятельно несете ответственность за направляемый трафик и соблюдение правил внешних сервисов и действующего законодательства.';
  static String get banDisclaimerClaimsTitle =>
      _en ? 'Disclaimer of claims' : 'Отказ от претензий';
  static String get banDisclaimerClaimsDesc => _en
      ? 'The developer is not liable for network connection disruptions, external platform failures, or any other consequences associated with the operation of the application.'
      : 'Разработчик не несет ответственности за перебои в работе сети, сбои внешних платформ или любые другие последствия, связанные с эксплуатацией приложения.';
  static String get banDisclaimerAccept => _en ? 'I accept' : 'Принимаю';
  static String reportStateLine(String state) =>
      _en ? '- State: $state' : '- Состояние: $state';

  static String get vkAuthWindowTitle =>
      _en ? 'VK authorization' : 'Авторизация ВКонтакте';
  static String get vkWindowClosedEarly => _en
      ? 'window closed before login completed'
      : 'окно закрыто до завершения входа';
  static String vkLoginTimeoutMsg(int minutes) => _en
      ? 'no token after $minutes min — the window was closed'
      : 'токен не получен за $minutes мин — окно закрыто';
  static String get vkSilentTokenFailed => _en
      ? 'VK issued a silent token instead of an access token. '
            'Try logging in again.'
      : 'ВК выдал silent_token вместо access_token. Попробуй войти ещё раз.';
  static String get vkFloodControl => _en
      ? 'VK is limiting login attempts (Flood control). '
            'Wait from an hour to a day, then try once.'
      : 'ВК ограничил попытки входа (Flood control). '
            'Подожди от часа до суток и попробуй один раз.';
  static String get vkPasskeyUnsupported => _en
      ? 'This account requires a passkey, and the built-in browser cannot '
            'use it. Sign in via the official VK app or a regular browser.'
      : 'Для этого аккаунта требуется вход по электронному ключу (passkey), '
            'встроенный браузер его не поддерживает. Войди через официальное '
            'приложение ВК или обычный браузер.';
  static String get vkAccountBlocked => _en
      ? 'Your VK page is blocked. Restore access on vk.com, then sign in again.'
      : 'Страница ВК заблокирована. Восстанови доступ на vk.com и войди заново.';
  static String vkApiError(int code, String text) {
    final detail = text.isEmpty ? '' : ': $text';
    return _en ? 'VK API error $code$detail' : 'Ошибка VK API $code$detail';
  }

  static String vkOAuthError(String error, String description) {
    final detail = description.isEmpty ? error : '$error: $description';
    return _en
        ? 'VK rejected the login ($detail)'
        : 'ВК отклонил вход ($detail)';
  }

  static String get vkCodeInsteadOfToken => _en
      ? 'VK returned an authorization code instead of a token'
      : 'ВК вернул код авторизации вместо токена';
  static String vkSaveFailedMsg(Object e) =>
      _en ? 'failed to save the token: $e' : 'не удалось сохранить токен: $e';
  static String serverProfileName(int n) => _en ? 'Server #$n' : 'Сервер #$n';
  static String get dbNotInitialized =>
      _en ? 'DbService.init() was not called' : 'DbService.init() не вызван';

  // Диагностический отчёт
  static String reportAutostartLine(String state) =>
      _en ? 'Autostart: $state' : 'Автозапуск: $state';
  static String reportAutostartState(bool? enabled) => enabled == null
      ? (_en
            ? 'not supported on this platform'
            : 'не поддерживается на этой платформе')
      : (enabled
            ? (_en ? 'enabled' : 'включён')
            : (_en ? 'disabled' : 'выключен'));
  static String get reportLinuxEnv =>
      _en ? 'Linux environment:' : 'Linux-окружение:';
  static String get reportConnectionSection =>
      _en ? 'Connection:' : 'Подключение:';
  static String reportStreamsLine(int workers, String up, String down) => _en
      ? '- Streams: $workers | Sent: $up | Received: $down'
      : '- Потоки: $workers | Отдано: $up | Получено: $down';
  static String reportLastErrorLine(String e) =>
      _en ? '- Last error: $e' : '- Последняя ошибка: $e';
  static String reportProfilesLine(int n) =>
      _en ? 'Profiles: $n' : 'Профили: $n';
  static String reportProfile(
    String name,
    String ip,
    String port,
    String power,
    bool isActive,
  ) => _en
      ? '- ${isActive ? "[active] " : ""}$name: $ip:$port, streams: $power'
      : '- ${isActive ? "[активный] " : ""}$name: $ip:$port, потоков: $power';
  static String get reportActiveFirst => _en
      ? 'The active profile is listed first.'
      : 'Активный профиль указан первым.';
  static String reportLogTail(int n) =>
      _en ? 'Journal (last $n lines):' : 'Журнал (последние $n строк):';
  static String get reportTunMissing => _en
      ? '/dev/net/tun: missing (tun kernel module not loaded?)'
      : '/dev/net/tun: отсутствует (модуль ядра tun не загружен?)';
  static String reportTunPresent(String mode) =>
      _en ? '/dev/net/tun: present ($mode)' : '/dev/net/tun: есть ($mode)';
  static String reportCapsLine(String v) =>
      _en ? 'Permissions: $v' : 'Права: $v';
  static String get reportLauncherYes => _en
      ? 'Launcher: yes (library checks and TUN permission were performed)'
      : 'Лаунчер: да (проверки библиотек и права TUN выполнялись)';
  static String get reportLauncherNo => _en
      ? 'Launcher: no — the binary was started directly, run focsq.sh'
      : 'Лаунчер: нет — запущен бинарник напрямую, запускай focsq.sh';
  static String get reportDnsResolved => _en
      ? 'DNS: systemd-resolved (switching via resolvectl)'
      : 'DNS: systemd-resolved (переключение через resolvectl)';
  static String get reportDnsPlain => _en
      ? 'DNS: without systemd-resolved (/etc/resolv.conf will be overwritten)'
      : 'DNS: без systemd-resolved (будет перезаписан /etc/resolv.conf)';
  static String get reportUnknownValue => _en ? 'unknown' : 'неизвестен';
  static String get reportGnomeHint => _en
      ? ' — AppIndicator extension is required for the tray'
      : ' — для трея нужно расширение AppIndicator';
  static String reportDesktopLine(
    String desktop,
    String session,
    String hint,
  ) => _en
      ? 'Desktop: $desktop, session $session$hint'
      : 'Рабочий стол: $desktop, сессия $session$hint';
  static String get reportCapsRoot =>
      _en ? 'root — tunnel available' : 'root — туннель доступен';
  static String get reportCapsGranted => _en
      ? 'CAP_NET_ADMIN present (via focsq-tun/launcher) — tunnel available'
      : 'CAP_NET_ADMIN есть (через focsq-tun/лаунчер) — туннель доступен';
  static String reportCapsUser(int uid) => _en
      ? 'regular user (uid $uid), no CAP_NET_ADMIN — run via focsq.sh '
            '(it grants the focsq-tun permission)'
      : 'обычный пользователь (uid $uid), CAP_NET_ADMIN нет — запусти через '
            'focsq.sh (выдаст право на focsq-tun)';
  static String get reportCapsUnknown => _en
      ? 'unknown — /proc/self/status not read'
      : 'неизвестны — /proc/self/status не прочитан';
  static String reportKernel(String raw) => _en ? 'kernel $raw' : 'ядро $raw';
  static String get unitB => _en ? 'B' : 'Б';
  static String get unitKB => _en ? 'KB' : 'КБ';
  static String get unitMB => _en ? 'MB' : 'МБ';
  static String get unitGB => _en ? 'GB' : 'ГБ';
}
