# KCP Stream Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional KCP stream mode as a write-coalescing layer, selectable per-player route via `/mctransport set` command.

**Architecture:** Extract `ClientStream`/`ServerStream` into interfaces, keeping current impl as `Direct*Stream`. Add `KcpClientStream`/`KcpServerStream` that feed socket bytes through `KcpCore` for coalescing. Route via factory by `RouteConfig.streamMode`. KCP params fixed: nodelay/stream/nocwnd=true, fastresend=0, interval=20.

**Tech Stack:** Java 17, JUnit 5, Fabric 1.21.1 + 1.20.1, Gradle

---

### Task 1: Branch setup + StreamMode enum

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/protocol/StreamMode.java`

- [ ] **Step 1: Create StreamMode enum**

```java
package dev.kifuko.mctransport.protocol;

/**
 * Stream transport mode selectable per-player route.
 *
 * <p>{@link #DIRECT} maps TCP socket reads 1:1 to DATA frames (current).
 * {@link #KCP} feeds socket reads through KCP stream-mode coalescing before
 * framing, reducing frame count and protocol-header overhead.</p>
 */
public enum StreamMode {
    DIRECT,
    KCP;

    public static StreamMode fromString(String s) {
        if (s == null || s.isBlank()) {
            return DIRECT;
        }
        return switch (s.trim().toUpperCase()) {
            case "KCP" -> KCP;
            case "DIRECT", "DEFAULT" -> DIRECT;
            default -> throw new IllegalArgumentException(
                    "unknown stream mode: " + s + ". Valid: DIRECT, KCP");
        };
    }
}
```

- [ ] **Step 2: Build and verify compiles**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :compileJava --no-daemon -q
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/protocol/StreamMode.java
git commit -m "feat: add StreamMode enum for DIRECT/KCP route selection"
```

---

### Task 2: KcpOutput + KcpConfig

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/kcp/KcpOutput.java`
- Create: `src/main/java/dev/kifuko/mctransport/kcp/KcpConfig.java`

- [ ] **Step 1: Create KcpOutput callback interface**

```java
package dev.kifuko.mctransport.kcp;

import java.nio.ByteBuffer;

/**
 * Callback invoked by {@link KcpCore} when it has a segment ready to send.
 * The buffer's position..limit contains the segment bytes.
 */
@FunctionalInterface
public interface KcpOutput {
    /**
     * @param data  segment bytes; position=0, limit=segment length.
     *              Callee must NOT retain the reference after returning.
     * @param kcp   the source KCP instance
     */
    void out(ByteBuffer data, KcpCore kcp);
}
```

- [ ] **Step 2: Create KcpConfig value object**

```java
package dev.kifuko.mctransport.kcp;

/**
 * Immutable KCP tuning parameters. All fields except {@code mss},
 * {@code sndWnd}, and {@code rcvWnd} are fixed for the TCP-over-TCP scenario.
 */
public final class KcpConfig {

    /** Maximum segment size in bytes. Frame payload == mss. */
    private final int mss;

    /** Send window (number of segments in flight). */
    private final int sndWnd;

    /** Receive window (number of segments). */
    private final int rcvWnd;

    /** Fixed: stream mode enabled (coalesce small writes). */
    private final boolean stream = true;

    /** Fixed: no congestion control (TCP underneath handles it). */
    private final boolean nocwnd = true;

    /** Fixed: disable fast retransmit (TCP guarantees delivery). */
    private final int fastresend = 0;

    /** Fixed: nodelay mode for low latency. */
    private final boolean nodelay = true;

    /** Fixed: internal update interval in milliseconds. */
    private final int interval = 20;

    public static final int DEFAULT_MSS = 8192;
    public static final int DEFAULT_SND_WND = 128;
    public static final int DEFAULT_RCV_WND = 128;

    /** Max MSS to keep encrypted frame under Minecraft's ~32766 byte limit.
     *  FrameCodec header(15) + AEAD(nonce 12 + tag 16) = 43 bytes overhead.
     *  32766 - 43 = 32723; we cap at 32000 for safety margin. */
    public static final int MAX_SAFE_MSS = 32000;

    public KcpConfig() {
        this(DEFAULT_MSS, DEFAULT_SND_WND, DEFAULT_RCV_WND);
    }

    public KcpConfig(int mss, int sndWnd, int rcvWnd) {
        if (mss <= 0 || mss > MAX_SAFE_MSS) {
            throw new IllegalArgumentException(
                    "mss must be 1.." + MAX_SAFE_MSS + ", got: " + mss);
        }
        if (sndWnd <= 0) {
            throw new IllegalArgumentException("sndWnd must be positive, got: " + sndWnd);
        }
        if (rcvWnd <= 0) {
            throw new IllegalArgumentException("rcvWnd must be positive, got: " + rcvWnd);
        }
        this.mss = mss;
        this.sndWnd = sndWnd;
        this.rcvWnd = rcvWnd;
    }

    public int mss() { return mss; }
    public int sndWnd() { return sndWnd; }
    public int rcvWnd() { return rcvWnd; }
    public boolean stream() { return stream; }
    public boolean nocwnd() { return nocwnd; }
    public int fastresend() { return fastresend; }
    public boolean nodelay() { return nodelay; }
    public int interval() { return interval; }
}
```

- [ ] **Step 3: Verify compiles and commit**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :compileJava --no-daemon -q
git add src/main/java/dev/kifuko/mctransport/kcp/
git commit -m "feat: add KcpOutput callback and KcpConfig parameter object"
```

---

