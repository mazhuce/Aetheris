-- R1 spark reconciliation query; export columns and rows as JSON.
SELECT category,
       COUNT(*) AS order_count,
       CAST(SUM(amount) AS DECIMAL(18, 2)) AS total_amount
FROM lakehouse.bronze.r1_orders
GROUP BY category
ORDER BY category;
