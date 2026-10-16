#!/usr/bin/env bash
# 从 README 的「更新记录」里取出某个 tag 对应的条目，供 release 工作流拼进 Release 正文。
# 顺带校验 tag 与 build.sh 里的 VERSION_NAME 一致：标签打错时宁可在这里失败，
# 也不要把版本号对不上的包发出去。
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${1:-}"
if [ -z "$TAG" ]; then
    echo "usage: $0 <tag>  (e.g. v0.1.5)" >&2
    exit 2
fi

VERSION="${TAG#v}"
BUILD_VERSION=$(awk -F'"' '/^VERSION_NAME="/ { print $2; exit }' build.sh)
if [ -z "$BUILD_VERSION" ]; then
    echo "error: VERSION_NAME not found in build.sh" >&2
    exit 1
fi
if [ "$VERSION" != "$BUILD_VERSION" ]; then
    echo "error: tag $TAG does not match VERSION_NAME=$BUILD_VERSION in build.sh" >&2
    exit 1
fi

# 取该 tag 那条记录，直到下一条 "- **" 或下一个标题为止；条目写成多行也能完整带出。
# 先 tr 掉 \r：Windows 上检出时 README.md 可能是 CRLF。
ENTRY=$(tr -d '\r' < README.md | awk -v tag="$TAG" '
    /^#+ / { if (found) exit }
    found && /^- \*\*/ { exit }
    $0 ~ "^[-] \\*\\*" tag "\\*\\*" { found = 1 }
    found { print }
')
if [ -z "$ENTRY" ]; then
    echo "error: no changelog entry for $TAG in README.md; add a \"- **$TAG**：...\" line under 更新记录" >&2
    exit 1
fi

printf '%s\n' "$ENTRY"
