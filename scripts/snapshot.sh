#!/usr/bin/env bash
#
# Снапшот рабочего дерева: полностью восстановимая копия того, что сейчас на
# диске — включая незакоммиченные правки, новые файлы и то, что в git не
# попадает (prompts/, config.properties). Нужен, чтобы любую работу можно было
# откатить, даже если кто-то (агент или человек) что-то перезапишет.
#
# Использование:
#   bash scripts/snapshot.sh                # имя по времени
#   bash scripts/snapshot.sh перед-подпиской # имя по времени + метка
#   bash scripts/snapshot.sh --list         # что уже есть
#
# Куда кладёт: <репозиторий>/.snapshots/<дата-время>[-метка]/
# Внутри:
#   worktree.tar.gz  — всё дерево (кроме build/.gradle/.git/.snapshots/data/logs)
#   HEAD.txt         — ветка, коммит, время
#   git-status.txt   — какие файлы изменены/новые
#   git-diff.patch   — правки отслеживаемых файлов
#   MANIFEST.txt     — список файлов в архиве
#
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SNAPDIR="$REPO/.snapshots"

if [ "${1:-}" = "--list" ]; then
    if [ ! -d "$SNAPDIR" ]; then
        echo "Снапшотов ещё нет."
        exit 0
    fi
    echo "Снапшоты (новые сверху):"
    ls -1t "$SNAPDIR" | while read -r name; do
        size=$(du -sh "$SNAPDIR/$name" 2>/dev/null | cut -f1)
        label=$(sed -n 's/^label: //p' "$SNAPDIR/$name/HEAD.txt" 2>/dev/null | head -1)
        echo "  $name  ($size)${label:+  — $label}"
    done
    exit 0
fi

LABEL="${1:-}"
STAMP="$(date +%Y%m%d-%H%M%S)"
NAME="$STAMP"
[ -n "$LABEL" ] && NAME="$STAMP-$LABEL"
DEST="$SNAPDIR/$NAME"

mkdir -p "$DEST"

# Что не кладём в архив: тяжёлое и восстановимое (сборка, кэши, рантайм-данные).
EXCLUDES=(
    --exclude="./build"
    --exclude="./.gradle"
    --exclude="./.git"
    --exclude="./.snapshots"
    --exclude="./data"
    --exclude="./logs"
    --exclude="./cache"
    --exclude="./session"
    --exclude="./.idea"
    --exclude="./node_modules"
)

# Архив дерева. Путь внутри архива относительный (./...), чтобы распаковывать в корень.
tar -czf "$DEST/worktree.tar.gz" -C "$REPO" "${EXCLUDES[@]}" . 2>/dev/null

# Провенанс: где мы в истории и что менялось.
{
    echo "label: ${LABEL:-}"
    echo "date: $(date -Iseconds)"
    echo "branch: $(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
    echo "commit: $(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo '?')"
    echo "commit_subject: $(git -C "$REPO" log -1 --pretty=%s 2>/dev/null || echo '?')"
} > "$DEST/HEAD.txt"

git -C "$REPO" status --short > "$DEST/git-status.txt" 2>/dev/null || true
# diff только отслеживаемых файлов; новые файлы уже лежат в архиве
git -C "$REPO" diff --binary > "$DEST/git-diff.patch" 2>/dev/null || true
git -C "$REPO" diff --cached --binary > "$DEST/git-diff-staged.patch" 2>/dev/null || true

# Манифест: что именно сохранено, чтобы можно было проверить целостность.
tar -tzf "$DEST/worktree.tar.gz" > "$DEST/MANIFEST.txt" 2>/dev/null || true

COUNT=$(wc -l < "$DEST/MANIFEST.txt" 2>/dev/null || echo 0)
SIZE=$(du -sh "$DEST" 2>/dev/null | cut -f1)
echo "Снапшот готов: .snapshots/$NAME ($COUNT файлов, $SIZE)"
echo "Восстановить: bash scripts/restore-snapshot.sh $NAME"
