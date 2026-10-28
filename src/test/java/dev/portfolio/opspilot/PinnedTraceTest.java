package dev.portfolio.opspilot;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.portfolio.opspilot.agent.*;
import dev.portfolio.opspilot.lab.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PinnedTraceTest {
 @Test void traceRemainsQueryableAfterRollingBufferEvictsIt() {
   var lab=mock(LabService.class);var json=new ObjectMapper().findAndRegisterModules();var now=Instant.now();
   var metrics=new LabService.Metrics("window",now,6,0,0,12,1,1,12,0,4,0,4,List.of("trace-1"));
   var trace=new LabService.Trace("trace-1",now,12,"OK",List.of(new LabService.Span("jdbc.query",1,"OK")));
   when(lab.metrics("window",now)).thenReturn(metrics);
   when(lab.trace("window","trace-1")).thenReturn(trace).thenThrow(new IllegalArgumentException("evicted"));
   var executor=new ToolExecutor(lab,new Runbooks(),json,new JvmInspector());
   var pinned=new HashMap<String,LabService.Trace>();
   executor.execute("get_metrics",json.createObjectNode(),"window",now,List.of(),pinned);
   var data=json.createObjectNode().set("traceIds",json.valueToTree(List.of("trace-1")));
   var evidence=List.of(new Investigation.Evidence("EV-001","get_metrics",now,data));
   var result=executor.execute("get_trace",json.createObjectNode().put("traceId","trace-1"),"window",now,evidence,pinned);
   assertThat(result.path("id").asText()).isEqualTo("trace-1");verify(lab,times(1)).trace("window","trace-1");
   assertThatThrownBy(()->executor.execute("get_trace",json.createObjectNode().put("traceId","unseen"),"window",now,evidence,pinned)).isInstanceOf(IllegalArgumentException.class);
 }
}