### Task 3: KcpCore — port KCP algorithm from java-Kcp

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/kcp/KcpCore.java`

This is the largest single file. Ported from [java-Kcp](https://github.com/l42111996/java-Kcp) `Kcp.java`, adapted to remove Netty/ByteBuf dependencies.

- [ ] **Step 1: Create KcpCore.java**

Key adaptations from original java-Kcp Kcp.java:
- Replace `io.netty.buffer.ByteBuf` → `java.nio.ByteBuffer`
- Replace `io.netty.util.Recycler` → simple `new Segment()` allocation (GC is fine for our connection counts)
- Replace `ReItrLinkedList` → `java.util.LinkedList` + standard `Iterator`
- Replace `io.netty.util.internal.logging` → `java.util.logging.Logger`
- Keep identical algorithm logic: send/recv/input/flush/update/check
- Keep identical constants: IKCP_OVERHEAD, IKCP_MTU_DEF, window sizes, probe intervals

```java
package dev.kifuko.mctransport.kcp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Java port of KCP reliable transport algorithm, adapted from
 * <a href="https://github.com/skywind3000/kcp">skywind3000/kcp</a> via
 * <a href="https://github.com/l42111996/java-Kcp">l42111996/java-Kcp</a>.
 *
 * <p>This implementation uses {@link java.nio.ByteBuffer} instead of Netty
 * ByteBuf and omits object pooling. It is intended for TCP-over-TCP scenarios
 * where the underlying transport already provides reliability; KCP adds
 * write-coalescing (stream mode) and adaptive backpressure.</p>
 *
 * <p>Configuration is applied via {@link KcpConfig} after construction
 * through {@link #applyConfig(KcpConfig)}.</p>
 */
public final class KcpCore {

    private static final Logger LOG = Logger.getLogger(KcpCore.class.getName());

    // ---- Protocol constants (unchanged from C KCP) ----

    public static final int IKCP_RTO_NDL = 30;
    public static final int IKCP_RTO_MIN = 100;
    public static final int IKCP_RTO_DEF = 200;
    public static final int IKCP_RTO_MAX = 60000;
    public static final byte IKCP_CMD_PUSH = 81;
    public static final byte IKCP_CMD_ACK  = 82;
    public static final byte IKCP_CMD_WASK = 83;
    public static final byte IKCP_CMD_WINS = 84;
    public static final int IKCP_ASK_SEND = 1;
    public static final int IKCP_ASK_TELL = 2;
    public static final int IKCP_WND_SND = 32;
    public static final int IKCP_WND_RCV = 32;
    public static final int IKCP_MTU_DEF = 1400;
    public static final int IKCP_INTERVAL = 100;
    public static final int IKCP_OVERHEAD = 24;
    public static final int IKCP_DEADLINK = 20;
    public static final int IKCP_THRESH_INIT = 2;
    public static final int IKCP_THRESH_MIN = 2;
    public static final int IKCP_PROBE_INIT = 7000;
    public static final int IKCP_PROBE_LIMIT = 120000;

    // ---- KCP state ----

    private int conv;
    private int mtu = IKCP_MTU_DEF;
    private int mss;
    private int state;
    private long sndUna;
    private long sndNxt;
    private long rcvNxt;
    private long tsLastack;
    private int ssthresh = IKCP_THRESH_INIT;
    private int rxRttval;
    private int rxSrtt;
    private int rxRto = IKCP_RTO_DEF;
    private int rxMinrto = IKCP_RTO_MIN;
    private int sndWnd = IKCP_WND_SND;
    private int rcvWnd = IKCP_WND_RCV;
    private int rmtWnd = IKCP_WND_RCV;
    private int cwnd;
    private int probe;
    private int interval = IKCP_INTERVAL;
    private long tsFlush = IKCP_INTERVAL;
    private boolean nodelay;
    private boolean updated;
    private long tsProbe;
    private int probeWait;
    private int incr;
    private boolean ackNoDelay;
    private boolean stream;
    private boolean nocwnd;
    private int fastresend;
    private int reserved;

    // ---- Queues ----

    private final LinkedList<Segment> sndQueue = new LinkedList<>();
    private final LinkedList<Segment> sndBuf   = new LinkedList<>();
    private final LinkedList<Segment> rcvQueue = new LinkedList<>();
    private final LinkedList<Segment> rcvBuf   = new LinkedList<>();

    private long[] acklist = new long[8];
    private int ackcount;

    private KcpOutput output;
    private Object user;
    private final long startTicks = System.currentTimeMillis();

    // ---- Construction ----

    public KcpCore(int conv, KcpOutput output) {
        this.conv = conv;
        this.output = output;
        this.mss = mtu - IKCP_OVERHEAD;
    }

    /**
     * Applies KCP tuning from a config object. Call once after construction.
     */
    public void applyConfig(KcpConfig cfg) {
        nodelay(cfg.nodelay(), cfg.interval(), cfg.fastresend(), cfg.nocwnd());
        setSndWnd(cfg.sndWnd());
        setRcvWnd(cfg.rcvWnd());
        setStream(cfg.stream());
        setMtu(cfg.mss() + IKCP_OVERHEAD); // mtu = mss + overhead
    }

    // ---- Public API: send / recv / input / update / flush / check ----

    /**
     * Queues application data for sending. In stream mode, multiple calls
     * are coalesced into MTU-sized segments.
     *
     * @return 0 on success, -2 if too many fragments
     */
    public int send(ByteBuffer buf) {
        int len = buf.remaining();
        if (len == 0) return -1;

        if (stream && !sndQueue.isEmpty()) {
            Segment last = sndQueue.peekLast();
            ByteBuffer lastData = last.data;
            int lastLen = lastData.remaining();
            if (lastLen < mss) {
                int capacity = mss - lastLen;
                int extend = Math.min(len, capacity);
                // Merge: allocate new buffer, copy old + new
                byte[] merged = new byte[lastLen + extend];
                lastData.position(0);
                lastData.get(merged, 0, lastLen);
                buf.get(merged, lastLen, extend);
                last.data = ByteBuffer.wrap(merged);
                len = buf.remaining();
                if (len == 0) return 0;
            }
        }

        int count = (len <= mss) ? 1 : (len + mss - 1) / mss;
        if (count > 255) return -2;

        for (int i = 0; i < count; i++) {
            int size = Math.min(len, mss);
            byte[] slice = new byte[size];
            buf.get(slice);
            Segment seg = new Segment();
            seg.data = ByteBuffer.wrap(slice);
            seg.frg = (short) (stream ? 0 : count - i - 1);
            sndQueue.add(seg);
            len = buf.remaining();
        }
        return 0;
    }

    /**
     * Receives one complete application message. Returns null if no complete
     * message is available yet.
     */
    public ByteBuffer recv() {
        return mergeRecv();
    }

    /**
     * Feeds raw KCP data received from the transport into the protocol.
     *
     * @param data    the received bytes
     * @param regular true for regular data, false for FEC-corrected
     * @param current current time in milliseconds
     * @return 0 on success, negative on error
     */
    public int input(ByteBuffer data, boolean regular, long current) {
        long oldSndUna = sndUna;
        if (data == null || data.remaining() < IKCP_OVERHEAD) return -1;

        long latest = 0;
        boolean flag = false;
        long uintCurrent = long2Uint(currentMs(current));

        while (data.remaining() >= IKCP_OVERHEAD) {
            int conv = data.getInt(data.position()) & 0xFFFFFFFF;
            data.getInt(); // skip conv bytes
            byte cmd = data.get(data.position()); data.get();
            short frg = (short) (data.get(data.position()) & 0xFF); data.get();
            int wnd = data.getShort(data.position()) & 0xFFFF; data.getShort();
            long ts = data.getInt(data.position()) & 0xFFFFFFFFL; data.getInt();
            long sn = data.getInt(data.position()) & 0xFFFFFFFFL; data.getInt();
            long una = data.getInt(data.position()) & 0xFFFFFFFFL; data.getInt();
            int len = data.getInt();

            if (conv != this.conv) return -4;
            if (data.remaining() < len || len < 0) return -2;
            if (cmd != IKCP_CMD_PUSH && cmd != IKCP_CMD_ACK &&
                cmd != IKCP_CMD_WASK && cmd != IKCP_CMD_WINS) return -3;

            if (regular) this.rmtWnd = wnd;
            if (parseUna(una) > 0) { /* window slides */ }
            shrinkBuf();

            boolean readed = false;
            switch (cmd) {
                case IKCP_CMD_ACK:
                    parseAck(sn);
                    parseFastack(sn, ts);
                    flag = true;
                    latest = ts;
                    break;
                case IKCP_CMD_PUSH:
                    if (itimediff(sn, rcvNxt + rcvWnd) < 0) {
                        ackPush(sn, ts);
                        if (itimediff(sn, rcvNxt) >= 0) {
                            Segment seg;
                            if (len > 0) {
                                byte[] segData = new byte[len];
                                data.get(segData);
                                seg = new Segment();
                                seg.data = ByteBuffer.wrap(segData);
                                readed = true;
                            } else {
                                seg = new Segment();
                                seg.data = ByteBuffer.allocate(0);
                            }
                            seg.conv = conv;
                            seg.cmd = cmd;
                            seg.frg = frg;
                            seg.wnd = wnd;
                            seg.ts = ts;
                            seg.sn = sn;
                            seg.una = una;
                            parseData(seg);
                        }
                    }
                    break;
                case IKCP_CMD_WASK:
                    probe |= IKCP_ASK_TELL;
                    break;
                case IKCP_CMD_WINS:
                    break;
                default:
                    return -3;
            }
            if (!readed && len > 0) {
                data.position(data.position() + len);
            }
        }

        if (flag && regular) {
            int rtt = itimediff(uintCurrent, latest);
            if (rtt >= 0) updateAck(rtt);
        }

        if (!nocwnd && itimediff(sndUna, oldSndUna) > 0) {
            if (cwnd < rmtWnd) {
                int mss = this.mss;
                if (cwnd < ssthresh) {
                    cwnd++;
                    incr += mss;
                } else {
                    if (incr < mss) incr = mss;
                    incr += (mss * mss) / incr + (mss / 16);
                    if ((cwnd + 1) * mss <= incr) {
                        cwnd = (mss > 0) ? (incr + mss - 1) / mss : incr + mss - 1;
                    }
                }
                if (cwnd > rmtWnd) { cwnd = rmtWnd; incr = rmtWnd * mss; }
            }
        }
        return 0;
    }

    /**
     * Drives the internal timer. Call periodically (every interval ms).
     */
    public void update(long current) {
        if (!updated) {
            updated = true;
            tsFlush = current;
        }
        int slap = itimediff(current, tsFlush);
        if (slap >= 10000 || slap < -10000) {
            tsFlush = current;
            slap = 0;
        }
        if (slap >= 0) {
            tsFlush += interval;
            if (itimediff(current, tsFlush) >= 0) tsFlush = current + interval;
        } else {
            tsFlush = current + interval;
        }
        flush(false, current);
    }

    /**
     * Returns the next time (in ms) when update() should be called.
     */
    public long check(long current) {
        if (!updated) return current;
        long tsFlush = this.tsFlush;
        int slap = itimediff(current, tsFlush);
        if (slap >= 10000 || slap < -10000) {
            tsFlush = current;
            slap = 0;
        }
        if (slap >= 0) return current;

        int tmFlush = itimediff(tsFlush, current);
        int tmPacket = Integer.MAX_VALUE;
        for (Segment seg : sndBuf) {
            int diff = itimediff(seg.resendts, current);
            if (diff <= 0) return current;
            if (diff < tmPacket) tmPacket = diff;
        }
        int minimal = Math.min(tmPacket, tmFlush);
        if (minimal >= interval) minimal = interval;
        return current + minimal;
    }

    /** Flush pending data through the output callback. */
    public long flush(boolean ackOnly, long current) {
        current = currentMs(current);

        Segment seg = new Segment();
        seg.conv = conv;
        seg.cmd = IKCP_CMD_ACK;
        seg.wnd = wndUnused();
        seg.una = rcvNxt;

        ByteBuffer buffer = null;

        // flush acks
        int count = ackcount;
        for (int i = 0; i < count; i++) {
            long sn = acklist[i * 2];
            if (itimediff(sn, rcvNxt) >= 0 || count - 1 == i) {
                buffer = makeSpace(buffer, IKCP_OVERHEAD);
                seg.sn = sn;
                seg.ts = acklist[i * 2 + 1];
                encodeSeg(buffer, seg);
            }
        }
        ackcount = 0;

        if (ackOnly) {
            flushBuffer(buffer);
            return interval;
        }

        // probe
        if (rmtWnd == 0) {
            if (probeWait == 0) {
                probeWait = IKCP_PROBE_INIT;
                tsProbe = current + probeWait;
            } else if (itimediff(current, tsProbe) >= 0) {
                if (probeWait < IKCP_PROBE_INIT) probeWait = IKCP_PROBE_INIT;
                probeWait += probeWait / 2;
                if (probeWait > IKCP_PROBE_LIMIT) probeWait = IKCP_PROBE_LIMIT;
                tsProbe = current + probeWait;
                probe |= IKCP_ASK_SEND;
            }
        } else {
            tsProbe = 0;
            probeWait = 0;
        }

        if ((probe & IKCP_ASK_SEND) != 0) {
            seg.cmd = IKCP_CMD_WASK;
            buffer = makeSpace(buffer, IKCP_OVERHEAD);
            encodeSeg(buffer, seg);
        }
        if ((probe & IKCP_ASK_TELL) != 0) {
            seg.cmd = IKCP_CMD_WINS;
            buffer = makeSpace(buffer, IKCP_OVERHEAD);
            encodeSeg(buffer, seg);
        }
        probe = 0;

        // move data from snd_queue to snd_buf
        int cwnd0 = Math.min(sndWnd, rmtWnd);
        if (!nocwnd) cwnd0 = Math.min(this.cwnd, cwnd0);

        int newSegsCount = 0;
        while (itimediff(sndNxt, sndUna + cwnd0) < 0) {
            Segment newSeg = sndQueue.poll();
            if (newSeg == null) break;
            newSeg.conv = conv;
            newSeg.cmd = IKCP_CMD_PUSH;
            newSeg.sn = sndNxt;
            sndBuf.add(newSeg);
            sndNxt++;
            newSegsCount++;
        }

        int resent = fastresend > 0 ? fastresend : Integer.MAX_VALUE;
        int change = 0;
        boolean lost = false;
        long minrto = interval;

        for (Iterator<Segment> itr = sndBuf.iterator(); itr.hasNext(); ) {
            Segment segment = itr.next();
            boolean needsend = false;
            if (segment.xmit == 0) {
                needsend = true;
                segment.rto = rxRto;
                segment.resendts = current + segment.rto;
            } else if (segment.fastack >= resent) {
                needsend = true;
                segment.fastack = 0;
                segment.rto = rxRto;
                segment.resendts = current + segment.rto;
                change++;
            } else if (segment.fastack > 0 && newSegsCount == 0) {
                needsend = true;
                segment.fastack = 0;
                segment.rto = rxRto;
                segment.resendts = current + segment.rto;
                change++;
            } else if (itimediff(current, segment.resendts) >= 0) {
                needsend = true;
                if (!nodelay) segment.rto += rxRto;
                else segment.rto += rxRto / 2;
                segment.fastack = 0;
                segment.resendts = current + segment.rto;
                lost = true;
            }

            if (needsend) {
                segment.xmit++;
                segment.ts = long2Uint(current);
                segment.wnd = seg.wnd;
                segment.una = rcvNxt;

                ByteBuffer segData = segment.data;
                int segLen = segData.remaining();
                int need = IKCP_OVERHEAD + segLen;
                buffer = makeSpace(buffer, need);
                encodeSeg(buffer, segment);

                if (segLen > 0) {
                    buffer.put(segData.duplicate());
                }

                long rto = itimediff(segment.resendts, current);
                if (rto > 0 && rto < minrto) minrto = rto;
            }
        }

        flushBuffer(buffer);

        if (!nocwnd) {
            if (change > 0) {
                int inflight = (int) (sndNxt - sndUna);
                ssthresh = inflight / 2;
                if (ssthresh < IKCP_THRESH_MIN) ssthresh = IKCP_THRESH_MIN;
                cwnd = ssthresh + resent;
                incr = cwnd * mss;
            }
            if (lost) {
                ssthresh = cwnd0 / 2;
                if (ssthresh < IKCP_THRESH_MIN) ssthresh = IKCP_THRESH_MIN;
                cwnd = 1;
                incr = mss;
            }
            if (cwnd < 1) { cwnd = 1; incr = mss; }
        }
        return minrto;
    }

    // ---- Accessors ----

    public int waitSnd() { return sndBuf.size() + sndQueue.size(); }
    public int getConv() { return conv; }
    public int getState() { return state; }
    public void setState(int state) { this.state = state; }
    public int getSndWnd() { return sndWnd; }
    public Object getUser() { return user; }
    public void setUser(Object user) { this.user = user; }

    // ---- Internal helpers ----

    private int setMtu(int mtu) {
        if (mtu < IKCP_OVERHEAD + reserved || mtu < 50) return -1;
        this.mtu = mtu;
        this.mss = mtu - IKCP_OVERHEAD - reserved;
        return 0;
    }

    private int nodelay(boolean nodelay, int interval, int resend, boolean nc) {
        this.nodelay = nodelay;
        this.rxMinrto = nodelay ? IKCP_RTO_NDL : IKCP_RTO_MIN;
        if (interval >= 0) this.interval = Math.min(Math.max(interval, 10), 5000);
        if (resend >= 0) this.fastresend = resend;
        this.nocwnd = nc;
        return 0;
    }

    private void setSndWnd(int wnd) { this.sndWnd = wnd; }
    private void setRcvWnd(int wnd) { this.rcvWnd = wnd; }
    private void setStream(boolean stream) { this.stream = stream; }

    private long currentMs(long now) { return now - startTicks; }

    private static long long2Uint(long n) { return n & 0xFFFFFFFFL; }

    private static int itimediff(long later, long earlier) { return (int) (later - earlier); }

    private static int ibound(int lower, int middle, int upper) {
        return Math.min(Math.max(lower, middle), upper);
    }

    private int wndUnused() {
        return rcvQueue.size() < rcvWnd ? rcvWnd - rcvQueue.size() : 0;
    }

    private ByteBuffer makeSpace(ByteBuffer buffer, int space) {
        if (buffer == null) {
            buffer = ByteBuffer.allocate(mtu);
            return buffer;
        }
        if (buffer.remaining() < space) {
            buffer.flip();
            output.out(buffer, this);
            return ByteBuffer.allocate(mtu);
        }
        return buffer;
    }

    private void flushBuffer(ByteBuffer buffer) {
        if (buffer == null || buffer.position() == 0) return;
        buffer.flip();
        if (buffer.remaining() > 0) {
            output.out(buffer, this);
        }
    }

    private static void encodeSeg(ByteBuffer buf, Segment seg) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(seg.conv);
        buf.put(seg.cmd);
        buf.put((byte) seg.frg);
        buf.putShort((short) seg.wnd);
        buf.putInt((int) seg.ts);
        buf.putInt((int) seg.sn);
        buf.putInt((int) seg.una);
        buf.putInt(seg.data != null ? seg.data.remaining() : 0);
    }

    private void updateAck(int rtt) {
        if (rxSrtt == 0) {
            rxSrtt = rtt;
            rxRttval = rtt >> 2;
        } else {
            int delta = rtt - rxSrtt;
            rxSrtt += delta >> 3;
            delta = Math.abs(delta);
            if (rtt < rxSrtt - rxRttval) {
                rxRttval += (delta - rxRttval) >> 5;
            } else {
                rxRttval += (delta - rxRttval) >> 2;
            }
        }
        int rto = rxSrtt + Math.max(interval, rxRttval << 2);
        rxRto = ibound(rxMinrto, rto, IKCP_RTO_MAX);
    }

    private void shrinkBuf() {
        if (!sndBuf.isEmpty()) {
            sndUna = sndBuf.peek().sn;
        } else {
            sndUna = sndNxt;
        }
    }

    private void parseAck(long sn) {
        if (itimediff(sn, sndUna) < 0 || itimediff(sn, sndNxt) >= 0) return;
        for (Iterator<Segment> itr = sndBuf.iterator(); itr.hasNext(); ) {
            Segment seg = itr.next();
            if (sn == seg.sn) { itr.remove(); break; }
            if (itimediff(sn, seg.sn) < 0) break;
        }
    }

    private int parseUna(long una) {
        int count = 0;
        for (Iterator<Segment> itr = sndBuf.iterator(); itr.hasNext(); ) {
            Segment seg = itr.next();
            if (itimediff(una, seg.sn) > 0) { count++; itr.remove(); }
            else break;
        }
        return count;
    }

    private void parseFastack(long sn, long ts) {
        if (itimediff(sn, sndUna) < 0 || itimediff(sn, sndNxt) >= 0) return;
        for (Segment seg : sndBuf) {
            if (itimediff(sn, seg.sn) < 0) break;
            else if (sn != seg.sn && itimediff(seg.ts, ts) <= 0) seg.fastack++;
        }
    }

    private void ackPush(long sn, long ts) {
        int newSize = 2 * (ackcount + 1);
        if (newSize > acklist.length) {
            long[] newArray = new long[acklist.length * 2];
            System.arraycopy(acklist, 0, newArray, 0, acklist.length);
            acklist = newArray;
        }
        acklist[2 * ackcount] = sn;
        acklist[2 * ackcount + 1] = ts;
        ackcount++;
    }

    private boolean parseData(Segment newSeg) {
        long sn = newSeg.sn;
        if (itimediff(sn, rcvNxt + rcvWnd) >= 0 || itimediff(sn, rcvNxt) < 0) {
            return true; // discard
        }

        boolean repeat = false;
        ListIterator<Segment> listItr = null;
        if (!rcvBuf.isEmpty()) {
            listItr = rcvBuf.listIterator(rcvBuf.size());
            while (listItr.hasPrevious()) {
                Segment seg = listItr.previous();
                if (seg.sn == sn) { repeat = true; break; }
                if (itimediff(sn, seg.sn) > 0) break;
            }
        }

        if (!repeat) {
            if (listItr == null) {
                rcvBuf.add(newSeg);
            } else {
                if (listItr.hasPrevious()) listItr.next(); // adjust
                listItr.add(newSeg);
            }
        }
        moveRcvData();
        return repeat;
    }

    private void moveRcvData() {
        for (Iterator<Segment> itr = rcvBuf.iterator(); itr.hasNext(); ) {
            Segment seg = itr.next();
            if (seg.sn == rcvNxt && rcvQueue.size() < rcvWnd) {
                itr.remove();
                rcvQueue.add(seg);
                rcvNxt++;
            } else break;
        }
    }

    private int peekSize() {
        if (rcvQueue.isEmpty()) return -1;
        Segment seg = rcvQueue.peek();
        if (seg.frg == 0) return seg.data.remaining();
        if (rcvQueue.size() < seg.frg + 1) return -1;
        int len = 0;
        for (Segment s : rcvQueue) {
            len += s.data.remaining();
            if (s.frg == 0) break;
        }
        return len;
    }

    private ByteBuffer mergeRecv() {
        if (rcvQueue.isEmpty()) return null;
        int peekSize = peekSize();
        if (peekSize < 0) return null;

        boolean recover = rcvQueue.size() >= rcvWnd;

        byte[] merged = new byte[peekSize];
        int pos = 0;
        for (Iterator<Segment> itr = rcvQueue.iterator(); itr.hasNext(); ) {
            Segment seg = itr.next();
            ByteBuffer data = seg.data;
            int len = data.remaining();
            data.position(0);
            data.get(merged, pos, len);
            pos += len;
            itr.remove();
            if (seg.frg == 0) break;
        }

        moveRcvData();

        if (rcvQueue.size() < rcvWnd && recover) {
            probe |= IKCP_ASK_TELL;
        }

        return ByteBuffer.wrap(merged);
    }

    public void release() {
        sndQueue.clear();
        sndBuf.clear();
        rcvQueue.clear();
        rcvBuf.clear();
        state = -1;
    }

    // ---- Internal Segment ----

    private static class Segment {
        int conv;
        byte cmd;
        short frg;
        int wnd;
        long ts, sn, una;
        long resendts;
        int rto, fastack, xmit;
        ByteBuffer data;
    }
}
```

- [ ] **Step 2: Verify compiles**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :compileJava --no-daemon -q
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/kcp/KcpCore.java
git commit -m "feat: port KCP core algorithm from java-Kcp with java.nio.ByteBuffer"
```

