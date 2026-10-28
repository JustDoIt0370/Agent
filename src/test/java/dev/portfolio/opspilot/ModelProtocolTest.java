package dev.portfolio.opspilot;
import dev.portfolio.opspilot.agent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ModelProtocolTest {
 @Test void verifiesSecondTurnCarriesOriginalToolCallId() throws Exception {
   var count=new AtomicInteger();
   ModelGateway mock=(messages,evidence)->{
     int i=count.getAndIncrement();
     if(i==1){assertThat(messages.getLast()).isInstanceOf(ToolResponseMessage.class);
       var response=((ToolResponseMessage)messages.getLast()).getResponses().getFirst();assertThat(response.id()).isEqualTo("probe-0");}
     return new ModelGateway.Turn(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("probe-"+i,"function","get_metrics","{}"))).build(),10,5);
   };
   var r=ModelConnection.probe(mock,new ObjectMapper());assertThat(r.status()).isEqualTo("PASSED");assertThat(r.tokens()).isEqualTo(30);
 }
 @Test void refusesPlainChatAndArbitraryTools() throws Exception {
   ModelGateway chat=(m,e)->new ModelGateway.Turn(new AssistantMessage("你好"),1,1);
   assertThat(ModelConnection.probe(chat,new ObjectMapper()).status()).isEqualTo("INCOMPATIBLE");
   ModelGateway shell=(m,e)->new ModelGateway.Turn(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("id","function","shell","{}"))).build(),0,0);
   assertThat(ModelConnection.probe(shell,new ObjectMapper()).status()).isEqualTo("INCOMPATIBLE");
 }
}
