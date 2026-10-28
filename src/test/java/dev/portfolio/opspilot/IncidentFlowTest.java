package dev.portfolio.opspilot;

import dev.portfolio.opspilot.agent.*;
import dev.portfolio.opspilot.lab.LabService;
import dev.portfolio.opspilot.workflow.OpsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:flow", "agent.api-key="})
class IncidentFlowTest {
 @Autowired LabService lab;
 @Autowired AgentEngine engine;
 @Autowired OpsService ops;
 @Test void diagnosesRealFaultsAndRecoversAfterApproval() throws Exception {
   for(var scenario:LabService.Scenario.values()) {
     var state=ops.activate(scenario);
     var run=engine.execute(engine.prepare(state.windowId(),"BASELINE","定位当前异常"));
     assertThat(run.status()).withFailMessage("%s", run.error()).isEqualTo("COMPLETED");
     assertThat(run.diagnosis().causeCode()).isEqualTo(scenario.name());
     assertThat(run.evidence()).hasSizeGreaterThanOrEqualTo(3);
     if(scenario==LabService.Scenario.HEALTHY) continue;
     var receipt=ops.approve(run.id(),state.windowId());
     assertThat(ops.approve(run.id(),state.windowId()).id()).isEqualTo(receipt.id());
     long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
     while(!ops.busy().isEmpty()&&System.nanoTime()<deadline) Thread.sleep(100);
     assertThat(ops.approval(run.id()).status()).isEqualTo("RECOVERED");
     assertThat(ops.approval(run.id()).verification().errors()).isZero();
   }
 }
 @Test void rejectsStaleApproval() throws Exception {
   var state=ops.activate(LabService.Scenario.SLOW_SQL);
   var run=engine.execute(engine.prepare(state.windowId(),"BASELINE","调查"));
   ops.activate(LabService.Scenario.HEALTHY);
   assertThatThrownBy(()->ops.approve(run.id(),state.windowId())).isInstanceOf(IllegalStateException.class);
 }
}

