package de.zalando.aruha.nakadi.controller;

import de.zalando.aruha.nakadi.domain.Subscription;
import de.zalando.aruha.nakadi.domain.SubscriptionBase;
import de.zalando.aruha.nakadi.exceptions.DuplicatedSubscriptionException;
import de.zalando.aruha.nakadi.exceptions.InternalNakadiException;
import de.zalando.aruha.nakadi.exceptions.NakadiException;
import de.zalando.aruha.nakadi.exceptions.NoSuchEventTypeException;
import de.zalando.aruha.nakadi.problem.ValidationProblem;
import de.zalando.aruha.nakadi.repository.EventTypeRepository;
import de.zalando.aruha.nakadi.repository.db.SubscriptionDbRepository;
import de.zalando.aruha.nakadi.util.FeatureToggleService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

import javax.validation.Valid;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;
import static de.zalando.aruha.nakadi.util.FeatureToggleService.Feature.HIGH_LEVEL_API;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.zalando.problem.MoreStatus.UNPROCESSABLE_ENTITY;
import static org.zalando.problem.spring.web.advice.Responses.create;

@RestController
@RequestMapping(value = "/subscriptions")
public class SubscriptionController {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionController.class);

    private final SubscriptionDbRepository subscriptionRepository;

    private final EventTypeRepository eventTypeRepository;

    private final FeatureToggleService featureToggleService;

    @Autowired
    public SubscriptionController(final SubscriptionDbRepository subscriptionRepository,
                                  final EventTypeRepository eventTypeRepository,
                                  final FeatureToggleService featureToggleService) {
        this.subscriptionRepository = subscriptionRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.featureToggleService = featureToggleService;
    }

    @RequestMapping(method = RequestMethod.POST)
    public ResponseEntity<?> createOrGetSubscription(@Valid @RequestBody final SubscriptionBase subscriptionBase,
                                                     final Errors errors, final NativeWebRequest nativeWebRequest) {

        if (!featureToggleService.isFeatureEnabled(HIGH_LEVEL_API)) {
            return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
        }
        if (errors.hasErrors()) {
            return create(new ValidationProblem(errors), nativeWebRequest);
        }

        try {
            // check that event types exist
            final List<String> noneExistingEventTypes = newArrayList();
            for (final String etName : subscriptionBase.getEventTypes()) {
                try {
                    eventTypeRepository.findByName(etName);
                } catch (NoSuchEventTypeException e) {
                    noneExistingEventTypes.add(etName);
                }
            }
            if (!noneExistingEventTypes.isEmpty()) {
                final String errorMessage = "Failed to create subscription, event type(s) not found: '" +
                        StringUtils.join(noneExistingEventTypes, "','") + "'";
                LOG.debug(errorMessage);
                return create(UNPROCESSABLE_ENTITY, errorMessage, nativeWebRequest);
            }

            // generate subscription id and try to create subscription in DB
            final Subscription subscription = subscriptionRepository.createSubscription(subscriptionBase);
            return new ResponseEntity<>(subscription, HttpStatus.CREATED);

        } catch (final DuplicatedSubscriptionException e) {
            try {
                // if the subscription with such parameters already exists - return it instead of creating a new one
                final Subscription existingSubscription = subscriptionRepository.getSubscription(
                        subscriptionBase.getOwningApplication(), subscriptionBase.getEventTypes(),
                        subscriptionBase.getConsumerGroup());
                return new ResponseEntity<>(existingSubscription, HttpStatus.OK);

            } catch (final NakadiException ex) {
                LOG.error("Error occurred during fetching existing subscription", ex);
                return create(INTERNAL_SERVER_ERROR, ex.getProblemMessage(), nativeWebRequest);
            }
        } catch (final InternalNakadiException e) {
            LOG.error("Error occurred during subscription creation", e);
            return create(e.asProblem(), nativeWebRequest);
        }
    }

}
