package com.aetheris.learning.repository;

import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.support.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 学习域持久化。读模型走显式 SQL，写模型只允许通过受控方法，避免动态拼接。 */
@Repository
public class LearningRepository {

    private final JdbcTemplate jdbc;

    public LearningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PathRow(String code, String title, String description, String targetRole, String level,
                          int version, int estimatedHours, int labCount) {
    }

    public record SkillRow(String code, String title, String level, String knowledgePointsJson, String outcome) {
    }

    public record ModuleRow(String code, String title, String objective, String level, int orderNo,
                            List<String> relatedFeatures) {
    }

    public record AttemptRow(String labCode, int bestScore, boolean passed, String status) {
    }

    public record ProgressRow(String skillCode, String status, int bestScore, int attempts) {
    }

    public record ResultRow(long id, long submissionId, int score, boolean passed, String summary, String evidenceJson,
                            Instant createdAt) {
    }

    public record ItemRow(String itemKey, String name, int weight, boolean passed, String detail) {
    }

    // ---------------------------------------------------------------- catalog

    public List<PathRow> listPaths() {
        return jdbc.query("""
                SELECT p.code, p.title, p.description, p.target_role, p.level, p.version, p.estimated_hours,
                       (SELECT COUNT(*) FROM learning.lab l
                          JOIN learning.course_module m ON m.code = l.module_code
                          JOIN learning.course c ON c.code = m.course_code
                         WHERE c.path_code = p.code AND l.status = 'published') AS lab_count
                  FROM learning.learning_path p
                 WHERE p.status = 'published'
                 ORDER BY p.order_no, p.id
                """, pathMapper());
    }

    public Optional<PathRow> findPath(String code) {
        return jdbc.query("""
                SELECT p.code, p.title, p.description, p.target_role, p.level, p.version, p.estimated_hours,
                       (SELECT COUNT(*) FROM learning.lab l
                          JOIN learning.course_module m ON m.code = l.module_code
                          JOIN learning.course c ON c.code = m.course_code
                         WHERE c.path_code = p.code AND l.status = 'published') AS lab_count
                  FROM learning.learning_path p
                 WHERE p.code = ?
                """, pathMapper(), code).stream().findFirst();
    }

    public List<SkillRow> listSkills(String pathCode) {
        return jdbc.query("""
                SELECT code, title, level, knowledge_points::text AS knowledge_points, outcome
                  FROM learning.skill_node WHERE path_code = ? ORDER BY order_no, id
                """, (rs, index) -> new SkillRow(rs.getString("code"), rs.getString("title"), rs.getString("level"),
                rs.getString("knowledge_points"), rs.getString("outcome")), pathCode);
    }

    public List<ModuleRow> listModules(String pathCode) {
        return jdbc.query("""
                SELECT m.code, m.title, m.objective, m.level, m.order_no,
                       m.related_features::text AS related_features
                  FROM learning.course_module m
                  JOIN learning.course c ON c.code = m.course_code
                 WHERE c.path_code = ?
                 ORDER BY m.order_no, m.id
                """, (rs, index) -> new ModuleRow(rs.getString("code"), rs.getString("title"),
                rs.getString("objective"), rs.getString("level"), rs.getInt("order_no"),
                Json.readStringList(rs.getString("related_features"))), pathCode);
    }

    public List<LearningModels.LabRecord> listLabs(String pathCode) {
        return jdbc.query(labSelect() + """
                 WHERE c.path_code = ? AND l.status = 'published'
                 ORDER BY m.order_no, l.order_no, l.id
                """, labMapper(), pathCode);
    }

    public Optional<LearningModels.LabRecord> findLab(String code) {
        return jdbc.query(labSelect() + " WHERE l.code = ?", labMapper(), code).stream().findFirst();
    }

    public List<LearningModels.LabRecord> listAllLabs() {
        return jdbc.query(labSelect() + " ORDER BY m.order_no, l.order_no, l.id", labMapper());
    }

    private static String labSelect() {
        return """
                SELECT l.id, l.code, l.module_code, l.title, l.level, l.difficulty, l.task_type, l.summary,
                       l.scenario, l.objective, l.steps::text AS steps, l.table_refs::text AS table_refs,
                       l.starter_sql, l.expected_sql, l.static_policy::text AS static_policy,
                       l.rubric::text AS rubric, l.pass_score, l.estimated_minutes, l.status, l.version,
                       l.order_no, COALESCE(l.sandbox_tier, 'light') AS sandbox_tier,
                       l.related_features::text AS related_features, l.knowledge::text AS knowledge
                  FROM learning.lab l
                  JOIN learning.course_module m ON m.code = l.module_code
                  JOIN learning.course c ON c.code = m.course_code
                """;
    }

