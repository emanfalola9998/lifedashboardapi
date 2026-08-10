# --- !Ups

CREATE TABLE trading212_keys (
  user_id    VARCHAR(255) PRIMARY KEY,
  api_key    TEXT         NOT NULL,
  created_at TIMESTAMPTZ  DEFAULT NOW()
);

# --- !Downs

DROP TABLE IF EXISTS trading212_keys;