---

### Task 4: KcpCore unit tests

**Files:**
- Create: `src/test/java/dev/kifuko/mctransport/kcp/KcpCoreTest.java`

- [ ] **Step 1: Create basic send/recv round-trip test**

```java
package dev.kifuko.mctransport.kcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KcpCoreTest {

    private KcpCore clientKcp;
    private KcpCore serverKcp;
    private List<ByteBuffer> clientToServer;
    private List<ByteBuffer> serverToClient;
    private long now;

    @BeforeEach
    void setUp() {
        now = System.currentTimeMillis();
        clientToServer = new ArrayList<>();
        serverToClient = new ArrayList<>();

        clientKcp = new KcpCore(1, (data, kcp) -> {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            clientToServer.add(ByteBuffer.wrap(copy));
        });
        KcpConfig cfg = new KcpConfig(8192, 128, 128);
        clientKcp.applyConfig(cfg);

        serverKcp = new KcpCore(1, (data, kcp) -> {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            serverToClient.add(ByteBuffer.wrap(copy));
        });
        serverKcp.applyConfig(cfg);
    }

    private void pump() {
        // Client → Server
        for (ByteBuffer buf : clientToServer) {
            serverKcp.input(buf, true, now);
        }
        clientToServer.clear();
        // Server → Client
        for (ByteBuffer buf : serverToClient) {
            clientKcp.input(buf, true, now);
        }
        serverToClient.clear();
    }

    @Test
    void sendRecvSingleSmallMessage() {
        byte[] payload = "hello kcp".getBytes();
        clientKcp.send(ByteBuffer.wrap(payload));
        clientKcp.update(now);
        pump();
        serverKcp.update(now);

        ByteBuffer received = serverKcp.recv();
        assertNotNull(received);
        byte[] result = new byte[received.remaining()];
        received.get(result);
        assertArrayEquals(payload, result);
    }

    @Test
    void sendRecvMultipleMessages() {
        for (int i = 0; i < 10; i++) {
            byte[] payload = ("msg-" + i).getBytes();
            clientKcp.send(ByteBuffer.wrap(payload));
        }
        clientKcp.update(now);
        pump();
        serverKcp.update(now);

        for (int i = 0; i < 10; i++) {
            ByteBuffer received = serverKcp.recv();
            assertNotNull(received, "message " + i);
            byte[] result = new byte[received.remaining()];
            received.get(result);
            assertArrayEquals(("msg-" + i).getBytes(), result);
        }
        assertNull(serverKcp.recv());
    }

    @Test
    void sendLargePayloadSplitsIntoSegments() {
        byte[] large = new byte[20000];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i % 256);

        assertEquals(0, clientKcp.send(ByteBuffer.wrap(large)));
        clientKcp.update(now);
        pump();
        serverKcp.update(now);

        ByteBuffer received = serverKcp.recv();
        assertNotNull(received);
        assertEquals(large.length, received.remaining());
        byte[] result = new byte[received.remaining()];
        received.get(result);
        assertArrayEquals(large, result);
    }

    @Test
    void waitSndReflectsPendingData() {
        assertEquals(0, clientKcp.waitSnd());
        clientKcp.send(ByteBuffer.wrap(new byte[100]));
        assertTrue(clientKcp.waitSnd() > 0);
    }

    @Test
    void streamModeCoalescesSmallWrites() {
        // Send many small writes - should produce fewer output segments
        for (int i = 0; i < 100; i++) {
            clientKcp.send(ByteBuffer.wrap(new byte[]{1}));
        }
        clientKcp.update(now);
        // Output should be coalesced: fewer than 100 calls
        assertTrue(clientToServer.size() < 50,
                "Expected coalescing, got " + clientToServer.size() + " output chunks for 100 writes");
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :test --tests "dev.kifuko.mctransport.kcp.KcpCoreTest" --no-daemon
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dev/kifuko/mctransport/kcp/KcpCoreTest.java
git commit -m "test: add KcpCore send/recv round-trip and coalescing tests"
```

