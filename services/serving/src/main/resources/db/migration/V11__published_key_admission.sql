-- Existing rows deliberately remain unbound; a username is not an identity migration.
ALTER TABLE serving.service_api_key ADD COLUMN owner_uid BIGINT, ADD COLUMN owner_access_version BIGINT;
ALTER TABLE serving.service_api_key ADD CONSTRAINT ck_api_key_identity_pair CHECK (
    (owner_uid IS NULL AND owner_access_version IS NULL) OR
    (owner_uid IS NOT NULL AND owner_uid > 0 AND owner_access_version IS NOT NULL AND owner_access_version >= 0));
ALTER TABLE serving.service_api_key_application ADD COLUMN requester_uid BIGINT, ADD COLUMN requester_access_version BIGINT;
ALTER TABLE serving.service_api_key_application ADD CONSTRAINT ck_api_application_identity_pair CHECK (
    (requester_uid IS NULL AND requester_access_version IS NULL) OR
    (requester_uid IS NOT NULL AND requester_uid > 0 AND requester_access_version IS NOT NULL AND requester_access_version >= 0));
CREATE INDEX idx_api_application_uid ON serving.service_api_key_application(requester_uid,created_at DESC);

CREATE FUNCTION serving.protect_key_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='service_api_key' THEN
        IF TG_OP='INSERT' AND (NEW.owner_uid IS NULL OR NEW.owner_access_version IS NULL) THEN RAISE EXCEPTION 'key identity binding required'; END IF;
        IF TG_OP='UPDATE' AND (NEW.owner_uid,NEW.owner_username,NEW.owner_access_version) IS DISTINCT FROM (OLD.owner_uid,OLD.owner_username,OLD.owner_access_version) THEN RAISE EXCEPTION 'key identity is immutable'; END IF;
        IF TG_OP='UPDATE' AND OLD.status='revoked' AND NEW.status<>'revoked' THEN RAISE EXCEPTION 'revoked key is terminal'; END IF;
    ELSE
        IF TG_OP='INSERT' AND (NEW.requester_uid IS NULL OR NEW.requester_access_version IS NULL) THEN RAISE EXCEPTION 'application identity binding required'; END IF;
        IF TG_OP='UPDATE' AND (NEW.requester_uid,NEW.requester,NEW.requester_access_version) IS DISTINCT FROM (OLD.requester_uid,OLD.requester,OLD.requester_access_version) THEN RAISE EXCEPTION 'application identity is immutable'; END IF;
        IF NEW.status='approved' AND (NEW.requester_uid IS NULL OR NEW.requester_access_version IS NULL) AND (TG_OP='INSERT' OR OLD.status<>'approved') THEN RAISE EXCEPTION 'unbound application cannot be approved'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_key_identity BEFORE INSERT OR UPDATE ON serving.service_api_key FOR EACH ROW EXECUTE FUNCTION serving.protect_key_identity();
CREATE TRIGGER protect_application_identity BEFORE INSERT OR UPDATE ON serving.service_api_key_application FOR EACH ROW EXECUTE FUNCTION serving.protect_key_identity();

