package org.zalando.nakadi.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.zalando.nakadi.domain.ItemsWrapper;
import org.zalando.nakadi.domain.SubscriptionEventTypeStats;
import org.zalando.nakadi.exceptions.runtime.ErrorGettingCursorTimeLagException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.runtime.FeatureNotAvailableException;
import org.zalando.nakadi.exceptions.runtime.InconsistentStateException;
import org.zalando.nakadi.exceptions.runtime.NoSuchEventTypeException;
import org.zalando.nakadi.exceptions.runtime.ServiceTemporarilyUnavailableException;
import org.zalando.nakadi.exceptions.runtime.TimeLagStatsTimeoutException;
import org.zalando.nakadi.service.FeatureToggleService;
import org.zalando.nakadi.service.WebResult;
import org.zalando.nakadi.service.subscription.SubscriptionService;
import org.zalando.nakadi.service.subscription.SubscriptionService.StatsMode;
import org.zalando.problem.Problem;
import org.zalando.problem.spring.web.advice.Responses;

import javax.annotation.Nullable;
import javax.ws.rs.core.Response;
import java.util.Set;

import static javax.ws.rs.core.Response.Status.NOT_IMPLEMENTED;
import static javax.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static org.zalando.nakadi.service.FeatureToggleService.Feature.HIGH_LEVEL_API;
import static org.zalando.problem.MoreStatus.UNPROCESSABLE_ENTITY;


@RestController
@RequestMapping(value = "/subscriptions")
public class SubscriptionController {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionController.class);

    private final FeatureToggleService featureToggleService;
    private final SubscriptionService subscriptionService;

    @Autowired
    public SubscriptionController(final FeatureToggleService featureToggleService,
                                  final SubscriptionService subscriptionService) {
        this.featureToggleService = featureToggleService;
        this.subscriptionService = subscriptionService;
    }

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<?> listSubscriptions(
            @Nullable @RequestParam(value = "owning_application", required = false) final String owningApplication,
            @Nullable @RequestParam(value = "event_type", required = false) final Set<String> eventTypes,
            @RequestParam(value = "show_status", required = false, defaultValue = "false") final boolean showStatus,
            @RequestParam(value = "limit", required = false, defaultValue = "20") final int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") final int offset,
            final NativeWebRequest request) {
        featureToggleService.checkFeatureOn(HIGH_LEVEL_API);

        return WebResult.wrap(
                () -> subscriptionService.listSubscriptions(owningApplication, eventTypes, showStatus, limit, offset),
                request);
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
    public ResponseEntity<?> getSubscription(@PathVariable("id") final String subscriptionId,
                                             final NativeWebRequest request) {
        featureToggleService.checkFeatureOn(HIGH_LEVEL_API);

        return WebResult.wrap(() -> subscriptionService.getSubscription(subscriptionId), request);
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteSubscription(@PathVariable("id") final String subscriptionId,
                                                final NativeWebRequest request) {
        featureToggleService.checkFeatureOn(HIGH_LEVEL_API);

        return WebResult.wrap(() -> subscriptionService.deleteSubscription(subscriptionId), request,
                HttpStatus.NO_CONTENT);
    }

    @RequestMapping(value = "/{id}/stats", method = RequestMethod.GET)
    public ItemsWrapper<SubscriptionEventTypeStats> getSubscriptionStats(
            @PathVariable("id") final String subscriptionId,
            @RequestParam(value = "show_time_lag", required = false, defaultValue = "false") final boolean showTimeLag)
            throws NakadiException, InconsistentStateException, ServiceTemporarilyUnavailableException {
        featureToggleService.checkFeatureOn(HIGH_LEVEL_API);

        final StatsMode statsMode = showTimeLag ? StatsMode.TIMELAG : StatsMode.NORMAL;
        return subscriptionService.getSubscriptionStat(subscriptionId, statsMode);
    }

    @ExceptionHandler(NakadiException.class)
    public ResponseEntity<Problem> handleNakadiException(final NakadiException ex,
                                                         final NativeWebRequest request) {
        LOG.debug(ex.getMessage(), ex);
        return Responses.create(ex.asProblem(), request);
    }

    @ExceptionHandler(FeatureNotAvailableException.class)
    public ResponseEntity<Problem> handleFeatureTurnedOff(final FeatureNotAvailableException ex,
                                                          final NativeWebRequest request) {
        LOG.debug(ex.getMessage(), ex);
        return Responses.create(Problem.valueOf(NOT_IMPLEMENTED, ex.getMessage()), request);
    }

    @ExceptionHandler(ErrorGettingCursorTimeLagException.class)
    public ResponseEntity<Problem> handleTimeLagException(final ErrorGettingCursorTimeLagException ex,
                                                          final NativeWebRequest request) {
        LOG.debug(ex.getMessage(), ex);
        return Responses.create(Problem.valueOf(UNPROCESSABLE_ENTITY, ex.getMessage()), request);
    }

    @ExceptionHandler(InconsistentStateException.class)
    public ResponseEntity<Problem> handleInconsistentState(final InconsistentStateException ex,
                                                           final NativeWebRequest request) {
        LOG.debug(ex.getMessage(), ex);
        return Responses.create(
                Problem.valueOf(
                        SERVICE_UNAVAILABLE,
                        ex.getMessage()),
                request);
    }

    @ExceptionHandler(ServiceTemporarilyUnavailableException.class)
    public ResponseEntity<Problem> handleServiceTemporarilyUnavailable(final ServiceTemporarilyUnavailableException ex,
                                                                       final NativeWebRequest request) {
        LOG.debug(ex.getMessage(), ex);
        return Responses.create(
                Problem.valueOf(
                        SERVICE_UNAVAILABLE,
                        ex.getMessage()),
                request);
    }

    @ExceptionHandler(TimeLagStatsTimeoutException.class)
    public ResponseEntity<Problem> handleTimeLagStatsTimeoutException(final TimeLagStatsTimeoutException e,
                                                                      final NativeWebRequest request) {
        LOG.warn(e.getMessage());
        return Responses.create(Response.Status.REQUEST_TIMEOUT, e.getMessage(), request);
    }

    @ExceptionHandler(NoSuchEventTypeException.class)
    public ResponseEntity<Problem> handleNoSuchEventTypeException(final NoSuchEventTypeException e,
                                                                      final NativeWebRequest request) {
        LOG.debug(e.getMessage());
        return Responses.create(Response.Status.NOT_FOUND, e.getMessage(), request);
    }

}
