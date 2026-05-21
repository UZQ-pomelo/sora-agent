package com.sora.sora_agent.exception;

import lombok.Getter;

/**
 * 通用错误码枚举，定义项目中所有业务错误的编码、描述信息及对应的 HTTP 状态码。
 *
 * 错误码采用五位数编码规则：
 *   {@code 0} —— 成功
 *   {@code 4xxxx} —— 客户端错误（参数校验、业务规则等）
 *   {@code 5xxxx} —— 服务端业务错误
 *   {@code 6xxxx} —— 外部服务调用错误（AI 服务等）
 *   {@code 9xxxx} —— 系统级未知错误
 *
 * @author sora-agent
 */
@Getter
public enum ErrorCode {

    /** 操作成功 */
    SUCCESS(0, "操作成功", 200),
    /** 参数校验失败 */
    PARAM_ERROR(40000, "请求参数错误", 400),
    /** 业务逻辑异常 */
    BUSINESS_ERROR(50000, "业务处理异常", 400),
    /** AI 服务调用异常 */
    AI_ERROR(60000, "AI 服务调用异常", 502),
    /** 系统内部未知错误 */
    SYSTEM_ERROR(99999, "系统内部错误", 500);

    //状态码
    private final int code;

    //信息
    private final String message;

    //http响应码
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
