package dev.portfolio.opspilot.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import java.util.List;

public interface ModelGateway {
    record Turn(AssistantMessage message, int inputTokens, int outputTokens) {}
    Turn next(List<Message> messages, List<Investigation.Evidence> evidence);
}