---

### Task 5: RouteConfig + ConfigLoader — stream mode support

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/config/RouteConfig.java`
- Modify: `src/main/java/dev/kifuko/mctransport/config/ConfigLoader.java`

- [ ] **Step 1: Add StreamMode field to RouteConfig**

Add field and constructor parameter:

```java
// Add import
import dev.kifuko.mctransport.protocol.StreamMode;

// Add field (after targetPort)
private final StreamMode mode;

// Update constructor signature:
public RouteConfig(UUID playerUuid,
                   String playerName,
                   int listenPort,
                   String targetHost,
                   int targetPort,
                   StreamMode mode) {
    // ... existing validation ...
    this.mode = mode == null ? StreamMode.DIRECT : mode;
}

// Add backward-compat constructor:
public RouteConfig(UUID playerUuid,
                   String playerName,
                   int listenPort,
                   String targetHost,
                   int targetPort) {
    this(playerUuid, playerName, listenPort, targetHost, targetPort, StreamMode.DIRECT);
}

// Add getter:
public StreamMode getMode() { return mode; }

// Update equals/hashCode/toString to include mode
```

- [ ] **Step 2: Update ConfigLoader to read/write stream_mode**

In `ConfigLoader.writeServer()`, add to route table:
```java
out.append("stream_mode = \"").append(route.getMode().name()).append("\"\n");
```

In `ConfigLoader.toRouteConfig()`, add:
```java
// After parsing target_port
Object modeObj = table.get("stream_mode");
StreamMode mode = modeObj instanceof String s ? StreamMode.fromString(s) : StreamMode.DIRECT;
return new RouteConfig(uuid, name, (int) listenPort, targetHost, (int) targetPort, mode);
```

- [ ] **Step 3: Update all RouteConfig callers**

Find and update all `new RouteConfig(...)` calls — they'll use the 5-arg compat constructor automatically.

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && grep -rn "new RouteConfig" --include="*.java" src/
```

