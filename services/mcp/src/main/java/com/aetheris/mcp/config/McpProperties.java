package com.aetheris.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MCP 服务参数：协议版本、结果截断、限流与本地演示开关。 */
@ConfigurationProperties(prefix = "aetheris.mcp")
public class McpProperties {

    private boolean devMode = false;
    private String devUser = "dev-learner";
    private String protocolVersion = "2026-07-28";
    private int maxResultChars = 12_000;
    private int rateLimitCapacity = 60;

    public boolean isDevMode() { return devMode; }
    public void setDevMode(boolean devMode) { this.devMode = devMode; }
    public String getDevUser() { return devUser; }
    public void setDevUser(String devUser) { this.devUser = devUser; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public int getMaxResultChars() { return maxResultChars; }
    public void setMaxResultChars(int maxResultChars) { this.maxResultChars = maxResultChars; }
    public int getRateLimitCapacity() { return rateLimitCapacity; }
    public void setRateLimitCapacity(int rateLimitCapacity) { this.rateLimitCapacity = rateLimitCapacity; }
}
