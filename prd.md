# Minecraft Transport Dialer 需求文档

版本：v0.2
项目代号：MC Transport Dialer
目标平台：Minecraft Java Edition
推荐技术栈：Fabric 双端 Mod
核心目标：提供一个最小化的 Minecraft 传输层，使外部代理工具可以把一条 TCP stream 通过真实 Minecraft 客户端连接传输到服务端。

## 1. 项目定位

本项目不是完整代理软件。

本项目不负责 SOCKS5、HTTP CONNECT、TUN、规则路由、DNS 分流、目标站点拨号、负载均衡或代理协议栈。

本项目只负责一件事：

将本机外部程序交给 Minecraft 客户端 Mod 的 TCP 字节流，通过真实 Minecraft 客户端与真实 Minecraft 服务端之间的连接，可靠地传输到远端，并在远端还原为一条 TCP 字节流。

可以将本项目理解为一个 Minecraft 内部传输层，或者一个 Minecraft Dialer Adapter。

典型链路为：

客户端外部代理工具
→ 本机 TCP 转发入口
→ Minecraft 客户端 Mod
→ 真实 Minecraft 客户端连接
→ Minecraft 服务端 Mod
→ 服务端本地 TCP 转发出口
→ 服务端外部代理工具或目标服务

## 2. 设计原则

### 2.1 单一职责

Minecraft Mod 只负责传输，不负责代理协议。

客户端 Mod 不解析 SOCKS5。

客户端 Mod 不解析 HTTP CONNECT。

客户端 Mod 不做 DNS 规则。

客户端 Mod 不做目标网站拨号。

服务端 Mod 不直接访问任意互联网目标，除非作为调试模式。

服务端 Mod 默认只连接服务端本机或指定内网地址上的 TCP 端口。

### 2.2 外部组合

上层能力由外部工具提供。

例如：

* Xray 负责 SOCKS inbound、routing、DNS、freedom outbound。
* sing-box 负责 TUN、ruleset、DNS、selector。
* gost 负责 TCP relay、SOCKS、HTTP、forward。
* frp / socat / nginx stream 负责简单 TCP 转发。

本项目只需要给这些工具提供一条“看起来像 TCP 连接”的传输通道。

### 2.3 真实 Minecraft 连接

所有跨境或远端传输流量必须进入真实 Minecraft 客户端进程。

Minecraft 客户端必须真实连接 Minecraft 服务端。

代理数据必须通过 Minecraft 客户端与服务端之间的 Mod 网络通道传输。

不接受独立程序模拟 Minecraft 协议后直接发流量的方案。

## 3. 最小架构

项目包含两个必选组件：

1. 客户端 Mod
   运行在 Minecraft 客户端内，监听本机 TCP 端口，接收外部工具传入的一条或多条 TCP stream，并通过 Minecraft 连接发送给服务端。

2. 服务端 Mod
   运行在 Minecraft 服务端内，接收客户端 Mod 传来的 stream，在服务端侧连接一个预配置 TCP 地址，并进行双向转发。

最小链路示例：

客户端 Xray SOCKS inbound
→ 客户端 Xray dokodemo-door / TCP outbound
→ `127.0.0.1:25580`
→ Minecraft 客户端 Mod
→ Minecraft 游戏连接
→ Minecraft 服务端 Mod
→ `127.0.0.1:10000`
→ 服务端 Xray inbound
→ 服务端 Xray freedom / proxy outbound

在这个模型中，本项目只替代“客户端到服务端之间的一段传输链路”。

## 4. 职责边界

### 4.1 客户端 Mod 职责

客户端 Mod 负责：

* 在本机监听一个 TCP 端口。
* 接收外部工具传入的 TCP 连接。
* 为每条 TCP 连接分配 stream ID。
* 将 TCP 字节流切分为内部帧。
* 对内部帧进行加密和认证。
* 通过 Minecraft Mod networking channel 发送帧。
* 接收服务端返回帧。
* 将返回数据写回本地 TCP 连接。
* 处理连接关闭、错误、超时和 Minecraft 断线。

客户端 Mod 不负责：

* SOCKS5。
* HTTP CONNECT。
* TUN。
* DNS。
* 规则路由。
* 目标网站拨号。
* 系统代理配置。
* 互联网 outbound。

### 4.2 服务端 Mod 职责

服务端 Mod 负责：

* 接收来自客户端 Mod 的内部帧。
* 解密和验证帧。
* 为每个 stream 建立一个服务端侧 TCP 连接。
* 服务端侧 TCP 连接的目标地址必须来自配置。
* 在 Minecraft stream 和服务端侧 TCP socket 之间双向转发数据。
* 处理连接关闭、错误、超时和玩家离线。
* 实施认证、白名单和资源限制。

服务端 Mod 不负责：

