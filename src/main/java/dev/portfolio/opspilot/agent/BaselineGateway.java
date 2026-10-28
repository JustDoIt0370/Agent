package dev.portfolio.opspilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import java.util.*;

/** A transparent deterministic comparison baseline. It sees tool observations, never the injected scenario. */
@Component
public class BaselineGateway implements ModelGateway {
    private final ObjectMapper json;
    public BaselineGateway(ObjectMapper json) { this.json = json; }
    @Override public Turn next(List<Message> messages, List<Investigation.Evidence> evidence) {
        if (!has(evidence, "get_metrics")) return call("get_metrics", Map.of(), "读取当前窗口的请求指标与连接池状态");
        JsonNode metrics = data(evidence, "get_metrics");
        if (!has(evidence, "get_trace")) return call("get_trace", Map.of("traceId", metrics.path("traceIds").get(0).asText()), "抽取耗时最高的请求，定位时间花在哪一段");
        String code = classify(metrics, data(evidence, "get_trace"));
        if (code.equals("POOL_EXHAUSTION") && !has(evidence, "get_thread_dump"))
            return call("get_thread_dump", Map.of(), "采集当前 JVM 线程栈，辅助核对连接等待");
        if (metrics.path("errors").asInt() > 0 && !has(evidence, "query_logs"))
            return call("query_logs", Map.of("limit", 12), "用错误日志交叉核对调用链中的异常");
        if (!code.equals("HEALTHY") && !has(evidence, "get_recent_changes"))
            return call("get_recent_changes", Map.of(), "检查配置变更，为故障原因补充上下文");
        if (!has(evidence, "search_runbooks")) return call("search_runbooks", Map.of("query", code), "检索与证据相符的排查手册和受控修复动作");
        JsonNode book = data(evidence, "search_runbooks").get(0);
        String summary = switch (code) {
            case "SLOW_SQL" -> "SQL 执行阶段存在持续延迟：平均 " + metrics.path("meanSqlMs").asDouble() + "ms，调用链将耗时定位到 jdbc.query。结合配置变更，建议恢复实验室 SQL 延迟参数。";
            case "POOL_EXHAUSTION" -> "请求在获取数据库连接时超时，连接池已无空闲连接。日志与 pool.acquire span 一致，建议释放故障注入器持有的连接。";
            case "DOWNSTREAM_TIMEOUT" -> "库存服务 HTTP 调用触发客户端超时，数据库阶段未出现同等延迟。日志与 http.inventory span 一致，建议恢复本机下游响应时间。";
            case "HEALTHY" -> "当前 " + metrics.path("samples").asInt() + " 个请求未出现错误，SQL、连接等待及下游耗时均在实验室正常范围。当前窗口无需修复。";
            default -> "已有指标与调用链不符合受支持的故障模式，保留证据并转人工排查，不执行变更。";
        };
        return call("finish_diagnosis", Map.of("causeCode", code, "summary", summary,
                "confidence", code.equals("UNKNOWN") ? .35 : .9, "evidenceIds", evidence.stream().map(Investigation.Evidence::id).toList(),
                "runbookId", book.path("id").asText(), "actionKey", book.path("actionKey").asText()), "提交带证据引用的诊断，等待人工确认后才能修复");
    }
    static String classify(JsonNode metrics, JsonNode trace) {
        String outcome = trace.path("outcome").asText();
        if (outcome.equals("POOL_ACQUIRE_TIMEOUT") && metrics.path("meanPoolWaitMs").asDouble() >= 500) return "POOL_EXHAUSTION";
        if (outcome.equals("DOWNSTREAM_TIMEOUT") && metrics.path("meanDownstreamMs").asDouble() >= 250) return "DOWNSTREAM_TIMEOUT";
        if (metrics.path("meanSqlMs").asDouble() >= 120) return "SLOW_SQL";
        if (metrics.path("errors").asInt() == 0 && metrics.path("meanPoolWaitMs").asDouble() < 100 && metrics.path("meanDownstreamMs").asDouble() < 220) return "HEALTHY";
        return "UNKNOWN";
    }
    private boolean has(List<Investigation.Evidence> evidence, String tool) { return evidence.stream().anyMatch(e -> e.tool().equals(tool)); }
    private JsonNode data(List<Investigation.Evidence> evidence, String tool) { return evidence.stream().filter(e -> e.tool().equals(tool)).findFirst().orElseThrow().data(); }
    private Turn call(String name, Object arguments, String note) {
        return new Turn(AssistantMessage.builder().content(note).toolCalls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, json.valueToTree(arguments).toString()))).build(), 0, 0);
    }
}
