package dev.bcrick.bitwigpal.rpc;

/**
 * Abstraction for scheduling deferred tasks across flush cycles.
 * In production, wraps {@code ControllerHost.scheduleTask()}.
 * In tests, can execute tasks immediately for synchronous verification.
 */
@FunctionalInterface
public interface TaskScheduler {
    void schedule(Runnable task, long delayMs);
}
