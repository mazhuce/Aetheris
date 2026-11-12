-- 修正 l6-alert-threshold：percentile_cont 返回 double precision，
-- 必须先转 numeric 才能用 round(x, 1)。
UPDATE learning.lab
   SET expected_sql = $q$WITH delays AS (
  SELECT GREATEST(EXTRACT(EPOCH FROM (arrived_at - expected_at)) / 60, 0) AS delay_minutes
    FROM daily_arrival
)
SELECT COUNT(*) AS total_days,
       COUNT(*) FILTER (WHERE delay_minutes > 0) AS late_days,
       ROUND(percentile_cont(0.95) WITHIN GROUP (ORDER BY delay_minutes)::numeric, 1) AS p95_delay_minutes
  FROM delays$q$,
       updated_at = now()
 WHERE code = 'l6-alert-threshold';
