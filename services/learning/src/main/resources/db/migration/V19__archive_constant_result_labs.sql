-- 这 10 个任务的标准答案是固定文案（无数据查询），判分按结果校验和比对，
-- 学员无法从数据推导，实际不可通过。归档保留内容，待内容管理员改造成
-- 基于真实数据的可判分任务后再重新发布。
UPDATE learning.lab
SET status = 'archived', updated_at = now()
WHERE status = 'published'
  AND code IN (
      'dg-l2-01', 'dg-l2-02', 'dg-l3-01',
      'ops-l2-01', 'ops-l2-02', 'ops-l2-03',
      'ops-l3-01', 'ops-l3-02', 'ops-l3-03', 'ops-l3-04')
  AND expected_sql NOT ILIKE '%from %'
  AND expected_sql NOT ILIKE '%with %';