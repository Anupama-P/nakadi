package de.zalando.aruha.nakadi.service.subscription.model;

import java.util.Collection;

public class Partition {
    public static class PartitionKey {
        public final String topic;

        public final String partition;

        public PartitionKey(final String topic, final String partition) {
            this.topic = topic;
            this.partition = partition;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final PartitionKey that = (PartitionKey) o;

            return topic.equals(that.topic) && partition.equals(that.partition);

        }

        @Override
        public int hashCode() {
            int result = topic.hashCode();
            result = 31 * result + partition.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "{" + topic + ':' + partition + '}';
        }
    }

    public enum State {
        UNASSIGNED,
        REASSIGNING,
        ASSIGNED,;
    }

    private final PartitionKey key;
    private final String session;
    private final String nextSession;
    private final State state;

    public Partition(final PartitionKey key, final String session, final String nextSession, final State state) {
        this.key = key;
        this.session = session;
        this.nextSession = nextSession;
        this.state = state;
    }

    public Partition toState(final State state, final String session, final String nextSession) {
        return new Partition(key, session, nextSession, state);
    }

    public Partition moveToSessionId(final String sessionId, final Collection<String> existingSessionIds) {
        switch (state) {
            case UNASSIGNED:
                return toState(State.ASSIGNED, sessionId, null);
            case ASSIGNED:
                if (sessionId.equals(this.session) || !existingSessionIds.contains(this.session)) {
                    return toState(State.ASSIGNED, sessionId, null);
                } else {
                    return toState(State.REASSIGNING, this.session, sessionId);
                }
            case REASSIGNING:
                if (!existingSessionIds.contains(this.session)) {
                    return toState(State.ASSIGNED, sessionId, null);
                } else if (this.session.equals(sessionId)) {
                    return toState(State.ASSIGNED, sessionId, null);
                } else {
                    return toState(State.REASSIGNING, this.session, sessionId);
                }
            default:
                throw new IllegalStateException("Unsupported current state " + state);
        }
    }

    public boolean mustBeRebalanced(final Collection<String> activeSessionIds) {
        switch (state) {
            case UNASSIGNED:
                return true;
            case ASSIGNED:
                return !activeSessionIds.contains(session);
            case REASSIGNING:
                return !activeSessionIds.contains(session) || !activeSessionIds.contains(nextSession);
            default:
                throw new IllegalStateException("State of partition " + state + " is not supported");
        }

    }

    public PartitionKey getKey() {
        return key;
    }

    public State getState() {
        return state;
    }

    public String getSession() {
        return session;
    }

    public String getNextSession() {
        return nextSession;
    }

    public String getSessionOrNextSession() {
        if (state == State.REASSIGNING) {
            return nextSession;
        }
        return session;
    }

    @Override
    public String toString() {
        return key + "->" + state + ":" + session + "->" + nextSession;
    }
}