    private static RowMapper<PathRow> pathMapper() {
        return (rs, index) -> new PathRow(rs.getString("code"), rs.getString("title"), rs.getString("description"),
                rs.getString("target_role"), rs.getString("level"), rs.getInt("version"),
                rs.getInt("estimated_hours"), rs.getInt("lab_count"));
    }

    private static RowMapper<LearningModels.LabRecord> labMapper() {
        return (rs, index) -> new LearningModels.LabRecord(
                rs.getLong("id"), rs.getString("code"), rs.getString("module_code"), rs.getString("title"),
                rs.getString("level"), rs.getString("difficulty"), rs.getString("task_type"), rs.getString("summary"),
                rs.getString("scenario"), rs.getString("objective"),
                Json.readList(rs.getString("steps"), new TypeReference<List<LearningModels.Step>>() {
                }),
                Json.readStringList(rs.getString("table_refs")),
                rs.getString("starter_sql"), rs.getString("expected_sql"),
                Json.readTree(rs.getString("static_policy")),
                Json.readList(rs.getString("rubric"), new TypeReference<List<LearningModels.RubricItem>>() {
                }),
                rs.getInt("pass_score"), rs.getInt("estimated_minutes"), rs.getString("status"),
                rs.getInt("version"), rs.getInt("order_no"),
                rs.getString("sandbox_tier") == null ? "light" : rs.getString("sandbox_tier"),
                Json.readStringList(rs.getString("related_features")),
                Json.readList(rs.getString("knowledge"), new TypeReference<List<LearningModels.LabKnowledge>>() {
                }));
    }

    public List<String> listLabSkills(String pathCode) {
        return jdbc.query("""
                SELECT ls.skill_code FROM learning.lab_skill ls
                  JOIN learning.lab l ON l.code = ls.lab_code
                  JOIN learning.course_module m ON m.code = l.module_code
                  JOIN learning.course c ON c.code = m.course_code
                 WHERE c.path_code = ? AND l.status = 'published'
                """, (rs, index) -> rs.getString(1), pathCode);
    }

    public Map<String, AttemptRow> bestAttempts(long uid, String pathCode) {
        Map<String, AttemptRow> result = new java.util.HashMap<>();
        jdbc.query("""
                SELECT s.lab_code, MAX(COALESCE(s.score, 0)) AS best_score,
                       BOOL_OR(COALESCE(s.passed, false)) AS passed,
                       (ARRAY_AGG(s.status ORDER BY s.created_at DESC))[1] AS last_status
                  FROM learning.submission s
                  JOIN learning.lab l ON l.code = s.lab_code
                  JOIN learning.course_module m ON m.code = l.module_code
                  JOIN learning.course c ON c.code = m.course_code
                 WHERE s.learner_uid = ? AND c.path_code = ?
                 GROUP BY s.lab_code
                """, rs -> {
            result.put(rs.getString("lab_code"), new AttemptRow(rs.getString("lab_code"),
                    rs.getInt("best_score"), rs.getBoolean("passed"), rs.getString("last_status")));
        }, uid, pathCode);
        return result;
    }

    public Map<String, AttemptRow> bestAttemptsByProject(long uid, String projectCode) {
        Map<String, AttemptRow> result = new java.util.HashMap<>();
        jdbc.query("""
                SELECT s.lab_code, MAX(COALESCE(s.score, 0)) AS best_score,
                       BOOL_OR(COALESCE(s.passed, false)) AS passed,
                       (ARRAY_AGG(s.status ORDER BY s.created_at DESC))[1] AS last_status
                  FROM learning.submission s
                  JOIN learning.lab l ON l.code = s.lab_code
                 WHERE s.learner_uid = ? AND l.project_code = ?
                 GROUP BY s.lab_code
                """, rs -> {
            result.put(rs.getString("lab_code"), new AttemptRow(rs.getString("lab_code"),
                    rs.getInt("best_score"), rs.getBoolean("passed"), rs.getString("last_status")));
        }, uid, projectCode);
        return result;
    }

