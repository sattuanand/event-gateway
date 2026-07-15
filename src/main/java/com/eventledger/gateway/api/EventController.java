package com.eventledger.gateway.api;

import com.eventledger.gateway.api.dto.EventRequest;
import com.eventledger.gateway.api.dto.EventResponse;
import com.eventledger.gateway.service.EventMapper;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.SubmitOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/events")
@Validated
public class EventController {

    private final EventService eventService;
    private final EventMapper mapper;

    public EventController(EventService eventService, EventMapper mapper) {
        this.eventService = eventService;
        this.mapper = mapper;
    }

    /**
     * 201 for a newly accepted event, 200 for a duplicate. The distinction matters: 201 means "this
     * changed something", 200 means "you already told us, nothing changed, stop worrying".
     * Either way the body is the ORIGINAL stored event, never the resubmitted payload.
     */
    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        SubmitOutcome outcome = eventService.submit(request);
        HttpStatus status = outcome.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(mapper.toResponse(outcome.record()));
    }

    @GetMapping("/{id}")
    public EventResponse getById(@PathVariable("id") String id) {
        return mapper.toResponse(eventService.get(id));
    }

    /** Ordered by eventTimestamp — chronological by when it happened upstream, not by when it landed. */
    @GetMapping
    public List<EventResponse> listByAccount(
            @RequestParam("account") @NotBlank(message = "account query parameter is required") String accountId) {
        return eventService.listByAccount(accountId).stream()
                .map(mapper::toResponse)
                .toList();
    }
}