All existing call sites use 5 args, so they compile unchanged.

- [ ] **Step 4: Build and run tests**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :test --no-daemon
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/config/RouteConfig.java src/main/java/dev/kifuko/mctransport/config/ConfigLoader.java
git commit -m "feat: add StreamMode field to RouteConfig with TOML support"
```

---

### Task 6: Client-side — extract interface, rename DirectClientStream

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/client/ClientStream.java` (becomes interface)
- Modify: `src/main/java/dev/kifuko/mctransport/client/DirectClientStream.java` (rename target)
- Modify: All files referencing `ClientStream` directly

- [ ] **Step 1: Rename ClientStream.java → DirectClientStream.java**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && git mv src/main/java/dev/kifuko/mctransport/client/ClientStream.java src/main/java/dev/kifuko/mctransport/client/DirectClientStream.java
```

Edit `DirectClientStream.java`:
- Change class name: `public final class DirectClientStream implements ClientStream`
- Change constructor name: `public DirectClientStream(...)`

- [ ] **Step 2: Create ClientStream interface**

```java
package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.protocol.Frame;

import java.net.Socket;

/**
 * Common interface for client-side transport streams. Both
 * {@link DirectClientStream} and {@link KcpClientStream} implement this.
 */
public interface ClientStream {
    int streamId();

    /** Starts reading from the socket. */
    void attach(Socket socket, byte[] readBuffer);

    /** Handles an inbound DATA/CLOSE/RESET frame. */
    void onFrame(Frame frame);

    /** Idempotent close + cleanup. */
    void closeSocketAndRelease();

    boolean isClosed();

    Socket socket();

    /** Max payload size for DATA frame creation. */
    int maxPayload();
}
```

- [ ] **Step 3: Update ClientTunnelSession**

Change `Map<Integer, ClientStream>` — no type change needed (now interface).
Change `openLocalStream()` return type — stays `ClientStream`.
Update `ClientStreamFactory`:

```java
// Change return type:
ClientStream create(ClientTunnelSession session, int streamId);
```

- [ ] **Step 4: Update DefaultClientStreamFactory**

```java
@Override
public ClientStream create(ClientTunnelSession session, int streamId) {
    return new DirectClientStream(session, streamId, budget, reservations, maxPayloadSize);
}
```

- [ ] **Step 5: Update all test imports**

```bash
# Tests that import ClientStream directly — change to import DirectClientStream if constructing
cd /Users/kifuko/dev/mc_port_forward_tool && grep -rn "new ClientStream\|import.*ClientStream" --include="*.java" src/test/
```

Update `LoopbackTcpTransportTest.java`, `InMemoryTunnelIntegrationTest.java`, `CloseResetSemanticsTest.java`:
- Import `DirectClientStream` instead of `ClientStream` only where `new DirectClientStream(...)` is called
- `ClientStream` interface import stays for variable/parameter types

- [ ] **Step 6: Update Fabric mod files**

`McTransportClient.java` (both fabric1201 and fabric1211):
```java
// Change:
(sess, id) -> new DirectClientStream(sess, id, ...)
```

- [ ] **Step 7: Build and run all tests**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :test --no-daemon
```

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: extract ClientStream interface, rename impl to DirectClientStream"
```

---

### Task 7: KcpClientStream

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/client/KcpClientStream.java`

- [ ] **Step 1: Create KcpClientStream implementing ClientStream**

