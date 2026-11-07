---
name: clint-remote-automation
description: 通过 Clint 浏览器内置的远程自动化接口（HTTP + 访问令牌，可选 WebSocket 中继 / Termux 打洞 / CDP）让 AI 打开网页、读取内容、点击、输入、截图。Drive the Clint Android browser remotely via its built-in token-protected HTTP API, without ADB.
---

# Clint 远程自动化

**English summary:** Clint ships an opt-in, token-protected HTTP API (default port 9333) that runs inside the
app. Turn on *Settings → Misc → Allow remote automation*, copy the access token, then call the API from the LAN
with `curl` or `clint_remote.py`. For internet access either expose `127.0.0.1:9333` from Termux
(cloudflared quick tunnel, SSH reverse tunnel, frp, Tailscale/ZeroTier) or set a *relay server address* so Clint
connects out over WebSocket. No ADB needed. A CDP (WebView debugging) switch exists for ADB users.

## 1. 开启

1. Clint → 设置 → 其他 → **AI 远程自动化** → 打开「允许 AI 远程自动化」（默认关闭）。
2. 首次开启会自动生成**访问令牌**；点按令牌一行即可复制，「重新生成」会让旧令牌立即失效。
3. 「服务状态」一行显示 `运行中 · <手机局域网IP>:9333`、已处理请求数和最近一次请求，可用来确认 AI 是否连上。
4. 可选项：
   - 「监听端口」：默认 9333（1024–65535）。
   - 「仅本机监听（配合 Termux 打洞）」：只绑定 `127.0.0.1`，局域网无法直连，配合下面第 4 节使用。
   - 「中继服务器地址」：填 `wss://...` 后 Clint 主动连出去（第 5 节）。
   - 「允许 WebView 调试（CDP）」：额外开放 Chrome DevTools 协议，只能经 ADB 访问（附录）。

> 接口只在 Clint 进程存活时可用。命令需要浏览器主界面打开过（`/status` 里 `windowOpen=true`）；
> 若系统把 Clint 冻结在后台，请把它切回前台或在系统里允许后台运行。

## 2. 认证

除 `GET /ping` 外所有请求都必须带令牌，二选一：

- 请求头 `Authorization: Bearer <TOKEN>`（推荐）
- 查询参数 `?token=<TOKEN>`（会出现在日志里，尽量少用）

令牌错误返回 `401`。

## 3. 局域网直连（curl / 脚本）

```bash
export CLINT_HOST=http://<PHONE_IP>:9333
export CLINT_TOKEN=<TOKEN>
H="Authorization: Bearer $CLINT_TOKEN"

curl -s $CLINT_HOST/ping
curl -s -H "$H" $CLINT_HOST/tabs
curl -s -H "$H" -d '{"url":"https://example.com","newTab":true}' $CLINT_HOST/open
curl -s -H "$H" -d '{"js":"document.title"}' $CLINT_HOST/eval
curl -s -H "$H" -d '{"selector":"a"}' $CLINT_HOST/click
curl -s -H "$H" -d '{"selector":"input[name=q]","text":"你好","submit":true}' $CLINT_HOST/type
curl -s -H "$H" $CLINT_HOST/text
curl -s -H "$H" "$CLINT_HOST/screenshot?scale=0.5" -o shot.png
curl -s -H "$H" -X POST $CLINT_HOST/back      # 同理 /forward /reload
curl -s -H "$H" -d '{"tabId":"<TAB_ID>"}' $CLINT_HOST/close
```

`clint_remote.py`（仅用 Python 标准库）封装了同样的接口：

```bash
python3 clint_remote.py tabs
python3 clint_remote.py open https://example.com --new-tab
python3 clint_remote.py eval "document.title"
python3 clint_remote.py click "button[type=submit]"
python3 clint_remote.py type "input[name=q]" "hello" --submit
python3 clint_remote.py text --max 2000
python3 clint_remote.py screenshot -o page.png --scale 0.5
python3 clint_remote.py back | forward | reload
python3 clint_remote.py close --tab <TAB_ID>
python3 clint_remote.py activate --tab <TAB_ID>
python3 clint_remote.py raw POST /eval '{"js":"location.href"}'
```

