#!/data/data/com.termux/files/usr/bin/bash
# 用 cloudflared 快速隧道把 Clint 的本机接口暴露到公网（无需账号、无需域名）。
# Quick tunnel: exposes 127.0.0.1:${CLINT_PORT:-9333} at a random https://*.trycloudflare.com URL.
#   pkg install cloudflared
#   bash cloudflared-quick.sh
# 输出里出现的 https://xxxx.trycloudflare.com 就是对外地址，配合访问令牌使用。
set -eu
PORT="${CLINT_PORT:-9333}"
ADDR="${CLINT_ADDR:-127.0.0.1}"  # 隧道跑在另一台设备上时，填 Clint 手机的局域网地址
command -v cloudflared >/dev/null || { echo "请先执行: pkg install cloudflared"; exit 1; }
termux-wake-lock 2>/dev/null || true
exec cloudflared tunnel --no-autoupdate --url "http://${ADDR}:${PORT}"
