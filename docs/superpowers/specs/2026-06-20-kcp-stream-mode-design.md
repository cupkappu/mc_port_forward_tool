# KCP Stream Mode — Design Spec

版本：v0.2.3
日期：2026-06-20

## 概述

为 MC Transport Dialer 增加可选的 KCP 流模式。KCP 不作为传输协议，而是作为内部写合并和自适应背压层，降低 Minecraft networking channel 上的 Frame 数量和协议头开销。每个玩家的 route 可独立选择 `DIRECT`（现有，默认）或 `KCP`（新增）模式。

外部接口不变：客户端仍监听 TCP，服务端仍连接 TCP target。

## 架构

```
RouteConfig ──→ StreamMode ──┬── DIRECT → ClientStream / ServerStream (现有)
                             └── KCP    → KcpClientStream / KcpServerStream (新增)
                                                │
                                        共用 KcpCore (移植自 java-Kcp)
```

两个模式共用：
- `TunnelBridge`（Frame 收发）
- `FrameCodec` / `SecureFrameCodec`（编解码）
- `BufferBudget` / `ReservationState`（内存预算）
- `LocalTcpListener`（客户端监听）
- `TargetTcpConnector`（服务端拨号）

## 组件

### 1. StreamMode 枚举

```java
public enum StreamMode {
    DIRECT,  // 现有直通模式（默认）
    KCP      // KCP 合并模式
}
```

### 2. KcpCore