参数 `--host` / `--token` 可替代环境变量 `CLINT_HOST` / `CLINT_TOKEN`。

### 接口一览

| 方法 | 路径 | 参数（JSON 或查询串） | 返回 |
|---|---|---|---|
| GET | `/ping` | 无需令牌 | `{ok, app, auth}` |
| GET | `/status` | | `{ok, version, windowOpen}` |
| GET | `/tabs` | | `{tabs:[{id,index,title,url,active,incognito,profileId,groupId}]}` |
| POST | `/open` | `url`, `newTab`(bool) | `{tab}`；未打开窗口时会尝试拉起 Clint |
| POST | `/eval` | `js`, `tabId?`, `timeoutMs?` | `{result}`（JSON 值） |
| POST | `/click` | `selector`, `tabId?` | `{ok, tag, text}`，找不到元素返回 404 |
| POST | `/type` | `selector`, `text`, `submit?`, `tabId?` | `{ok, tag}` |
| GET | `/text` | `tabId?` | `{title, url, text}`（`document.body.innerText`） |
| GET | `/screenshot` | `scale?`(0.1–1), `tabId?` | PNG；只能截当前可见标签页，否则 409 |
| POST | `/back` `/forward` `/reload` | `tabId?` | `{ok, canGoBack, canGoForward}` |
| POST | `/activate` | `tabId` | 切换到该标签页 |
| POST | `/close` | `tabId?`（默认当前） | `{closed, remaining}` |

不传 `tabId` 时作用于当前标签页。所有 WebView 操作都在主线程执行并带超时（超时返回 504）。

## 4. 公网访问：Termux 打洞（推荐「仅本机监听」）

先在 Clint 里打开「仅本机监听（配合 Termux 打洞）」，然后在手机 Termux 中任选一种方式，把
`127.0.0.1:9333` 暴露出去。脚本都在 `termux/` 目录，所有地址都是占位符，请自行替换。

先确认本机可用：`CLINT_TOKEN=<TOKEN> bash termux/check-local.sh`

- **cloudflared 快速隧道**（无需账号/域名）：
  `pkg install cloudflared && bash termux/cloudflared-quick.sh`
  → 输出 `https://<随机>.trycloudflare.com`，即 `CLINT_HOST`。
- **SSH 反向隧道**（有自己的服务器时最稳）：
  `pkg install openssh autossh`，配好密钥后
  `REMOTE=<USER>@<HOST> REMOTE_PORT=<REMOTE_PORT> bash termux/ssh-reverse.sh`
  → 在服务器上 `CLINT_HOST=http://127.0.0.1:<REMOTE_PORT>`。等价命令：
  `ssh -N -R <REMOTE_PORT>:127.0.0.1:9333 <USER>@<HOST>`（脚本带 autossh/自动重连）。
- **frp**：复制 `termux/frpc.toml.example` 为 `~/.config/clint/frpc.toml`，填 `<FRPS_HOST>` 等占位符，
  `bash termux/frpc.sh`。
- **Tailscale / ZeroTier**：手机和电脑加入同一虚拟网络后，关闭「仅本机监听」，用手机的虚拟网 IP
  （如 `http://<TAILSCALE_IP>:9333`）直连即可，无需额外脚本。Tailscale 的 Android 客户端是独立 App，
  与 Termux 无关；也可在 Termux 里用 `tailscale serve` 之类的方式转发，但不是必须的。

保持隧道常驻：`termux-wake-lock`（脚本已调用），并在系统电池设置里允许 Termux 与 Clint 后台运行。

### 隧道跑在另一台设备上（同一局域网）

隧道不一定要和 Clint 在同一台手机上：Clint 装在手机 A，cloudflared / frp / SSH 装在同一局域网的手机 B
（或电脑、路由器、NAS）也可以。做法：

1. 手机 A 的 Clint 里**关闭**「仅本机监听」，让接口监听局域网；在设置的「服务状态」里看到
   `<CLINT_LAN_IP>:9333`。
2. 在设备 B 上先确认能直连：`CLINT_ADDR=<CLINT_LAN_IP> CLINT_TOKEN=<TOKEN> bash termux/check-local.sh`
   （返回 401 说明网络通、只是缺令牌；超时则说明两台设备不在同一网段或被 AP 隔离挡住了）。
