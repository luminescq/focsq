#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-FileCopyrightText: 2026 luminescq
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# Лаунчер FOCSQ (Linux) — «распаковал бандл и запустил» без полной
# установки, при этом со всей интеграцией в систему.
#
# Что делает при каждом запуске (быстрые проверки; если всё в порядке —
# ни одного вопроса):
#   1. ldd по бинарнику бандла — все ли системные библиотеки на месте
#      (webkit2gtk — капча/вход ВК, libsecret — токен ВК,
#      ayatana-appindicator — трей).
#   2. Если чего-то нет — определяет дистрибутив, показывает список
#      пакетов и ставит их (pkexec — графовый запрос пароля, как UAC).
#   3. Право TUN: setcap cap_net_admin+ep на обёртку focsq-tun (НЕ на
#      бинарник: file capability переводит процесс в AT_SECURE, и
#      glibc перестаёт разворачивать $ORIGIN в RUNPATH — приложение
#      не находит собственные lib/*.so). Обёртка поднимает капу в
#      ambient-набор и exec'ает бинарник: тот стартует уже с правом,
#      оставаясь «обычным» процессом. Capability может слететь (cp не
#      переносит xattr security.capability) — потому проверяем каждый
#      запуск, а не один раз.
#   4. Первый запуск (или после переноса папки): предлагает добавить
#      FOCSQ в список приложений — .desktop + иконки hicolor в
#      ~/.local/share (без root), кэши иконок обновляет.
#   5. Запускает бинарник (exec — выше уже всё проверено).
#
# Режимы:
#   (без аргументов)  проверка + запуск
#   --install         «полная установка»: то же, но без запуска
#   --uninstall       убрать .desktop и иконки из ~/.local/share
#   --check           молчаливая диагностика: exit 0 — всё в порядке
#   --minimized       пробрасывается приложению (автозапуск из XDG);
#                     в этом режиме скрипт НЕ спрашивает ничего и НЕ
#                     трогает root — только журнал и запуск
#   прочие аргументы  пробрасываются приложению как есть
#
# Запуск через лаунчер отмечается переменной FOCSQ_LAUNCHER=1 —
# диагностический отчёт показывает, выполнялись ли проверки (прямой
# запуск бинарника из бандла их пропускает).
#
# Инженерное замечание: setcap на файле в домашнем каталоге — паттерн
# всех десктопных VPN (по-другому cap_net_admin обычному пользователю
# не выдать); capability не даёт root, только управление сетевыми
# интерфейсами и маршрутами.

set -u

APP_NAME='FOCSQ'
BINARY_NAME='focsq'
APP_ID='app.focsq.focsq'
# Обёртка запуска с правом TUN (см. ensure_caps). В dev-сборке без
# лаунчера её может не быть — тогда стартуем бинарник напрямую.
WRAPPER_NAME='focsq-tun'

# --- самоопределение (работает из любого cwd, через симлинки и пробелы) ---
SELF="$(readlink -f "${BASH_SOURCE[0]}")" || {
  echo 'focsq.sh: не смог разрешить собственный путь' >&2
  exit 1
}
BUNDLE_DIR="$(dirname "$SELF")"
BIN="$BUNDLE_DIR/$BINARY_NAME"
WRAPPER="$BUNDLE_DIR/$WRAPPER_NAME"

INTERACTIVE=1        # можно спрашивать пользователя
MODE='run'

case "${1:-}" in
  --install)   MODE='install';   shift ;;
  --uninstall) MODE='uninstall'; shift ;;
  --check)     MODE='check';     shift ;;
esac

# Автозапуск XDG стартует приложение без терминала и до полного
# десктопа: никаких вопросов и pkexec — только журнал и запуск.
case " ${*:-} " in
  *' --minimized '*) INTERACTIVE=0 ;;
esac

LOG_FILE="${XDG_STATE_HOME:-"${HOME:-/tmp}/.local/state"}/focsq/launcher.log"

# --- маленькие помощники ----------------------------------------------------

log() { # всегда в файл, на экран — только при терминале
  local line
  line="$(date '+%Y-%m-%d %H:%M:%S') $*"
  mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
  printf '%s\n' "$line" >> "$LOG_FILE" 2>/dev/null || true
  [ -t 2 ] && printf '%s\n' "$*" >&2
  return 0
}

