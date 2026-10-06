-- Documents can have a front and a back side (Cambodian ID card, driving licence and vehicle
-- registration card). A document is still reviewed once and expires once; its files move to
-- driver_document_files. Vehicle insurance is no longer collected.

-- Staging test data only: insurance documents can no longer be represented. Their encrypted files
-- stay in storage, unreferenced.
DELETE FROM driver.driver_documents WHERE type = 'VEHICLE_INSURANCE';

CREATE TABLE driver.driver_document_files (
    id            UUID          PRIMARY KEY,
    document_id   UUID          NOT NULL REFERENCES driver.driver_documents (id),
    side          VARCHAR(5)    NOT NULL CHECK (side IN ('FRONT', 'BACK')),
    -- File content lives in StorageService (encrypted); only metadata is here.
    storage_key   VARCHAR(200)  NOT NULL UNIQUE,
    content_type  VARCHAR(50)   NOT NULL,
    size_bytes    INTEGER       NOT NULL CHECK (size_bytes > 0),
    sha256        BYTEA         NOT NULL,
    CONSTRAINT uq_document_files_side UNIQUE (document_id, side)
);

-- Every existing document had exactly one file: it becomes the front side, at the same storage key.
INSERT INTO driver.driver_document_files (id, document_id, side, storage_key, content_type, size_bytes, sha256)
SELECT gen_random_uuid(), id, 'FRONT', storage_key, content_type, size_bytes, sha256
FROM driver.driver_documents;

ALTER TABLE driver.driver_documents
    DROP COLUMN storage_key,
    DROP COLUMN content_type,
    DROP COLUMN size_bytes,
    DROP COLUMN sha256;

ALTER TABLE driver.driver_documents
    DROP CONSTRAINT driver_documents_type_check,
    ADD CONSTRAINT ck_documents_type
        CHECK (type IN ('NATIONAL_ID', 'DRIVING_LICENSE', 'PROFILE_PHOTO', 'VEHICLE_REGISTRATION')),
    DROP CONSTRAINT ck_documents_vehicle,
    ADD CONSTRAINT ck_documents_vehicle CHECK ((type = 'VEHICLE_REGISTRATION') = (vehicle_id IS NOT NULL));
