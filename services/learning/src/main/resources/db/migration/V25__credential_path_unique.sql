-- 结业凭证按路径唯一：同一学员同一路径只允许一张 PATH_COMPLETION 凭证，
-- 防止并发提交时重复发证（此前去重只按凭证类型，导致第二条路径不再发证）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_credential_path_completion
    ON learning.credential (learner_uid, (evidence->>'pathCode'))
    WHERE type = 'PATH_COMPLETION';