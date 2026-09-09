package com.sora.sora_agent.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量滑动窗口限流器（单实例内存版）。
 *
 * <p>每个 key 独立窗口；窗口时间内的请求数超过上限则拒绝。
 * 无外部依赖，适合单实例部署；多实例需要分布式限流（本项目暂不涉及）。</p>
 */
public class RateLimiter {

    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 尝试获取一个配额。
     *
     * @param key          限流维度（如 API Key 或客户端标识）
     * @param max          窗口内最大请求数
     * @param windowMillis 窗口时长（毫秒）
     * @return true=放行；false=超限拒绝
     */
    public boolean tryAcquire(String key, int max, long windowMillis) {
        long now = System.currentTimeMillis();
        Deque<Long> deque = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() >= windowMillis) {
                deque.pollFirst();
            }
            if (deque.size() >= max) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /** 清空所有记录（测试与重置用）。 */
    public void clear() {
        windows.clear();
    }
}
