-- Admin web console: in-person driver onboarding.

-- Driving licence number: encrypted like the national ID, with a blind index for uniqueness.
ALTER TABLE driver.drivers
    ADD COLUMN license_number_enc  BYTEA,
    ADD COLUMN license_number_hash BYTEA UNIQUE,
    ADD COLUMN home_zone           VARCHAR(30) CHECK (home_zone IN ('CHAMKAR_MON', 'DAUN_PENH', 'PRAMPIR_MAKARA',
        'TUOL_KOUK', 'DANGKAO', 'MEAN_CHEY', 'RUSSEY_KEO', 'SEN_SOK', 'POR_SENCHEY', 'CHROY_CHANGVAR', 'PREK_PNOV',
        'CHBAR_AMPOV', 'BOENG_KENG_KANG', 'KAMBOUL')),
    ADD CONSTRAINT ck_drivers_license_number CHECK ((license_number_enc IS NULL) = (license_number_hash IS NULL));

-- Search by name (pg_trgm is installed by V1).
CREATE INDEX ix_drivers_full_name_trgm ON driver.drivers USING gin (lower(full_name) gin_trgm_ops);

-- Vehicle owner: the driver, a third party (e.g. rented tuk-tuks) or the company.
ALTER TABLE driver.vehicles
    ADD COLUMN ownership   VARCHAR(20) NOT NULL DEFAULT 'DRIVER' CHECK (ownership IN ('DRIVER', 'THIRD_PARTY', 'COMPANY')),
    ADD COLUMN owner_name  VARCHAR(200),
    ADD COLUMN owner_phone VARCHAR(16),
    ADD CONSTRAINT ck_vehicles_third_party_owner CHECK (ownership <> 'THIRD_PARTY'
        OR (owner_name IS NOT NULL AND owner_phone IS NOT NULL));

-- Assignments get a validity period [valid_from, valid_to); valid_to NULL = open-ended.
-- Ending an assignment sets valid_to. Overlaps are prevented by the application (row locks on the
-- driver and the vehicle), since an exclusion constraint would need the btree_gist extension.
ALTER TABLE driver.driver_vehicle_assignments
    ADD COLUMN valid_from TIMESTAMPTZ,
    ADD COLUMN valid_to   TIMESTAMPTZ;
UPDATE driver.driver_vehicle_assignments SET valid_from = assigned_at, valid_to = unassigned_at;
DROP INDEX driver.uq_assignment_active_driver;
DROP INDEX driver.uq_assignment_active_vehicle;
ALTER TABLE driver.driver_vehicle_assignments
    DROP CONSTRAINT ck_assignment_period,
    DROP COLUMN unassigned_at,
    ALTER COLUMN valid_from SET NOT NULL,
    ADD CONSTRAINT ck_assignment_validity CHECK (valid_to IS NULL OR valid_to >= valid_from);
ALTER TABLE driver.driver_vehicle_assignments RENAME COLUMN unassigned_by TO ended_by;
CREATE INDEX ix_assignments_driver ON driver.driver_vehicle_assignments (driver_id, valid_from);
CREATE INDEX ix_assignments_vehicle ON driver.driver_vehicle_assignments (vehicle_id, valid_from);

-- Who uploaded a document: NULL = the driver themselves, otherwise the staff member.
ALTER TABLE driver.driver_documents ADD COLUMN uploaded_by UUID;
CREATE INDEX ix_documents_pending ON driver.driver_documents (uploaded_at) WHERE status = 'PENDING_REVIEW';

-- A driver's audit trail includes entries on their documents (details.driverId).
CREATE INDEX ix_audit_logs_driver ON audit.audit_logs ((details ->> 'driverId')) WHERE details ? 'driverId';