```java
package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.kcp.KcpConfig;
import dev.kifuko.mctransport.kcp.KcpCore;
import dev.kifuko.mctransport.kcp.KcpOutput;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * KCP-backed client stream. Feeds local TCP socket reads into KCP for
 * stream-mode coalescing; sends coalesced segments as DATA frames.
 * Receives DATA frames from the peer and feeds them into KCP for
 * reassembly before writing to the local socket.
 */
public final class KcpClientStream implements ClientStream {

    private static final Logger LOG = Logger.getLogger(KcpClientStream.class.getName());

    private final ClientTunnelSession session;
    private final int streamId;
    private final BufferBudget budget;
    private final ReservationState reservations;
    private final KcpCore kcp;
    private final KcpConfig kcpConfig;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Socket localSocket;
    private volatile Thread readThread;
    private byte[] readBuffer;
    private volatile boolean writeBlocked;

    public KcpClientStream(ClientTunnelSession session,
                           int streamId,
                           BufferBudget budget,
                           ReservationState reservations,
                           KcpConfig kcpConfig) {
        this.session = session;
        this.streamId = streamId;
        this.budget = budget;
        this.reservations = reservations;
        this.kcpConfig = kcpConfig;
        this.kcp = new KcpCore(streamId, new BridgeOutput());
        this.kcp.applyConfig(kcpConfig);
    }

    @Override
    public int streamId() { return streamId; }

    @Override
    public int maxPayload() { return kcpConfig.mss(); }

    @Override
    public boolean isClosed() { return closed.get(); }

    @Override
    public Socket socket() { return localSocket; }

    @Override
    public synchronized void attach(Socket socket, byte[] readBuffer) {
        if (closed.get()) {
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }
        if (this.localSocket != null) throw new IllegalStateException("already attached");
        this.localSocket = socket;
        this.readBuffer = readBuffer;
        Thread t = new Thread(this::drainLoop, "mctransport-kcp-read-" + streamId);
        t.setDaemon(true);
        this.readThread = t;
        t.start();
    }

    private void drainLoop() {
        Socket sock = localSocket;
        if (sock == null) return;
        byte[] buf = this.readBuffer;
        try {
            InputStream in = sock.getInputStream();
            while (!closed.get()) {
                // Backpressure: pause reading when KCP send queue is full
                if (kcp.waitSnd() > kcpConfig.sndWnd() * 2) {
                    Thread.sleep(1);
                    kcp.update(System.currentTimeMillis());
                    continue;
                }
                int n = in.read(buf);
                if (n < 0) { sendClose(); break; }
                if (n == 0) continue;
                budget.reserve(streamId, n, reservations);
                kcp.send(ByteBuffer.wrap(buf, 0, n));
                // Drive KCP timer to flush coalesced segments
                kcp.update(System.currentTimeMillis());
            }
        } catch (IOException e) {
            sendReset();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSocketAndRelease();
        }
    }

    @Override
    public void onFrame(Frame frame) {
        if (closed.get()) return;
        switch (frame.type()) {
            case DATA -> handleData(frame);
            case CLOSE, RESET, ERROR -> closeFromPeer();
        }
    }

    private void handleData(Frame frame) {
        if (frame.payloadLength() == 0) return;
        long now = System.currentTimeMillis();
        kcp.input(ByteBuffer.wrap(frame.payload()), true, now);
        kcp.update(now);

        // Drain reassembled messages from KCP
        Socket sock = localSocket;
        for (;;) {
            ByteBuffer merged = kcp.recv();
            if (merged == null) break;
            if (sock == null || sock.isClosed()) {
                closeReset();
                return;
            }
            try {
                byte[] bytes = new byte[merged.remaining()];
                merged.get(bytes);
                OutputStream out = sock.getOutputStream();
                out.write(bytes);
                out.flush();
                budget.release(streamId, bytes.length, reservations);
            } catch (IOException e) {
                closeReset();
                return;
            }
        }
    }

    private void sendDataFrame(byte[] data, int len) {
        byte[] body = new byte[len];
        System.arraycopy(data, 0, body, 0, len);
        Frame f = Frame.create(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.DATA,
                (byte) 0, body, kcpConfig.mss());
        session.bridge().send(f);
    }

    private void sendClose() {
        if (!closed.compareAndSet(false, true)) return;
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.CLOSE,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
        cleanup();
    }

    private void sendReset() {
        if (!closed.compareAndSet(false, true)) return;
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.RESET,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
        cleanup();
    }

    private void closeFromPeer() {
        if (!closed.compareAndSet(false, true)) return;
        cleanup();
    }

    @Override
    public synchronized void closeSocketAndRelease() {
        if (!closed.compareAndSet(false, true)) return;
        cleanup();
    }

    private void cleanup() {
        kcp.release();
        budget.releaseAll(streamId, reservations);
        readBuffer = null;
        Socket sock = localSocket;
        localSocket = null;
        if (sock != null) {
            try { sock.close(); } catch (IOException ignored) {}
        }
        Thread t = readThread;
        if (t != null) t.interrupt();
        session.closeLocalStream(streamId);
    }

    /** KcpOutput that bridges KCP segments into DATA frames. */
    private class BridgeOutput implements KcpOutput {
        @Override
        public void out(ByteBuffer data, KcpCore kcp) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sendDataFrame(bytes, bytes.length);
        }
    }
}
```

- [ ] **Step 2: Build and verify**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :compileJava --no-daemon -q
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/client/KcpClientStream.java
git commit -m "feat: add KcpClientStream with KCP coalescing for client side"
```

---

### Task 8: Server-side — extract interface, rename DirectServerStream

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/server/ServerStream.java` (becomes interface)
- Modify: `src/main/java/dev/kifuko/mctransport/server/DirectServerStream.java` (rename target)
- Modify: All files referencing `ServerStream` directly

- [ ] **Step 1: Rename ServerStream.java → DirectServerStream.java**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && git mv src/main/java/dev/kifuko/mctransport/server/ServerStream.java src/main/java/dev/kifuko/mctransport/server/DirectServerStream.java
```

Edit `DirectServerStream.java`:
- Class: `public final class DirectServerStream implements ServerStream`
- Constructor: `public DirectServerStream(...)`

- [ ] **Step 2: Create ServerStream interface**

```java
package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.protocol.Frame;

import java.io.IOException;

public interface ServerStream {
    int streamId();
    boolean isClosed();
    void onFrame(Frame frame);
    void closeClean();
    void closeReset();
    void closeFromPeer();
    
    /** Reads one chunk from target socket. Returns bytes read, 0 on EOF, -1 on error. */
    int readTargetChunk(byte[] buffer) throws IOException;
    
    /** Builds a DATA frame from bytes read off the target. */
    Frame buildDataFrame(byte[] chunk, int length);
    
    /** Sends target bytes as a DATA frame. */
    void sendTargetBytes(byte[] chunk, int length);
    
    /** Releases budget for delivered bytes. */
    void releaseBudget(int bytes);
}
```

- [ ] **Step 3: Update ServerStreamReader**

Change `ServerStream` → interface, no code changes.

- [ ] **Step 4: Update DefaultServerStreamFactory**

Change `Map<PlayerTunnelSession, Map<Integer, ServerStream>>` → still works.
Change `dialAndAttach` → create `DirectServerStream`.

- [ ] **Step 5: Update test files importing ServerStream**

```bash
grep -rn "import.*ServerStream\|new ServerStream" --include="*.java" src/test/
```

Update to `import dev.kifuko.mctransport.server.DirectServerStream` where constructing.

- [ ] **Step 6: Build and run tests**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :test --no-daemon
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: extract ServerStream interface, rename impl to DirectServerStream"
```

---

### Task 9: KcpServerStream

**Files:**
- Create: `src/main/java/dev/kifuko/mctransport/server/KcpServerStream.java`

- [ ] **Step 1: Create KcpServerStream**

```java
package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.kcp.KcpConfig;
import dev.kifuko.mctransport.kcp.KcpCore;
import dev.kifuko.mctransport.kcp.KcpOutput;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * KCP-backed server stream. Receives DATA frames, feeds into KCP for
 * reassembly, writes merged data to the target socket. Reads from target
 * socket are fed into KCP for coalescing before sending as DATA frames.
 */
public final class KcpServerStream implements ServerStream {

    private static final Logger LOG = Logger.getLogger(KcpServerStream.class.getName());

    private final PlayerTunnelSession session;
    private final int streamId;
    private final Socket targetSocket;
    private final BufferBudget budget;
    private final ReservationState reservations;
    private final KcpCore kcp;
    private final KcpConfig kcpConfig;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final byte protocolVersion;
    private long totalBytesRead;

    public KcpServerStream(PlayerTunnelSession session,
                           int streamId,
                           Socket targetSocket,
                           BufferBudget budget,
                           ReservationState reservations,
                           KcpConfig kcpConfig,
                           byte protocolVersion) {
        this.session = session;
        this.streamId = streamId;
        this.targetSocket = targetSocket;
        this.budget = budget;
        this.reservations = reservations;
        this.kcpConfig = kcpConfig;
        this.protocolVersion = protocolVersion;
        this.kcp = new KcpCore(streamId, new BridgeOutput());
        this.kcp.applyConfig(kcpConfig);
    }

    @Override public int streamId() { return streamId; }
    @Override public boolean isClosed() { return closed.get(); }

    @Override
    public void onFrame(Frame frame) {
        if (closed.get()) return;
        switch (frame.type()) {
            case DATA -> handleData(frame);
            case CLOSE, RESET, ERROR -> closeFromPeer();
        }
    }

    private void handleData(Frame frame) {
        if (frame.payloadLength() == 0) return;
        long now = System.currentTimeMillis();
        kcp.input(ByteBuffer.wrap(frame.payload()), true, now);
        kcp.update(now);

        try {
            for (;;) {
                ByteBuffer merged = kcp.recv();
                if (merged == null) break;
                byte[] bytes = new byte[merged.remaining()];
                merged.get(bytes);
                targetSocket.getOutputStream().write(bytes);
                targetSocket.getOutputStream().flush();
                budget.release(streamId, bytes.length, reservations);
            }
        } catch (IOException e) {
            closeReset();
        }
    }

    @Override
    public int readTargetChunk(byte[] buffer) {
        if (closed.get()) return -1;
        try {
            return targetSocket.getInputStream().read(buffer);
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public Frame buildDataFrame(byte[] chunk, int length) {
        // Not used in KCP mode — data goes through kcp.send()
        throw new UnsupportedOperationException("KCP mode uses internal frame building");
    }

    @Override
    public void sendTargetBytes(byte[] chunk, int length) {
        if (closed.get() || length <= 0) return;
        long now = System.currentTimeMillis();
        kcp.send(ByteBuffer.wrap(chunk, 0, length));
        kcp.update(now);
    }

    @Override
    public void releaseBudget(int bytes) {
        if (bytes > 0) budget.release(streamId, bytes, reservations);
    }

    @Override public void closeClean() {
        if (!closed.compareAndSet(false, true)) return;
        sendFrame(FrameType.CLOSE);
        closeResources();
    }

    @Override public void closeReset() {
        if (!closed.compareAndSet(false, true)) return;
        sendFrame(FrameType.RESET);
        closeResources();
    }

    @Override public void closeFromPeer() {
        if (!closed.compareAndSet(false, true)) return;
        closeResources();
    }

    private void sendFrame(FrameType type) {
        Frame f = Frame.createTrusted(protocolVersion, 0, streamId, type, (byte) 0, new byte[0]);
        session.bridge().send(f);
    }

    private void closeResources() {
        kcp.release();
        budget.releaseAll(streamId, reservations);
        try { targetSocket.close(); } catch (IOException ignored) {}
        session.registry().remove(streamId);
    }

    private class BridgeOutput implements KcpOutput {
        @Override
        public void out(ByteBuffer data, KcpCore kcp) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            Frame f = Frame.create(protocolVersion, 0, streamId,
                    FrameType.DATA, (byte) 0, bytes, kcpConfig.mss());
            session.bridge().send(f);
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :compileJava --no-daemon -q
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/kifuko/mctransport/server/KcpServerStream.java
git commit -m "feat: add KcpServerStream with KCP reassembly for server side"
```

