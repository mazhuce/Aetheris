-- 真实样例会以新版本登记同一数据集；主键必须是 (code, version)。
-- 旧主键 code 只能保留一份版本，无法承载后续真实脱敏样例的版本演进。
ALTER TABLE learning.dataset_manifest DROP CONSTRAINT IF EXISTS dataset_manifest_pkey;
ALTER TABLE learning.dataset_manifest
    ADD CONSTRAINT dataset_manifest_pkey PRIMARY KEY (code, version);