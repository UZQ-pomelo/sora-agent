package com.sora.sora_agent.exception;

/**
 * 参数校验异常，用于表示请求参数不合法。
 */
public class ParamException extends BaseException {

    /** 参数校验异常默认 HTTP 状态码 */
    private static final int DEFAULT_HTTP_STATUS = 400;

    /**
     * 使用 ErrorCode 及自定义消息构造。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     */
    public ParamException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 使用 ErrorCode 构造，消息取自枚举默认值。
     *
     * @param errorCode 错误码枚举
     */
    public ParamException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 使用 ErrorCode、自定义消息及原始异常构造。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     * @param cause     原始异常
     */
    public ParamException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * 使用自定义 code 和 message 构造。
     *
     * @param code    自定义错误码
     * @param message 错误消息
     */
    public ParamException(String code, String message) {
        super(code, message);
    }

    /**
     * 使用自定义 code、message 及原始异常构造。
     *
     * @param code    自定义错误码
     * @param message 错误消息
     * @param cause   原始异常
     */
    public ParamException(String code, String message, Throwable cause) {
        super(code, message, DEFAULT_HTTP_STATUS, cause);
    }

    @Override
    protected int defaultHttpStatus() {
        return DEFAULT_HTTP_STATUS;
    }
}
