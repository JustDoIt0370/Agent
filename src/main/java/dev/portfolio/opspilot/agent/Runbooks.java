package dev.portfolio.opspilot.agent;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class Runbooks {
    public record Runbook(String id, String version, String causeCode, String title, String symptom,
                          List<String> checks, String actionKey, String actionLabel, String risk, String rollback, List<String> tags) {}
    private final List<Runbook> entries = List.of(
        new Runbook("RB-SQL-001", "1.0", "SLOW_SQL", "SQL 执行时间异常", "jdbc.query 耗时持续升高，连接获取时间可能随之上涨。",
            List.of("核对 jdbc.query span 与连接等待耗时", "检查最近查询配置变化，避免仅凭连接池使用率判断"),
            "RESET_SQL_DELAY", "恢复 SQL 延迟配置", "仅清除本地实验室的 SQL 等待参数；生产环境应先检查执行计划与锁等待。", "在故障实验室重新注入 SQL 延迟。", List.of("sql", "慢查询", "数据库", "jdbc", "SLOW_SQL")),
        new Runbook("RB-POOL-001", "1.0", "POOL_EXHAUSTION", "数据库连接池耗尽", "pool.acquire 出现超时，连接持续占满且有请求等待。",
            List.of("确认 POOL_ACQUIRE_TIMEOUT 日志与连接等待 span", "区分持有连接与 SQL 变慢导致的二次排队"),
            "RELEASE_POOL_HOLDERS", "释放实验室占用连接", "只释放故障注入器持有的连接；不结束业务线程，也不执行数据库 kill。", "在故障实验室重新注入连接池耗尽。", List.of("pool", "连接池", "连接", "hikari", "POOL_EXHAUSTION")),
        new Runbook("RB-HTTP-001", "1.0", "DOWNSTREAM_TIMEOUT", "下游库存服务超时", "http.inventory span 超过客户端 300ms 超时阈值。",
            List.of("确认下游超时日志与 HTTP span", "检查数据库耗时是否正常，避免将所有超时归因于数据库"),
            "RESET_PARTNER_LATENCY", "恢复下游响应延迟", "只恢复本机库存服务响应时间；不会修改真实外部服务。", "在故障实验室重新注入下游超时。", List.of("http", "timeout", "下游", "超时", "DOWNSTREAM_TIMEOUT")),
        new Runbook("RB-HEALTH-001", "1.0", "HEALTHY", "当前窗口未发现异常", "有效样本无错误，SQL、连接等待和下游耗时处于实验室正常范围。",
            List.of("至少观察 6 个完整请求", "正常结论仅适用于当前采样窗口"), "NONE", "无需修复", "有限采样无法保证整个系统永久健康。", "无需回滚。", List.of("healthy", "健康", "正常", "HEALTHY")),
        new Runbook("RB-UNKNOWN-001", "1.0", "UNKNOWN", "证据不足或故障超出范围", "已有证据不支持任何已知故障分类。",
            List.of("保留证据与未确认项", "转人工调查，不提出未经验证的变更"), "NONE", "需要人工排查", "不执行修复动作。", "无需回滚。", List.of("unknown", "未知", "不足", "UNKNOWN"))
    );
    public List<Runbook> all() { return entries; }
    public Runbook get(String id) { return entries.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Runbook 不存在")); }
    public List<Runbook> search(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return entries.stream().sorted(Comparator.<Runbook>comparingInt(r -> score(r, q)).reversed()).limit(3).toList();
    }
    private int score(Runbook r, String query) {
        return r.tags().stream().mapToInt(t -> query.contains(t.toLowerCase(Locale.ROOT)) ? (t.equals(r.causeCode()) ? 20 : 2) : 0).sum();
    }
}