have_cmd() { command -v "$1" >/dev/null 2>&1; }

die() { # финальный провал: видно и в терминале, и окном zenity
  log "ОШИБКА: $*"
  if [ "$INTERACTIVE" = 1 ] && have_cmd zenity; then
    zenity --error --title="$APP_NAME" --text="$*" 2>/dev/null || true
  fi
  exit 1
}

ask_yesno() { # 0 — да, 1 — нет; из терминала — read, из GUI — zenity
  if [ -t 0 ]; then
    local answer=''
    read -r -p "$1 [y/N]: " answer || answer='n'
    case "$answer" in
      y|Y|yes|Yes|д|Д) return 0 ;;
      *) return 1 ;;
    esac
  elif have_cmd zenity; then
    zenity --question --title="$APP_NAME" --text="$1" \
      --ok-label='Да' --cancel-label='Нет' 2>/dev/null
  else
    return 1 # негде спросить — считаем отказом
  fi
}

as_root() { # as_root <команда...> — pkexec (графовый пароль), запасной sudo
  if have_cmd pkexec; then
    pkexec "$@"
  elif have_cmd sudo; then
    sudo "$@"
  else
    log "нет pkexec/sudo для: $*"
    return 1
  fi
}

flat() { # многострочный список → строка через запятую (для сообщений)
  printf '%s' "$*" | tr '\n' ' ' | sed 's/ \+/ /g; s/ $//; s/ /, /g'
}

# --- 1. системные библиотеки --------------------------------------------------

# Отсутствующие soname: ldd ТОЛЬКО по бинарнику бандла, не по lib/*.so.
# Все CMake-плагины (webkit2gtk, libsecret, appindicator, трей) линкуются
# с бинарником напрямую (generated_plugins.cmake → target_link_libraries)
# и цепочка их зависимостей видна через ldd самого бинарника.
# ldd поверх lib/*.so даёт ложные «not found» на живых системах:
#   - libflutter_linux_gtk.so: у плагинных .so нет собственного RPATH
#     (движок резолвится рантаймом через уже загруженный бинарником
#     soname — ldd об этом не знает);
#   - libjvm.so: libdartjni.so (транзитивный jni-плагин от
#     path_provider_android) линкуется на сборщике с JDK; FOCSQ JNI не
#     использует, на десктопе эту библиотеку никто не грузит. В новых
#     сборках её в бандле нет (CMAKE_DISABLE_FIND_PACKAGE_JNI), старые —
#     тоже должны проходить проверку.
# Вывод: по одному soname в строку.
missing_libs() {
  ldd "$BIN" 2>/dev/null \
    | awk '$2=="=>" && $3=="not" && $4=="found" {print $1}' \
    | sort -u
}

detect_pm() { # apt | dnf | pacman | zypper | ''
  local pm
  for pm in apt-get dnf pacman zypper; do
    have_cmd "$pm" && { printf '%s\n' "$pm"; return 0; }
  done
  return 1
}

# Рантайм-пакеты FOCSQ по менеджерам (webkit2gtk-4.1 — вход ВК/капча,
# libsecret — токен ВК, ayatana-appindicator — трей). Прочие soname
# (gtk, glib...) приезжают транзитивно или уже есть от десктопа.
pm_packages() {
  case "$1" in
    apt)     printf '%s\n' 'libwebkit2gtk-4.1-0' 'libsecret-1-0' 'libayatana-appindicator3-1' ;;
    apt-get) printf '%s\n' 'libwebkit2gtk-4.1-0' 'libsecret-1-0' 'libayatana-appindicator3-1' ;;
    dnf)     printf '%s\n' 'webkit2gtk4.1' 'libsecret' 'libayatana-appindicator-gtk3' ;;
    pacman)  printf '%s\n' 'webkit2gtk-4.1' 'libsecret' 'libayatana-appindicator' ;;
    # имена zypper не выверены по живой системе — при провале установки
    # покажем soname-список для ручной установки/репорта
    zypper)  printf '%s\n' 'libwebkit2gtk-4_1-0' 'libsecret-1-0' 'libayatana-appindicator3-1' ;;
    *)       return 1 ;;
  esac
}

pacman_dbdir() { # каталог базы pacman (DBPath из pacman.conf)
  pacman-conf DBPath 2>/dev/null || printf '%s\n' '/var/lib/pacman'
}

