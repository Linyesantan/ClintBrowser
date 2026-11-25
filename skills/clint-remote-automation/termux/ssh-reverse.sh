#!/data/data/com.termux/files/usr/bin/bash
# SSH 反向隧道：把手机上的 127.0.0.1:9333 映射到你自己服务器的 <REMOTE_PORT>。
# SSH reverse tunnel with auto-reconnect (uses autossh if installed, else a retry loop).
#   pkg install openssh autossh
#   ssh-keygen -t ed25519 && ssh-copy-id -p <SSH_PORT> <USER>@<HOST>
#   REMOTE=<USER>@<HOST> REMOTE_PORT=<REMOTE_PORT> SSH_PORT=22 bash ssh-reverse.sh
# 服务器上即可访问: curl -H "Authorization: Bearer <TOKEN>" http://127.0.0.1:<REMOTE_PORT>/tabs
# 默认只绑定服务器的 127.0.0.1；如需对外开放请自行加反向代理/TLS，而不是改成 0.0.0.0。
set -u
: "${REMOTE:?请设置 REMOTE=<USER>@<HOST>}"
REMOTE_PORT="${REMOTE_PORT:-19333}"
SSH_PORT="${SSH_PORT:-22}"
LOCAL_PORT="${CLINT_PORT:-9333}"
LOCAL_ADDR="${CLINT_ADDR:-127.0.0.1}"  # 隧道跑在另一台设备上时，填 Clint 手机的局域网地址
OPTS=(-N -o ExitOnForwardFailure=yes -o ServerAliveInterval=20 -o ServerAliveCountMax=3
      -o StrictHostKeyChecking=accept-new -p "$SSH_PORT"
      -R "127.0.0.1:${REMOTE_PORT}:${LOCAL_ADDR}:${LOCAL_PORT}" "$REMOTE")
termux-wake-lock 2>/dev/null || true
if command -v autossh >/dev/null; then
  export AUTOSSH_GATETIME=0
  exec autossh -M 0 "${OPTS[@]}"
fi
while true; do
  ssh "${OPTS[@]}"
  echo "隧道断开，5 秒后重连…" >&2
  sleep 5
done
