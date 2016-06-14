package de.zalando.aruha.nakadi.service.subscription.state;

import de.zalando.aruha.nakadi.service.subscription.zk.ZKSubscription;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PartitionData {
    private final ZKSubscription subscription;
    private final NavigableMap<Long, String> nakadiEvents = new TreeMap<>();
    private Long commitOffset;
    private Long sentOffset;
    private long lastSendMillis;
    private int keepAlivesInARow;
    private static final Logger LOG = LoggerFactory.getLogger(PartitionData.class);

    PartitionData(final ZKSubscription subscription, final Long commitOffset) {
        this.subscription = subscription;
        this.commitOffset = commitOffset;
        this.sentOffset = commitOffset;
        this.lastSendMillis = System.currentTimeMillis();
    }

    SortedMap<Long, String> takeEventsToStream(final long currentTimeMillis, final int batchSize, final long batchTimeoutMillis) {
        if (nakadiEvents.size() >= batchSize || (currentTimeMillis - lastSendMillis) > batchTimeoutMillis) {
            lastSendMillis = currentTimeMillis;
            return extract(batchSize);
        } else {
            return null;
        }
    }

    Long getSentOffset() {
        return sentOffset;
    }

    long getLastSendMillis() {
        return lastSendMillis;
    }

    private SortedMap<Long, String> extract(final int count) {
        final SortedMap<Long, String> result = new TreeMap<>();
        for (int i = 0; i < Math.min(count, nakadiEvents.size()); ++i) {
            final Long offset = nakadiEvents.firstKey();
            result.put(offset, nakadiEvents.remove(offset));
        }
        if (!result.isEmpty()) {
            this.sentOffset = result.lastKey();
            this.keepAlivesInARow = 0;
        } else {
            this.keepAlivesInARow += 1;
        }
        return result;
    }

    int getKeepAlivesInARow() {
        return keepAlivesInARow;
    }

    /**
     * Ensures, that last commit and last send positions corresponds to offsets available in kafka.
     * The situation is possible whenever old subscriptions are stared. commit offset is N, but kafka
     * already deleted all the data with offsets > N (for example [N, M]). One need to start streaming with
     * new positions, and update commit offset as well (because it could happened that there are no messages to
     * stream according to window size)
     *
     * @param position First position available in kafka
     */
    void ensureDataAvailable(final long position) {
        if (position > commitOffset) {
            LOG.warn("Oldest kafka position is " + position + " and commit offset is " + commitOffset + ", updating");
            commitOffset = position;
        }
        if (position > sentOffset) {
            LOG.warn("Oldest kafka position is " + position + " and sent offset is " + commitOffset + ", updating");
            sentOffset = position;
        }
    }

    static class CommitResult {
        final boolean seekOnKafka;
        final long commitedCount;

        private CommitResult(final boolean seekOnKafka, final long commitedCount) {
            this.seekOnKafka = seekOnKafka;
            this.commitedCount = commitedCount;
        }
    }

    CommitResult onCommitOffset(final Long offset) {
        boolean seekKafka = false;
        if (offset > sentOffset) {
            LOG.error("Commit in future: current: " + sentOffset + ", commited " + commitOffset + " will skip sending obsolete data");
            seekKafka = true;
        }
        final long commited;
        if (offset >= commitOffset) {
            commited = offset - commitOffset;
            commitOffset = offset;
        } else {
            LOG.error("Commits in past are evil!: Commiting in " + offset + " while current commit is " + commitOffset);
            seekKafka = true;
            commitOffset = offset;
            sentOffset = commitOffset;
            commited = 0;
        }
        while (nakadiEvents.floorKey(commitOffset) != null) {
            nakadiEvents.pollFirstEntry();
        }
        return new CommitResult(seekKafka, commited);
    }

    void addEvent(final long offset, final String event) {
        if (offset > (sentOffset + nakadiEvents.size() + 1)) {
            LOG.warn("Adding event that is too far from last sent. Dunno how it happend, but it is.");
        }
        nakadiEvents.put(offset, event);
    }

    void clearEvents() {
        nakadiEvents.clear();
    }

    boolean isCommited() {
        return sentOffset <= commitOffset;
    }

    long getUnconfirmed() {
        return sentOffset - commitOffset;
    }

    public ZKSubscription getSubscription() {
        return subscription;
    }
}
