# --- !Ups

CREATE TABLE IF NOT EXISTS dashboard_data (
  id TEXT PRIMARY KEY,
  data JSONB NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO dashboard_data (id, data) 
VALUES ('default-user', '{}') 
ON CONFLICT (id) DO NOTHING;

# --- !Downs

DROP TABLE IF EXISTS dashboard_data;