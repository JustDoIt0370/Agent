package dev.portfolio.opspilot.lab;
import org.springframework.stereotype.Component;
import java.lang.management.*;
import java.time.Instant;
import java.util.*;

/** Bounded read-only observations; never reads environment variables or invokes external commands. */
@Component
public class JvmInspector {
 public record ThreadSample(long id,String name,String state,String lockName,String lockOwner,List<String> stack) {}
 public record Snapshot(Instant collectedAt,long uptimeMs,long heapUsedBytes,long heapMaxBytes,int liveThreads,
   List<Long> deadlockedThreadIds,Map<String,Integer> states,List<ThreadSample> threads,boolean truncated,String scope) {}
 public Snapshot snapshot() {
   var bean=ManagementFactory.getThreadMXBean(); var memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
   long[] dead=bean.isSynchronizerUsageSupported()?bean.findDeadlockedThreads():bean.findMonitorDeadlockedThreads();
   var ids=dead==null?List.<Long>of():Arrays.stream(dead).boxed().toList();
   var infos=Arrays.stream(bean.getThreadInfo(bean.getAllThreadIds(),10)).filter(Objects::nonNull).toList();
   var states=new TreeMap<String,Integer>(); infos.forEach(t->states.merge(t.getThreadState().name(),1,Integer::sum));
   var samples=infos.stream().sorted(Comparator.<ThreadInfo>comparingInt(t->ids.contains(t.getThreadId())?0:t.getThreadName().startsWith("checkout-worker-")?1:t.getThreadState()==Thread.State.BLOCKED?2:3)
     .thenComparingLong(ThreadInfo::getThreadId)).limit(20).map(t->new ThreadSample(t.getThreadId(),t.getThreadName(),t.getThreadState().name(),t.getLockName(),t.getLockOwnerName(),Arrays.stream(t.getStackTrace()).map(StackTraceElement::toString).toList())).toList();
   return new Snapshot(Instant.now(),ManagementFactory.getRuntimeMXBean().getUptime(),memory.getUsed(),memory.getMax(),bean.getThreadCount(),ids,states,samples,infos.size()>samples.size(),
     "当前 OpsPilot JVM，包含工作台与故障实验室；瞬时 WAITING 不等于死锁，单次采样不能证明持续阻塞。");
 }
}