    public List<LearningModels.SubmissionRow> listSubmissions(long uid, String labCode, int limit) {
        return jdbc.query("""
                SELECT id, learner_uid, lab_code, attempt, status, score, passed, created_at
                  FROM learning.submission
                 WHERE learner_uid = ? AND lab_code = ?
                 ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, index) -> new LearningModels.SubmissionRow(rs.getLong("id"), rs.getLong("learner_uid"),
                rs.getString("lab_code"), rs.getInt("attempt"), rs.getString("status"),
                (Integer) rs.getObject("score"), (Boolean) rs.getObject("passed"),
                instant(rs, "created_at")), uid, labCode, limit);
    }

    public Optional<LearningModels.SubmissionView> findSubmission(long id, long uid) {
        return jdbc.query("""
                SELECT id, lab_code, attempt, sql_text, notes, status, score, passed, created_at, graded_at
                  FROM learning.submission WHERE id = ? AND learner_uid = ?
                """, (rs, index) -> new LearningModels.SubmissionView(
                rs.getLong("id"), rs.getString("lab_code"), rs.getInt("attempt"), rs.getString("sql_text"),
                rs.getString("notes"), rs.getString("status"), (Integer) rs.getObject("score"),
                (Boolean) rs.getObject("passed"), instant(rs, "created_at"), instant(rs, "graded_at"), null),
                id, uid).stream().findFirst();
    }

    public Optional<LearningModels.SubmissionRow> latestSubmission(long uid, String labCode) {
        return jdbc.query("""
                SELECT id, learner_uid, lab_code, attempt, status, score, passed, created_at
                  FROM learning.submission WHERE learner_uid = ? AND lab_code = ?
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, (rs, index) -> new LearningModels.SubmissionRow(rs.getLong("id"), rs.getLong("learner_uid"),
                rs.getString("lab_code"), rs.getInt("attempt"), rs.getString("status"),
                (Integer) rs.getObject("score"), (Boolean) rs.getObject("passed"),
                instant(rs, "created_at")), uid, labCode)
                .stream().findFirst();
    }

    public Optional<String> latestSubmissionSql(long uid, String labCode) {
        return jdbc.query("""
                SELECT sql_text FROM learning.submission WHERE learner_uid = ? AND lab_code = ?
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, (rs, index) -> rs.getString(1), uid, labCode).stream().findFirst();
    }

    public int countSubmissions(long uid, String labCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM learning.submission WHERE learner_uid = ? AND lab_code = ?
                """, Integer.class, uid, labCode);
        return count == null ? 0 : count;
    }

    public Optional<ResultRow> findResult(long submissionId) {
        return jdbc.query("""
                SELECT id, submission_id, score, passed, summary, evidence::text AS evidence, created_at
                  FROM learning.grading_result WHERE submission_id = ?
                """, (rs, index) -> new ResultRow(rs.getLong("id"), rs.getLong("submission_id"),
                rs.getInt("score"), rs.getBoolean("passed"), rs.getString("summary"), rs.getString("evidence"),
                instant(rs, "created_at")), submissionId).stream().findFirst();
    }

    public List<ItemRow> listResultItems(long resultId) {
        return jdbc.query("""
                SELECT item_key, name, weight, passed, detail FROM learning.grading_item
                 WHERE grading_result_id = ? ORDER BY id
                """, (rs, index) -> new ItemRow(rs.getString("item_key"), rs.getString("name"),
                rs.getInt("weight"), rs.getBoolean("passed"), rs.getString("detail")), resultId);
    }

    // -------------------------------------------------------------- submissions

    public int nextAttempt(long uid, String labCode) {
        Integer current = jdbc.queryForObject("""
                SELECT COUNT(*) FROM learning.submission WHERE learner_uid = ? AND lab_code = ?
                """, Integer.class, uid, labCode);
        return (current == null ? 0 : current) + 1;
    }

