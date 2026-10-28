package dev.portfolio.opspilot;
import dev.portfolio.opspilot.agent.*;
import dev.portfolio.opspilot.lab.JvmInspector;
import dev.portfolio.opspilot.workflow.ReportExporter;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DiagnosticExtensionsTest {
 @Test void boundsRealJvmObservations(){
   var s=new JvmInspector().snapshot();
   assertThat(s.liveThreads()).isPositive();assertThat(s.heapUsedBytes()).isPositive();
   assertThat(s.threads()).isNotEmpty().hasSizeLessThanOrEqualTo(20);
   assertThat(s.threads()).allMatch(t->t.stack().size()<=10);
 }
 @Test void exportsIncompleteRunWithoutInventingDiagnosis(){
   var run=new Investigation("run","window",Instant.now(),"BASELINE","~~~~\n<script>alert(1)</script>","FAILED",List.of(),List.of(),null,"budget",0,0,12);
   var report=new ReportExporter(new Runbooks()).markdown(run,null);
   assertThat(report).contains("BASELINE","window","budget","没有已记录的人工确认","~~~~~text");
   assertThat(report).doesNotContain("## 诊断结论");
 }
}