* SOCKS5 协议解析。
* HTTP CONNECT 协议解析。
* DNS 解析策略。
* 目标站点直接拨号。
* 复杂代理路由。
* 代理链选择。
* 互联网出口策略。

### 4.3 外部工具职责

外部工具负责：

* 提供 SOCKS5 / HTTP / TUN inbound。
* 解析代理协议。
* 处理 DNS。
* 执行规则路由。
* 管理多个 outbound。
* 在服务端侧连接真实目标站点。
* 处理代理认证、订阅、策略组等高级能力。

## 5. 传输模型

### 5.1 基本模型

客户端 Mod 将每个本地 TCP 连接映射为一个逻辑 stream。

服务端 Mod 将每个逻辑 stream 映射为一个服务端侧 TCP 连接。

每个 stream 独立关闭、独立报错、独立流控。

所有 stream 共享同一条 Minecraft 客户端到服务端的真实连接。

### 5.2 服务端固定出口

MVP 阶段，服务端 Mod 不允许客户端指定任意目标地址。

服务端 Mod 只连接一个固定配置的 TCP 地址，例如：

```toml
target_host = "127.0.0.1"
target_port = 10000
```

这样可以避免服务端 Mod 变成开放代理。

真正的目标站点访问由服务端外部代理工具完成。

### 5.3 客户端固定入口

MVP 阶段，客户端 Mod 只监听一个本地 TCP 地址，例如：

```toml
listen_host = "127.0.0.1"
listen_port = 25580
```

外部工具只需要把 TCP 流量转发到这个端口。

## 6. 内部帧协议

内部协议应尽可能简单。

最低需要以下帧类型：

* `AUTH`
* `AUTH_OK`
* `OPEN`
* `DATA`
* `CLOSE`
* `RESET`
* `PING`
* `PONG`
* `ERROR`

### 6.1 帧字段

每个帧包含：

* protocol version
* session ID
* stream ID
* frame type
* flags
* payload length
* encrypted payload
* authentication tag

### 6.2 OPEN

客户端收到一条新的本地 TCP 连接时，向服务端发送 `OPEN`。

服务端收到 `OPEN` 后，连接预配置的 TCP 目标地址。

连接成功后，该 stream 进入转发状态。

连接失败时，服务端返回 `ERROR` 或 `RESET`。

### 6.3 DATA

TCP 字节流被切分为多个 `DATA` 帧。

`DATA` 帧只表示字节序列，不携带 SOCKS、HTTP 或目标地址语义。

### 6.4 CLOSE

任一侧正常关闭写方向时发送 `CLOSE`。

实现可以先采用简单全关闭语义，MVP 阶段不强制支持 TCP half-close。

### 6.5 RESET

异常关闭、超时、Minecraft 断线或目标连接错误时发送 `RESET`。

### 6.6 PING / PONG

用于检测 Mod 隧道存活状态。

这不替代 Minecraft 自身 keepalive，只用于传输层内部状态管理。

## 7. 加密与认证

内部帧 payload 必须加密。

最低要求：

* 使用预共享密钥。
* 使用 AEAD，例如 ChaCha20-Poly1305 或 AES-GCM。
* 每个帧具备完整性校验。
* 未认证客户端不能建立 stream。
* 服务端必须有玩家白名单。

MVP 可以采用简单 PSK 认证。

推荐握手流程：

1. 客户端进入 Minecraft 服务器。
2. 客户端 Mod 通过 Mod channel 发送 `AUTH`。
3. 服务端验证玩家 UUID 和 PSK。
4. 验证成功后返回 `AUTH_OK`。
5. 后续 stream 帧才被接受。

## 8. 流控与背压

必须实现基本背压，避免 Minecraft 客户端或服务端内存被打满。

MVP 最低要求：

* 每个 stream 有最大缓冲区。
* 全局有最大缓冲区。
* 本地 socket 读取速度受 Minecraft 发送队列限制。
* 发送队列满时暂停读取本地 socket。
* 连接关闭时释放所有缓冲区。

推荐要求：

* `WINDOW_UPDATE` 帧。
* per-stream flow window。
* 全局 flow window。
* 可配置带宽限制。

MVP 可以先不实现完整 `WINDOW_UPDATE`，但必须有硬性队列上限。

## 9. Minecraft 集成

### 9.1 网络通道

客户端和服务端之间使用 Fabric networking channel 或等价 Mod networking channel。

该 channel 只承载加密后的内部帧。

channel 名称应可配置。

### 9.2 线程模型

客户端要求：

* 不阻塞 render thread。
* 不阻塞 Minecraft Netty event loop。
* 本地 TCP socket 使用独立线程池或异步 I/O。
* 加解密和分片处理不在渲染线程执行。

服务端要求：

* 不阻塞 server tick thread。
* 不阻塞服务端 Netty event loop。
* 目标 TCP socket 使用独立线程池或异步 I/O。
* 玩家离线时清理该玩家所有 stream。

## 10. 配置