---

### Task 10: RouteControlPayload — carry StreamMode in CONFIG_APPLY

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/protocol/RouteControlPayload.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java`
- Modify: `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSession.java`

- [ ] **Step 1: Extend Apply record to include mode**

```java
public record Apply(String listenHost, int listenPort, StreamMode mode) {
    // Backward compat: decode old payloads without mode → DIRECT
    public Apply(String listenHost, int listenPort) {
        this(listenHost, listenPort, StreamMode.DIRECT);
    }
}
```

- [ ] **Step 2: Update encodeApply / decodeApply**

```java
public static byte[] encodeApply(String listenHost, int listenPort, StreamMode mode) {
    // ... existing validation ...
    return ("{\"listenHost\":\"" + listenHost
            + "\",\"listenPort\":" + listenPort
            + ",\"streamMode\":\"" + mode.name() + "\"}")
            .getBytes(StandardCharsets.UTF_8);
}

public static Apply decodeApply(byte[] payload) {
    // ... existing parsing ...
    String modeStr;
    try {
        modeStr = extractString(body, "streamMode");
    } catch (ProtocolException e) {
        modeStr = null; // backward compat: old payloads without mode
    }
    StreamMode mode = modeStr != null ? StreamMode.fromString(modeStr) : StreamMode.DIRECT;
    return new Apply(host, port, mode);
}
```

- [ ] **Step 3: Update PlayerTunnelSession.sendConfigApply**

```java
private void sendConfigApply(RouteConfig route) {
    activeRoute = route;
    Frame f = Frame.createTrusted(PROTOCOL_VERSION, SESSION_ID, 0,
            FrameType.CONFIG_APPLY, (byte) 0,
            RouteControlPayload.encodeApply(
                    route.getListenHost(), route.getListenPort(), route.getMode()));
    bridge.send(f);
}
```

- [ ] **Step 4: Update ClientTunnelSession.handleConfigApply**

```java
private void handleConfigApply(Frame frame) {
    RouteControlPayload.Apply apply = RouteControlPayload.decodeApply(frame.payload());
    try {
        listenerController.apply(apply.listenHost(), apply.listenPort());
        routeApplied = true;
        this.streamMode = apply.mode();  // Store mode for later stream creation
        sendConfigAck(true, "listening on " + apply.listenHost() + ":" + apply.listenPort());
    } catch (IOException | RuntimeException e) {
        routeApplied = false;
        sendConfigAck(false, "failed to bind " + apply.listenHost() + ":" + apply.listenPort());
    }
}
```

Add `private volatile StreamMode streamMode = StreamMode.DIRECT;` to `ClientTunnelSession`.

- [ ] **Step 5: Build and commit**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :compileJava --no-daemon -q
git add -A
git commit -m "feat: carry StreamMode in CONFIG_APPLY route control payload"
```

---

### Task 11: Factory routing — StreamFactory mode dispatch

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/client/ClientStreamFactory.java`
- Modify: `src/main/java/dev/kifuko/mctransport/client/DefaultClientStreamFactory.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/ServerStreamFactory.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/DefaultServerStreamFactory.java`
- Modify: `src/main/java/dev/kifuko/mctransport/client/ClientTunnelSession.java`
- Modify: `src/main/java/dev/kifuko/mctransport/server/PlayerTunnelSession.java`

- [ ] **Step 1: Update ClientStreamFactory**

```java
// Add mode parameter:
ClientStream create(ClientTunnelSession session, int streamId, StreamMode mode);
```

- [ ] **Step 2: Update DefaultClientStreamFactory**

Add KcpConfig parameter. Keep backward compat constructor for tests:

```java
private final KcpConfig kcpConfig;

// Full constructor for production
public DefaultClientStreamFactory(BufferBudget budget,
                                  ReservationState reservations,
                                  int maxPayloadSize,
                                  KcpConfig kcpConfig) {
    this.budget = budget;
    this.reservations = reservations;
    this.maxPayloadSize = maxPayloadSize;
    this.kcpConfig = kcpConfig != null ? kcpConfig : new KcpConfig();
}

// Backward compat: KCP mode not available (used by tests)
public DefaultClientStreamFactory(BufferBudget budget,
                                  ReservationState reservations,
                                  int maxPayloadSize) {
    this(budget, reservations, maxPayloadSize, new KcpConfig());
}

@Override
public ClientStream create(ClientTunnelSession session, int streamId, StreamMode mode) {
    return switch (mode) {
        case KCP -> new KcpClientStream(session, streamId, budget, reservations, kcpConfig);
        case DIRECT -> new DirectClientStream(session, streamId, budget, reservations, maxPayloadSize);
    };
}
```

- [ ] **Step 3: Update ClientTunnelSession**

Add `StreamMode` field, set from `handleConfigApply`:

```java
private volatile StreamMode streamMode = StreamMode.DIRECT;

// In handleConfigApply, after successful route apply:
RouteControlPayload.Apply apply = RouteControlPayload.decodeApply(frame.payload());
// Extract mode from apply payload (extend RouteControlPayload.Apply record)
// ...
```

Wait, we need to pass stream mode through the CONFIG_APPLY frame. Let me extend `RouteControlPayload`:

```java
// Extend Apply record to include mode
public record Apply(String listenHost, int listenPort, StreamMode mode) {}

public static byte[] encodeApply(String listenHost, int listenPort, StreamMode mode) {
    // Add mode byte after port
}

public static Apply decodeApply(byte[] payload) {
    // Read mode byte
}
```

Update `PlayerTunnelSession.sendConfigApply` to include mode.

- [ ] **Step 4: Update ServerStreamFactory**

```java
ServerStream createServerStream(PlayerTunnelSession session, int streamId, Socket socket);
// No mode param needed — session knows its mode
void dialAndAttach(PlayerTunnelSession session, int streamId, StreamMode mode);
```

- [ ] **Step 5: Update DefaultServerStreamFactory**

```java
private final KcpConfig kcpConfig;

// Updated constructor
public DefaultServerStreamFactory(TargetTcpConnector connector, int maxPayloadSize,
                                  int readChunkSize, ExecutorService io,
                                  KcpConfig kcpConfig) {
    this.connector = connector;
    this.maxPayloadSize = maxPayloadSize;
    this.readChunkSize = readChunkSize;
    this.io = io;
    this.kcpConfig = kcpConfig != null ? kcpConfig : new KcpConfig();
}

// Backward compat constructor (tests) — KCP mode not available
public DefaultServerStreamFactory(TargetTcpConnector connector, int maxPayloadSize,
                                  int readChunkSize, ExecutorService io) {
    this(connector, maxPayloadSize, readChunkSize, io, new KcpConfig());
}

