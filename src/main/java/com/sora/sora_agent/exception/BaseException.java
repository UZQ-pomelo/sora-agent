package com.sora.sora_agent.exception;

import lombok.Getter;

/**
 * 项目统一异常基类，所有业务异常均继承此类。
 *
 */
@Getter
public abstract class BaseException extends RuntimeException {

    /** 业务错误码（字符串形式，便于与外部系统对接） */
    private final String code;

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
        this.code = String.valueOf(errorCode.getCode());
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
        this.code = String.valueOf(errorCode.getCode());
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
        this.code = String.valueOf(errorCode.getCode());
        this.message = message;
        this.httpStatus = defaultHttpStatus();
    }

    /**
     * 使用自定义 code 和 message 构造异常，HTTP 状态码取子类默认值。
     *
     * @param code    自定义错误码
     * @param message 错误消息
     */
    protected BaseException(String code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.httpStatus = defaultHttpStatus();
    }

    /**
     * 使用自定义 code、message 及原始异常构造。
     *
     * @param code      自定义错误码
     * @param message   错误消息
     * @param httpStatus HTTP 状态码
     * @param cause     原始异常
     */
    protected BaseException(String code, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * 子类定义默认的 HTTP 状态码。
     *
     * @return 默认 HTTP 状态码
     */
    protected abstract int defaultHttpStatus();
}
