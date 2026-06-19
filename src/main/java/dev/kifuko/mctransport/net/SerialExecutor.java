package dev.kifuko.mctransport.net;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Serializes tasks onto a backing executor while preserving submission order.
 */
public final class SerialExecutor implements Executor {

    private final Executor executor;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private Runnable active;

    public SerialExecutor(Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        this.executor = executor;
    }

    @Override
    public synchronized void execute(Runnable command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        tasks.add(() -> {
            try {
                command.run();
            } finally {
                scheduleNext();
            }
        });
        if (active == null) {
            scheduleNext();
        }
    }

    private synchronized void scheduleNext() {
        active = tasks.poll();
        if (active != null) {
            executor.execute(active);
        }
    }
}
