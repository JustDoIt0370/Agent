package dev.portfolio.opspilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Explicit two-turn protocol probe. Sends synthetic data only and never executes incident tools. */
@Service
public class ModelConnection {
 public record Result(String status,String message,Instant checkedAt,long durationMs,int tokens) {}
 public record Status(boolean configured,String model,String endpointHost,String protocol,Result lastCheck) {}
 private final AgentSettings settings;
 private final ModelGateway gateway;
 private final ObjectMapper json;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private volatile Result last;
 private boolean running;
 public ModelConnection(AgentSettings settings,SpringAiGateway gateway,ObjectMapper json){this.settings=settings;this.gateway=gateway;this.json=json;}
 public Status status(){
   String host="未配置";try{host=URI.create(settings.baseUrl()).getHost();}catch(RuntimeException ignored){}
   return new Status(settings.liveAvailable(),settings.model(),host,"OpenAI Chat Completions / Function Calling",last);
 }
 public Result check(){
   synchronized(this){
     if(running)throw new IllegalStateException("连接检测正在进行，请稍后查看结果");
     if(!settings.liveAvailable())return last=new Result("NOT_CONFIGURED","请设置 AGENT_API_KEY、AGENT_BASE_URL、AGENT_MODEL 后重启。",Instant.now(),0,0);
     if(last!=null&&last.checkedAt().plusSeconds(30).isAfter(Instant.now()))return last;
     running=true;
   }
   long start=System.nanoTime();
   Future<Result> task=worker.submit(()->probe(gateway,json));
   try{
     var r=task.get(45,TimeUnit.SECONDS);
     return last=new Result(r.status(),r.message(),Instant.now(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),r.tokens());
   }catch(InterruptedException e){Thread.currentThread().interrupt();task.cancel(true);return last=failure("INTERRUPTED","检测已中断",start);}
   catch(TimeoutException e){task.cancel(true);return last=failure("TIMEOUT","模型响应超过 45 秒，请检查网络或选择响应更快的模型。",start);}
   catch(Exception e){task.cancel(true);return last=failure("CONNECTION_FAILED","调用失败：请检查 API Key、模型权限、接口地址及供应商兼容性；密钥和原始错误不会返回页面。",start);}
   finally{synchronized(this){running=false;}}
 }
 private Result failure(String code,String message,long start){return new Result(code,message,Instant.now(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),0);}
 public static Result probe(ModelGateway gateway,ObjectMapper json) throws Exception {
   List<Message> messages=new ArrayList<>(List.of(new SystemMessage("这是协议兼容性测试，不是故障诊断。每次只调用一次 get_metrics，参数必须是空对象。收到模拟工具结果后再次调用同一工具。不要调用其他工具。"),new UserMessage("开始第一次工具调用。")));
   int tokens=0;
   for(int i=0;i<2;i++){
     var turn=gateway.next(List.copyOf(messages),List.of());tokens+=turn.inputTokens()+turn.outputTokens();
     var message=turn.message();
     if(!message.hasToolCalls()||message.getToolCalls().size()!=1)return new Result("INCOMPATIBLE","模型未按约定返回单个工具调用；可重试或更换支持 Function Calling 的模型。",Instant.now(),0,tokens);
     var call=message.getToolCalls().getFirst();
     if(!"get_metrics".equals(call.name())||call.id()==null||call.id().isBlank()||call.arguments()==null||call.arguments().length()>1000)
       return new Result("INCOMPATIBLE","工具名称、ID 或参数格式不兼容。",Instant.now(),0,tokens);
     var args=json.readTree(call.arguments());
     if(args==null||!args.isObject()||!args.isEmpty())return new Result("INCOMPATIBLE","工具参数未遵循 JSON Schema。",Instant.now(),0,tokens);
     messages.add(message);
     messages.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(call.id(),call.name(),"{\"probe\":true,\"note\":\"模拟结果，不包含故障数据\"}"))).build());
   }
   return new Result("PASSED","两轮工具调用与 tool_call_id 回传通过。仅验证基础协议兼容性；诊断能力仍需运行模型模式的四场景评测。",Instant.now(),0,tokens);
 }
 @PreDestroy void close(){worker.shutdownNow();}
}
