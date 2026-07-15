package com.eventledger.gateway.service;

import com.eventledger.gateway.domain.EventRecord;

/**
 * @param record    the stored event, always the authoritative one (on a duplicate this is the
 *                  ORIGINAL, not the resubmitted payload)
 * @param duplicate true when this eventId had already been accepted — drives 200 vs 201
 */
public record SubmitOutcome(EventRecord record, boolean duplicate) {
}
