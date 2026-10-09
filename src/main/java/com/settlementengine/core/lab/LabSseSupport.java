package com.settlementengine.core.lab;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Opens server-sent-event streams within the per-IP and global caps. A connection lives at most the configured
 * timeout (default 5 minutes); the front-end hook reconnects, or falls back to polling.
 */
@LabComponent
public class LabSseSupport {

    private final SseConnectionRegistry registry;
    private final LabProperties props;

    public LabSseSupport(LabProperties props) {
        this.props = props;
        this.registry = new SseConnectionRegistry(props.sse().maxPerIp(), props.sse().maxTotal());
    }

    public int active() {
        return registry.active();
    }

    /** {@code wire} attaches the emitter to its event source and returns whatever must be closed on disconnect. */
    public SseEmitter open(HttpServletRequest request, Function<SseEmitter, AutoCloseable> wire) {
        String ip = ClientIp.of(request, props.trustForwardedFor());
        SseConnectionRegistry.Slot slot = registry.tryAcquire(ip)
                .orElseThrow(() -> new LabBusyException("Too many live streams open from this address or on the server; "
                        + "the page will fall back to polling."));
        SseEmitter emitter = new SseEmitter(props.sse().idleTimeoutMinutes() * 60_000L);
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        Runnable cleanup = () -> {
            slot.close();
            AutoCloseable s = subscription.getAndSet(null);
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // nothing useful to do
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            emitter.complete();
            cleanup.run();
        });
        emitter.onError(e -> cleanup.run());
        try {
            subscription.set(wire.apply(emitter));
        } catch (RuntimeException e) {
            cleanup.run();
            throw e;
        }
        return emitter;
    }
}
