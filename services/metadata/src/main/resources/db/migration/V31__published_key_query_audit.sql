-- Published-key provenance contains no bearer, key secret, proof, or raw admission.
ALTER TABLE metadata.semantic_query_audit
    ADD COLUMN api_key_id BIGINT CHECK (api_key_id>0),
    ADD COLUMN admission_id UUID,
    ADD COLUMN request_id VARCHAR(128) CHECK (request_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    ADD COLUMN owner_uid BIGINT CHECK (owner_uid>0);
ALTER TABLE metadata.semantic_query_audit ADD CONSTRAINT semantic_query_key_provenance
    CHECK ((api_key_id IS NULL AND admission_id IS NULL AND owner_uid IS NULL)
        OR (api_key_id IS NOT NULL AND admission_id IS NOT NULL AND owner_uid IS NOT NULL AND request_id IS NOT NULL));
CREATE INDEX semantic_query_admission ON metadata.semantic_query_audit(admission_id) WHERE admission_id IS NOT NULL;
