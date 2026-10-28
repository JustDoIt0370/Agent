package dev.portfolio.opspilot.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Component
public class SpringAiGateway implements ModelGateway {
    private final OpenAiChatModel model;
    public SpringAiGateway(AgentSettings settings) {
        if (!settings.liveAvailable()) { model = null; return; }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        var api = OpenAiApi.builder().baseUrl(settings.baseUrl()).apiKey(settings.apiKey())
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        model = OpenAiChatModel.builder().openAiApi(api).retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(OpenAiChatOptions.builder().model(settings.model()).temperature(0.1).maxTokens(1800)
                        .internalToolExecutionEnabled(false).toolCallbacks(ToolCatalog.TOOLS).build()).build();
    }
    @Override public Turn next(List<Message> messages, List<Investigation.Evidence> evidence) {
        if (model == null) throw new IllegalStateException("模型未配置，请设置 AGENT_API_KEY 后重启");
        var response = model.call(new Prompt(messages));
        if (response == null || response.getResult() == null) throw new IllegalStateException("模型返回空响应");
        var usage = response.getMetadata().getUsage();
        return new Turn(response.getResult().getOutput(), usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
    }
}
