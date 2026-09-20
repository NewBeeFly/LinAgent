package com.javaagent.agent.facade;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SerializedEmitSinkTest {

    @Test
    void concurrentEmittersDoNotLoseEvents() throws Exception {
        Sinks.Many<Object> raw = Sinks.many().unicast().onBackpressureBuffer();
        SerializedEmitSink sink = new SerializedEmitSink(raw);

        int threads = 8;
        int perThread = 200;
        int total = threads * perThread;
        List<Object> received = new CopyOnWriteArrayList<>();
        CountDownLatch receivedAll = new CountDownLatch(total);
        raw.asFlux().subscribe(e -> {
            received.add(e);
            receivedAll.countDown();
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perThread; j++) {
                    // 未包装的 unicast sink 并发至此会返回 FAIL_NON_SERIALIZED 丢事件
                    Sinks.EmitResult result = sink.tryEmitNext("e" + idx + "-" + j);
                    org.junit.jupiter.api.Assertions.assertEquals(Sinks.EmitResult.OK, result);
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(receivedAll.await(5, TimeUnit.SECONDS)).isTrue();
        sink.tryEmitComplete();

        // 无静默丢失：并发发射全部送达
        assertThat(received).hasSize(total);
    }

    @Test
    void delegatesCompleteAndFlux() {
        Sinks.Many<Object> raw = Sinks.many().unicast().onBackpressureBuffer();
        SerializedEmitSink sink = new SerializedEmitSink(raw);

        sink.tryEmitNext("a");
        sink.tryEmitComplete();

        List<Object> received = new CopyOnWriteArrayList<>();
        raw.asFlux().subscribe(received::add);
        assertThat(received).containsExactly("a");
    }
}
