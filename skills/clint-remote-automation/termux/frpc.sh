#!/data/data/com.termux/files/usr/bin/bash
# 用 frpc 把 Clint 本机接口转发到你的 frps 服务器（配置见 frpc.toml.example）。
set -eu
CONF="${1:-$HOME/.config/clint/frpc.toml}"
command -v frpc >/dev/null || { echo "请先安装 frpc: pkg install frp"; exit 1; }
[ -f "$CONF" ] || { echo "找不到配置 $CONF，请参考 frpc.toml.example"; exit 1; }
termux-wake-lock 2>/dev/null || true
exec frpc -c "$CONF"
