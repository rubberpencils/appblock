-- Feedback and support requests sent from the app (POST /api/feedback), one
-- row a message. What gets searched or sorted by has its own column; the rest of
-- what the app reports about its setup is JSON in `diagnostics`.
CREATE TABLE feedback (
  id              TEXT PRIMARY KEY,   -- a UUID: the app's for the message, so a retry isn't a second row
  received_at     TEXT NOT NULL,      -- UTC, ISO 8601
  kind            TEXT NOT NULL CHECK (kind IN ('feedback', 'support')),   -- support wants an answer
  message         TEXT NOT NULL,
  contact         TEXT,               -- the sender's email, if they gave one
  sender_id       TEXT,               -- a random id the app made up once: the same person's messages share it
  app_version     TEXT,
  app_build       INTEGER,
  android_version TEXT,
  android_sdk     INTEGER,
  device          TEXT,               -- maker and model
  installed_at    TEXT,               -- when the app was first installed
  updated_at      TEXT,               -- when it was last updated
  diagnostics     TEXT,               -- JSON
  country         TEXT,               -- two letters, from Cloudflare
  emailed         INTEGER NOT NULL DEFAULT 0   -- 1 once the maintainer was mailed about it
);
CREATE INDEX feedback_received_at ON feedback (received_at);
CREATE INDEX feedback_sender_id ON feedback (sender_id);
-- The few that were never mailed, found without reading the rest.
CREATE INDEX feedback_unmailed ON feedback (emailed) WHERE emailed = 0;
