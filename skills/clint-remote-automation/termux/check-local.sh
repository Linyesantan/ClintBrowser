#!/data/data/com.termux/files/usr/bin/bash
# 在 Termux 里检查 Clint 接口是否在本机可用（打洞前先跑一下）。
#   CLINT_TOKEN=<TOKEN> bash check-local.sh
PORT="${CLINT_PORT:-9333}"
curl -s "http://127.0.0.1:${PORT}/ping"; echo
[ -n "${CLINT_TOKEN:-}" ] && curl -s -H "Authorization: Bearer ${CLINT_TOKEN}" "http://127.0.0.1:${PORT}/tabs"; echo
