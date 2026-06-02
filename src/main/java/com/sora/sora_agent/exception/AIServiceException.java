package com.sora.sora_agent.exception;

/**
 * AI 服务调用异常，用于封装调用 AI 接口（如 Spring AI、LangChain4j 等）时出现的错误。
 */
public class AIServiceException extends BaseException {

    /** AI 服务异常默认 HTTP 状态码 */
    private static final int DEFAULT_HTTP_STATUS = 502;

    /**
     * 使用 ErrorCode 及自定义消息构造。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     */
    public AIServiceException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 使用 ErrorCode 构造，消息取自枚举默认值。
     *
     * @param errorCode 错误码枚举
     */
    public AIServiceException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 使用 ErrorCode、自定义消息及原始异常构造。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     * @param cause     原始异常
     */
    public AIServiceException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    @Override
    protected int defaultHttpStatus() {
        return DEFAULT_HTTP_STATUS;
    }
}
