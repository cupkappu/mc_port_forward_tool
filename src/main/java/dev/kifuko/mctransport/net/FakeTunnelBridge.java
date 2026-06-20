package dev.kifuko.mctransport.net;

import dev.kifuko.mctransport.protocol.Frame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory {@link TunnelBridge} used by tests. Records sent frames and
 * allows test code to inject inbound frames.
 */
public final class FakeTunnelBridge implements TunnelBridge {

    private final List<Frame> sent = new ArrayList<>();
    private Receiver receiver;
    private boolean closed;

    public synchronized List<Frame> sentFrames() {
        return Collections.unmodifiableList(new ArrayList<>(sent));
    }

    public synchronized void clearSent() {
        sent.clear();
    }

    /** Append a frame to the sent list without firing any receiver. */
    public synchronized void peekSent(Frame frame) {
        sent.add(frame);
    }

    /** Outbound listener: invoked by {@link #send(Frame)} after the frame
     *  has been recorded. Tests wire this to forward frames to a peer's
     *  {@link #injectInbound(Frame)} so the bridge never loops on itself. */
    private Receiver outboundListener;

    public synchronized void setOutboundListener(Receiver listener) {
        this.outboundListener = listener;
    }

    @Override
    public void send(Frame frame) {
        Receiver ol;
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("bridge is closed");
            }
            sent.add(frame);
            ol = outboundListener;
        }
        if (ol != null) {
            ol.onFrame(frame);
        }
    }

    @Override
    public synchronized void setReceiver(Receiver receiver) {
        this.receiver = receiver;
    }

    /** Injects an inbound frame into the receiver, if one is registered. */
    public void injectInbound(Frame frame) {
        Receiver r;
        synchronized (this) {
            r = receiver;
        }
        if (r == null) {
            // No receiver: silent drop, mirroring real Minecraft where
            // frames arriving before the mod is ready are discarded.
            return;
        }
        r.onFrame(frame);
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    public synchronized boolean isClosed() {
        return closed;
    }
}
