# --- !Ups

CREATE TABLE google_tokens (
  user_id       VARCHAR(255) PRIMARY KEY,
  access_token  TEXT         NOT NULL,
  refresh_token TEXT,
  expires_at    BIGINT       NOT NULL,
  created_at    TIMESTAMPTZ  DEFAULT NOW(),
  updated_at    TIMESTAMPTZ  DEFAULT NOW()
);

# --- !Downs

DROP TABLE IF EXISTS google_tokens;
