CREATE TABLE IF NOT EXISTS telemetry_events (
    event_id UUID PRIMARY KEY,
    vehicle_id VARCHAR(100) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    event_timestamp TIMESTAMPTZ NOT NULL,
    speed DOUBLE PRECISION CHECK (
        speed IS NULL OR (speed >= 0 AND speed < 'Infinity'::DOUBLE PRECISION)
    )
);

ALTER TABLE telemetry_events
    ADD COLUMN IF NOT EXISTS speed DOUBLE PRECISION CHECK (
        speed IS NULL OR (speed >= 0 AND speed < 'Infinity'::DOUBLE PRECISION)
    );

CREATE INDEX IF NOT EXISTS idx_telemetry_events_vehicle_timestamp_event
    ON telemetry_events (vehicle_id, event_timestamp, event_id);
