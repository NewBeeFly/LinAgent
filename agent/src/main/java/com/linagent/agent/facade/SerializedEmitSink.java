package com.linagent.agent.facade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 线程安全的 Sinks.Many 包装：三路发射方（ThinkingTap 模型流线程、
 * EventEmittingToolInterceptor 工具执行线程——SAA 并行 tool call 时会有多个）
 * 共享同一 unicast sink，裸 tryEmitNext 并发会返回 FAIL_NON_SERIALIZED 静默丢事件。
 *
 * 本包装将 tryEmit* 收口到同一监视器上串行执行（临界区极小，仅 sink 内部入队），
 * 并对失败结果打日志而非静默：
 * - FAIL_NON_SERIALIZED / FAIL_OVERFLOW → warn（真实数据丢失风险）
 * - FAIL_TERMINATED / FAIL_CANCELLED / FAIL_ZERO_SUBSCRIBER → debug（终止后迟发，属预期边界）
 */
public class SerializedEmitSink implements Sinks.Many<Object> {

    private static final Logger log = LoggerFactory.getLogger(SerializedEmitSink.class);

    private final Sinks.Many<Object> delegate;

    public SerializedEmitSink(Sinks.Many<Object> delegate) {
        this.delegate = delegate;
    }

    @Override
    public synchronized Sinks.EmitResult tryEmitNext(Object o) {
        Sinks.EmitResult result = delegate.tryEmitNext(o);
        if (result != Sinks.EmitResult.OK) {
            logResult("tryEmitNext", result);
        }
        return result;
    }

    @Override
    public synchronized Sinks.EmitResult tryEmitComplete() {
        return delegate.tryEmitComplete();
    }

    @Override
    public synchronized Sinks.EmitResult tryEmitError(Throwable error) {
        Sinks.EmitResult result = delegate.tryEmitError(error);
        if (result != Sinks.EmitResult.OK) {
            logResult("tryEmitError", result);
        }
        return result;
    }

    private void logResult(String op, Sinks.EmitResult result) {
        if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED
            || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            log.debug("{} 发射失败（终止后迟发/无订阅者，预期边界）: {}", op, result);
        } else {
            log.warn("{} 发射失败，事件可能丢失: {}", op, result);
        }
    }

    // ---- 以下方法不在发射热路径，直接委托 ----

    @Override
    public void emitNext(Object o, Sinks.EmitFailureHandler failureHandler) {
        delegate.emitNext(o, failureHandler);
    }

    @Override
    public void emitComplete(Sinks.EmitFailureHandler failureHandler) {
        delegate.emitComplete(failureHandler);
    }

    @Override
    public void emitError(Throwable error, Sinks.EmitFailureHandler failureHandler) {
        delegate.emitError(error, failureHandler);
    }

    @Override
    public int currentSubscriberCount() {
        return delegate.currentSubscriberCount();
    }

    @Override
    public Flux<Object> asFlux() {
        return delegate.asFlux();
    }

    @Override
    public Object scanUnsafe(Attr key) {
        return delegate.scanUnsafe(key);
    }
}
