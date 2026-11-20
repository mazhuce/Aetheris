package com.aetheris.learning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 学习域运行参数：沙箱生命周期、判分边界与导师提示上限。 */
@ConfigurationProperties(prefix = "aetheris.learning")
public class LearningProperties {

    private boolean devMode = false;
    private String devUser = "dev-learner";
    private java.util.List<String> adminUsers = java.util.List.of("admin");
    private Sandbox sandbox = new Sandbox();
    private Grading grading = new Grading();
    private Tutor tutor = new Tutor();
    private Ai ai = new Ai();

    public boolean isDevMode() { return devMode; }
    public void setDevMode(boolean devMode) { this.devMode = devMode; }
    public String getDevUser() { return devUser; }
    public void setDevUser(String devUser) { this.devUser = devUser; }
    public java.util.List<String> getAdminUsers() { return adminUsers; }
    public void setAdminUsers(java.util.List<String> adminUsers) { this.adminUsers = adminUsers; }
    public Sandbox getSandbox() { return sandbox; }
    public void setSandbox(Sandbox sandbox) { this.sandbox = sandbox; }
    public Grading getGrading() { return grading; }
    public void setGrading(Grading grading) { this.grading = grading; }
    public Tutor getTutor() { return tutor; }
    public void setTutor(Tutor tutor) { this.tutor = tutor; }
    public Ai getAi() { return ai; }
    public void setAi(Ai ai) { this.ai = ai; }

    public static class Sandbox {
        private int ttlHours = 24;
        private Duration cleanupInterval = Duration.ofMinutes(10);
        private int maxActivePerUser = 3;
        private int mediumConcurrency = 2;
        private int mediumStatementTimeoutMs = 60000;
        /** 学员 SQL 的执行角色：仅能访问本沙箱 schema 与 teach 只读表。 */
        private String dbRole = "aetheris_sandbox";

        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
        public Duration getCleanupInterval() { return cleanupInterval; }
        public void setCleanupInterval(Duration cleanupInterval) { this.cleanupInterval = cleanupInterval; }
        public int getMaxActivePerUser() { return maxActivePerUser; }
        public void setMaxActivePerUser(int maxActivePerUser) { this.maxActivePerUser = maxActivePerUser; }
        public int getMediumConcurrency() { return mediumConcurrency; }
        public void setMediumConcurrency(int mediumConcurrency) { this.mediumConcurrency = mediumConcurrency; }
        public int getMediumStatementTimeoutMs() { return mediumStatementTimeoutMs; }
        public void setMediumStatementTimeoutMs(int mediumStatementTimeoutMs) {
            this.mediumStatementTimeoutMs = mediumStatementTimeoutMs;
        }
        public String getDbRole() { return dbRole; }
        public void setDbRole(String dbRole) { this.dbRole = dbRole; }
    }

    public static class Grading {
        private int statementTimeoutMs = 8000;
        private int maxSqlBytes = 20000;
        private int sampleRows = 20;

        public int getStatementTimeoutMs() { return statementTimeoutMs; }
        public void setStatementTimeoutMs(int statementTimeoutMs) { this.statementTimeoutMs = statementTimeoutMs; }
        public int getMaxSqlBytes() { return maxSqlBytes; }
        public void setMaxSqlBytes(int maxSqlBytes) { this.maxSqlBytes = maxSqlBytes; }
        public int getSampleRows() { return sampleRows; }
        public void setSampleRows(int sampleRows) { this.sampleRows = sampleRows; }
    }

    public static class Tutor {
        private int maxHintsPerLab = 6;
        /** 单个任务允许的 AI 导师提问条数；超出后自动降级规则导师，防止模型预算失控。 */
        private int maxChatsPerLab = 20;

        public int getMaxHintsPerLab() { return maxHintsPerLab; }
        public void setMaxHintsPerLab(int maxHintsPerLab) { this.maxHintsPerLab = maxHintsPerLab; }
        public int getMaxChatsPerLab() { return maxChatsPerLab; }
        public void setMaxChatsPerLab(int maxChatsPerLab) { this.maxChatsPerLab = maxChatsPerLab; }
    }

    public static class Ai {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private int timeoutMs = 15000;
        private int maxTokens = 700;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }
}