    public long insertSubmission(long uid, String username, String labCode, int labVersion, int attempt,
                                 String sql, String notes) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO learning.submission (learner_uid, learner_name, lab_code, lab_version, attempt,
                                                     sql_text, notes, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'grading')
                    """, new String[]{"id"});
            statement.setLong(1, uid);
            statement.setString(2, username);
            statement.setString(3, labCode);
            statement.setInt(4, labVersion);
            statement.setInt(5, attempt);
            statement.setString(6, sql);
            statement.setString(7, notes);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("提交写入失败");
        return key.longValue();
    }

    public void completeSubmission(long id, int score, boolean passed) {
        jdbc.update("""
                UPDATE learning.submission SET status = 'graded', score = ?, passed = ?, graded_at = now()
                 WHERE id = ?
                """, score, passed, id);
    }

    public long insertGradingResult(long submissionId, int score, boolean passed, String summary, String evidenceJson) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO learning.grading_result (submission_id, score, passed, summary, evidence)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    """, new String[]{"id"});
            statement.setLong(1, submissionId);
            statement.setInt(2, score);
            statement.setBoolean(3, passed);
            statement.setString(4, summary);
            statement.setString(5, evidenceJson);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("评分结果写入失败");
        return key.longValue();
    }

    public void insertGradingItem(long resultId, LearningModels.GradingItemView item) {
        jdbc.update("""
                INSERT INTO learning.grading_item (grading_result_id, item_key, name, weight, passed, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """, resultId, item.key(), item.name(), item.weight(), item.passed(), item.detail());
    }

    public void insertExecutionEvidence(long submissionId, String schema, String statementHash, int rowCount,
                                        String checksum, int durationMs) {
        jdbc.update("""
                INSERT INTO learning.execution_evidence
                    (submission_id, sandbox_schema, statement_hash, row_count, checksum, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, submissionId, schema, statementHash, rowCount, checksum, durationMs);
    }

    public void markSubmissionError(long id) {
        jdbc.update("UPDATE learning.submission SET status = 'error', graded_at = now() WHERE id = ?", id);
    }

    public void upsertProgress(long uid, String skillCode, String status, int score, String labCode) {
        jdbc.update("""
                INSERT INTO learning.learner_skill_progress
                    (learner_uid, skill_code, status, best_score, attempts, last_lab_code, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, now())
                ON CONFLICT (learner_uid, skill_code) DO UPDATE
                   SET status = CASE WHEN EXCLUDED.status = 'passed' THEN 'passed'
                                     ELSE learning.learner_skill_progress.status END,
                       best_score = GREATEST(learning.learner_skill_progress.best_score, EXCLUDED.best_score),
                       attempts = learning.learner_skill_progress.attempts + 1,
                       last_lab_code = EXCLUDED.last_lab_code,
                       updated_at = now()
                """, uid, skillCode, status, score, labCode);
    }

    public List<ProgressRow> listProgress(long uid) {
        return jdbc.query("""
                SELECT skill_code, status, best_score, attempts FROM learning.learner_skill_progress
                 WHERE learner_uid = ?
                """, (rs, index) -> new ProgressRow(rs.getString("skill_code"), rs.getString("status"),
                rs.getInt("best_score"), rs.getInt("attempts")), uid);
    }

    public List<String> skillsOfLab(String labCode) {
        return jdbc.query("SELECT skill_code FROM learning.lab_skill WHERE lab_code = ?",
                (rs, index) -> rs.getString(1), labCode);
    }

    public java.util.Map<String, List<String>> allLabSkills() {
        java.util.Map<String, List<String>> result = new java.util.HashMap<>();
        jdbc.query("SELECT lab_code, skill_code FROM learning.lab_skill ORDER BY lab_code, skill_code", rs -> {
            result.computeIfAbsent(rs.getString("lab_code"), key -> new java.util.ArrayList<>())
                    .add(rs.getString("skill_code"));
        });
        return result;
    }

    public String pathOfLab(String labCode) {
        List<String> paths = jdbc.query("""
                SELECT c.path_code FROM learning.lab l
                  JOIN learning.course_module m ON m.code = l.module_code
                  JOIN learning.course c ON c.code = m.course_code
                 WHERE l.code = ?
                """, (rs, index) -> rs.getString(1), labCode);
        return paths.isEmpty() ? "" : paths.getFirst();
    }

    public List<LearningModels.CredentialView> listCredentials(long uid) {
        return jdbc.query("""
                SELECT id, type, title, issued_at FROM learning.credential
                 WHERE learner_uid = ? ORDER BY issued_at DESC
                """, (rs, index) -> new LearningModels.CredentialView(rs.getLong("id"), rs.getString("type"),
                rs.getString("title"), instant(rs, "issued_at")), uid);
    }

    public boolean hasCredential(long uid, String type) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM learning.credential WHERE learner_uid = ? AND type = ?
                """, Integer.class, uid, type);
        return count != null && count > 0;
    }

    public void insertCredential(long uid, String type, String title, String evidenceJson) {
        jdbc.update("""
                INSERT INTO learning.credential (learner_uid, type, title, evidence) VALUES (?, ?, ?, ?::jsonb)
                """, uid, type, title, evidenceJson);
    }

    // ------------------------------------------------------------------ hints

    public int countHints(long uid, String labCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM learning.tutor_hint_log WHERE learner_uid = ? AND lab_code = ?
                """, Integer.class, uid, labCode);
        return count == null ? 0 : count;
    }

    public void insertHint(long uid, String labCode, int level, String reason) {
        jdbc.update("""
                INSERT INTO learning.tutor_hint_log (learner_uid, lab_code, level, reason) VALUES (?, ?, ?, ?)
                """, uid, labCode, level, reason);
    }

    // ---------------------------------------------------------------- sandbox

    public Optional<LearningModels.SandboxRow> findActiveSandbox(long uid) {
        return jdbc.query("""
                SELECT id, learner_uid, lab_code, schema_name, status, tier, expires_at, created_at, last_reset_at
                  FROM learning.sandbox_instance WHERE learner_uid = ? AND status = 'active'
                 ORDER BY id DESC LIMIT 1
                """, sandboxMapper(), uid).stream().findFirst();
    }

    public void insertSandbox(long uid, String username, String labCode, String schemaName, String tier,
                              Instant expiresAt) {
        jdbc.update("""
                INSERT INTO learning.sandbox_instance
                    (learner_uid, learner_name, lab_code, schema_name, status, tier, expires_at)
                VALUES (?, ?, ?, ?, 'active', ?, ?)
                """, uid, username, labCode, schemaName, tier == null ? "light" : tier, Timestamp.from(expiresAt));
    }

    /** Medium 槽位占用：其他学员仍活跃的 medium 沙箱数量（本人复用不占新槽）。 */
    public int countActiveMediumSandboxes(long excludeUid) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT learner_uid) FROM learning.sandbox_instance
                 WHERE status = 'active' AND COALESCE(tier, 'light') = 'medium' AND learner_uid <> ?
                """, Integer.class, excludeUid);
        return count == null ? 0 : count;
    }

    public void updateSandboxLab(long id, String labCode, String tier, Instant expiresAt) {
        jdbc.update("""
                UPDATE learning.sandbox_instance SET lab_code = ?, tier = ?, expires_at = ?, last_reset_at = now()
                 WHERE id = ?
                """, labCode, tier == null ? "light" : tier, Timestamp.from(expiresAt), id);
    }

    public void markSandboxDropped(long id) {
        jdbc.update("""
                UPDATE learning.sandbox_instance SET status = 'dropped', dropped_at = now() WHERE id = ?
                """, id);
    }

    public List<LearningModels.SandboxRow> listExpiredSandboxes(int limit) {
        return jdbc.query("""
                SELECT id, learner_uid, lab_code, schema_name, status, tier, expires_at, created_at, last_reset_at
                  FROM learning.sandbox_instance
                 WHERE status = 'active' AND expires_at < now()
                 ORDER BY expires_at LIMIT ?
                """, sandboxMapper(), limit);
    }

    private static RowMapper<LearningModels.SandboxRow> sandboxMapper() {
        return (rs, index) -> new LearningModels.SandboxRow(rs.getLong("id"), rs.getLong("learner_uid"),
                rs.getString("lab_code"), rs.getString("schema_name"), rs.getString("status"),
                rs.getString("tier"), instant(rs, "expires_at"), instant(rs, "created_at"),
                instant(rs, "last_reset_at"));
    }

    // ------------------------------------------------------------------ admin

    public int publishLab(String code) {
        return jdbc.update("""
                UPDATE learning.lab SET status = 'published', published_at = now(), updated_at = now()
                 WHERE code = ?
                """, code);
    }

    public List<LearningModels.ProjectView> listProjects() {
        return jdbc.query("""
                SELECT p.code, p.title, p.summary, p.level, p.scenario, p.deliverables::text AS deliverables,
                       (SELECT COUNT(*) FROM learning.lab l WHERE l.project_code = p.code AND l.status = 'published') AS lab_count
                  FROM learning.project p
                 WHERE p.status = 'published'
                 ORDER BY p.order_no, p.id
                """, (rs, index) -> new LearningModels.ProjectView(rs.getString("code"), rs.getString("title"),
                rs.getString("summary"), rs.getString("level"), rs.getString("scenario"),
                Json.readStringList(rs.getString("deliverables")), rs.getInt("lab_count"), 0));
    }

    public Optional<LearningModels.ProjectView> findProject(String code) {
        return jdbc.query("""
                SELECT p.code, p.title, p.summary, p.level, p.scenario, p.deliverables::text AS deliverables,
                       (SELECT COUNT(*) FROM learning.lab l WHERE l.project_code = p.code AND l.status = 'published') AS lab_count
                  FROM learning.project p WHERE p.code = ?
                """, (rs, index) -> new LearningModels.ProjectView(rs.getString("code"), rs.getString("title"),
                rs.getString("summary"), rs.getString("level"), rs.getString("scenario"),
                Json.readStringList(rs.getString("deliverables")), rs.getInt("lab_count"), 0), code)
                .stream().findFirst();
    }

    public List<LearningModels.LabRecord> listProjectLabs(String projectCode) {
        return jdbc.query(labSelect() + " WHERE l.project_code = ? AND l.status = 'published' ORDER BY l.order_no, l.id",
                labMapper(), projectCode);
    }

    public List<LearningModels.LabAdminView> listAdminLabs() {
        return jdbc.query("""
                SELECT id, code, module_code, project_code, title, level, difficulty, summary, objective, status,
                       review_status, review_note, author, version, pass_score, estimated_minutes, expected_sql, updated_at
                  FROM learning.lab ORDER BY updated_at DESC, id DESC
                """, adminLabMapper());
    }

    public Optional<LearningModels.LabAdminView> findAdminLab(String code) {
        return jdbc.query("""
                SELECT id, code, module_code, project_code, title, level, difficulty, summary, objective, status,
                       review_status, review_note, author, version, pass_score, estimated_minutes, expected_sql, updated_at
                  FROM learning.lab WHERE code = ?
                """, adminLabMapper(), code).stream().findFirst();
    }

    private static RowMapper<LearningModels.LabAdminView> adminLabMapper() {
        return (rs, index) -> new LearningModels.LabAdminView(rs.getLong("id"), rs.getString("code"),
                rs.getString("module_code"), rs.getString("project_code"), rs.getString("title"),
                rs.getString("level"), rs.getString("difficulty"), rs.getString("summary"),
                rs.getString("objective"), rs.getString("status"), rs.getString("review_status"),
                rs.getString("review_note"), rs.getString("author"), rs.getInt("version"),
                rs.getInt("pass_score"), rs.getInt("estimated_minutes"), rs.getString("expected_sql"),
                instant(rs, "updated_at"));
    }

    public long insertLab(LearningModels.LabRecord lab, String projectCode, String author) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO learning.lab
                        (module_code, code, title, level, difficulty, task_type, summary, scenario, objective, steps,
                         table_refs, starter_sql, expected_sql, static_policy, rubric, pass_score, estimated_minutes,
                         status, version, order_no, project_code, author, review_status, sandbox_tier)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 'draft',
                            1, ?, ?, ?, 'draft', ?)
                    """, new String[]{"id"});
            int i = 1;
            statement.setString(i++, lab.moduleCode());
            statement.setString(i++, lab.code());
            statement.setString(i++, lab.title());
            statement.setString(i++, lab.level());
            statement.setString(i++, lab.difficulty());
            statement.setString(i++, lab.taskType());
            statement.setString(i++, lab.summary());
            statement.setString(i++, lab.scenario());
            statement.setString(i++, lab.objective());
            statement.setString(i++, Json.write(lab.steps()));
            statement.setString(i++, Json.write(lab.tableRefs()));
            statement.setString(i++, lab.starterSql());
            statement.setString(i++, lab.expectedSql());
            statement.setString(i++, Json.write(lab.staticPolicy()));
            statement.setString(i++, Json.write(lab.rubric()));
            statement.setInt(i++, lab.passScore());
            statement.setInt(i++, lab.estimatedMinutes());
            statement.setInt(i++, lab.orderNo());
            statement.setString(i++, projectCode);
            statement.setString(i++, author);
            statement.setString(i, lab.sandboxTier() == null ? "light" : lab.sandboxTier());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("实验任务写入失败");
        return key.longValue();
    }

    public int updateLab(LearningModels.LabRecord lab, String projectCode, String author) {
        return jdbc.update("""
                UPDATE learning.lab SET module_code = ?, title = ?, level = ?, difficulty = ?, task_type = ?,
                       summary = ?, scenario = ?, objective = ?, steps = ?::jsonb, table_refs = ?::jsonb,
                       starter_sql = ?, expected_sql = ?, static_policy = ?::jsonb, rubric = ?::jsonb,
                       pass_score = ?, estimated_minutes = ?, order_no = ?, project_code = ?, author = ?,
                       version = version + 1, review_status = 'draft', review_note = NULL, status = 'draft',
                       updated_at = now()
                 WHERE code = ?
                """, lab.moduleCode(), lab.title(), lab.level(), lab.difficulty(), lab.taskType(), lab.summary(),
                lab.scenario(), lab.objective(), Json.write(lab.steps()), Json.write(lab.tableRefs()),
                lab.starterSql(), lab.expectedSql(), Json.write(lab.staticPolicy()), Json.write(lab.rubric()),
                lab.passScore(), lab.estimatedMinutes(), lab.orderNo(), projectCode, author, lab.code());
    }

    public int reviewLab(String code, boolean approve, String note) {
        return jdbc.update("""
                UPDATE learning.lab SET review_status = ?, review_note = ?, updated_at = now() WHERE code = ?
                """, approve ? "approved" : "rejected", note, code);
    }

    public int publishReviewedLab(String code) {
        return jdbc.update("""
                UPDATE learning.lab SET status = 'published', review_status = 'approved', published_at = now(),
                       updated_at = now()
                 WHERE code = ? AND review_status = 'approved' AND expected_sql IS NOT NULL AND expected_sql <> ''
                """, code);
    }

    public int unpublishLab(String code) {
        return jdbc.update("""
                UPDATE learning.lab SET status = 'draft', published_at = NULL, updated_at = now()
                 WHERE code = ? AND status = 'published'
                """, code);
    }

    // -------------------------------------------------------------- approvals

    public long insertApproval(String type, String targetCode, String payloadJson, String reason, long uid,
                               String username) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO learning.approval_request (type, target_code, payload, reason, requested_by, requested_by_name)
                    VALUES (?, ?, ?::jsonb, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setString(1, type);
            statement.setString(2, targetCode);
            statement.setString(3, payloadJson);
            statement.setString(4, reason);
            statement.setLong(5, uid);
            statement.setString(6, username);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("审批单写入失败");
        return key.longValue();
    }

    public List<LearningModels.ApprovalView> listApprovals(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT id, type, target_code, payload::text AS payload, reason, status, requested_by,
                           requested_by_name, decided_by_name, decision_note, created_at, decided_at
                      FROM learning.approval_request ORDER BY created_at DESC LIMIT 100
                    """, approvalMapper());
        }
        return jdbc.query("""
                SELECT id, type, target_code, payload::text AS payload, reason, status, requested_by,
                       requested_by_name, decided_by_name, decision_note, created_at, decided_at
                  FROM learning.approval_request WHERE status = ? ORDER BY created_at DESC LIMIT 100
                """, approvalMapper(), status);
    }

    public Optional<LearningModels.ApprovalView> findApproval(long id) {
        return jdbc.query("""
                SELECT id, type, target_code, payload::text AS payload, reason, status, requested_by,
                       requested_by_name, decided_by_name, decision_note, created_at, decided_at
                  FROM learning.approval_request WHERE id = ?
                """, approvalMapper(), id).stream().findFirst();
    }

    public Optional<LearningModels.ApprovalView> findPendingApproval(String type, String targetCode) {
        return jdbc.query("""
                SELECT id, type, target_code, payload::text AS payload, reason, status, requested_by,
                       requested_by_name, decided_by_name, decision_note, created_at, decided_at
                  FROM learning.approval_request
                 WHERE type = ? AND target_code = ? AND status = 'pending'
                 ORDER BY created_at DESC LIMIT 1
                """, approvalMapper(), type, targetCode).stream().findFirst();
    }

    private static RowMapper<LearningModels.ApprovalView> approvalMapper() {
        return (rs, index) -> new LearningModels.ApprovalView(rs.getLong("id"), rs.getString("type"),
                rs.getString("target_code"), rs.getString("payload"), rs.getString("reason"), rs.getString("status"),
                rs.getLong("requested_by"), rs.getString("requested_by_name"), rs.getString("decided_by_name"),
                rs.getString("decision_note"), instant(rs, "created_at"), instant(rs, "decided_at"));
    }

    public int decideApproval(long id, boolean approve, long uid, String username, String note) {
        return jdbc.update("""
                UPDATE learning.approval_request
                   SET status = ?, decided_by = ?, decided_by_name = ?, decided_at = now(), decision_note = ?
                 WHERE id = ? AND status = 'pending'
                """, approve ? "approved" : "rejected", uid, username, note, id);
    }

    public int countPendingApprovals() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning.approval_request WHERE status = 'pending'", Integer.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ tutor

    public int countTutorUserMessages(long uid, String labCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning.tutor_message WHERE learner_uid = ? AND lab_code = ? AND role = 'user'",
                Integer.class, uid, labCode);
        return count == null ? 0 : count;
    }

    public void insertTutorMessage(long uid, String labCode, String role, String content, String model) {
        jdbc.update("""
                INSERT INTO learning.tutor_message (learner_uid, lab_code, role, content, model)
                VALUES (?, ?, ?, ?, ?)
                """, uid, labCode, role, content, model);
    }

    public List<LearningModels.TutorMessageView> listTutorMessages(long uid, String labCode, int limit) {
        return jdbc.query("""
                SELECT role, content, model, created_at FROM learning.tutor_message
                 WHERE learner_uid = ? AND lab_code = ? ORDER BY created_at DESC LIMIT ?
                """, (rs, index) -> new LearningModels.TutorMessageView(rs.getString("role"),
                rs.getString("content"), rs.getString("model"), instant(rs, "created_at")), uid, labCode, limit)
                .reversed();
    }

    // --------------------------------------------------------------- evidence

    public void insertAttemptEvent(long submissionId, String eventType, String detail) {
        jdbc.update("""
                INSERT INTO learning.attempt_event (submission_id, event_type, detail) VALUES (?, ?, ?)
                """, submissionId, eventType, detail);
    }

    public List<LearningModels.AttemptEventView> listAttemptEvents(long submissionId) {
        return jdbc.query("""
                SELECT event_type, detail, created_at FROM learning.attempt_event
                 WHERE submission_id = ? ORDER BY created_at
                """, (rs, index) -> new LearningModels.AttemptEventView(rs.getString("event_type"),
                rs.getString("detail"), instant(rs, "created_at")), submissionId);
    }

    // --------------------------------------------------------------- analytics

    public List<LearningModels.LabAnalytics> labAnalytics() {
        Map<String, String> topFailures = new java.util.HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (s.lab_code) s.lab_code, e.event_type
                  FROM learning.attempt_event e
                  JOIN learning.submission s ON s.id = e.submission_id
                 WHERE e.event_type <> 'PASSED'
                 GROUP BY s.lab_code, e.event_type
                 ORDER BY s.lab_code, COUNT(*) DESC
                """, rs -> {
            topFailures.put(rs.getString("lab_code"), rs.getString("event_type"));
        });
        return jdbc.query("""
                SELECT l.code, l.title, l.level, COUNT(s.id) AS attempts,
                       COUNT(DISTINCT s.learner_uid) AS learners,
                       COUNT(DISTINCT s.learner_uid) FILTER (WHERE s.passed) AS passed_learners,
                       COALESCE(ROUND(AVG(s.score)::numeric, 1), 0) AS avg_score
                  FROM learning.lab l
                  LEFT JOIN learning.submission s ON s.lab_code = l.code
                 WHERE l.status = 'published'
                 GROUP BY l.id, l.code, l.title, l.level, l.order_no
                 ORDER BY l.order_no, l.code
                """, (rs, index) -> new LearningModels.LabAnalytics(rs.getString("code"), rs.getString("title"),
                rs.getString("level"), rs.getInt("attempts"), rs.getInt("learners"), rs.getInt("passed_learners"),
                rs.getDouble("avg_score"), topFailures.get(rs.getString("code"))));
    }

    public Map<String, Object> learningOpsOverview() {
        Map<String, Object> overview = new java.util.LinkedHashMap<>(adminOverview());
        overview.put("pendingApprovals", countPendingApprovals());
        overview.put("activeLearners7d", jdbc.queryForObject("""
                SELECT COUNT(DISTINCT learner_uid) FROM learning.submission
                 WHERE created_at > now() - interval '7 days'
                """, Integer.class));
        overview.put("passRate", jdbc.queryForObject("""
                SELECT COALESCE(ROUND(100.0 * COUNT(DISTINCT learner_uid) FILTER (WHERE passed)
                       / NULLIF(COUNT(DISTINCT learner_uid), 0), 1), 0)
                  FROM learning.submission
                """, Double.class));
        return overview;
    }

    public Map<String, Object> adminOverview() {
        Map<String, Object> overview = new java.util.LinkedHashMap<>();
        overview.put("paths", jdbc.queryForObject("SELECT COUNT(*) FROM learning.learning_path", Integer.class));
        overview.put("labs", jdbc.queryForObject("SELECT COUNT(*) FROM learning.lab", Integer.class));
        overview.put("publishedLabs", jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning.lab WHERE status = 'published'", Integer.class));
        overview.put("submissions", jdbc.queryForObject("SELECT COUNT(*) FROM learning.submission", Integer.class));
        overview.put("gradedSubmissions", jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning.submission WHERE status = 'graded'", Integer.class));
        overview.put("activeSandboxes", jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning.sandbox_instance WHERE status = 'active'", Integer.class));
        overview.put("learners", jdbc.queryForObject(
                "SELECT COUNT(DISTINCT learner_uid) FROM learning.submission", Integer.class));
        return overview;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
