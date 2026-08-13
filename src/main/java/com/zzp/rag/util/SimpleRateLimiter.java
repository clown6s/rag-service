package com.zzp.rag.util;

/**
 * 简单令牌桶限流器 — 保护共享 LLM 配额
 * <p>
 * 背景：DeepSeek 同一个 key 被图构建（批量实体抽取）、查询改写、摘要、评测共用。
 * 历史坑（2026-08-13）：图构建 2500+ 调用把 key 打满限流窗口，评测评判调用 145s/条全部超时。
 * 用于后台批量 LLM 调用（图构建），给用户请求和评测留出配额空间。
 */
public class SimpleRateLimiter {

    private final double ratePerSecond;
    private final double burstCapacity;
    private final Object lock = new Object();
    private double tokens;
    private long lastRefillNanos;

    public SimpleRateLimiter(double ratePerSecond, double burstCapacity) {
        this.ratePerSecond = ratePerSecond;
        this.burstCapacity = burstCapacity;
        this.tokens = burstCapacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** 阻塞直到获得一个令牌（线程中断则放弃等待直接返回） */
    public void acquire() {
        while (true) {
            long waitNanos;
            synchronized (lock) {
                refill();
                if (tokens >= 1) {
                    tokens -= 1;
                    return;
                }
                waitNanos = (long) ((1 - tokens) / ratePerSecond * 1_000_000_000L);
            }
            if (waitNanos <= 0) {
                continue;
            }
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1e9;
        tokens = Math.min(tokens + elapsedSec * ratePerSecond, burstCapacity);
        lastRefillNanos = now;
    }
}
