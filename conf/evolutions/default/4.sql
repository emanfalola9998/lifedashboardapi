# --- !Ups

-- Optimistic locking: each save bumps the version, and a save only succeeds if the client
-- sends the version it last saw. Existing rows start at 1.
ALTER TABLE dashboard_data ADD COLUMN version BIGINT NOT NULL DEFAULT 1;

# --- !Downs

ALTER TABLE dashboard_data DROP COLUMN version;
