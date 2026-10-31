CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE reservations
    ADD CONSTRAINT reservations_no_overlapping_confirmed
    EXCLUDE USING gist (
    property_id WITH =,
    daterange(start_date, end_date, '[)') WITH &&
)
WHERE (status = 'CONFIRMED');