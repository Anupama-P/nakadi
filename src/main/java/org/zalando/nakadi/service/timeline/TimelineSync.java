package org.zalando.nakadi.service.timeline;

import java.io.Closeable;
import java.util.function.Consumer;

/**
 * Interface for timeline locking.
 * Nodes looks like:
 * <pre>
 * - timelines
 *  + - lock                    lock for timeline versions synchronization
 *  + - version: {version}      monotonically_incremented long value
 *  + - locked_et:
 *     | - et_1                 Event types that are paused right now
 *     | - et_2                 The same goes here
 *  + - nodes                   nakadi nodes
 *    + - {node1}: {version}    Each nakadi node exposes version being used to this node
 * </pre>
 */
public interface TimelineSync {
    /**
     * Call while publishing to event type. When publishing complete - call {@link Closeable#close()}
     *
     * @param eventType Event type to publish to
     * @return Closeable object, that should be closed when publishing complete
     */
    Closeable workWithEventType(String eventType) throws InterruptedException;

    /**
     * Lock event type publishing while switching to next timeline
     *
     * @param eventType Event type to lock publishing to.
     * @param timeoutMs Timeout for sync operation.
     */
    void startTimelineUpdate(String eventType, long timeoutMs) throws InterruptedException;

    /**
     * Release publishing lock to event type
     *
     * @param eventType Event type to unlock publishing to.
     */
    void finishTimelineUpdate(String eventType) throws InterruptedException;

    interface ListenerRegistration {
        void cancel();
    }

    /**
     * Register listener for timelines modification. It will be called when everyone is pretty sure that new timeline is
     * available in db and each node knows about it.
     *
     * @param eventType Event type to register listener for
     * @param listener  Listener that will accept event type.
     * @return Registration for listener. one should call cancel once registration is not needed anymore.
     */
    ListenerRegistration registerTimelineChangeListener(String eventType, Consumer<String> listener);
}
