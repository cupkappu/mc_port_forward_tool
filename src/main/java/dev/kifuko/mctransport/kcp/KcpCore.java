package dev.kifuko.mctransport.kcp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
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

    /** no delay min rto */
    public static final int IKCP_RTO_NDL = 30;
    /** normal min rto */
    public static final int IKCP_RTO_MIN = 100;
    public static final int IKCP_RTO_DEF = 200;
    public static final int IKCP_RTO_MAX = 60000;
    /** cmd: push data */
    public static final byte IKCP_CMD_PUSH = 81;
    /** cmd: ack */
    public static final byte IKCP_CMD_ACK  = 82;
    /** cmd: window probe (ask) */
    public static final byte IKCP_CMD_WASK = 83;
    /** cmd: window size (tell) */
    public static final byte IKCP_CMD_WINS = 84;
    /** need to send IKCP_CMD_WASK */
    public static final int IKCP_ASK_SEND = 1;
    /** need to send IKCP_CMD_WINS */
    public static final int IKCP_ASK_TELL = 2;
    public static final int IKCP_WND_SND = 32;
    public static final int IKCP_WND_RCV = 32;
    public static final int IKCP_MTU_DEF = 1400;
    public static final int IKCP_INTERVAL = 100;
    /** KCP segment header size without ack mask */
    public static final int IKCP_OVERHEAD = 24;
    /** maximum dead link count before disconnect */
    public static final int IKCP_DEADLINK = 20;
    public static final int IKCP_THRESH_INIT = 2;
    public static final int IKCP_THRESH_MIN = 2;
    /** 7 secs to probe window size */
    public static final int IKCP_PROBE_INIT = 7000;
    /** up to 120 secs to probe window */
    public static final int IKCP_PROBE_LIMIT = 120000;

    // ---- KCP state ----

    /** conversation id */
    private int conv;
    /** maximum transmission unit */
    private int mtu = IKCP_MTU_DEF;
    /** maximum segment size = mtu - IKCP_OVERHEAD */
    private int mss;
    /** state: 0=normal, -1=closed */
    private int state;
    /** sent but unacknowledged */
    private long sndUna;
    /** next send sequence number */
    private long sndNxt;
    /** next receive sequence number */
    private long rcvNxt;
    private long tsLastack;
    /** slow start threshold */
    private int ssthresh = IKCP_THRESH_INIT;
    /** RTT variance */
    private int rxRttval;
    /** smoothed RTT */
    private int rxSrtt;
    /** retransmission timeout */
    private int rxRto = IKCP_RTO_DEF;
    /** minimum RTO */
    private int rxMinrto = IKCP_RTO_MIN;
    /** send window (segments) */
    private int sndWnd = IKCP_WND_SND;
    /** receive window (segments) */
    private int rcvWnd = IKCP_WND_RCV;
    /** remote window size */
    private int rmtWnd = IKCP_WND_RCV;
    /** congestion window */
    private int cwnd;
    /** probe flags bitmask */
    private int probe;
    /** internal flush interval in ms */
    private int interval = IKCP_INTERVAL;
    /** next flush timestamp */
    private long tsFlush = IKCP_INTERVAL;
    /** nodelay mode enabled */
    private boolean nodelay;
    /** has update been called at least once */
    private boolean updated;
    /** probe timestamp */
    private long tsProbe;
    /** probe wait counter */
    private int probeWait;
    /** congestion increment counter */
    private int incr;
    /** ack immediately on receive */
    private boolean ackNoDelay;
    /** stream mode (coalesce writes) */
    private boolean stream;
    /** disable congestion control */
    private boolean nocwnd;
    /** fast retransmit threshold */
    private int fastresend;
    /** reserved header bytes (for FEC/CRC) */
    private int reserved;

    // ---- Queues (replacing ReItrLinkedList with standard LinkedList) ----

    /** segments waiting to be sent */
    private final LinkedList<Segment> sndQueue = new LinkedList<>();
    /** segments sent and awaiting ack */
    private final LinkedList<Segment> sndBuf   = new LinkedList<>();
    /** received segments, ordered */
    private final LinkedList<Segment> rcvQueue = new LinkedList<>();
    /** received segments, unordered buffer */
    private final LinkedList<Segment> rcvBuf   = new LinkedList<>();

    private long[] acklist = new long[8];
    private int ackcount;

    private KcpOutput output;
    private Object user;
    private final long startTicks = System.currentTimeMillis();

    // ---- Construction ----

    /**
     * Creates a KCP instance.
     *
     * @param conv   conversation id
     * @param output callback for outgoing segments
     */
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
        setMtu(cfg.mss() + IKCP_OVERHEAD);
    }

    // ---- Public API: send / recv / input / update / flush / check ----

    /**
     * Queues application data for sending. In stream mode, multiple calls
     * are coalesced into MTU-sized segments.
     *
     * @param buf data to send; position..limit bytes are consumed
     * @return 0 on success, -2 if too many fragments
     */
    public int send(ByteBuffer buf) {
        int len = buf.remaining();
        if (len == 0) return -1;

        // stream mode: append to previous segment if possible
        if (stream && !sndQueue.isEmpty()) {
            Segment last = sndQueue.peekLast();
            if (last != null) {
                ByteBuffer lastData = last.data;
                int lastLen = lastData.remaining();
                if (lastLen < mss) {
                    int capacity = mss - lastLen;
                    int extend = Math.min(len, capacity);
                    byte[] merged = new byte[lastLen + extend];
                    lastData.position(0);
                    lastData.get(merged, 0, lastLen);
                    buf.get(merged, lastLen, extend);
                    last.data = ByteBuffer.wrap(merged);
                    len = buf.remaining();
                    if (len == 0) return 0;
                }
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
     * Receives one complete reassembled application message.
     *
     * @return a ByteBuffer containing the message, or null if no complete
     *         message is available
     */
    public ByteBuffer recv() {
        return mergeRecv();
    }

    /**
     * Feeds raw KCP data received from the transport into the protocol.
     *
     * @param data    received bytes (little-endian KCP segments)
     * @param regular true for regular data, false for FEC-corrected
     * @param current current time in milliseconds
     * @return 0 on success, -1 insufficient header, -2 insufficient data,
     *         -3 bad command, -4 conversation id mismatch
     */
    public int input(ByteBuffer data, boolean regular, long current) {
        long oldSndUna = sndUna;
        if (data == null || data.remaining() < IKCP_OVERHEAD) return -1;

        long latest = 0;
        boolean flag = false;
        long uintCurrent = long2Uint(currentMs(current));

        while (data.remaining() >= IKCP_OVERHEAD) {
            int conv;
            byte cmd;
            short frg;
            int wnd;
            long ts, sn, una;
            int len;
            Segment seg;

            // Read KCP header in little-endian order
            int pos = data.position();

            conv = readIntLE(data);
            cmd = data.get();
            frg = (short) (data.get() & 0xFF);
            wnd = readShortLE(data) & 0xFFFF;
            ts = readIntLE(data) & 0xFFFFFFFFL;
            sn = readIntLE(data) & 0xFFFFFFFFL;
            una = readIntLE(data) & 0xFFFFFFFFL;
            len = readIntLE(data);

            if (conv != this.conv) return -4;
            if (data.remaining() < len || len < 0) return -2;
            if (cmd != IKCP_CMD_PUSH && cmd != IKCP_CMD_ACK
                    && cmd != IKCP_CMD_WASK && cmd != IKCP_CMD_WINS) {
                return -3;
            }

            // Update remote window from regular data
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
                    // do nothing
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

        // Congestion control (only when not disabled)
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

    /**
     * Flushes pending KCP data through the output callback.
     *
     * @param ackOnly if true, only flush acknowledgements
     * @param current current time in milliseconds
     * @return next flush interval in ms
     */
    public long flush(boolean ackOnly, long current) {
        current = currentMs(current);

        // Build ACK segment template
        Segment seg = new Segment();
        seg.conv = conv;
        seg.cmd = IKCP_CMD_ACK;
        seg.wnd = wndUnused();
        seg.una = rcvNxt;

        ByteBuffer buffer = null;

        // Flush acknowledgements
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

        // Probe remote window if zero
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

        // Flush window probes
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

        // Calculate send window
        int cwnd0 = Math.min(sndWnd, rmtWnd);
        if (!nocwnd) cwnd0 = Math.min(this.cwnd, cwnd0);

        // Move data from snd_queue to snd_buf
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

        // Calculate fast retransmit threshold
        int resent = fastresend > 0 ? fastresend : Integer.MAX_VALUE;

        // Flush data segments
        int change = 0;
        boolean lost = false;
        long minrto = interval;

        for (Iterator<Segment> itr = sndBuf.iterator(); itr.hasNext(); ) {
            Segment segment = itr.next();
            boolean needsend = false;

            if (segment.xmit == 0) {
                // First transmission
                needsend = true;
                segment.rto = rxRto;
                segment.resendts = current + segment.rto;
            } else if (segment.fastack >= resent) {
                // Fast retransmit
                needsend = true;
                segment.fastack = 0;
                segment.rto = rxRto;
                segment.resendts = current + segment.rto;
                change++;
            } else if (segment.fastack > 0 && newSegsCount == 0) {
                // Early retransmit
                needsend = true;
                segment.fastack = 0;
                segment.rto = rxRto;
                segment.resendts = current + segment.rto;
                change++;
            } else if (itimediff(current, segment.resendts) >= 0) {
                // Timeout retransmit
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
                    ByteBuffer dup = segData.duplicate();
                    dup.position(0);
                    buffer.put(dup);
                }

                long rto = itimediff(segment.resendts, current);
                if (rto > 0 && rto < minrto) minrto = rto;
            }
        }

        // Flush remaining buffer
        flushBuffer(buffer);

        // Update congestion window
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

    /** Returns the total number of segments waiting to be sent or awaiting ack. */
    public int waitSnd() { return sndBuf.size() + sndQueue.size(); }

    public int getConv() { return conv; }
    public void setConv(int conv) { this.conv = conv; }

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

    private static int itimediff(long later, long earlier) {
        return (int) (later - earlier);
    }

    private static int ibound(int lower, int middle, int upper) {
        return Math.min(Math.max(lower, middle), upper);
    }

    private int wndUnused() {
        return rcvQueue.size() < rcvWnd ? rcvWnd - rcvQueue.size() : 0;
    }

    // ---- Little-endian byte reading helpers ----

    private static int readIntLE(ByteBuffer buf) {
        byte b0 = buf.get();
        byte b1 = buf.get();
        byte b2 = buf.get();
        byte b3 = buf.get();
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | (b3 << 24);
    }

    private static short readShortLE(ByteBuffer buf) {
        byte b0 = buf.get();
        byte b1 = buf.get();
        return (short) ((b0 & 0xFF) | ((b1 & 0xFF) << 8));
    }

    // ---- Buffer management ----

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

    /**
     * Encodes a KCP segment header into a ByteBuffer in little-endian order.
     */
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
        buf.order(ByteOrder.BIG_ENDIAN); // restore default
    }

    // ---- ACK / RTT / retransmission logic ----

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

    // ---- Receive data path ----

    private boolean parseData(Segment newSeg) {
        long sn = newSeg.sn;
        if (itimediff(sn, rcvNxt + rcvWnd) >= 0 || itimediff(sn, rcvNxt) < 0) {
            return true; // discard out-of-window segment
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
                if (listItr.hasPrevious()) listItr.next();
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

    // ---- Cleanup ----

    /** Releases all internal queues and marks the instance as closed. */
    public void release() {
        sndQueue.clear();
        sndBuf.clear();
        rcvQueue.clear();
        rcvBuf.clear();
        state = -1;
    }

    // ---- Internal Segment (simple allocation, no object pooling) ----

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
