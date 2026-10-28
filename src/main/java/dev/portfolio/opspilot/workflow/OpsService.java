package dev.portfolio.opspilot.workflow;

import dev.portfolio.opspilot.agent.*;
import dev.portfolio.opspilot.lab.LabService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class OpsService {
    public record Approval(String id, String runId, String windowId, String actionKey, String status,
                           Instant approvedAt, Instant appliedAt, LabService.Metrics verification, String message) {}
    public record EvalCase(String scenario, String expected, String actual, boolean passed, String runId, int toolCalls, long durationMs) {}
    public record Evaluation(String id, String mode, String status, Instant createdAt, List<EvalCase> cases, int total, int passed, String error) {
        public Evaluation { cases = List.copyOf(cases); }
    }
    private final LabService lab;
    private final AgentEngine engine;
    private final DocumentStore store;
    private final AgentSettings settings;
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private volatile String busy = "";
    public OpsService(LabService lab, AgentEngine engine, DocumentStore store, AgentSettings settings) {
        this.lab = lab; this.engine = engine; this.store = store; this.settings = settings;
    }
    @PostConstruct void recover() {
        for (Approval a : store.list("approval", Approval.class)) if (Set.of("APPLYING", "VERIFYING").contains(a.status()))
            store.save("approval", a.id(), new Approval(a.id(), a.runId(), a.windowId(), a.actionKey(), "INTERRUPTED", a.approvedAt(), a.appliedAt(), a.verification(), "服务已重启，旧窗口已失效；不会重复执行动作。"));
        for (Evaluation e : store.list("evaluation", Evaluation.class)) if (e.status().equals("RUNNING"))
            store.save("evaluation", e.id(), new Evaluation(e.id(), e.mode(), "FAILED", e.createdAt(), e.cases(), e.total(), e.passed(), "服务重启导致评测中断"));
    }
    public String busy() { return busy; }
    private synchronized void begin(String operation) {
        if (!busy.isEmpty()) throw new IllegalStateException("当前正在执行 " + busy + "，完成后可继续操作");
        busy = operation;
    }
    private synchronized void end() { busy = ""; }
    public synchronized LabService.State activate(LabService.Scenario scenario) {
        begin("故障切换");
        try { return lab.activate(scenario); } finally { end(); }
    }
    public synchronized Investigation investigate(String mode, String question) {
        begin("诊断调查");
        try {
            var run = engine.prepare(lab.state().windowId(), mode, question);
            jobs.submit(() -> { try { engine.execute(run); } finally { end(); } });
            return run;
        } catch (RuntimeException e) { end(); throw e; }
    }
    public List<Investigation> investigations() { return store.list("run", Investigation.class); }
    public Investigation investigation(String id) { return store.get("run", id, Investigation.class); }
    public Approval approval(String id) {
        try { return store.get("approval", id, Approval.class); } catch (NoSuchElementException e) { return null; }
    }
    public synchronized Approval approve(String id, String expectedWindow) {
        Approval previous = approval(id);
        if (previous != null) {
            if (!previous.windowId().equals(expectedWindow)) throw new IllegalStateException("审批窗口不匹配");
            return previous;
        }
        Investigation run = investigation(id);
        if (!run.status().equals("COMPLETED") || run.diagnosis() == null || run.diagnosis().actionKey().equals("NONE")) throw new IllegalStateException("此诊断没有可执行的修复动作");
        if (!run.windowId().equals(expectedWindow)) throw new IllegalStateException("审批窗口不匹配");
        if (run.createdAt().plus(Duration.ofMinutes(20)).isBefore(Instant.now())) throw new IllegalStateException("诊断超过 20 分钟，请重新采样后审批");
        lab.requireWindow(run.windowId());
        begin("修复与验证");
        try {
            var receipt = new Approval(id, id, run.windowId(), run.diagnosis().actionKey(), "APPLYING", Instant.now(), null, null, "人工确认已记录，准备执行本地白名单动作");
            store.save("approval", id, receipt);
            jobs.submit(() -> performRepair(receipt));
            return receipt;
        } catch (RuntimeException e) { end(); throw e; }
    }
    private void performRepair(Approval a) {
        Instant applied = null;
        try {
            applied = lab.repair(a.windowId(), a.actionKey());
            store.save("approval", a.id(), new Approval(a.id(), a.runId(), a.windowId(), a.actionKey(), "VERIFYING", a.approvedAt(), applied, null, "动作已执行，等待新的请求验证恢复"));
            Thread.sleep(1200); // Drain requests already in flight before selecting a fresh verification window.
            var metrics = lab.awaitSamples(a.windowId(), Instant.now(), 12, Duration.ofSeconds(8));
            boolean recovered = metrics.errors() == 0 && metrics.p95Ms() < 450 && metrics.meanSqlMs() < 120 && metrics.meanPoolWaitMs() < 100;
            store.save("approval", a.id(), new Approval(a.id(), a.runId(), a.windowId(), a.actionKey(), recovered ? "RECOVERED" : "NOT_RECOVERED", a.approvedAt(), applied, metrics,
                    recovered ? "新采样窗口无错误，SQL 与连接等待恢复正常；本地故障已解除。" : "新样本尚未满足恢复阈值，请检查证据并重新诊断。"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); saveRepairFailure(a, applied, "验证被中断，请重新诊断确认当前状态。");
        } catch (Exception e) { saveRepairFailure(a, applied, "修复或验证失败；动作可能已生效，请重新诊断确认状态。"); }
        finally { end(); }
    }
    private void saveRepairFailure(Approval a, Instant applied, String message) {
        store.save("approval", a.id(), new Approval(a.id(), a.runId(), a.windowId(), a.actionKey(), "FAILED", a.approvedAt(), applied, null, message));
    }
    public synchronized Evaluation evaluate(String mode) {
        if (!Set.of("BASELINE", "MODEL").contains(mode)) throw new IllegalArgumentException("未知诊断模式");
        if (mode.equals("MODEL") && !settings.liveAvailable()) throw new IllegalStateException("模型未配置");
        begin("场景评测");
        try {
            var report = new Evaluation(UUID.randomUUID().toString(), mode, "RUNNING", Instant.now(), List.of(), 4, 0, null);
            store.save("evaluation", report.id(), report);
            jobs.submit(() -> performEvaluation(report)); return report;
        } catch (RuntimeException e) { end(); throw e; }
    }
    private void performEvaluation(Evaluation report) {
        var cases = new ArrayList<EvalCase>(); String error = null;
        try {
            for (var scenario : LabService.Scenario.values()) {
                var state = lab.activate(scenario);
                var run = engine.execute(engine.prepare(state.windowId(), report.mode(), "根据当前观测判断服务健康状况，引用证据。"));
                String actual = run.diagnosis() == null ? "FAILED" : run.diagnosis().causeCode();
                boolean passed = actual.equals(scenario.name()) && run.status().equals("COMPLETED") && run.evidence().size() >= 3;
                cases.add(new EvalCase(scenario.name(), scenario.name(), actual, passed, run.id(), (int) run.steps().stream().filter(s -> !s.tool().isEmpty()).count(), run.durationMs()));
                store.save("evaluation", report.id(), evaluation(report, "RUNNING", cases, null));
            }
        } catch (Exception e) { error = "评测中断，请检查服务状态后重试"; }
        finally {
            try { lab.activate(LabService.Scenario.HEALTHY); }
            catch (Exception e) { error = "评测结束时未能恢复健康窗口，请手动切换"; }
            store.save("evaluation", report.id(), evaluation(report, error == null ? "COMPLETED" : "FAILED", cases, error));
            end();
        }
    }
    private Evaluation evaluation(Evaluation original, String status, List<EvalCase> cases, String error) {
        return new Evaluation(original.id(), original.mode(), status, original.createdAt(), cases, original.total(), (int) cases.stream().filter(EvalCase::passed).count(), error);
    }
    public List<Evaluation> evaluations() { return store.list("evaluation", Evaluation.class); }
    public Evaluation evaluation(String id) { return store.get("evaluation", id, Evaluation.class); }
    @PreDestroy void close() { jobs.shutdownNow(); }
}
