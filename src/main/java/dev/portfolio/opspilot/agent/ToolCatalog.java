package dev.portfolio.opspilot.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.List;

public final class ToolCatalog {
    private ToolCatalog() {}
    public static final List<ToolCallback> TOOLS = List.of(
        definition("get_metrics", "查询当前授权故障窗口的真实请求指标、连接池状态和可查询 traceId。请首先调用。", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
        definition("query_logs", "查询当前窗口最近的请求日志，返回实际错误代码及 traceId。", "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"required\":[\"limit\"],\"additionalProperties\":false}"),
        definition("get_trace", "根据已观察到的 traceId 查询 JDBC、连接等待和下游 HTTP span。", "{\"type\":\"object\",\"properties\":{\"traceId\":{\"type\":\"string\"}},\"required\":[\"traceId\"],\"additionalProperties\":false}"),
        definition("get_recent_changes", "查询当前窗口的配置变更事实，变更本身不等于故障原因。", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
        definition("search_runbooks", "按故障线索检索本地版本化 Runbook。返回排查步骤、动作及风险。", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":200}},\"required\":[\"query\"],\"additionalProperties\":false}"),
        definition("finish_diagnosis", "提交最终结构化诊断并结束调查。必须引用已返回的 evidenceId，至少包含指标、日志或调用链，以及所选 Runbook。此工具不会执行修复。无法支持已知原因时使用 UNKNOWN/NONE。", """
            {"type":"object","properties":{
              "causeCode":{"type":"string","enum":["SLOW_SQL","POOL_EXHAUSTION","DOWNSTREAM_TIMEOUT","HEALTHY","UNKNOWN"]},
              "summary":{"type":"string","maxLength":2000},"confidence":{"type":"number","minimum":0,"maximum":1},
              "evidenceIds":{"type":"array","items":{"type":"string"},"minItems":3},
              "runbookId":{"type":"string"},"actionKey":{"type":"string","enum":["RESET_SQL_DELAY","RELEASE_POOL_HOLDERS","RESET_PARTNER_LATENCY","NONE"]}},
              "required":["causeCode","summary","confidence","evidenceIds","runbookId","actionKey"],"additionalProperties":false}
            """)
    );
    private static ToolCallback definition(String name, String description, String schema) {
        var definition = ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { throw new IllegalStateException("工具必须通过受控执行器调用"); }
        };
    }
}
