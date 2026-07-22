-- Tracks how many times the outbox sweeper (or a client resubmit) has re-attempted a FAILED
-- event's downstream call. Used to cap automatic redrive of chronically-failing ("poison pill")
-- events — see OutboxSweeper / EventRepository.findRedrivable.
ALTER TABLE events ADD COLUMN redrive_count INTEGER NOT NULL DEFAULT 0;
