package com.sora.sora_agent.exception;

import com.sora.sora_agent.common.BaseResponse;
import lombok.extern.slf4j.Slf4j;
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
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== BaseException 及其子类 ====================

    /**
     * 处理所有继承自 {@link BaseException} 的业务异常。
     */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<BaseResponse<?>> handleBaseException(BaseException e) {
        log.warn("业务异常 [code={}, httpStatus={}]: {}", e.getCode(), e.getHttpStatus(), e.getMessage(), e);
        return ResponseEntity.status(e.getHttpStatus()).body(BaseResponse.error(e));
    }

    // ==================== 参数校验异常 ====================

    /**
     * 处理 Spring Validation（{@code @Valid} / {@code @Validated}）校验失败异常。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<?>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.error(ErrorCode.PARAM_ERROR, message));
    }

    /**
     * 处理 HTTP 请求体反序列化失败异常（如 JSON 格式错误、类型不匹配）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<?>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.error(ErrorCode.PARAM_ERROR, "请求体格式不正确，请检查 JSON 结构"));
    }

    // ==================== 运行时异常（含 AI 框架异常识别） ====================

    /**
     * 处理所有非业务型 {@link RuntimeException}，并识别常见 AI 框架异常。
     * <p>
     * 通过类名前缀识别以下 AI 框架异常：
     * <ul>
     *     <li>{@code RetryExhaustedException} —— Spring AI 重试耗尽 → 503</li>
     *     <li>{@code dev.langchain4j.*} —— LangChain4j 异常 → 502</li>
     *     <li>{@code org.springframework.ai.*} —— Spring AI 通用异常 → 502</li>
     * </ul>
     * 其余未识别的运行时异常按通用 {@code 500} 错误处理。
     * </p>
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BaseResponse<?>> handleRuntimeException(RuntimeException e) {
        BaseResponse<?> response = buildErrorResponse(e);
        logException(e, response);
        return ResponseEntity.status(mapHttpStatus(e)).body(response);
    }

    // ==================== 兜底处理 ====================

    /**
     * 兜底异常处理器，捕获所有受检异常（非 {@link RuntimeException}）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<?>> handleException(Exception e) {
        log.error("系统未知异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.error(ErrorCode.SYSTEM_ERROR, "系统内部错误，请联系管理员"));
    }

    // ==================== SSE / 流式异常转换工具 ====================

    /**
     * 将任意异常转换为统一错误响应，供 SSE 流式接口的 error 回调和
     * {@link #handleRuntimeException(RuntimeException)} 共用。
     * <p>
     * 确保 SSE 错误消息与普通 HTTP 错误响应格式一致。
     * </p>
     *
     * @param e 异常实例
     * @return 统一错误响应
     */
    public static BaseResponse<?> buildErrorResponse(Throwable e) {
        if (e instanceof BaseException be) {
            return BaseResponse.error(be);
        }

        String className = e.getClass().getName();

        // Spring AI / Alibaba AI 重试耗尽异常
        if (className.contains("RetryExhaustedException")) {
            return BaseResponse.error(ErrorCode.AI_ERROR, "AI 服务暂时不可用，请稍后重试");
        }

        // LangChain4j 异常
        if (className.contains("dev.langchain4j")) {
            return BaseResponse.error(ErrorCode.AI_ERROR, "AI 服务 RPC 调用失败");
        }

        // Spring AI / Alibaba AI 通用异常
        if (className.contains("org.springframework.ai") || className.contains("com.alibaba.cloud.ai")) {
            return BaseResponse.error(ErrorCode.AI_ERROR, "AI 服务异常");
        }

        // 超时
        if (e instanceof TimeoutException) {
            return BaseResponse.error(ErrorCode.AI_ERROR, "AI 服务调用超时，请稍后重试");
        }

        // 兜底
        return BaseResponse.error(ErrorCode.SYSTEM_ERROR, "系统内部错误，请联系管理员");
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据异常类型推断对应的 HTTP 状态码。
     */
    private static int mapHttpStatus(Throwable e) {
        if (e instanceof BaseException be) {
            return be.getHttpStatus();
        }
        String className = e.getClass().getName();
        if (className.contains("RetryExhaustedException") || e instanceof TimeoutException) {
            return HttpStatus.SERVICE_UNAVAILABLE.value();
        }
        if (className.contains("dev.langchain4j")
                || className.contains("org.springframework.ai")
                || className.contains("com.alibaba.cloud.ai")) {
            return HttpStatus.BAD_GATEWAY.value();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    /**
     * 按异常级别记录日志。
     */
    private static void logException(RuntimeException e, BaseResponse<?> response) {
        if (response.getCode() == ErrorCode.AI_ERROR.getCode()) {
            log.error("AI 服务异常: {}", e.getMessage(), e);
        } else {
            log.error("运行时异常", e);
        }
    }
}