### 10.1 客户端配置

```toml
enabled = true

listen_host = "127.0.0.1"
listen_port = 25580

channel_name = "mctransport:main"

psk = "change-me"

max_streams = 64
stream_buffer_size = 1048576
global_buffer_size = 33554432

log_level = "info"
```

### 10.2 服务端配置

```toml
enabled = true

target_host = "127.0.0.1"
target_port = 10000

channel_name = "mctransport:main"

psk = "change-me"

allowed_players = [
  "player-uuid-here"
]

max_streams_per_player = 64
stream_buffer_size = 1048576
global_buffer_size_per_player = 33554432

idle_timeout_seconds = 300
connect_timeout_seconds = 10

log_level = "info"
```

## 11. 推荐外部组合

### 11.1 TCP 转发模式

最简单组合：

客户端应用
→ 客户端外部代理工具
→ Minecraft 客户端 Mod 本地 TCP 入口
→ Minecraft 隧道
→ 服务端 Mod
→ 服务端外部代理工具
→ 互联网

### 11.2 客户端 Xray 示例角色

客户端 Xray 负责：

* SOCKS inbound。
* DNS。
* routing。
* 将选中的 outbound TCP 连接转发到 `127.0.0.1:25580`。

Minecraft 客户端 Mod 负责：

* 将 `127.0.0.1:25580` 收到的 TCP stream 传到服务端。

### 11.3 服务端 Xray 示例角色

服务端 Mod 连接：

* `127.0.0.1:10000`

服务端 Xray 在 `127.0.0.1:10000` 监听，并负责：

* 接收来自服务端 Mod 的 TCP stream。
* 解析代理协议或透明转发协议。
* 连接最终目标站点。
* 执行 outbound 策略。

具体外部工具协议可以后续决定，本项目不绑定 Xray、sing-box 或 gost。

## 12. MVP 范围

MVP 只做 TCP stream transport。

MVP 包含：

* Fabric 客户端 Mod。
* Fabric 服务端 Mod。
* 客户端本地 TCP listener。
* 服务端固定 TCP target dial。
* 多 stream multiplexing。
* 帧加密认证。
* 基础队列限制。
* 连接关闭和异常处理。
* 玩家白名单。
* 示例配置。
* 简单日志。

MVP 不包含：

* SOCKS5。
* HTTP CONNECT。
* UDP。
* TUN。
* DNS。
* 路由规则。
* 服务端任意目标拨号。
* GUI。
* 多服务器 fallback。
* 会话恢复。
* 高级混淆。
* 完整代理软件能力。

## 13. MVP 验收标准

MVP 完成后应满足：

1. 玩家启动真实 Minecraft 客户端并安装客户端 Mod。
2. 玩家连接安装服务端 Mod 的真实 Minecraft 服务端。
3. 客户端 Mod 在本机监听 `127.0.0.1:25580`。
4. 服务端 Mod 连接预配置的 `127.0.0.1:10000`。
5. 外部工具连接客户端 Mod 的本地端口后，可以建立 TCP 字节流。
6. 该 TCP 字节流通过 Minecraft 客户端连接传输至服务端。
7. 服务端 Mod 将字节流转发给服务端本地外部工具。
8. 多个并发 TCP 连接可以同时工作。
9. Minecraft 客户端不卡死。
10. Minecraft 服务端 TPS 不明显下降。
11. 玩家断线后，所有 stream 被清理。
12. 未授权玩家无法使用传输层。

## 14. 非目标

本项目不实现完整代理客户端。

本项目不实现 SOCKS5 服务端。

本项目不实现 HTTP 代理。

本项目不实现系统 TUN。

本项目不实现 DNS 分流。

本项目不负责连接最终互联网目标。

本项目不允许客户端指定任意远端目标地址作为 MVP 默认行为。

本项目不模拟 Minecraft 协议。

本项目不替代真实 Minecraft 客户端。

## 15. 后续扩展

后续可以考虑：

* UDP datagram transport。
* half-close 语义。
* `WINDOW_UPDATE` 流控。
* 与 Xray / sing-box 的自定义 outbound 集成。
* Unix domain socket 支持。
* QUIC-like datagram framing，但仍承载于 Minecraft TCP 连接。
* 多 Minecraft 服务器 fallback。
* 更完善的状态监控。
* 简单管理命令。
* GUI 配置界面。

这些都不属于 MVP。

## 16. 关键结论

本项目应被设计为一个最小化 Minecraft 传输层，而不是代理软件。

正确职责划分是：

外部代理工具负责代理协议和互联网访问。

Minecraft 客户端 Mod 负责把本地 TCP stream 送进真实 Minecraft 连接。

Minecraft 服务端 Mod 负责把 Minecraft 内部 stream 还原成本地 TCP stream。

这样可以保持架构清晰、实现更小、测试更简单，也更容易与现有成熟代理工具组合。

