package com.sora.sora_agent.agent.model;

/**
 * 代理执行状态的枚举类
 */
public enum AgentState {

    /**
     * 空闲状态
     */
    IDLE,

    /**
     * 运行中状态
     */
    RUNNING,

    /**
     * 已完成状态
     */
    FINISHED,

    /**
     * 错误状态
     */
    ERROR,

    /**
     * 陷入死循环状态（检测到重复/振荡模式后强制终止）
     */
    STUCK
}

