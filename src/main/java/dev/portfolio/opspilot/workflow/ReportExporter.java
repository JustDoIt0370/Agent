package dev.portfolio.opspilot.workflow;
import dev.portfolio.opspilot.agent.*;
import org.springframework.stereotype.Component;

@Component
public class ReportExporter {
 private final Runbooks books;
 public ReportExporter(Runbooks books){this.books=books;}
 public String markdown(Investigation run,OpsService.Approval approval){
   var out=new StringBuilder("# OpsPilot 故障诊断报告\n\n");
   section(out,"调查信息",run.id()+"\n窗口："+run.windowId()+"\n时间："+run.createdAt()+"\n模式："+run.mode()+"\n状态："+run.status()+"\n耗时："+run.durationMs()+"ms\nTokens："+(run.inputTokens()+run.outputTokens()));
   section(out,"调查目标",run.question());
   if(run.diagnosis()!=null){var d=run.diagnosis();var b=books.get(d.runbookId());
     section(out,"诊断结论",d.causeCode()+"\n"+d.summary()+"\n证据支持度自评（非校准概率）："+d.confidence()+"\n依据："+String.join(", ",d.evidenceIds()));
     section(out,"Runbook",b.id()+" / "+b.version()+"\n"+b.title()+"\n动作："+b.actionLabel()+"\n风险："+b.risk()+"\n回滚："+b.rollback());}
   if(run.error()!=null)section(out,"执行错误",run.error());
   for(var s:run.steps())section(out,"步骤 "+s.sequence(),s.timestamp()+" / "+s.tool()+" / "+s.title()+" / "+s.evidenceId()+" / "+s.durationMs()+"ms");
   for(var e:run.evidence())section(out,e.id()+" / "+e.tool(),e.collectedAt()+"\n"+e.data().toPrettyString());
   section(out,"修复记录",approval==null?"没有已记录的人工确认；导出报告不会执行修复。":approval.toString());
   out.append("\n报告基于本地受控实验室的有限观测，历史窗口不代表当前健康状态。\n");return out.toString();
 }
 private static void section(StringBuilder out,String title,String value){
   String fence="~~~~";while(value.contains(fence))fence+="~";
   out.append("\n## ").append(title).append("\n\n").append(fence).append("text\n").append(value).append("\n").append(fence).append("\n");
 }
}