install_deps() { # $1 — менеджер, $2 — soname-список (для диагностики)
  local pm="$1" missing="$2"
  local pkgs
  pkgs="$(pm_packages "$pm")" || pkgs=''
  if [ -n "$pkgs" ]; then
    log "установка пакетов ($pm): $(flat "$pkgs")"
    case "$pm" in
      apt|apt-get) as_root apt-get install -y $pkgs ;;
      dnf)         as_root dnf install -y $pkgs ;;
      pacman)
        local dbdir sync
        dbdir="$(pacman_dbdir)"; dbdir="${dbdir%/}"
        # База заблокирована: работает pacman/pamac (центр обновлений
        # Manjaro делает фоновую проверку) или прошлый запуск оборвался.
        if [ -e "$dbdir/db.lck" ]; then
          log "pacman: база заблокирована ($dbdir/db.lck)"
          if [ "$INTERACTIVE" = 1 ] && have_cmd zenity; then
            zenity --warning --title="$APP_NAME" \
              --text="База пакетов занята: работает pacman/pamac или прошлый запуск прерван.
Закрой центр обновлений и запусти FOCSQ снова.
Если точно ничего не устанавливается — удали $dbdir/db.lck" \
              2>/dev/null || true
          fi
          return 1
        fi
        # Live-ISO/свежая система: репозитории ещё не синхронизированы —
        # «target not found»; добавляем -y (обновить базу перед установкой).
        sync=''
        [ -z "$(ls -A "$dbdir/sync" 2>/dev/null)" ] && sync='-y'
        as_root pacman --noconfirm -S $sync $pkgs ;;
      zypper)      as_root zypper --non-interactive install $pkgs ;;
    esac
    if [ $? -eq 0 ] && [ -z "$(missing_libs)" ]; then
      log 'библиотеки на месте'
      return 0
    fi
  fi
  # Не осилили автоматически — пользователю нужен список, а не «ошибка».
  log "остались недоступные библиотеки: $(flat "$missing")"
  if [ "$INTERACTIVE" = 1 ] && have_cmd zenity; then
    zenity --warning --title="$APP_NAME" \
      --text="Не удалось поставить недостающее автоматически.
Установи вручную (библиотеки: $(flat "$missing"))
или приложи launcher.log к багрепорту." \
      2>/dev/null || true
  fi
  case "$missing" in
    *libgtk-3*|*libgtk-4*)
      log 'похоже, система без графического окружения — GUI-приложению нужен рабочий стол'
      ;;
  esac
  return 1
}

ensure_deps() { # 0 — библиотеки есть; 1 — нет (запускать бессмысленно)
  local missing pm pkgs
  missing="$(missing_libs)"
  [ -z "$missing" ] && return 0
  log "нет библиотек: $(flat "$missing")"
  [ "$INTERACTIVE" = 0 ] && return 1

  pm="$(detect_pm)" || { install_deps '' "$missing"; return 1; }
  pkgs="$(pm_packages "$pm" 2>/dev/null)" || pkgs=''
  if ask_yesno "$APP_NAME требует системные библиотеки (вход ВК, трей, хранилище токена):
$( [ -n "$pkgs" ] && printf '%s' "$(flat "$pkgs")" )
Установить сейчас?"; then
    install_deps "$pm" "$missing"
  else
    log 'пользователь отказался от установки библиотек'
    return 1
  fi
}

# --- 2. право на TUN (через обёртку focsq-tun) --------------------------------
#
# Почему право на обёртку, а не на бинарник: file capability на
# приложении переводит его процесс в AT_SECURE (secure-execution),
# и glibc в этом режиме НЕ разворачивает $ORIGIN в RUNPATH — бинарник
# не находит собственные lib/*.so и падает на старте («cannot open
# shared object file: libdesktop_webview_window_plugin.so», живая
# Manjaro 2026-09-03). Обёртка focsq-tun (крошечный C-exe из бандла)
# поднимает cap_net_admin в ambient-набор и exec'ает бинарник:
# capability наследуется, AT_SECURE не ставится, $ORIGIN работает.

has_wrapper() { [ -x "$WRAPPER" ]; }

# 0 — есть; 1 — нет; 2 — не знаем (нет getcap)
has_cap_net_admin() {
  have_cmd getcap || return 2
  getcap "$WRAPPER" 2>/dev/null | grep -q 'cap_net_admin'
  return $?  # grep: 0 — найдено, 1 — нет
}