@Override
public void dialAndAttach(PlayerTunnelSession session, int streamId, StreamMode mode) {
    var route = session.activeRoute();
    if (route == null) { sendReset(session, streamId); return; }
    Socket socket;
    try {
        socket = connector.connect(route.getTargetHost(), route.getTargetPort());
    } catch (IOException e) { sendReset(session, streamId); return; }
    session.registry().registerServer(streamId);

    ServerStream stream = switch (mode) {
        case KCP -> new KcpServerStream(session, streamId, socket,
                session.budget(), session.reservations(),
                kcpConfig, PlayerTunnelSession.PROTOCOL_VERSION);
        case DIRECT -> new DirectServerStream(session, streamId, socket,
                session.budget(), session.reservations(),
                PlayerTunnelSession.PROTOCOL_VERSION, maxPayloadSize);
    };
    streams.computeIfAbsent(session, s -> new HashMap<>()).put(streamId, stream);

    // KCP mode: read loop feeds into KCP via sendTargetBytes()
    // Direct mode: read loop calls sendTargetBytes() which creates frames directly
    if (io != null) {
        ServerStreamReader reader = new ServerStreamReader(stream, readChunkSize, io);
        reader.onClose(() -> forget(session, streamId));
        reader.start();
    }
}
```

- [ ] **Step 6: Build and fix compilation errors**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :compileJava --no-daemon
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: route stream creation through StreamMode-aware factories"
```

---

### Task 12: Command parsing — add mode parameter

**Files:**
- Modify: `src/main/java/dev/kifuko/mctransport/server/RouteCommandService.java`
- Modify: `src/fabric1211/main/java/.../McTransportCommands.java`
- Modify: `src/fabric1201/main/java/.../McTransportCommands.java`

- [ ] **Step 1: Update RouteCommandService**

```java
public String setRoute(UUID uuid, String playerName, int listenPort,
                       String targetHost, int targetPort, StreamMode mode) {
    RouteConfig route = new RouteConfig(uuid, playerName,
            listenPort, targetHost, targetPort, mode);
    store.setRoute(route);
    applier.apply(uuid);
    return "Set route for " + route.getPlayerName() + " (" + uuid + "): "
            + route.getListenHost() + ":" + route.getListenPort()
            + " -> " + route.getTargetHost() + ":" + route.getTargetPort()
            + " (mode=" + route.getMode() + ")";
}
```

- [ ] **Step 2: Update fabric1211 McTransportCommands**

```java
// In setRoute handler, parse optional 6th argument:
StreamMode mode = StreamMode.DIRECT;
if (args.length >= 6) {
    try {
        mode = StreamMode.fromString(args[5]);
    } catch (IllegalArgumentException e) {
        // feedback error to player
        return 1;
    }
}
String result = service.setRoute(uuid, playerName, port, host, targetPort, mode);
```

- [ ] **Step 3: Same for fabric1201**

- [ ] **Step 4: Build and test**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :compileJava --no-daemon -q
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add optional mode parameter to /mctransport set command"
```

---

### Task 13: KCP integration test

**Files:**
- Create: `src/test/java/dev/kifuko/mctransport/kcp/KcpStreamIntegrationTest.java`

- [ ] **Step 1: Create end-to-end test with FakeTunnelBridge**

```java
package dev.kifuko.mctransport.kcp;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.client.*;
import dev.kifuko.mctransport.config.RouteConfig;
import dev.kifuko.mctransport.config.ServerConfig;
import dev.kifuko.mctransport.net.FakeTunnelBridge;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.StreamMode;
import dev.kifuko.mctransport.server.*;
import dev.kifuko.mctransport.stream.StreamRegistry;
import org.junit.jupiter.api.*;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class KcpStreamIntegrationTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final int LISTEN_PORT = 25590;
    private static final int TARGET_PORT = 10001;

    private ExecutorService io;
    private FakeTunnelBridge clientBridge, serverBridge;

    @BeforeEach
    void setUp() throws Exception {
        io = Executors.newCachedThreadPool();
        clientBridge = new FakeTunnelBridge();
        serverBridge = new FakeTunnelBridge();

        // Wire bridges so frames sent on client appear on server and vice versa
        clientBridge.setOutboundListener(frame -> serverBridge.injectInbound(frame));
        serverBridge.setOutboundListener(frame -> clientBridge.injectInbound(frame));
    }

    @AfterEach
    void tearDown() {
        io.shutdownNow();
    }

    @Test
    void kcpRoundTripEcho() throws Exception {
        // Start echo server on target port
        ServerSocket echoServer = new ServerSocket(TARGET_PORT);
        Thread echoThread = new Thread(() -> {
            try {
                Socket s = echoServer.accept();
                s.getInputStream().transferTo(s.getOutputStream());
            } catch (Exception ignored) {}
        });
        echoThread.setDaemon(true);
        echoThread.start();

        // Setup server session with KCP mode
        RouteConfig route = new RouteConfig(PLAYER, "TestPlayer", LISTEN_PORT,
                "127.0.0.1", TARGET_PORT, StreamMode.KCP);
        TargetTcpConnector connector = new TargetTcpConnector(3, io);
        BufferBudget budget = new BufferBudget(1048576, 33554432);
        ReservationState reservations = new ReservationState();
        KcpConfig kcpConfig = new KcpConfig(8192, 128, 128);

        DefaultServerStreamFactory serverFactory = new DefaultServerStreamFactory(
                connector, 1048576, 4096, io, kcpConfig);
        RouteStore routeStore = new RouteStore();
        routeStore.setRoute(route);

        PlayerTunnelSession serverSession = new PlayerTunnelSession(PLAYER,
                serverBridge, ServerConfig.disabled(), routeStore,
                new StreamRegistry(64, false), budget, reservations,
                connector, System.currentTimeMillis(), serverFactory);

        // Setup client session with KCP mode
        DefaultClientStreamFactory clientFactory = new DefaultClientStreamFactory(
                budget, reservations, 1048576, kcpConfig);
        ClientTunnelSession clientSession = new ClientTunnelSession(clientBridge,
                new StreamRegistry(64, true), clientFactory,
                System.currentTimeMillis(),
                new DynamicLocalTcpListenerController(LISTEN_PORT, io));

        // Simulate CONFIG_APPLY with KCP mode
        // (In real scenario, server pushes this on player join)
        serverSession.sendRouteIfConfigured();
        // Pump frames
        pumpFrames();

        // Open a client stream
        ClientStream stream = clientSession.openLocalStream();
        assertNotNull(stream);

        // Connect to local listener port
        Socket clientSocket = new Socket("127.0.0.1", LISTEN_PORT);
        stream.attach(clientSocket, new byte[8192]);
        pumpFrames();

        // Write data
        clientSocket.getOutputStream().write("Hello KCP!".getBytes(StandardCharsets.UTF_8));
        clientSocket.getOutputStream().flush();
        Thread.sleep(200);
        pumpFrames();

        // Read echo
        byte[] response = new byte[100];
        int len = clientSocket.getInputStream().read(response);
        String echoed = new String(response, 0, len, StandardCharsets.UTF_8);
        assertEquals("Hello KCP!", echoed);

        stream.closeSocketAndRelease();
        echoServer.close();
    }

    private void pumpFrames() {
        // Trigger frame delivery by ticking sessions
        // In FakeTunnelBridge, frames are delivered synchronously via outboundListener
        // So the wiring above handles it
    }
}
```

- [ ] **Step 2: Run integration test**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :test --tests "dev.kifuko.mctransport.kcp.KcpStreamIntegrationTest" --no-daemon
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dev/kifuko/mctransport/kcp/KcpStreamIntegrationTest.java
git commit -m "test: add KCP end-to-end integration test with FakeTunnelBridge"
```

---

### Task 14: Run full test suite + fix regressions

- [ ] **Step 1: Run all tests**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew :test --no-daemon
```

- [ ] **Step 2: Fix any failures**

Common expected issues:
- `DefaultServerStreamFactory` constructor changed (added `KcpConfig` param) — update test setups
- `DefaultClientStreamFactory` constructor changed — update test setups  
- RouteConfig callers that now have 6-arg constructor but use 5-arg compat

- [ ] **Step 3: Verify DIRECT mode tests still pass**

The existing `InMemoryTunnelIntegrationTest`, `LoopbackTcpTransportTest`, etc. should all pass unchanged with DIRECT mode.

- [ ] **Step 4: Commit any fixes**

```bash
git add -A && git commit -m "fix: update test setups for new factory constructors"
```

---

### Task 15: Final verification + version bump

- [ ] **Step 1: Full clean build and test**

```bash
cd /Users/kifuko/dev/mc_port_forward_tool && ./gradlew clean :test --no-daemon
```

- [ ] **Step 2: Bump version to 0.2.3**

```bash
# Update build.gradle version
```

- [ ] **Step 3: Final commit**

```bash
git commit -m "chore: bump version to 0.2.3"
```

---