移植自 [java-Kcp](https://github.com/l42111996/java-Kcp) 的 `Kcp.java` 核心算法类。

**变更：**
- 去掉 `io.netty.buffer.ByteBuf` 依赖，改用 `java.nio.ByteBuffer`
- 去掉 `io.netty.util.Recycler`，改用简单对象池或直接分配
- 去掉 `ReItrLinkedList` 等自定义集合，改用 `java.util.LinkedList` + `Iterator`
- 保留完整 KCP 协议逻辑：分片/重组、滑动窗口、ACK 处理、flush/update/check

**固定参数（不可配置）：**

| 参数 | 值 | 理由 |
|---|---|---|
| `nodelay` | true | TCP-over-TCP 场景不需要 Nagle |
| `interval` | 20 | 20ms 刷新间隔，低延迟 |
| `fastresend` | 0 | 下层 TCP 保证可靠，快速重传冗余 |
| `nocwnd` | true | 下层 TCP 已做拥塞控制 |
| `stream` | true | 核心功能：合并小写 |

**可配置参数：**

| 参数 | 默认值 | 说明 |
|---|---|---|
| `kcpMss` | 8192 | Frame 最大 payload，≤ 32766 − 协议头 |
| `kcpSndWnd` | 128 | 发送窗口（包数），在途数据 = sndWnd × mss |
| `kcpRcvWnd` | 128 | 接收窗口（包数）|

### 3. RouteConfig 扩展

新增 `StreamMode mode` 字段，默认 `DIRECT`。

```
RouteConfig(uuid, playerName, listenPort, targetHost, targetPort, mode=DIRECT)
```

### 4. KcpClientStream

替代 `ClientStream` 的 KCP 变体。

**读取循环：**
```
while (!closed) {
    n = socket.read(buf)
    if (n <= 0) break

    kcp.send(buf[0..n])              // 喂 KCP，stream=true 自动合并

    if (kcp.waitSnd() > sndWnd * 2)  // 背压：待发送队列超过 2 倍窗口
        pause reading                  // 等 KcpOutput 回调消化后恢复
}
```

**KcpOutput 回调：**
```
kcp.flush() → KcpOutput.out(segmentBytes) → Frame(DATA, segmentBytes) → bridge.send()
```

**入站处理：**
```
onFrame(frame):
    kcp.input(frame.payload)
    merged = kcp.recv()              // null = 还没凑齐完整消息
    if merged != null:
        socket.write(merged)
        budget.release(length)
```

### 5. KcpServerStream

替代 `ServerStream` 的 KCP 变体。

**目标端读取：**
```
ServerStreamReader loop:
    n = targetSocket.read(buf)
    kcp.send(buf[0..n])
    // 同客户端背压逻辑
```

**入站处理：**
```
onFrame(frame):
    kcp.input(frame.payload)
    merged = kcp.recv()
    if merged != null:
        targetSocket.write(merged)
```

### 6. 工厂路由

`ServerStreamFactory` / `ClientStreamFactory` 增加 `createForMode(StreamMode)` 方法。

```java
interface ClientStreamFactory {
    ClientStreamLike create(ClientTunnelSession session, int streamId, StreamMode mode);
}
```

`DefaultClientStreamFactory` 内部持有 `KcpConfig`，按 mode 创建对应实例。

### 7. 命令扩展

```
/mctransport set <playerName> <listenPort> <targetHost> <targetPort> [mode]
```

`mode` 可选，不传默认 `DIRECT`。

```
/mctransport set Steve 25580 127.0.0.1 10000 KCP
/mctransport set Alex 25581 127.0.0.1 10001          # 默认 DIRECT
```

### 8. 配置文件

TOML 中 route 表格新增 `stream_mode` 字段：

```toml
[[routes]]
player_uuid = "..."
player_name = "Steve"
listen_port = 25580
target_host = "127.0.0.1"
target_port = 10000
stream_mode = "KCP"
```

未指定时默认 `DIRECT`。

### 9. 安全边界

- Frame 加密后总大小 ≤ 32766（Minecraft CustomPayload 上限）
- `kcpMss = 8192` → Frame payload 8192 → 加密后 ~8220 → 安全
- MSS 最大值硬编码为 `32766 − FrameCodec.HEADER_BYTES − AEAD_OVERHEAD` ≈ 32000

### 10. 测试

- `KcpCoreTest`：验证分片/重组/send/recv/背压
- `KcpStreamIntegrationTest`：端到端 TCP → KCP Client → FakeBridge → KCP Server → TCP
- 现有 DIRECT 模式测试全部保持通过
- `RouteConfigTest`：验证 mode 序列化/反序列化

## 数据流对比

```
DIRECT (现有):
  socket.read(16KB) → Frame(16KB) → bridge.send
  多次小读 → 多个小 Frame

KCP (新增):
  socket.read(16KB) → kcp.send × N → KCP 合并 → Frame(8KB) × M → bridge.send
  多次小读 → 合并为 MSS 大小 Frame
```

## 改动文件清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `protocol/StreamMode.java` | 新增 | 枚举 |
| `config/RouteConfig.java` | 修改 | 加 mode 字段 |
| `config/ConfigLoader.java` | 修改 | TOML 读写 stream_mode |
| `kcp/KcpCore.java` | 新增 | 移植的 KCP 核心 |
| `kcp/KcpOutput.java` | 新增 | 回调接口 |
| `kcp/KcpConfig.java` | 新增 | KCP 参数 |
| `client/KcpClientStream.java` | 新增 | KCP 客户端 Stream |
| `server/KcpServerStream.java` | 新增 | KCP 服务端 Stream |
| `client/ClientStreamFactory.java` | 修改 | 加 mode 参数 |
| `client/DefaultClientStreamFactory.java` | 修改 | KCP 分支 |
| `server/ServerStreamFactory.java` | 修改 | 加 mode 参数 |
| `server/DefaultServerStreamFactory.java` | 修改 | KCP 分支 |
| `server/RouteCommandService.java` | 修改 | set 命令加 mode |
| `fabric1211/.../McTransportCommands.java` | 修改 | 命令解析 |
| `fabric1201/.../McTransportCommands.java` | 修改 | 命令解析 |
| `fabric1211/.../FabricServerTunnelBridge.java` | 修改 | 工厂路由 |
| `fabric1201/.../FabricServerTunnelBridge.java` | 修改 | 工厂路由 |
| 测试文件 | 新增/修改 | KCP 测试 |
