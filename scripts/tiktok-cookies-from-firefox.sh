#!/usr/bin/env bash
#
# Перенос входа в TikTok из обычного Firefox в профиль браузера бота.
#
# Зачем: TikTok не пускает вход в автоматизированном окне Playwright
# («слишком много попыток» с первой же попытки, на любой способ).
#
# Обычный путь теперь проще и делает всё сам:
#     ./gradlew run --args="browser-login https://www.tiktok.com"
# Он откроет окно настоящего Firefox с отдельным профилем, вы войдёте, и куки
# перенесутся в её профиль автоматически.
#
# Этот скрипт — запасной путь: войти в своём личном Firefox и выгрузить куки
# файлом, если автоматический перенос не сработал.
#
# Куки берём прямо из базы Firefox (cookies.sqlite) через sqlite3: так мы
# гарантированно получаем и httpOnly-куки (sessionid, sid_tt и т.п.) — именно
# их расширения-экспортёры часто теряют, а без них вход не переносится.
#
# Как пользоваться:
#   1. Открой Firefox, зайди на https://www.tiktok.com и войди в аккаунт.
#   2. Закрой Firefox (не обязательно, но так база точно не занята).
#   3. bash scripts/tiktok-cookies-from-firefox.sh
#   4. ./gradlew run --args="tiktok-login"   — вольёт куки в профиль бота.
#
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/data/browser/cookies.txt"

# Firefox (и его форки) держат профиль в разных местах: старый путь
# ~/.mozilla/firefox, новый XDG-путь ~/.config/mozilla/firefox (именно так на
# свежих Firefox в Arch), flatpak, а также LibreWolf/Waterfox/Floorp. Ищем во всех.
CANDIDATE_DIRS=(
    "$HOME/.mozilla/firefox"
    "$HOME/.config/mozilla/firefox"
    "$HOME/.var/app/org.mozilla.firefox/.mozilla/firefox"
    "$HOME/.librewolf"
    "$HOME/.config/librewolf"
    "$HOME/.waterfox"
    "$HOME/.config/waterfox"
    "$HOME/.floorp"
    "$HOME/.config/floorp"
)

if ! command -v sqlite3 >/dev/null 2>&1; then
    echo "Нужен sqlite3. На Arch: sudo pacman -S sqlite"
    exit 1
fi

SEARCH_DIRS=()
for d in "${CANDIDATE_DIRS[@]}"; do
    [ -d "$d" ] && SEARCH_DIRS+=("$d")
done

if [ "${#SEARCH_DIRS[@]}" -eq 0 ]; then
    echo "Не нашёл ни одного профиля Firefox (искал в ~/.mozilla и ~/.config/mozilla)."
    echo "Сначала открой Firefox, войди в TikTok, потом повтори этот скрипт."
    exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ищем профиль, в котором реально есть куки TikTok (их может быть несколько)
BEST=""
BEST_COUNT=0
while IFS= read -r DB; do
    [ -f "$DB" ] || continue
    cp -f "$DB" "$TMP/c.sqlite"
    [ -f "$DB-wal" ] && cp -f "$DB-wal" "$TMP/c.sqlite-wal"
    [ -f "$DB-shm" ] && cp -f "$DB-shm" "$TMP/c.sqlite-shm"
    C=$(sqlite3 "$TMP/c.sqlite" "SELECT count(*) FROM moz_cookies WHERE host LIKE '%tiktok%';" 2>/dev/null || echo 0)
    if [ "${C:-0}" -gt "$BEST_COUNT" ]; then
        BEST="$DB"
        BEST_COUNT="$C"
    fi
    rm -f "$TMP/c.sqlite" "$TMP/c.sqlite-wal" "$TMP/c.sqlite-shm"
done < <(find "${SEARCH_DIRS[@]}" -maxdepth 2 -name cookies.sqlite 2>/dev/null)

if [ "$BEST_COUNT" -eq 0 ]; then
    echo "В Firefox нет кук TikTok."
    echo "Открой Firefox, зайди на https://www.tiktok.com, войди в аккаунт — и повтори скрипт."
    exit 1
fi

echo "Профиль Firefox с входом в TikTok: $BEST ($BEST_COUNT кук)"

# копируем базу (и WAL) в temp, чтобы читать даже если Firefox открыт
cp -f "$BEST" "$TMP/c.sqlite"
[ -f "$BEST-wal" ] && cp -f "$BEST-wal" "$TMP/c.sqlite-wal"
[ -f "$BEST-shm" ] && cp -f "$BEST-shm" "$TMP/c.sqlite-shm"

mkdir -p "$(dirname "$OUT")"

# формат Netscape (столбцы через табуляцию), httpOnly-строки с префиксом
# #HttpOnly_ — именно его понимает команда tiktok-login
sqlite3 "$TMP/c.sqlite" > "$OUT" <<'SQL'
.mode tabs
SELECT
  CASE WHEN isHttpOnly=1 THEN '#HttpOnly_' || host ELSE host END,
  CASE WHEN host LIKE '.%' THEN 'TRUE' ELSE 'FALSE' END,
  path,
  CASE WHEN isSecure=1 THEN 'TRUE' ELSE 'FALSE' END,
  expiry,
  name,
  value
FROM moz_cookies
WHERE host LIKE '%tiktok%'
ORDER BY host, name;
SQL

COUNT=$(grep -c . "$OUT" 2>/dev/null || echo 0)
echo "Готово: выгрузил $COUNT кук TikTok -> $OUT"
echo
echo "Теперь запусти:  ./gradlew run --args=\"tiktok-login\""
echo "Он вольёт эти куки в профиль бота (data/browser/profile) и проверит вход."
