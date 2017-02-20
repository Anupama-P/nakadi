package org.zalando.nakadi.service;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.echocat.jomon.runtime.concurrent.RetryForSpecifiedCountStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.zalando.nakadi.domain.NakadiCursor;
import org.zalando.nakadi.view.Cursor;
import org.zalando.nakadi.domain.CursorError;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.Subscription;
import org.zalando.nakadi.view.SubscriptionCursor;
import org.zalando.nakadi.exceptions.InternalNakadiException;
import org.zalando.nakadi.exceptions.InvalidCursorException;
import org.zalando.nakadi.exceptions.InvalidStreamIdException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.NakadiRuntimeException;
import org.zalando.nakadi.exceptions.NoSuchEventTypeException;
import org.zalando.nakadi.exceptions.NoSuchSubscriptionException;
import org.zalando.nakadi.exceptions.ServiceUnavailableException;
import org.zalando.nakadi.exceptions.Try;
import org.zalando.nakadi.repository.EventTypeRepository;
import org.zalando.nakadi.repository.TopicRepository;
import org.zalando.nakadi.repository.db.SubscriptionDbRepository;
import org.zalando.nakadi.repository.zookeeper.ZooKeeperHolder;
import org.zalando.nakadi.service.subscription.model.Partition;
import org.zalando.nakadi.service.subscription.zk.CuratorZkSubscriptionClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.text.MessageFormat.format;
import static org.echocat.jomon.runtime.concurrent.Retryer.executeWithRetry;

@Component
public class CursorsService {

    private static final Logger LOG = LoggerFactory.getLogger(CursorsService.class);

    private static final String PATH_ZK_OFFSET = "/nakadi/subscriptions/{0}/topics/{1}/{2}/offset";
    private static final String PATH_ZK_PARTITION = "/nakadi/subscriptions/{0}/topics/{1}/{2}";
    private static final String PATH_ZK_PARTITIONS = "/nakadi/subscriptions/{0}/topics/{1}";
    private static final String PATH_ZK_SESSION = "/nakadi/subscriptions/{0}/sessions/{1}";

    private static final String ERROR_COMMUNICATING_WITH_ZOOKEEPER = "Error communicating with zookeeper";

    private static final int COMMIT_CONFLICT_RETRY_TIMES = 5;

    private final ZooKeeperHolder zkHolder;
    private final TopicRepository topicRepository;
    private final SubscriptionDbRepository subscriptionRepository;
    private final EventTypeRepository eventTypeRepository;
    private final CursorTokenService cursorTokenService;
    private final CursorConverter cursorConverter;

    @Autowired
    public CursorsService(final ZooKeeperHolder zkHolder,
                          final TopicRepository topicRepository,
                          final SubscriptionDbRepository subscriptionRepository,
                          final EventTypeRepository eventTypeRepository,
                          final CursorTokenService cursorTokenService,
                          final CursorConverter cursorConverter) {
        this.zkHolder = zkHolder;
        this.topicRepository = topicRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.cursorTokenService = cursorTokenService;
        this.cursorConverter = cursorConverter;
    }

