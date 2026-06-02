package com.sora.sora_agent.common;

import com.sora.sora_agent.exception.BaseException;
import com.sora.sora_agent.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应包装类，所有 Controller 的返回值均使用此类进行包装。
 * <p>
 * 提供了一系列静态工厂方法用于快速构建成功或失败响应，支持通过
 * {@link ErrorCode} 枚举或 {@link BaseException} 直接生成错误响应。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 成功返回数据
 * return BaseResponse.success(user);
 *
 * // 成功无数据
 * return BaseResponse.success();
 *
 * // 通过 ErrorCode 返回错误
 * return BaseResponse.error(ErrorCode.PARAM_ERROR, "用户名不能为空");
 *
 * // 通过异常对象返回错误
 * return BaseResponse.error(new BusinessException("订单不存在"));
 * }</pre>
 *
 * @param <T> 响应数据的具体类型
 * @author sora-agent
 */
@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class BaseResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务状态码，{@code 0} 表示成功 */
    private int code;

    /** 响应消息，成功时可为 {@code null}，失败时为错误描述 */
    private String message;

    /** 响应数据，可为 {@code null} */
    private T data;

    /**
     * 构建成功响应并携带数据。
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> BaseResponse<T> success(T data) {
        BaseResponse<T> response = new BaseResponse<>();
        response.code = ErrorCode.SUCCESS.getCode();
        response.message = ErrorCode.SUCCESS.getMessage();
        response.data = data;
        return response;
    }

    /**
     * 构建成功响应，不携带数据。
     *
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> BaseResponse<T> success() {
        return success(null);
    }

    /**
     * 通过业务错误码和自定义消息构建错误响应。
     *
     * @param code    业务错误码
     * @param message 错误消息
     * @param <T>     数据类型
     * @return 错误响应
     */
    public static <T> BaseResponse<T> error(int code, String message) {
        BaseResponse<T> response = new BaseResponse<>();
        response.code = code;
        response.message = message;
        return response;
    }

    /**
     * 通过 ErrorCode 枚举构建错误响应，消息取自枚举默认值。
     *
     * @param errorCode 错误码枚举
     * @param <T>       数据类型
     * @return 错误响应
     */
    public static <T> BaseResponse<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMessage());
    }

    /**
     * 通过 ErrorCode 枚举及自定义消息构建错误响应。
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     * @param <T>       数据类型
     * @return 错误响应
     */
    public static <T> BaseResponse<T> error(ErrorCode errorCode, String message) {
        return error(errorCode.getCode(), message);
    }

    /**
     * 通过 BaseException 构建错误响应。
     *
     * @param e   BaseException 或其子类实例
     * @param <T> 数据类型
     * @return 错误响应
     */
    public static <T> BaseResponse<T> error(BaseException e) {
        return error(e.getCode(), e.getMessage());
    }
}
