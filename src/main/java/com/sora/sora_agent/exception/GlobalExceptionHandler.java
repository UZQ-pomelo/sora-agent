package com.sora.sora_agent.exception;

import com.sora.sora_agent.common.BaseResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.TimeoutException;

/**
 * 全局异常处理器，捕获并统一处理项目中所有未被 Controller 层自行处理的异常。
 */
@Slf4j
//@Hidden
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** MDC 中存储 traceId 的 key */
    private static final String TRACE_ID_KEY = "traceId";

    // ==================== BaseException 及其子类 ====================

    /**
     * 处理所有继承自 {@link BaseException} 的业务异常。
     * <p>
     * 根据异常中携带的 httpStatus 设置 HTTP 响应状态码，
     * 返回包含错误码、错误消息及 traceId 的统一错误响应。
     * </p>
     *
     * @param e 业务异常实例
     * @return 包含错误信息的 {@link ResponseEntity}
     */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<BaseResponse<?>> handleBaseException(BaseException e) {
        log.warn("业务异常 [code={}, httpStatus={}]: {}",
                e.getCode(), e.getHttpStatus(), e.getMessage(), e);
        BaseResponse<?> response = BaseResponse.error(e);
        appendTraceId(response);
        return ResponseEntity.status(e.getHttpStatus()).body(response);
    }

    // ==================== 参数校验异常 ====================

    /**
     * 处理 Spring Validation（{@code @Valid} / {@code @Validated}）校验失败异常。
     * <p>
     * 提取第一条校验失败消息作为响应消息，HTTP 状态码固定为 {@code 400}。
     * </p>
     *
     * @param e 校验失败异常
     * @return 包含校验错误信息的 {@link ResponseEntity}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<?>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        BaseResponse<?> response = BaseResponse.error(ErrorCode.PARAM_ERROR, message);
        appendTraceId(response);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 处理 HTTP 请求体反序列化失败异常（如 JSON 格式错误、类型不匹配）。
     * <p>
     * HTTP 状态码固定为 {@code 400}，提示请求体格式不正确。
     * </p>
     *
     * @param e 请求体解析异常
     * @return 包含错误信息的 {@link ResponseEntity}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<?>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        BaseResponse<?> response = BaseResponse.error(ErrorCode.PARAM_ERROR, "请求体格式不正确，请检查 JSON 结构");
        appendTraceId(response);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // ==================== AI 服务相关异常 ====================

    /**
     * 处理 AI 服务调用超时异常。
     * <p>
     * 当上游 AI 服务（如 OpenAI API）响应超时时，返回 {@code 503 Service Unavailable}，
     * 提示调用方可稍后重试。
     * </p>
     *
     * @param e 超时异常
     * @return 包含超时错误信息的 {@link ResponseEntity}
     */
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<BaseResponse<?>> handleTimeoutException(TimeoutException e) {
        log.error("AI 服务调用超时", e);
        BaseResponse<?> response = BaseResponse.error(ErrorCode.AI_ERROR, "AI 服务调用超时，请稍后重试");
        appendTraceId(response);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    /**
     * 处理所有非业务型 {@link RuntimeException}，并优先识别 Spring AI / LangChain4j 等
     * AI 框架抛出的异常以返回更精确的错误信息。
     * <p>
     * Spring 的异常匹配规则保证 {@link BaseException} 及其子类会优先由
     * {@link #handleBaseException(BaseException)} 处理，不会落入此方法。
     * </p>
     * <p>
     * 通过异常类名识别以下 AI 框架异常：
     * <ul>
     *     <li>{@code RetryExhaustedException} —— Spring AI 重试耗尽 → 503</li>
     *     <li>{@code RpcException} —— LangChain4j RPC 调用异常 → 502</li>
     *     <li>{@code AiServiceException} —— Spring AI 通用服务异常 → 502</li>
     * </ul>
     * 其余未识别的运行时异常按通用 {@code 500} 错误处理。
     * </p>
     *
     * @param e 运行时异常
     * @return 包含错误信息的 {@link ResponseEntity}
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BaseResponse<?>> handleRuntimeException(RuntimeException e) {
        // 尝试按 AI 框架异常处理
        ResponseEntity<BaseResponse<?>> aiResponse = tryHandleAiException(e);
        if (aiResponse != null) {
            return aiResponse;
        }
        // 未识别 → 兜底 500
        log.error("运行时异常", e);
        BaseResponse<?> response = BaseResponse.error(ErrorCode.SYSTEM_ERROR, "系统内部错误，请联系管理员");
        appendTraceId(response);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    // ==================== 兜底处理 ====================

    /**
     * 兜底异常处理器，捕获所有受检异常（非 {@link RuntimeException}）。
     * <p>
     * 返回 HTTP {@code 500} 及通用系统错误响应，并记录完整的异常堆栈。
     * </p>
     *
     * @param e 受检异常
     * @return 包含通用错误信息的 {@link ResponseEntity}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<?>> handleException(Exception e) {
        log.error("系统未知异常", e);
        BaseResponse<?> response = BaseResponse.error(ErrorCode.SYSTEM_ERROR, "系统内部错误，请联系管理员");
        appendTraceId(response);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 尝试按 AI 框架异常进行识别处理，识别成功则返回对应的响应，否则返回 {@code null}。
     * <p>
     * 当前支持的框架异常：
     * <ul>
     *     <li>Spring AI {@code RetryExhaustedException} —— 重试耗尽</li>
     *     <li>LangChain4j {@code RpcException} —— RPC 调用异常</li>
     *     <li>Spring AI {@code AiServiceException} —— 通用服务异常</li>
     * </ul>
     * </p>
     *
     * @param e 待识别的异常
     * @return AI 异常对应的响应，若未识别则返回 {@code null}
     */
    private ResponseEntity<BaseResponse<?>> tryHandleAiException(RuntimeException e) {
        String className = e.getClass().getName();

        // Spring AI RetryExhaustedException → 503
        if (className.contains("RetryExhaustedException")) {
            log.error("Spring AI 重试耗尽", e);
            BaseResponse<?> response = BaseResponse.error(ErrorCode.AI_ERROR, "AI 服务暂时不可用，请稍后重试");
            appendTraceId(response);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        // LangChain4j RpcException
        if (className.contains("RpcException") || className.contains("dev.langchain4j")) {
            log.error("LangChain4j RPC 调用异常", e);
            BaseResponse<?> response = BaseResponse.error(ErrorCode.AI_ERROR, "AI 服务 RPC 调用失败");
            appendTraceId(response);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
        }

        // Spring AI 通用异常
        if (className.contains("AiServiceException") || className.contains("org.springframework.ai")) {
            log.error("Spring AI 服务异常", e);
            BaseResponse<?> response = BaseResponse.error(ErrorCode.AI_ERROR, "AI 服务异常");
            appendTraceId(response);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
        }

        return null;
    }

    /**
     * 从 MDC 中获取 traceId 并追加到响应消息中，便于问题排查与链路追踪。
     * <p>
     * 若 MDC 中未配置 traceId，则不追加。
     * </p>
     *
     * @param response 响应对象
     */
    private void appendTraceId(BaseResponse<?> response) {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId != null && !traceId.isBlank()) {
            response.setMessage(response.getMessage() + " [traceId=" + traceId + "]");
        }
    }
}
