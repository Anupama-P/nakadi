package de.zalando.aruha.nakadi.controller;

import de.zalando.aruha.nakadi.domain.Cursor;
import de.zalando.aruha.nakadi.exceptions.InvalidCursorException;
import de.zalando.aruha.nakadi.exceptions.NakadiException;
import de.zalando.aruha.nakadi.service.CursorsCommitService;
import de.zalando.aruha.nakadi.util.FeatureToggleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.zalando.problem.Problem;

import java.util.List;

import static de.zalando.aruha.nakadi.util.FeatureToggleService.Feature.HIGH_LEVEL_API;
import static org.springframework.http.ResponseEntity.noContent;
import static org.springframework.http.ResponseEntity.ok;
import static org.zalando.problem.MoreStatus.UNPROCESSABLE_ENTITY;
import static org.zalando.problem.spring.web.advice.Responses.create;

@RestController
public class CursorsController {

    private final CursorsCommitService cursorsCommitService;
    private final FeatureToggleService featureToggleService;

    @Autowired
    public CursorsController(final CursorsCommitService cursorsCommitService,
                             final FeatureToggleService featureToggleService) {
        this.cursorsCommitService = cursorsCommitService;
        this.featureToggleService = featureToggleService;
    }

    @RequestMapping(value = "/subscriptions/{subscriptionId}/cursors", method = RequestMethod.PUT)
    public ResponseEntity<?> commitCursors(@PathVariable("subscriptionId") final String subscriptionId,
                                           @RequestBody final List<Cursor> cursors,
                                           final NativeWebRequest request) {

        if (!featureToggleService.isFeatureEnabled(HIGH_LEVEL_API)) {
            return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
        }
        try {
            boolean allCommitted = cursorsCommitService.commitCursors(subscriptionId, cursors);
            return allCommitted ? ok().build() : noContent().build();

        } catch (final NakadiException e) {
            return create(e.asProblem(), request);
        } catch (InvalidCursorException e) {
            return create(Problem.valueOf(UNPROCESSABLE_ENTITY, e.getMessage()), request);
        }
    }
}