CREATE TABLE serving.published_key_admission (
    id UUID PRIMARY KEY,
    key_id BIGINT NOT NULL REFERENCES serving.service_api_key(id),
    owner_uid BIGINT NOT NULL CHECK(owner_uid>0),
    owner_username VARCHAR(128) NOT NULL,
    owner_access_version BIGINT NOT NULL CHECK(owner_access_version>=0),
    asset_id BIGINT NOT NULL CHECK(asset_id>0),
    asset_version VARCHAR(32) NOT NULL CHECK(asset_version ~ '^v[1-9][0-9]*$'),
    canonical_admission TEXT NOT NULL CHECK(octet_length(canonical_admission) BETWEEN 1 AND 16384),
    admission_sha256 VARCHAR(64) NOT NULL CHECK(admission_sha256 ~ '^[a-f0-9]{64}$'),
    parameters_sha256 VARCHAR(64) NOT NULL CHECK(parameters_sha256 ~ '^[a-f0-9]{64}$'),
    created_at_epoch_millis BIGINT NOT NULL CHECK(created_at_epoch_millis>0),
    expires_at_epoch_millis BIGINT NOT NULL,
    redeemed_at_epoch_millis BIGINT,
    CHECK(expires_at_epoch_millis>created_at_epoch_millis AND expires_at_epoch_millis-created_at_epoch_millis=30000),
    CHECK(redeemed_at_epoch_millis IS NULL OR (redeemed_at_epoch_millis>=created_at_epoch_millis AND redeemed_at_epoch_millis<expires_at_epoch_millis)),
    CHECK(admission_sha256=encode(sha256(convert_to(canonical_admission,'UTF8')),'hex'))
);
CREATE INDEX idx_published_key_admission_expiry ON serving.published_key_admission(expires_at_epoch_millis);
CREATE FUNCTION serving.protect_key_admission() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE p JSONB; k serving.service_api_key%ROWTYPE; t BIGINT;
BEGIN
    t:=floor(extract(epoch FROM clock_timestamp())*1000)::bigint;
    IF TG_OP='DELETE' THEN
        IF OLD.expires_at_epoch_millis>=t THEN RAISE EXCEPTION 'live admission cannot be deleted'; END IF;
        RETURN OLD;
    END IF;
    IF TG_OP='UPDATE' THEN
        IF (to_jsonb(NEW)-'redeemed_at_epoch_millis') IS DISTINCT FROM (to_jsonb(OLD)-'redeemed_at_epoch_millis') OR
            OLD.redeemed_at_epoch_millis IS NOT NULL OR NEW.redeemed_at_epoch_millis IS NULL OR
            t>=OLD.expires_at_epoch_millis OR NEW.redeemed_at_epoch_millis>t THEN RAISE EXCEPTION 'admission is immutable or consumed'; END IF;
        RETURN NEW;
    END IF;
    IF NEW.redeemed_at_epoch_millis IS NOT NULL OR NEW.created_at_epoch_millis>t OR NEW.expires_at_epoch_millis<=t THEN RAISE EXCEPTION 'invalid admission time'; END IF;
    SELECT * INTO k FROM serving.service_api_key WHERE id=NEW.key_id;
    IF NOT FOUND OR k.status<>'active' OR (k.expires_at IS NOT NULL AND k.expires_at<=clock_timestamp()) OR
        (k.owner_uid,k.owner_username,k.owner_access_version) IS DISTINCT FROM (NEW.owner_uid,NEW.owner_username,NEW.owner_access_version) THEN RAISE EXCEPTION 'admission key binding mismatch'; END IF;
    p:=NEW.canonical_admission::jsonb;
    IF p->'schemaVersion' IS DISTINCT FROM '1'::jsonb OR p->'admissionId' IS DISTINCT FROM to_jsonb(NEW.id::text) OR
        p->'keyId' IS DISTINCT FROM to_jsonb(NEW.key_id) OR
        p->'owner' IS DISTINCT FROM jsonb_build_object('uid',NEW.owner_uid,'username',NEW.owner_username,'accessVersion',NEW.owner_access_version) OR
        p->'asset'->'id' IS DISTINCT FROM to_jsonb(NEW.asset_id) OR p->'asset'->'version' IS DISTINCT FROM to_jsonb(NEW.asset_version) OR
        p->'parametersSha256' IS DISTINCT FROM to_jsonb(NEW.parameters_sha256) OR p->'createdAtEpochMillis' IS DISTINCT FROM to_jsonb(NEW.created_at_epoch_millis) OR
        p->'expiresAtEpochMillis' IS DISTINCT FROM to_jsonb(NEW.expires_at_epoch_millis) THEN RAISE EXCEPTION 'admission payload binding mismatch'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_key_admission BEFORE INSERT OR UPDATE OR DELETE ON serving.published_key_admission FOR EACH ROW EXECUTE FUNCTION serving.protect_key_admission();

ALTER TABLE serving.service_api_usage ADD COLUMN api_key_id BIGINT, ADD COLUMN admission_id UUID,
    ADD COLUMN request_id VARCHAR(128), ADD COLUMN owner_uid BIGINT;
ALTER TABLE serving.service_api_usage ADD CONSTRAINT ck_api_usage_identity CHECK((api_key_id IS NULL OR api_key_id>0) AND (owner_uid IS NULL OR owner_uid>0));
CREATE INDEX idx_api_usage_admission ON serving.service_api_usage(admission_id) WHERE admission_id IS NOT NULL;
