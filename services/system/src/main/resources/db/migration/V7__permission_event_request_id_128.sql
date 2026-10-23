-- Match the signed USER/request correlation contract without truncating audit IDs.
ALTER TABLE system.permission_event ALTER COLUMN request_id TYPE VARCHAR(128);