3. 在设备 B 上照常运行上面的脚本，只多传一个 `CLINT_ADDR=<CLINT_LAN_IP>`，例如
   `CLINT_ADDR=<CLINT_LAN_IP> bash termux/cloudflared-quick.sh`，或
   `CLINT_ADDR=<CLINT_LAN_IP> REMOTE=<USER>@<HOST> bash termux/ssh-reverse.sh`；
   frp 则把 `frpc.toml` 里的 `localIP` 改成 `<CLINT_LAN_IP>`。
4. 外部照常用隧道地址 + `Authorization: Bearer <TOKEN>` 调用。

注意：

- 这种方式下局域网内任何设备都能连到 `<CLINT_LAN_IP>:9333`（仍需令牌），请只在可信网络使用，用完关闭开关。
- 给手机 A 在路由器里设固定 IP（DHCP 静态分配），否则地址变了隧道就断。
- 设备 B 若不在同一网段（例如连的是另一个 Wi-Fi），可以先用 `ssh -L 127.0.0.1:<LOCAL_PORT>:<CLINT_LAN_IP>:9333 <USER>@<能访问 A 的主机>`
  把接口转到 B 本机，再让隧道指向 `127.0.0.1:<LOCAL_PORT>`（`CLINT_ADDR=127.0.0.1 CLINT_PORT=<LOCAL_PORT>`）。

## 5. 中继模式（WebSocket，无需端口转发）

在「中继服务器地址」里填 `wss://<RELAY_HOST>/<PATH>`，Clint 会带 `Authorization: Bearer <TOKEN>` 主动连接，
断线自动重连（3 秒起，指数退避到 60 秒）。状态显示在设置里（已连接 / 正在连接… / 连接失败）。

协议（每条一行 JSON）：

```text
中继 → Clint: {"id":"1","method":"POST","path":"/eval","query":{},"body":{"js":"document.title"},"token":"<TOKEN>"}
Clint → 中继: {"id":"1","status":200,"contentType":"application/json","body":{"ok":true,"result":"..."}}
             二进制结果（截图）放在 "bodyBase64"
Clint 连接后先发 {"type":"hello","client":"clint","version":"..."}；收到 {"type":"ping"} 回 {"type":"pong"}
```

**每条命令都必须带 `token`**，中继本身无法冒充。`relay_server.py` 是一个最小实现（依赖 `pip install websockets`）：
它在 `--ws-port` 等 Clint 连入，并在 `127.0.0.1:--http-port` 提供与手机相同的 HTTP 接口，
因此 `CLINT_HOST=http://127.0.0.1:9341 python3 clint_remote.py tabs` 可直接使用。
公网使用时请在前面加 TLS（Caddy/Nginx/cloudflared），让 Clint 连 `wss://`。

## 6. 安全须知

- 拿到令牌 = 完全控制浏览器：能读取已登录网站的内容、执行任意 JS、操作账户。**不用时请关闭开关**。
- 令牌泄露后立即「重新生成」。不要把令牌写进仓库、截图或公开聊天。
- 局域网模式监听所有网卡，公共 Wi-Fi 下请改用「仅本机监听」+ 隧道，或干脆关闭。
- 隧道地址（尤其 trycloudflare）是公开可达的，只靠令牌保护；用完关掉隧道。
- 无痕标签页同样可以被操控。

## 附录：CDP（需要 ADB）

打开「允许 WebView 调试（CDP）」后，WebView 会暴露 `webview_devtools_remote_<pid>` 抽象套接字：

```bash
adb -s <ADB_SERIAL> shell pidof com.jhaiian.clint          # 取得 <PID>
adb -s <ADB_SERIAL> forward tcp:9222 localabstract:webview_devtools_remote_<PID>
curl -s http://127.0.0.1:9222/json                          # 列出页面，含 webSocketDebuggerUrl
```

之后可用任意 CDP 客户端（Playwright `connect_over_cdp`、chrome-remote-interface 等）连接。
任何能用 ADB 连接手机的人都能控制页面，调试完请关闭开关并 `adb forward --remove tcp:9222`。
