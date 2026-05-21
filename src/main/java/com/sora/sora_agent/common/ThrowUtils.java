package com.sora.sora_agent.common;

import com.sora.sora_agent.exception.AIServiceException;
import com.sora.sora_agent.exception.BusinessException;
import com.sora.sora_agent.exception.ErrorCode;
import com.sora.sora_agent.exception.ParamException;

import java.util.function.Supplier;

/**
 * 异常抛出工具类，用于简化条件判断并抛异常的模式。
 */
public final class ThrowUtils {

    private ThrowUtils() {
        // 工具类禁止实例化
    }

    /**
     * 当条件成立时抛出指定的运行时异常。
     *
     * @param condition 布尔条件
     * @param e         待抛出的异常实例
     */
    public static void throwIf(boolean condition, RuntimeException e) {
        if (condition) {
            throw e;
        }
    }

    /**
     * 当条件成立时，通过 Supplier 延迟创建并抛出异常。
     * <p>
     * 仅在条件成立时才会调用 Supplier 构建异常，避免不必要的对象创建。
     * </p>
     *
     * @param condition       布尔条件
     * @param exceptionSupplier 异常构建器
     */
    public static void throwIf(boolean condition, Supplier<RuntimeException> exceptionSupplier) {
        if (condition) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * 当条件成立时，抛出 {@link BusinessException}。
     *
     * @param condition 布尔条件
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new BusinessException(errorCode, message);
        }
    }

    /**
     * 当条件成立时，抛出 {@link ParamException}。
     *
     * @param condition 布尔条件
     * @param message   自定义错误消息
     */
    public static void throwParamIf(boolean condition, String message) {
        if (condition) {
            throw new ParamException(ErrorCode.PARAM_ERROR, message);
        }
    }

    /**
     * 当条件成立时，抛出 {@link AIServiceException}。
     *
     * @param condition 布尔条件
     * @param message   自定义错误消息
     */
    public static void throwAiIf(boolean condition, String message) {
        if (condition) {
            throw new AIServiceException(ErrorCode.AI_ERROR, message);
        }
    }

    /**
     * 当条件成立时，抛出 {@link AIServiceException}。
     *
     * @param condition 布尔条件
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     */
    public static void throwAiIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new AIServiceException(errorCode, message);
        }
    }
}
