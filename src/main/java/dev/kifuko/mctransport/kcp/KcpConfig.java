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
