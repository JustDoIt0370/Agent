package dev.portfolio.opspilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record Investigation(String id, String windowId, Instant createdAt, String mode, String question,
                            String status, List<Step> steps, List<Evidence> evidence, Diagnosis diagnosis,
                            String error, int inputTokens, int outputTokens, long durationMs) {
    public Investigation { steps = List.copyOf(steps); evidence = List.copyOf(evidence); }
    public record Step(int sequence, String kind, String title, String tool, Instant timestamp, long durationMs, String evidenceId, String detail) {}
    public record Evidence(String id, String tool, Instant collectedAt, JsonNode data) {}
    public record Diagnosis(String causeCode, String summary, double confidence, List<String> evidenceIds, String runbookId, String actionKey) {}
    public Investigation interrupted() {
        return new Investigation(id, windowId, createdAt, mode, question, "FAILED", steps, evidence, null,
                "服务已重启，未完成的诊断已终止；请重新发起。", inputTokens, outputTokens, durationMs);
    }
}
