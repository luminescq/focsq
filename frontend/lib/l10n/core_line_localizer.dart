// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

/// Локализация сырых строк Rust-ядра на границе приёма (LogService).
///
/// Ядро не знает про языки: словарь сопоставляет исходный литерал из
/// `backend/**/*.rs` с английским шаблоном, где `{0}..{n}` — подстановки
/// захваченных параметров. В русском режиме строки ядра остаются как есть.
///
/// Guard-тест `test/core_line_guard_test.dart` сканирует исходники ядра
/// и требует, чтобы каждый кириллический литерал лог-макроса был покрыт
/// правилом из `_entries`.
class CoreLineLocalizer {
  CoreLineLocalizer._();

  /// (литерал ядра, английский шаблон). `{n}` — номер захвата.
  static const List<(String, String)> _entries = [
    // --- VK JS (auth.rs, vk_js_calls.rs, lib.rs) ---
    (
      '[VK JS] Финализация звонков не уложилась в {sec} с — пропущена',
      '[VK JS] Call finalization did not finish within {0} s — skipped',
    ),
    (
      '[VK JS] Креды аккаунта {credential_id}/{MAX_ACCOUNT_CREDENTIALS} недоступны: {auto_js_error:#} · переход на Авто',
      '[VK JS] Account creds {0}/{1} unavailable: {2} · switching to Auto',
    ),
    (
      '[VK JS] Переход на Авто #{credential_id} выполнен',
      '[VK JS] Switch to Auto #{0} done',
    ),
    (
      '[VK JS] Переход на Авто #{credential_id} не выполнен: {auto_error:#}',
      '[VK JS] Switch to Auto #{0} failed: {1}',
    ),
    (
      '[STREAM {stream_id}] [VK Auth] Звонки не прошли ({error:#}) · повтор #{round} через {base}мс',
      '[STREAM {0}] [VK Auth] Calls failed ({1}) · retry #{2} in {3}ms',
    ),
    (
      '[VK JS] Создатель удерживает звонок',
      '[VK JS] Creator is holding the call',
    ),
    (
      '[VK JS] TURN-боты готовы, создатель выходит из звонка',
      '[VK JS] TURN bots ready, creator is leaving the call',
    ),
    ('[VK JS] Сессия Calls подтверждена', '[VK JS] Calls session confirmed'),
    ('[VK JS] Звонок создан', '[VK JS] Call created'),
    ('[VK JS] Звонок не создан: {error}', '[VK JS] Call not created: {0}'),
    (
      '[VK JS] Креды аккаунта {credential_id}/{MAX_ACCOUNT_CREDENTIALS} готовы · независимая сессия',
      '[VK JS] Account creds {0}/{1} ready · independent session',
    ),
    (
      '[VK JS] Создатель вышел из звонков {finished}/{total}',
      '[VK JS] Creator left calls {0}/{1}',
    ),
    (
      '[VK JS] Звонки завершены {finished}/{total}',
      '[VK JS] Calls finished {0}/{1}',
    ),

    // --- Капча (captcha.rs, captcha_slider.rs) ---
    (
      '[STREAM {stream_id}] [КАПЧА] WBV: режим из настроек Android',
      '[STREAM {0}] [CAPTCHA] WBV: mode from Android settings',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] RJS: Rust v2 выбран в настройках',
      '[STREAM {0}] [CAPTCHA] RJS: Rust v2 selected in settings',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] RJS: ошибка, fallback на WBV Auto: {error}',
      '[STREAM {0}] [CAPTCHA] RJS: error, falling back to WBV Auto: {1}',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] AUTO: старт цепочки',
      '[STREAM {0}] [CAPTCHA] AUTO: starting the chain',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] AUTO: Rust v2 решил капчу',
      '[STREAM {0}] [CAPTCHA] AUTO: Rust v2 solved the captcha',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] AUTO: Rust v2 не решил: {error} · открываю окно',
      '[STREAM {0}] [CAPTCHA] AUTO: Rust v2 failed: {1} · opening the window',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] AUTO: WBV Auto (timeout 45s, можно решить вручную)',
      '[STREAM {0}] [CAPTCHA] AUTO: WBV Auto (timeout 45s, can be solved manually)',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] AUTO: WBV решил капчу',
      '[STREAM {0}] [CAPTCHA] AUTO: WBV solved the captcha',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] AUTO: WBV Auto ошибка: {error}',
      '[STREAM {0}] [CAPTCHA] AUTO: WBV Auto error: {1}',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] AUTO: повторное окно (ручной режим, 60s)',
      '[STREAM {0}] [CAPTCHA] AUTO: re-opened window (manual mode, 60s)',
    ),
    (
      '[STREAM {stream_id}] [КАПЧА] WBV: {mode} solve succeeded',
      '[STREAM {0}] [CAPTCHA] WBV: {1} solve succeeded',
    ),
    (
      '[КАПЧА] Решаю VK Smart Captcha автоматически (v2, попыток={attempts})...',
      '[CAPTCHA] Solving VK Smart Captcha automatically (v2, attempts={0})...',
    ),
    (
      '[КАПЧА] v2 попытка {attempt} ошибка: {error}',
      '[CAPTCHA] v2 attempt {0} error: {1}',
    ),
    (
      '[КАПЧА] Превышен лимит сессии капчи. Ожидаю 5 секунд...',
      '[CAPTCHA] Captcha session limit exceeded. Waiting 5 seconds...',
    ),
    ('[КАПЧА] solveOnce URL: {}', '[CAPTCHA] solveOnce URL: {0}'),
    (
      '[КАПЧА] initSession (PoW fallback) ошибка: {error}',
      '[CAPTCHA] initSession (PoW fallback) error: {0}',
    ),
    (
      '[КАПЧА] v2 solving pow difficulty={}',
      '[CAPTCHA] v2 solving pow difficulty={0}',
    ),
    ('[КАПЧА] v2 pow solved', '[CAPTCHA] v2 pow solved'),
    (
      '[КАПЧА] v2 script version drift: known={SCRIPT_VERSION} latest={latest}',
      '[CAPTCHA] v2 script version drift: known={0} latest={1}',
    ),
    (
      '[КАПЧА] debug_info UUID не найден в HTML, сгенерирован fallback: {value}',
      '[CAPTCHA] debug_info UUID not found in HTML, generated fallback: {0}',
    ),
    (
      '[КАПЧА] Ошибка вызова initSession: {error}',
      '[CAPTCHA] initSession call error: {0}',
    ),
    (
      '[КАПЧА] v2 solving show_type={show_type}',
      '[CAPTCHA] v2 solving show_type={0}',
    ),
    (
      '[КАПЧА] v2 endSession failed: {error}',
      '[CAPTCHA] v2 endSession failed: {0}',
    ),
    (
      '[КАПЧА] v2 checkbox returned BOT, trying slider',
      '[CAPTCHA] v2 checkbox returned BOT, trying slider',
    ),
    (
      '[КАПЧА] v2 check status={} show_type={}',
      '[CAPTCHA] v2 check status={0} show_type={1}',
    ),
    ('[КАПЧА] v2 check status={}', '[CAPTCHA] v2 check status={0}'),
    (
      '[КАПЧА] v2 slider puzzle decoded: grid={} attempts={} swaps={}',
      '[CAPTCHA] v2 slider puzzle decoded: grid={0} attempts={1} swaps={2}',
    ),
    (
      '[КАПЧА] v2 slider guesses ranked: total={} limit={limit}',
      '[CAPTCHA] v2 slider guesses ranked: total={0} limit={1}',
    ),
    (
      '[КАПЧА] v2 slider attempt {}/{} (guess #{})',
      '[CAPTCHA] v2 slider attempt {0}/{1} (guess #{2})',
    ),
    (
      '[КАПЧА] v2 slider accepted on attempt {}',
      '[CAPTCHA] v2 slider accepted on attempt {0}',
    ),
    (
      '[КАПЧА] v2 slider payload had odd-length tail; fallback attempts={attempts}',
      '[CAPTCHA] v2 slider payload had odd-length tail; fallback attempts={0}',
    ),

    // --- Клиент (lib.rs) ---
    (
      '[КЛИЕНТ] Tunnel IP: {ip}/32 | DNS: {dns}',
      '[CLIENT] Tunnel IP: {0}/32 | DNS: {1}',
    ),
    (
      '[КЛИЕНТ] TUN-адаптер настроен (IP/DNS/маршруты)',
      '[CLIENT] TUN adapter configured (IP/DNS/routes) ✓',
    ),
    (
      '[ОШИБКА] Настройка TUN не удалась: {error:#}',
      '[ERROR] TUN setup failed: {0}',
    ),
    (
      '[КЛИЕНТ] Получен сигнал завершения',
      '[CLIENT] Termination signal received',
    ),
    ('[КЛИЕНТ] Все воркеры завершены', '[CLIENT] All workers finished'),
    ('[КЛИЕНТ] Канал управления закрыт', '[CLIENT] Control channel closed'),
    ('[УПР] {line}', '[CTRL] {0}'),
    ('[КЛИЕНТ] Получена команда STOP', '[CLIENT] STOP command received'),
    (
      '[КАПЧА] Результат от Kotlin записан в канал',
      '[CAPTCHA] Kotlin result written to the channel',
    ),
    (
      '[КАПЧА] Канал результата уже заполнен, устаревший ответ отклонён',
      '[CAPTCHA] Result channel already filled — stale answer rejected',
    ),
    (
      '[КЛИЕНТ] ═══════════════════════════════════════',
      '[CLIENT] ═══════════════════════════════════════',
    ),
    (
      '[КЛИЕНТ] VK Creds: Client IDs: {client_ids}',
      '[CLIENT] VK Creds: Client IDs: {0}',
    ),
    ('[КЛИЕНТ] VK Auth: {}', '[CLIENT] VK Auth: {0}'),
    ('[КЛИЕНТ] TLS: {} fingerprint', '[CLIENT] TLS: {0} fingerprint'),
    (
      '[КЛИЕНТ] Воркеров: {workers} (групп: {groups}, по {WORKERS_PER_GROUP})',
      '[CLIENT] Workers: {0} (groups: {1}, {2} per group)',
    ),
    ('[КЛИЕНТ] Хешей: {hashes}', '[CLIENT] Hashes: {0}'),
    (
      '[КЛИЕНТ] Слушаю: {} (порт {local_port}) | Пир: {}',
      '[CLIENT] Listening on: {0} (port {1}) | Peer: {2}',
    ),
    (
      '[КЛИЕНТ] Протокол: TURN {} | WRAP: ON | obfs={}',
      '[CLIENT] Protocol: TURN {0} | WRAP: ON | obfs={1}',
    ),
    ('[WRAP] WRAP Ключ вычислен ✓', '[WRAP] WRAP key computed ✓'),
    ('[КЛИЕНТ] Device ID: {}', '[CLIENT] Device ID: {0}'),
    ('[КЛИЕНТ] Captcha: {captcha}', '[CLIENT] Captcha: {0}'),

    // --- Статистика / TUN ---
    (
      '[СТАТИСТИКА] Активных: {active} | Трафик: {total_mb:.2} МБ',
      '[STATISTICS] Active: {0} | Traffic: {1} MB',
    ),
    (
      '[КЛИЕНТ] TUN-интерфейс создан: {}',
      '[CLIENT] TUN interface created: {0}',
    ),
    (
      '[TUN] VK-хост {host} не резолвится — исключение пропущено',
      '[TUN] VK host {0} does not resolve — exclude skipped',
    ),
    (
      '[TUN] Настроен: ip={address}/24 gw={tun_gateway} dns={dns} | исходный шлюз {gateway} (iface {uplink_index}), пир {peer} — через туннель',
      '[TUN] Configured: ip={0}/24 gw={1} dns={2} | original gateway {3} (iface {4}), peer {5} — via tunnel (routes/DNS applied)',
    ),
    (
      '[TUN] TURN {address}: перехвата ещё нет, исключим при TUNCONF',
      '[TUN] TURN {0}: no interception yet, will exclude on TUNCONF',
    ),
    (
      '[TUN] Exclude-маршрут TURN {address} добавлен',
      '[TUN] TURN exclude route {0} added',
    ),
    (
      '[TUN] Exclude-маршрут TURN {address}: {error:#}',
      '[TUN] TURN exclude route {0}: {1}',
    ),
    (
      '[TUN] Отложенный Exclude-маршрут TURN {address} добавлен',
      '[TUN] Deferred TURN exclude route {0} added',
    ),
    (
      '[TUN] Системный DNS {list} совпадает с DNS туннеля — уходит в туннель, /32-исключение не ставим',
      '[TUN] System DNS {0} matches the tunnel DNS — goes through the tunnel, no /32 exclude',
    ),
    (
      '[TUN] DNS туннеля {dns} не отвечает через туннель — переключаю резолвер на {backup}',
      '[TUN] Tunnel DNS {0} does not answer through the tunnel — switching the resolver to {1}',
    ),
    (
      '[TUN] DNS туннеля {dns} и резервы 1.1.1.1/8.8.8.8 не отвечают — DNS через туннель недоступен (проверь сервер)',
      '[TUN] Tunnel DNS {0} and the 1.1.1.1/8.8.8.8 fallbacks do not answer — tunnel DNS is unreachable (check the server)',
    ),
    (
      '[TUN] Отложенный exclude TURN {address}: {error:#}',
      '[TUN] Deferred TURN exclude {0}: {1}',
    ),
    (
      '[TUN] DNS направлен в {list} (systemd-resolved)',
      '[TUN] DNS pointed to {0} (systemd-resolved)',
    ),
    (
      '[TUN] DNS направлен в {list} (/etc/resolv.conf)',
      '[TUN] DNS pointed to {0} (/etc/resolv.conf)',
    ),
    ('[TUN] Снято аплинк-маршрутов: {}', '[TUN] Uplink routes removed: {0}'),
    (
      '[TUN] Интерфейс опущен (быстрое закрытие)',
      '[TUN] Interface brought down (fast shutdown)',
    ),
    (
      '[TUN] Exclude-маршрут {what} {destination} не добавлен: {error}',
      '[TUN] Exclude route {0} {1} not added: {2}',
    ),
    (
      '[TUN] Не удалось перечислить интерфейсы (reg query)',
      '[TUN] Failed to enumerate interfaces (reg query)',
    ),
    (
      '[TUN] Интерфейсы Tcpip не найдены — системные DNS пропущены',
      '[TUN] Tcpip interfaces not found — system DNS skipped',
    ),
    (
      '[TUN] Системные DNS не обнаружены — проверь маршруты до роутера',
      '[TUN] System DNS not detected — check routes to the router',
    ),
    (
      '[TUN] Конфигурация интерфейса (метрика/MTU) не выставлена: {error}',
      '[TUN] Interface configuration (metric/MTU) not applied: {0}',
    ),
    (
      '[TUN] Exclude-подсеть VK {cidr} не добавлена: {error:#}',
      '[TUN] VK exclude subnet {0} not added: {1}',
    ),
    (
      '[TUN] Настроен: ip={ip}/24 gw={gateway_tun} dns={dns} | исходный шлюз {gateway} (iface {interface}), пир {peer_ip} — через туннель, dns-exclude {} шт',
      '[TUN] Configured: ip={0}/24 gw={1} dns={2} | original gateway {3} (iface {4}), peer {5} — via tunnel, dns-exclude {6} entries',
    ),
    (
      '[TUN] NRPT: системный резолвер направлен в {dns_server} (через туннель)',
      '[TUN] NRPT: system resolver pointed to {0} (via tunnel)',
    ),
    (
      '[TUN] NRPT-правило не создано — резолюция пойдёт по интерфейсам: {error}',
      '[TUN] NRPT rule not created — resolution will go through interfaces: {0}',
    ),
    (
      '[TUN] NRPT: снятие правил не удалось: {error}',
      '[TUN] NRPT: failed to remove rules: {0}',
    ),
    (
      '[TUN] NRPT: в системе осталось {residual} посторонних правил (не наши — не трогаем)',
      '[TUN] NRPT: {0} foreign rules left in the system (not ours — leaving them)',
    ),
    ('[TUN] Адаптер удалён', '[TUN] Adapter removed'),

    // --- Воркеры / TURN (worker.rs) ---
    (
      '[СУПЕРВИЗОР] Задача группы завершилась аварийно: {error}',
      '[SUPERVISOR] Group task crashed: {0}',
    ),
    (
      '[ГРУППА #{group_id}] Задача воркера завершилась аварийно: {error}',
      '[GROUP #{0}] Worker task crashed: {1}',
    ),
    (
      '[ГРУППА #{group_id}][ВОРКЕР #{id}] Супервизор завершился аварийно: {error}',
      '[GROUP #{0}][WORKER #{1}] Supervisor crashed: {2}',
    ),
    (
      '[ГРУППА #{group_id}] Credentials недоступны или исчерпаны, группа остановлена',
      '[GROUP #{0}] Credentials unavailable or exhausted, group stopped',
    ),
    (
      '[КРЕД #{}] Запрос (хеш: {short_hash}...)',
      '[CRED #{0}] Request (hash: {1}...)',
    ),
    (
      '[TURN][RETRY] VK вернул прежний credential после 486; новый запрос через {:?}',
      '[TURN][RETRY] VK returned the same credential after 486; re-request in {0}',
    ),
    (
      '[КРЕД #{}] OK, TURN: {:?}, до {} воркеров',
      '[CRED #{0}] OK, TURN: {1}, up to {2} workers',
    ),
    (
      '[КРЕД #{}] Ошибка: {error:#}. Повторяем через {delay:?}...',
      '[CRED #{0}] Error: {1}. Retrying in {2}...',
    ),
    (
      '[КРЕД #{}] Невалиден, запрашиваем новый',
      '[CRED #{0}] Invalid, requesting a new one',
    ),
    (
      '[ВОРКЕР #{id}] Паника изолирована, перезапуск через 1 секунду: {error}',
      '[WORKER #{0}] Panic isolated, restart in 1 second: {1}',
    ),
    (
      '[ВОРКЕР #{id}] Задача отменена: {error}',
      '[WORKER #{0}] Task cancelled: {1}',
    ),
    (
      '[КОНФИГ] Все пути завершены; конфигурация будет запрошена при восстановлении',
      '[CONFIG] All paths finished; configuration will be re-requested on recovery',
    ),
    (
      '[ВОРКЕР #{id}] Repair: пересоздаём поток',
      '[WORKER #{0}] Repair: recreating the stream',
    ),
    (
      '[TURN][RETRY] Квота Allocate у креда #{}; обновляем cohort до {} потоков через {:?}',
      '[TURN][RETRY] Allocate quota on cred #{0}; updating cohort to {1} workers in {2}',
    ),
    (
      '[TURN][RETRY] Allocation mismatch у креда #{}; обновляем cohort',
      '[TURN][RETRY] Allocation mismatch on cred #{0}; updating cohort',
    ),
    (
      '[TURN][RETRY] Аутентификация у креда #{} исчерпана; обновляем cohort',
      '[TURN][RETRY] Auth exhausted on cred #{0}; updating cohort',
    ),
    (
      '[TURN][TCP] Удалённая сторона закрыла TCP-поток; восстановление через {:?}',
      '[TURN][TCP] Remote side closed the TCP stream; recovering in {0}',
    ),
    (
      '[СЕТЬ][RETRY] Локальный маршрут недоступен, {count} попыток восстановления',
      '[NET][RETRY] Local route unavailable, {0} recovery attempts',
    ),
    (
      '[ВОРКЕР #{id}] Критическая ошибка подключения, изолированный повтор через {:?}: {message}',
      '[WORKER #{0}] Critical connection error, isolated retry in {1}: {2}',
    ),
    (
      '[HANDSHAKE][RETRY] WRAP_AUTH_TIMEOUT, {count} попыток восстановления',
      '[HANDSHAKE][RETRY] WRAP_AUTH_TIMEOUT, {0} recovery attempts',
    ),
    (
      '[GETCONF][RETRY] Нет подтверждения, {count} попыток восстановления',
      '[GETCONF][RETRY] No confirmation, {0} recovery attempts',
    ),
    (
      '[TURN][RETRY] Нет ответа на служебную транзакцию, {count} попыток восстановления',
      '[TURN][RETRY] No answer to the service transaction, {0} recovery attempts',
    ),
    (
      '[TURN][RETRY] Allocate: нет ответа (вероятен потолок аллокаций VK TURN), {count} попыток восстановления',
      '[TURN][RETRY] Allocate: no answer (likely the VK TURN allocation cap), {0} recovery attempts',
    ),
    (
      '[ВОРКЕР #{id}] [TURN][RETRY] Попытка {attempt}: {message}',
      '[WORKER #{0}] [TURN][RETRY] Attempt {1}: {2}',
    ),
    (
      '[ВОРКЕР #{id}] Ошибка (попытка {attempt}): {message}',
      '[WORKER #{0}] Error (attempt {1}): {2}',
    ),
    (
      '[ВОРКЕР #{id}] [СЕТЬ][RETRY] Временная ошибка сокета, пересоздаём транспорт через {:?}: {message}',
      '[WORKER #{0}] [NET][RETRY] Temporary socket error, recreating transport in {1}: {2}',
    ),
    (
      '[ВОРКЕР #{id}] Сервер ограничил количество потоков (invalid_worker_count); воркер остановлен',
      '[WORKER #{0}] Server limited the number of streams (invalid_worker_count); worker stopped',
    ),
    (
      '[ВОРКЕР #{id}] Ошибка подключения, изолированный повтор через {:?}: {display_message}',
      '[WORKER #{0}] Connection error, isolated retry in {1}: {2}',
    ),
    // --- DNS (dns.rs) ---
    (
      '[DNS] Использован кэш для {host} (DNS недоступен)',
      '[DNS] Using cache for {0} (DNS unavailable)',
    ),
    (
      '[DNS] Ни один из DNS-серверов и системный резолвер не дали ответа для {host}.',
      '[DNS] No DNS servers or the system resolver responded for {0}.',
    ),
    // --- Direct relay (direct_relay.rs) ---
    (
      '[ДОМЕНЫ] Разрешено прямых IP (ЧС): {}',
      '[DOMAINS] Direct IPs allowed (blocklist): {0}',
    ),
    (
      '[ДОМЕНЫ] Предрезолвлен список БС для туннеля (доменов: {})',
      '[DOMAINS] Pre-resolved whitelist for tunnel (domains: {0})',
    ),
  ];

  static final List<({RegExp pattern, String template})> _rules = _entries
      .map(_buildRule)
      .toList();

  static final RegExp _placeholder = RegExp(r'\{[^{}]*\}');
  static final RegExp _slot = RegExp(r'\{(\d+)\}');

  static ({RegExp pattern, String template}) _buildRule(
    (String, String) entry,
  ) {
    final parts = entry.$1.split(_placeholder);
    final buffer = StringBuffer('^');
    for (var i = 0; i < parts.length; i++) {
      buffer.write(RegExp.escape(parts[i]));
      if (i < parts.length - 1) buffer.write('(.*?)');
    }
    buffer.write(r'$');
    return (
      pattern: RegExp(buffer.toString(), dotAll: true),
      template: entry.$2,
    );
  }

  /// Локализует строку ядра; null — правило не найдено.
  static String? localize(String line) {
    for (final rule in _rules) {
      final match = rule.pattern.firstMatch(line);
      if (match == null) continue;
      final groups = [
        for (var i = 1; i <= match.groupCount; i++) match.group(i),
      ];
      return rule.template.replaceAllMapped(
        _slot,
        (m) => groups[int.parse(m.group(1)!)] ?? '',
      );
    }
    return null;
  }

  /// Для guard-теста: покрыт ли пробный образец строки ядра словарём.
  static bool covers(String sample) => localize(sample) != null;
}
