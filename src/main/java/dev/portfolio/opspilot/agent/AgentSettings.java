package dev.portfolio.opspilot.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentSettings(String apiKey, String baseUrl, String model, int maxRounds, int maxToolCalls, int timeoutSeconds, String extraBodyJson) {
    public boolean liveAvailable() { return apiKey != null && !apiKey.isBlank(); }
}