    public Map<SubscriptionCursor, Boolean> commitCursors(final String streamId, final String subscriptionId,
                                                          final List<SubscriptionCursor> cursors)
            throws NakadiException, InvalidCursorException {

        validateStreamId(cursors, streamId, subscriptionId);

        return cursors.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        Try.<SubscriptionCursor, Boolean>wrap(cursor -> processCursor(subscriptionId, cursor))
                                .andThen(Try::getOrThrow)));
    }

    private void validateStreamId(final List<SubscriptionCursor> cursors, final String streamId,
                                  final String subscriptionId) throws NakadiException {

        if (!isActiveSession(subscriptionId, streamId)) {
            throw new InvalidStreamIdException("Session with stream id " + streamId + " not found");
        }

        try {
            final List<SubscriptionCursor> invalidCursors = cursors.stream()
                    .filter(cursor -> {
                        try {
                            final EventType eventType = eventTypeRepository.findByName(cursor.getEventType());
                            final String partitionSession = getPartitionSession(subscriptionId, eventType.getTopic(),
                                    cursor.getPartition());
                            return !streamId.equals(partitionSession);
                        } catch (final InternalNakadiException | NoSuchEventTypeException |
                                ServiceUnavailableException e) {
                            throw new NakadiRuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());

            if (!invalidCursors.isEmpty()) {
                throw new InvalidStreamIdException("Cursors " + invalidCursors + " cannot be committed with stream id "
                        + streamId);
            }
        } catch (final NakadiRuntimeException e) {
            LOG.error("Error occurred when validating cursors stream ids", e);
            throw new ServiceUnavailableException("Unexpected error occurred when commiting cursors");
        }

    }

    private String getPartitionSession(final String subscriptionId, final String topic, final String partition)
            throws ServiceUnavailableException {
        try {
            final String partitionPath = format(PATH_ZK_PARTITION, subscriptionId, topic, partition);
            final byte[] partitionData = zkHolder.get().getData().forPath(partitionPath);
            final Partition.PartitionKey partitionKey = new Partition.PartitionKey(topic, partition);
            return CuratorZkSubscriptionClient.deserializeNode(partitionKey, partitionData).getSession();
        } catch (final Exception e) {
            LOG.error(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
            throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER);
        }
    }

    private boolean isActiveSession(final String subscriptionId, final String streamId)
            throws ServiceUnavailableException {
        try {
            final String sessionsPath = format(PATH_ZK_SESSION, subscriptionId, streamId);
            return zkHolder.get().checkExists().forPath(sessionsPath) != null;
        } catch (final Exception e) {
            LOG.error(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
            throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER);
        }
    }

    private boolean processCursor(final String subscriptionId, final SubscriptionCursor cursor)
            throws InternalNakadiException, NoSuchEventTypeException, InvalidCursorException,
            ServiceUnavailableException, NoSuchSubscriptionException {

        SubscriptionCursor cursorToProcess = cursor;
        if (Cursor.BEFORE_OLDEST_OFFSET.equals(cursor.getOffset())) {
            cursorToProcess = new SubscriptionCursor(cursor.getPartition(), "-1", cursor.getEventType(),
                    cursor.getCursorToken());
        }
        final EventType eventType = eventTypeRepository.findByName(cursorToProcess.getEventType());
        final NakadiCursor toProcess = new NakadiCursor(
                eventType.getTopic(),
                cursorToProcess.getPartition(),
                cursorToProcess.getOffset());

        topicRepository.validateCommitCursor(toProcess);
        return commitCursor(subscriptionId, eventType.getTopic(), cursorToProcess);
    }

    private boolean commitCursor(final String subscriptionId, final String eventType, final SubscriptionCursor cursor)
            throws ServiceUnavailableException, NoSuchSubscriptionException, InvalidCursorException {

        final String offsetPath = format(PATH_ZK_OFFSET, subscriptionId, eventType, cursor.getPartition());
        try {
            @SuppressWarnings("unchecked")
            final Boolean committed = executeWithRetry(() -> {
                        final Stat stat = new Stat();
                        final byte[] currentOffsetData = zkHolder.get()
                                .getData()
                                .storingStatIn(stat)
                                .forPath(offsetPath);
                        final String currentOffset = new String(currentOffsetData, Charsets.UTF_8);

                        // Yep, here we are trying to hack a little. This code
                        // should be removed during timelines implementation
                        final NakadiCursor cursorToCommit = new NakadiCursor(eventType, cursor.getPartition(),
                                cursor.getOffset());
                        final NakadiCursor currentCursor = new NakadiCursor(eventType, cursor.getPartition(),
                                currentOffset);
                        if (topicRepository.compareOffsets(cursorToCommit, currentCursor) > 0) {
                            zkHolder.get()
                                    .setData()
                                    .withVersion(stat.getVersion())
                                    .forPath(offsetPath, cursor.getOffset().getBytes(Charsets.UTF_8));
                            return true;
                        } else {
                            return false;
                        }
                    },
                    new RetryForSpecifiedCountStrategy<Boolean>(COMMIT_CONFLICT_RETRY_TIMES)
                            .withExceptionsThatForceRetry(KeeperException.BadVersionException.class));

            return Optional.ofNullable(committed).orElse(false);

        } catch (final IllegalArgumentException e) {
            throw new InvalidCursorException(CursorError.INVALID_FORMAT, cursor);
        } catch (final Exception e) {
            throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
        }
    }

    public List<SubscriptionCursor> getSubscriptionCursors(final String subscriptionId) throws NakadiException {
        final Subscription subscription = subscriptionRepository.getSubscription(subscriptionId);
        final ImmutableList.Builder<SubscriptionCursor> cursorsListBuilder = ImmutableList.builder();

        for (final String eventType : subscription.getEventTypes()) {

            final String topic = eventTypeRepository.findByName(eventType).getTopic();
            final String partitionsPath = format(PATH_ZK_PARTITIONS, subscriptionId, topic);
            try {
                final List<String> partitions = zkHolder.get().getChildren().forPath(partitionsPath);

                final List<SubscriptionCursor> eventTypeCursors = partitions.stream()
                        .map(partition -> readCursor(subscriptionId, topic, partition, eventType))
                        .collect(Collectors.toList());

                cursorsListBuilder.addAll(eventTypeCursors);
            } catch (final KeeperException.NoNodeException nne) {
                LOG.debug(nne.getMessage(), nne);
                return Collections.emptyList();
            } catch (final Exception e) {
                LOG.error(e.getMessage(), e);
                throw new ServiceUnavailableException(ERROR_COMMUNICATING_WITH_ZOOKEEPER, e);
            }
        }
        return cursorsListBuilder.build();
    }

    private SubscriptionCursor readCursor(final String subscriptionId, final String topic, final String partition,
                                          final String eventType)
            throws RuntimeException {
        try {
            final String offsetPath = format(PATH_ZK_OFFSET, subscriptionId, topic, partition);
            final NakadiCursor nakadiCursor = new NakadiCursor(
                    topic,
                    partition,
                    new String(zkHolder.get().getData().forPath(offsetPath), Charsets.UTF_8));
            return cursorConverter.convert(nakadiCursor, eventType, cursorTokenService.generateToken());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

}
