package dev.kifuko.mctransport.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SerialExecutorTest {

    @Test
    void runsOneTaskAtATimeInSubmissionOrder() {
        Deque<Runnable> backingQueue = new ArrayDeque<>();
        SerialExecutor executor = new SerialExecutor(backingQueue::add);
        List<Integer> calls = new ArrayList<>();

        executor.execute(() -> calls.add(1));
        executor.execute(() -> calls.add(2));

        assertEquals(1, backingQueue.size());
        backingQueue.removeFirst().run();
        assertEquals(List.of(1), calls);
        assertEquals(1, backingQueue.size());
        backingQueue.removeFirst().run();
        assertEquals(List.of(1, 2), calls);
        assertEquals(0, backingQueue.size());
    }
}
