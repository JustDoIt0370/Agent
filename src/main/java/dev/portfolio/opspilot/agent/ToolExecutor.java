package dev.portfolio.opspilot.agent;

import com.fasterxml.jackson.databind.*;
import dev.portfolio.opspilot.lab.LabService;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class ToolExecutor {
    private final LabService lab;
    private final Runbooks runbooks;
    private final ObjectMapper json;
    private final dev.portfolio.opspilot.lab.JvmInspector jvm;
    public ToolExecutor(LabService lab, Runbooks runbooks, ObjectMapper json, dev.portfolio.opspilot.lab.JvmInspector jvm) { this.lab = lab; this.runbooks = runbooks; this.json = json; this.jvm = jvm; }
    public JsonNode execute(String name, JsonNode args, String window, Instant since, List<Investigation.Evidence> evidence, Map<String, LabService.Trace> pinnedTraces) {
        lab.requireWindow(window);
        Object result = switch (name) {
            case "get_thread_dump" -> { keys(args); yield jvm.snapshot(); }
            case "get_metrics" -> {
                keys(args); var metrics = lab.metrics(window, since);
                for (String id : metrics.traceIds()) pinnedTraces.put(id, lab.trace(window, id));
                yield metrics;
            }
            case "query_logs" -> {
                keys(args, "limit");
                if (!args.path("limit").isInt() || args.get("limit").asInt() < 1 || args.get("limit").asInt() > 20) throw new IllegalArgumentException("limit 必须为 1–20 的整数");
                var logs = lab.logs(window, args.get("limit").asInt());
                for (var log : logs) pinnedTraces.put(log.traceId(), lab.trace(window, log.traceId()));
                yield logs;
            }
            case "get_trace" -> {
                keys(args, "traceId"); String traceId = text(args, "traceId", 64);
                boolean seen = evidence.stream().anyMatch(e -> (e.tool().equals("get_metrics") && contains(e.data().path("traceIds"), traceId))
                        || (e.tool().equals("query_logs") && iterable(e.data()).stream().anyMatch(log -> log.path("traceId").asText().equals(traceId))));
                if (!seen) throw new IllegalArgumentException("traceId 必须来自本次查询返回的指标或日志");
                yield pinnedTraces.containsKey(traceId) ? pinnedTraces.get(traceId) : lab.trace(window, traceId);
            }
            case "get_recent_changes" -> { keys(args); yield lab.changes(window); }
            case "search_runbooks" -> { keys(args, "query"); yield runbooks.search(text(args, "query", 200)); }
            default -> throw new IllegalArgumentException("工具不在只读查询白名单内");
        };
        return json.valueToTree(result);
    }
    public Investigation.Diagnosis validateDiagnosis(JsonNode args, List<Investigation.Evidence> evidence) {
        keys(args, "causeCode", "summary", "confidence", "evidenceIds", "runbookId", "actionKey");
        String code = text(args, "causeCode", 40), summary = text(args, "summary", 2000);
        String bookId = text(args, "runbookId", 64), action = text(args, "actionKey", 64);
        JsonNode confidence = args.path("confidence"), ids = args.path("evidenceIds");
        if (!confidence.isNumber() || !Double.isFinite(confidence.asDouble()) || confidence.asDouble() < 0 || confidence.asDouble() > 1)
            throw new IllegalArgumentException("confidence 必须介于 0 与 1 之间");
        if (!ids.isArray() || ids.size() < 3 || ids.size() > 12) throw new IllegalArgumentException("至少引用三项有效证据");
        var cited = new ArrayList<Investigation.Evidence>(); var unique = new HashSet<String>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || !unique.add(id.asText())) throw new IllegalArgumentException("证据引用不能重复");
            cited.add(evidence.stream().filter(e -> e.id().equals(id.asText())).findFirst().orElseThrow(() -> new IllegalArgumentException("引用了不存在的证据")));
        }
        if (cited.stream().noneMatch(e -> e.tool().equals("get_metrics") && e.data().path("samples").asInt() >= 6))
            throw new IllegalArgumentException("evidenceIds 必须包含有效 get_metrics 的证据 ID");
        if (cited.stream().noneMatch(e -> e.tool().equals("query_logs") || e.tool().equals("get_trace")))
            throw new IllegalArgumentException("evidenceIds 必须包含 query_logs 或 get_trace 的证据 ID");
        if (cited.stream().noneMatch(e -> e.tool().equals("search_runbooks") && iterable(e.data()).stream().anyMatch(b -> b.path("id").asText().equals(bookId))))
            throw new IllegalArgumentException("evidenceIds 必须包含 search_runbooks 返回的证据 ID，并选择其中存在的 runbookId；Runbook 编号不是证据 ID");
        var book = runbooks.get(bookId);
        if (!book.causeCode().equals(code) || !book.actionKey().equals(action)) throw new IllegalArgumentException("诊断分类和修复动作必须符合所选 Runbook");
        return new Investigation.Diagnosis(code, summary, confidence.asDouble(), iterable(ids).stream().map(JsonNode::asText).toList(), bookId, action);
    }
    private static boolean contains(JsonNode array, String value) { return iterable(array).stream().anyMatch(n -> n.asText().equals(value)); }
    private static List<JsonNode> iterable(JsonNode array) { var result = new ArrayList<JsonNode>(); array.forEach(result::add); return result; }
    private static void keys(JsonNode args, String... names) {
        if (!args.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        var expected = Set.of(names); var actual = new HashSet<String>(); args.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("工具参数字段不匹配：" + String.join(",", names));
    }
    private static String text(JsonNode args, String key, int maximum) {
        JsonNode value = args.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maximum) throw new IllegalArgumentException(key + " 长度或格式无效");
        return value.asText();
    }
}
