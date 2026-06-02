package com.sora.sora_agent.exception;

import lombok.Getter;

/**
 * 项目统一异常基类，所有业务异常均继承此类。
 *
 * @author sora-agent
 */
@Getter
public abstract class BaseException extends RuntimeException {

    /** 业务错误码 */
    private final int code;

    /** 错误消息（覆写 {@link RuntimeException#getMessage()}） */
    private final String message;

    /** HTTP 响应状态码 */
    private final int httpStatus;

    /**
     * 使用 ErrorCode 及自定义消息构造异常，HTTP 状态码取子类默认值。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     */
    protected BaseException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.message = message;
        this.httpStatus = defaultHttpStatus();
    }

    /**
     * 使用 ErrorCode 构造异常，消息取自 ErrorCode 枚举，HTTP 状态码取子类默认值。
     *
     * @param errorCode 错误码枚举
     */
    protected BaseException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
        this.httpStatus = defaultHttpStatus();
    }

    /**
     * 使用 ErrorCode、自定义消息及原始异常构造，HTTP 状态码取子类默认值。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     * @param cause     原始异常
     */
    protected BaseException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.message = message;
        this.httpStatus = defaultHttpStatus();
    }

    /**
     * 子类定义默认的 HTTP 状态码。
     *
     * @return 默认 HTTP 状态码
     */
    protected abstract int defaultHttpStatus();
}
