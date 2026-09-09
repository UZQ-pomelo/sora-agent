package com.sora.sora_agent.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.sora.sora_agent.config.ModelConfig;
import com.sora.sora_agent.config.ModelConfig.ModelEntry;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 模型调用服务 — 统一的 ChatModel 调用入口。
 *
 * <p>职责：
 * <ul>
 *   <li>按用户选择（或默认）模型发起调用</li>
 *   <li>调用失败时按配置顺序自动 fallback 到下一个可用模型</li>
 *   <li>收集每个模型的失败原因，在彻底失败时返回完整信息</li>
 *   <li>为流式调用注入 model_info 命名事件</li>
 * </ul>
 * </p>
 *
 * <p>403（模型未授权）视为致命错误，不触发 fallback 而是直接抛出。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelFallbackService {

    /**
     * -- GETTER --
     * 暴露 ChatModel 供特殊场景直接使用（如 RAG、结构化输出等不使用 fallback 的场景）。
     */
    @Getter
    private final ChatModel dashscopeChatModel;
    private final ModelConfig modelConfig;

    /**
     * 同步调用（用于非流式接口）。
     *
     * @param requestedModel 请求指定的模型名（可为空，为空则用默认模型）
     * @param promptAction   对 ChatClient.ChatClientRequestSpec 的配置动作（system prompt、tools等）
     * @return 调用结果 + 模型元信息
     */
    public SyncModelResult callSync(String requestedModel,
                                    Consumer<ChatClient.ChatClientRequestSpec> promptAction) {
        String targetModel = resolveTargetModel(requestedModel);
        List<ModelAttempt> attempts = new ArrayList<>();

        // 构建 fallback 链：从目标模型开始，余下的按配置顺序附加
        List<String> fallbackChain = buildFallbackChain(targetModel);

        Exception lastError = null;
        for (String model : fallbackChain) {
            try {
                ChatResponse response = doSyncCall(model, promptAction);
                // 成功
                ModelInvokeInfo info = buildInfo(targetModel, model, attempts);
                return new SyncModelResult(response, info);
            } catch (Exception e) {
                lastError = e;
                String reason = classifyFailure(e);
                attempts.add(new ModelAttempt(model, reason));
                log.warn("模型 {} 调用失败（{}），继续 fallback...", model, reason);

                if (isFatal(e)) {
                    log.error("模型 {} 返回致命错误（403），中断 fallback", model);
                    break;
                }
            }
        }

        // 全部失败
        ModelInvokeInfo info = ModelInvokeInfo.builder()
                .actualModel(null)
                .requestedModel(targetModel)
                .fallback(false)
                .build();
        throw new AllModelsFailedException(info, attempts, lastError);
    }

    /**
     * 流式调用（用于 SSE 接口）。
     *
     * <p>在返回的 Flux 最前面会插入一条 model_info 命名事件；
     * 如果连接阶段即失败，则在 Flux 中只包含 model_info + 错误信息。</p>
     *
     * @param requestedModel 请求指定的模型名（可为空）
     * @param promptAction   对 ChatClient.ChatClientRequestSpec 的配置动作
     * @return 流式调用结果 + 模型元信息
     */
    public StreamModelResult callStream(String requestedModel,
                                        Consumer<ChatClient.ChatClientRequestSpec> promptAction) {
        String targetModel = resolveTargetModel(requestedModel);
        List<ModelAttempt> attempts = new ArrayList<>();

        List<String> fallbackChain = buildFallbackChain(targetModel);

        Exception lastError = null;
        for (String model : fallbackChain) {
            try {
                Flux<String> stream = doStreamCall(model, promptAction);

                // 在流开头注入 model_info 事件
                ModelInvokeInfo info = buildInfo(targetModel, model, attempts);
                String modelInfoEvent = formatModelInfoEvent(info);
                Flux<String> enriched = Flux.just(modelInfoEvent).concatWith(stream);

                return new StreamModelResult(enriched, info);
            } catch (Exception e) {
                lastError = e;
                String reason = classifyFailure(e);
                attempts.add(new ModelAttempt(model, reason));
                log.warn("模型 {} 流式连接失败（{}），继续 fallback...", model, reason);

                if (isFatal(e)) {
                    log.error("模型 {} 返回致命错误（403），中断 fallback", model);
                    break;
                }
            }
        }

        // 全部失败 — 返回仅含 model_info + 错误的 Flux
        ModelInvokeInfo errorInfo = ModelInvokeInfo.builder()
                .actualModel(null)
                .requestedModel(targetModel)
                .fallback(false)
                .attempts(attempts)
                .build();
        String errorEvent = formatModelInfoEvent(errorInfo);
        Flux<String> errorFlux = Flux.just(
                errorEvent,
                formatSseData("❌ 所有可用模型均调用失败：" +
                        attempts.stream()
                                .map(a -> a.getModel() + "（" + a.getReason() + "）")
                                .reduce((a, b) -> a + "；" + b)
                                .orElse("未知原因"))
        ).concatWith(Flux.just("[DONE]"));

        return new StreamModelResult(errorFlux, errorInfo);
    }

    /**
     * 同步调用，不 fallback（用于 Agent 场景，一次任务锁死模型）。
     *
     * @return ChatResponse（失败时直接抛异常）
     */
    public ChatResponse callSyncNoFallback(String model,
                                           Consumer<ChatClient.ChatClientRequestSpec> promptAction) {
        String target = resolveTargetModel(model);
        return doSyncCall(target, promptAction);
    }

    /**
     * 流式调用，不 fallback（用于 Agent 场景）。
     *
     * @return Flux<String>（失败时直接抛异常）
     */
    public Flux<String> callStreamNoFallback(String model,
                                             Consumer<ChatClient.ChatClientRequestSpec> promptAction) {
        String target = resolveTargetModel(model);
        return doStreamCall(target, promptAction);
    }

    // ---- 内部方法 ----

    private String resolveTargetModel(String requested) {
        if (requested != null && !requested.isBlank()) {
            // 校验是否为已知模型
            boolean known = modelConfig.getAvailable().stream()
                    .anyMatch(e -> e.getName().equals(requested));
            if (!known) {
                log.warn("未知模型 {} — 回退到默认 {}", requested, modelConfig.getDefaultModel());
                return modelConfig.getDefaultModel();
            }
            return requested;
        }
        return modelConfig.getDefaultModel();
    }

    /**
     * 构建 fallback 链：目标模型排第一，其余按配置顺序去重附后。
     */
    private List<String> buildFallbackChain(String targetModel) {
        List<String> chain = new ArrayList<>();
        chain.add(targetModel);
        for (ModelEntry entry : modelConfig.getAvailable()) {
            if (!entry.getName().equals(targetModel)) {
                chain.add(entry.getName());
            }
        }
        return chain;
    }

    private ChatResponse doSyncCall(String model,
                                    Consumer<ChatClient.ChatClientRequestSpec> promptAction) {
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel).build();
        // ★ per-request options 优先级高于全局 auto-configuration
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .options(DashScopeChatOptions.builder()
                        .withModel(model)
                        .build());
        promptAction.accept(spec);
        return spec.call().chatResponse();
    }

    private Flux<String> doStreamCall(String model,
                                      Consumer<ChatClient.ChatClientRequestSpec> promptAction) {
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel).build();
        // ★ per-request options 优先级高于全局 auto-configuration
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .options(DashScopeChatOptions.builder()
                        .withModel(model)
                        .build());
        promptAction.accept(spec);
        return spec.stream().content();
    }

    private ModelInvokeInfo buildInfo(String requested, String actual, List<ModelAttempt> attempts) {
        boolean fallback = !actual.equals(requested);
        ModelInvokeInfo.ModelInvokeInfoBuilder builder = ModelInvokeInfo.builder()
                .requestedModel(requested)
                .actualModel(actual)
                .fallback(fallback);
        if (fallback && !attempts.isEmpty()) {
            builder.fallbackFrom(requested);
            ModelAttempt firstFail = attempts.get(0);
            builder.fallbackReason(firstFail.getModel() + ": " + firstFail.getReason());
            builder.attempts(attempts);
        }
        return builder.build();
    }

    /**
     * 将失败原因归类为人类可读的简短描述。
     *
     * <p>优先用 {@link HttpStatusCodeException} 的准确状态码（不依赖文案/i18n），
     * 字符串匹配仅作为非 HTTP 异常（超时/连接）的兜底。</p>
     */
    private String classifyFailure(Exception e) {
        if (e instanceof HttpStatusCodeException hse) {
            int code = hse.getStatusCode().value();
            return switch (code) {
                case 400 -> "请求参数错误(400)";
                case 401 -> "API Key 无效(401)";
                case 403 -> "模型未授权(403)";
                case 429 -> "请求限流(429)";
                case 500 -> "服务端错误(500)";
                case 502, 503, 504 -> "网关错误(" + code + ")";
                default -> "HTTP " + code;
            };
        }
        if (e.getMessage() == null) {
            return "未知错误";
        }
        String msg = e.getMessage().toLowerCase();
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "超时";
        }
        if (msg.contains("connect") || msg.contains("refused")) {
            return "连接失败";
        }
        if (msg.contains("insufficient") || msg.contains("balance") || msg.contains("quota")) {
            return "余额不足";
        }
        String shortMsg = e.getMessage();
        return shortMsg.length() > 80 ? shortMsg.substring(0, 80) + "..." : shortMsg;
    }

    /**
     * 是否致命错误（不应重试）。401/403 为致命——API Key 无效或模型未授权，
     * 换模型也无效，直接中断而非继续 fallback。
     */
    private boolean isFatal(Exception e) {
        if (e instanceof HttpStatusCodeException hse) {
            int code = hse.getStatusCode().value();
            return code == 401 || code == 403;
        }
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("forbidden") || msg.contains("unauthorized");
    }

    /**
     * 将 ModelInvokeInfo 格式化为 SSE 命名事件 data 的 JSON。
     */
    private String formatModelInfoEvent(ModelInvokeInfo info) {
        // 简易 JSON 拼接，避免依赖 Jackson
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escapeJson(info.getActualModel())).append("\"");
        sb.append(",\"fallback\":").append(info.isFallback());
        if (info.getFallbackFrom() != null) {
            sb.append(",\"fallbackFrom\":\"").append(escapeJson(info.getFallbackFrom())).append("\"");
            sb.append(",\"fallbackReason\":\"").append(escapeJson(info.getFallbackReason())).append("\"");
        }
        sb.append("}");
        return "event: model_info\n" + "data: " + sb + "\n\n";
    }

    private String formatSseData(String text) {
        return "data: " + text + "\n\n";
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ===== 数据类 =====

    @Data
    @Builder
    public static class ModelInvokeInfo {
        /** 实际成功调用的模型名（null 表示全失败） */
        private String actualModel;
        /** 用户请求的模型名 */
        private String requestedModel;
        /** 是否触发了 fallback */
        private boolean fallback;
        /** fallback 来源模型（首次失败的那个） */
        private String fallbackFrom;
        /** fallback 原因简短描述 */
        private String fallbackReason;
        /** 本次调用中所有尝试的记录 */
        private List<ModelAttempt> attempts;
    }

    @Data
    public static class ModelAttempt {
        private final String model;
        private final String reason;
    }

    public record SyncModelResult(ChatResponse response, ModelInvokeInfo info) {}

    public record StreamModelResult(Flux<String> stream, ModelInvokeInfo info) {}

    /**
     * 所有模型均调用失败时抛出的异常 — 携带完整尝试历史。
     */
    public static class AllModelsFailedException extends RuntimeException {
        private final ModelInvokeInfo info;
        private final List<ModelAttempt> attempts;

        public AllModelsFailedException(ModelInvokeInfo info, List<ModelAttempt> attempts, Throwable cause) {
            super("所有模型调用均失败", cause);
            this.info = info;
            this.attempts = attempts;
        }

        public ModelInvokeInfo getInfo() { return info; }
        public List<ModelAttempt> getAttempts() { return attempts; }
    }
}
