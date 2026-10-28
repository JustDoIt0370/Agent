package dev.portfolio.opspilot.workflow;

import dev.portfolio.opspilot.agent.*;
import dev.portfolio.opspilot.lab.LabService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    public record ScenarioRequest(@NotNull LabService.Scenario scenario) {}
    public record InvestigateRequest(@NotBlank @Pattern(regexp = "BASELINE|MODEL") String mode, @NotBlank @Size(max = 800) String question) {}
    public record ApprovalRequest(@NotBlank @Size(max = 64) String windowId) {}
    public record EvalRequest(@NotBlank @Pattern(regexp = "BASELINE|MODEL") String mode) {}
    private final OpsService ops;
    private final LabService lab;
    private final AgentSettings settings;
    private final Runbooks runbooks;
    private final dev.portfolio.opspilot.lab.JvmInspector jvm;
    private final ReportExporter exporter;
    public ApiController(OpsService ops, LabService lab, AgentSettings settings, Runbooks runbooks, dev.portfolio.opspilot.lab.JvmInspector jvm, ReportExporter exporter) { this.jvm=jvm; this.exporter=exporter; this.ops = ops; this.lab = lab; this.settings = settings; this.runbooks = runbooks; }
    @GetMapping("/jvm") public dev.portfolio.opspilot.lab.JvmInspector.Snapshot jvm() { return jvm.snapshot(); }
    @GetMapping("/investigations/{id}/report") public org.springframework.http.ResponseEntity<byte[]> report(@PathVariable String id) {
        var content=exporter.markdown(ops.investigation(id),ops.approval(id));
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition", "attachment; filename=opspilot-report.md")
            .contentType(org.springframework.http.MediaType.parseMediaType("text/markdown;charset=UTF-8")).body(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @GetMapping("/overview") public Map<String, Object> overview() {
        return Map.of("name", "OpsPilot", "version", "1.0.0", "service", lab.state(), "busy", ops.busy(),
                "modelAvailable", settings.liveAvailable(), "model", settings.model(), "limits", Map.of("rounds", settings.maxRounds(), "toolCalls", settings.maxToolCalls(), "timeoutSeconds", settings.timeoutSeconds()));
    }
    @PostMapping("/lab/scenario") public LabService.State scenario(@Valid @RequestBody ScenarioRequest request) { return ops.activate(request.scenario()); }
    @GetMapping("/investigations") public List<Investigation> investigations() { return ops.investigations(); }
    @PostMapping("/investigations") public Investigation investigate(@Valid @RequestBody InvestigateRequest request) { return ops.investigate(request.mode(), request.question()); }
    @GetMapping("/investigations/{id}") public Investigation investigation(@PathVariable String id) { return ops.investigation(id); }
    @GetMapping("/investigations/{id}/approval") public Map<String, Object> approval(@PathVariable String id) {
        ops.investigation(id); var a = ops.approval(id); return a == null ? Map.of() : Map.of("approval", a);
    }
    @PostMapping("/investigations/{id}/approve") public OpsService.Approval approve(@PathVariable String id, @Valid @RequestBody ApprovalRequest request) { return ops.approve(id, request.windowId()); }
    @GetMapping("/runbooks") public List<Runbooks.Runbook> runbooks() { return runbooks.all(); }
    @GetMapping("/evaluations") public List<OpsService.Evaluation> evaluations() { return ops.evaluations(); }
    @PostMapping("/evaluations") public OpsService.Evaluation evaluate(@Valid @RequestBody EvalRequest request) { return ops.evaluate(request.mode()); }
    @GetMapping("/evaluations/{id}") public OpsService.Evaluation evaluation(@PathVariable String id) { return ops.evaluation(id); }
}