grant_caps() {
  log "setcap cap_net_admin+ep $WRAPPER"
  if as_root setcap cap_net_admin+ep "$WRAPPER"; then
    log 'право TUN выдано (обёртка focsq-tun)'
    return 0
  fi
  # Частая причина: бандл на FS без xattr (FUSE, сетевые каталоги) или
  # root не видит путь (например ~/ на шифрованном разделе).
  log 'setcap не удался'
  if [ "$INTERACTIVE" = 1 ] && have_cmd zenity; then
    zenity --error --title="$APP_NAME" \
      --text="Не удалось выдать право TUN (setcap).
Обычная причина — бандл лежит на разделе без поддержки xattr (сетевые/FUSE-каталоги).
Перенеси папку FOCSQ в домашний каталог и запусти снова." \
      2>/dev/null || true
  fi
  return 1
}

ensure_caps() {
  # Без обёртки (dev-сборка) право проверять/выдавать некуда: бинарник
  # запустится напрямую, туннель без права откажет с понятной ошибкой.
  has_wrapper || { log 'focsq-tun нет в бандле — запуск без права TUN'; return 0; }
  local rc
  has_cap_net_admin; rc=$?
  [ "$rc" -eq 0 ] && return 0
  if [ "$rc" -eq 2 ]; then
    # getcap нет (редко): пропускаем — ядро само скажет при коннекте,
    # отчёт диагностики покажет фактические CapEff.
    log 'getcap недоступен — пропуск проверки права TUN'
    return 0
  fi
  if [ "$INTERACTIVE" = 0 ]; then
    log 'нет cap_net_admin (автозапуск молчит — запусти FOCSQ из меню для починки)'
    return 1
  fi
  if ask_yesno "Туннель требует право управления сетевыми интерфейсами (cap_net_admin).
Выдать сейчас? (запрос пароля)"; then
    grant_caps
  else
    log 'пользователь отказался от setcap — туннель не поднимется'
    return 1
  fi
}

# --- 3. список приложений (без root) -----------------------------------------

xdg_data_home() { printf '%s\n' "${XDG_DATA_HOME:-"${HOME:-}/.local/share"}"; }

desktop_file() { printf '%s\n' "$(xdg_data_home)/applications/$APP_ID.desktop"; }

is_integrated() { # .desktop существует И указывает на этот бандл
  local file
  file="$(desktop_file)"
  # [FOCSQ] Exec ведёт на бинарник этого бандла (не на скрипт).
  [ -f "$file" ] && grep -qF "$BIN" "$file" 2>/dev/null
}

integrate_desktop() {
  local data_home apps_dir icons_dir src_icons
  data_home="$(xdg_data_home)"
  apps_dir="$data_home/applications"
  icons_dir="$data_home/icons"
  mkdir -p "$apps_dir" "$icons_dir/hicolor" || return 1

  # [FOCSQ] Exec — сам бинарник: он самодостаточная точка входа
  # (при старте сам перезапускается через обёртку focsq-tun, право
  # при отсутствии предложит выдать pkexec-диалогом при подключении).
  # Скрипт остаётся установщиком первого запуска и диагностикой.
  cat > "$apps_dir/$APP_ID.desktop" <<ENTRY
[Desktop Entry]
Type=Application
Name=$APP_NAME
Comment=QUIC-туннель с профилями серверов
Exec="$BIN"
Icon=focsq
Terminal=false
Categories=Network;System;
# prgname GTK-раннера = app.focsq.focsq: по нему GNOME/KDE матчат окно
# с пунктом меню (иначе иконка в доке «теряется» после старта)
StartupWMClass=$APP_ID
ENTRY

  # иконки бандла → hicolor пользователя (кэш обновим ниже)
  src_icons="$BUNDLE_DIR/data/icons/hicolor"
  if [ -d "$src_icons" ]; then
    cp -a "$src_icons/." "$icons_dir/hicolor/" || return 1
  fi

  have_cmd update-desktop-database && update-desktop-database "$apps_dir" 2>/dev/null || true
  have_cmd gtk-update-icon-cache && gtk-update-icon-cache -q -t -f "$icons_dir/hicolor" 2>/dev/null || true
  log "пункт меню и иконки установлены: $apps_dir/$APP_ID.desktop"
}

