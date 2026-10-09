CREATE TABLE IF NOT EXISTS telemetry_events (
    event_id UUID PRIMARY KEY,
    vehicle_id VARCHAR(100) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    event_timestamp TIMESTAMPTZ NOT NULL
);
