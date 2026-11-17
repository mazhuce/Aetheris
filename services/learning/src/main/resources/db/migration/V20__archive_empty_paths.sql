-- 若一条已发布路径已没有可判分任务，则一并归档，避免学员看到空路径。
UPDATE learning.learning_path p
SET status = 'archived'
WHERE p.status = 'published'
  AND NOT EXISTS (
      SELECT 1
        FROM learning.course c
        JOIN learning.course_module m ON m.course_code = c.code
        JOIN learning.lab l ON l.module_code = m.code
       WHERE c.path_code = p.code
         AND l.status = 'published');