uninstall_desktop() {
  local file icons_dir d
  file="$(desktop_file)"
  rm -f "$file"
  icons_dir="$(xdg_data_home)/icons"
  for d in "$icons_dir"/hicolor/*x*/apps; do
    [ -d "$d" ] && rm -f "$d/$BINARY_NAME.png"
  done
  have_cmd gtk-update-icon-cache && gtk-update-icon-cache -q -t -f "$icons_dir/hicolor" 2>/dev/null || true
  log "пункт меню и иконки удалены ($file)"
}

ensure_desktop() {
  is_integrated && return 0
  [ "$INTERACTIVE" = 0 ] && return 0
  if ask_yesno "Добавить $APP_NAME в список приложений?
(иконка и пункт меню; убрать — ./focsq.sh --uninstall)"; then
    integrate_desktop || log 'интеграция в меню не удалась (продолжаем)'
  else
    log 'пользователь отказался от пункта меню'
  fi
}

# --- режимы --------------------------------------------------------------------

[ -x "$BIN" ] || die "бинарник не найден: $BIN"

case "$MODE" in
  uninstall)
    uninstall_desktop
    printf '%s\n' "Удалены пункт меню и иконки из $(xdg_data_home) (бандл и настройки не тронуты)."
    exit 0
    ;;
  check)
    problems=0
    missing="$(missing_libs)"
    if [ -n "$missing" ]; then
      printf '%s\n' 'НЕТ библиотек:' $missing
      problems=1
    fi
    if has_wrapper; then
      rc=0
      has_cap_net_admin; rc=$?
      if [ "$rc" -eq 0 ]; then
        printf '%s\n' 'cap_net_admin: ок (обёртка focsq-tun)'
      elif [ "$rc" -eq 2 ]; then
        printf '%s\n' 'cap_net_admin: неизвестно (нет getcap)'
      else
        printf '%s\n' 'cap_net_admin: НЕТ — туннель не поднимется'
        problems=1
      fi
    else
      printf '%s\n' 'focsq-tun: нет в бандле — запуск будет без права TUN'
    fi
    if [ -n "$(getcap "$BIN" 2>/dev/null)" ]; then
      printf '%s\n' 'ВНИМАНИЕ: на бинарнике остался setcap (ломает запуск, $ORIGIN) — ./focsq.sh уберёт при запуске'
    fi
    if is_integrated; then
      printf '%s\n' 'пункт меню: есть'
    else
      printf '%s\n' 'пункт меню: нет (--install добавит)'
    fi
    exit "$problems"
    ;;
esac

log "--- запуск (mode=$MODE, args: ${*:-}) ---"

# Библиотеки — блокирующее условие: без них бинарник не загрузится.
ensure_deps || die 'Не хватает системных библиотек — подробности в launcher.log'

# Миграция со старой схемы (setcap на бинарнике — ломал запуск через
# AT_SECURE/$ORIGIN): снять, право живёт на обёртке focsq-tun.
if [ -n "$(getcap "$BIN" 2>/dev/null)" ] && have_cmd setcap; then
  log 'снимаю старый setcap с бинарника (право теперь на focsq-tun)'
  if ! as_root setcap -r "$BIN"; then
    log 'не удалось снять setcap с бинарника — запуск может сломаться (старое право мешает $ORIGIN)'
  fi
fi

# Право и меню — нет: ядро само диагностирует отсутствие права,
# отчёт покажет детали.
ensure_caps   || true
ensure_desktop || true

if [ "$MODE" = 'install' ]; then
  printf '%s\n' "Готово. Пункт меню — «$APP_NAME», бинарник — $BIN"
  printf '%s\n' '(бандл можно переносить: пункт меню обновится при следующем запуске)'
  exit 0
fi

# Отметка для приложения (диагностический отчёт показывает это)
export FOCSQ_LAUNCHER=1
# Запуск: через обёртку focsq-tun, если она есть и право выдано —
# приложение унаследует cap_net_admin (ambient) без AT_SECURE-ломки
# $ORIGIN. Без обёртки/права — напрямую: туннель откажет с понятной
# ошибкой, GUI работает.
if has_wrapper && has_cap_net_admin; then
  log "запуск через focsq-tun: $WRAPPER $BIN $*"
  exec "$WRAPPER" "$BIN" "$@"
fi
exec "$BIN" "$@"
