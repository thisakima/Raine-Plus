#!/usr/bin/env bash
#
# Восстановление рабочего дерева из снапшота.
#
# ВАЖНО: перед восстановлением сам делает снапшот текущего состояния, чтобы
# откат тоже можно было откатить. Ничего не удаляет из .git.
#
# Использование:
#   bash scripts/restore-snapshot.sh --list
#   bash scripts/restore-snapshot.sh latest
#   bash scripts/restore-snapshot.sh 20261114-120000
#   bash scripts/restore-snapshot.sh 20261114-120000 --only src/main/java
#
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SNAPDIR="$REPO/.snapshots"

if [ "${1:-}" = "--list" ] || [ $# -eq 0 ]; then
    bash "$REPO/scripts/snapshot.sh" --list
    exit 0
fi

WANT="$1"
shift || true
ONLY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --only) ONLY="${2:-}"; shift 2 ;;
        *) shift ;;
    esac
done

if [ ! -d "$SNAPDIR" ]; then
    echo "Снапшотов нет: $SNAPDIR"
    exit 1
fi

if [ "$WANT" = "latest" ]; then
    NAME="$(ls -1t "$SNAPDIR" | head -1)"
else
    NAME="$WANT"
fi

SRC="$SNAPDIR/$NAME"
if [ ! -f "$SRC/worktree.tar.gz" ]; then
    echo "Нет такого снапшота: $NAME"
    echo "Есть:"; ls -1t "$SNAPDIR" | head -10
    exit 1
fi

echo "Перед откатом сохраняю текущее состояние (на случай ошибки):"
bash "$REPO/scripts/snapshot.sh" "before-restore-$NAME" >/dev/null
echo "  готово"
echo

echo "Восстанавливаю из $NAME ..."
if [ -n "$ONLY" ]; then
    # Частичный откат: только указанный префикс.
    tar -xzf "$SRC/worktree.tar.gz" -C "$REPO" "$ONLY" 2>/dev/null
    echo "Восстановлено только: $ONLY"
else
    tar -xzf "$SRC/worktree.tar.gz" -C "$REPO" 2>/dev/null
    echo "Восстановлено всё дерево."
fi

echo
echo "Готово. Проверить: git status --short | head"
echo "  (правки в отслеживаемых файлах появились как изменения — это ожидаемо)"
