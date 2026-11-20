package dev.frontlineand2engineers.ocradditions.compute;

import dev.frontlineand2engineers.ocradditions.Config;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Shared, bounded, host-friendly worker pool powering parallel CUDA-style compute
 * across all OpenComputers Graphics Cards and APUs.
 */
public final class GpuComputeEngine {
    public static final int SLICE_SIZE = 4096;
    private static final long MAX_SLICE_SLEEP_NANOS = 12_000_000L;

    private static final Set<GpuComputeSession> SESSIONS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger GLOBAL_JOBS = new AtomicInteger(0);
    private static final AtomicInteger WORKER_SEQ = new AtomicInteger(1);

    private static volatile ExecutorService executor;
    private static volatile int configuredThreadCount = -1;

    private GpuComputeEngine() {
    }

    public static int resolveWorkerThreadCount() {
        int configured = Config.gpuComputeWorkerThreads;
        if (configured > 0) {
            return Math.clamp(configured, 1, 32);
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, cpus / 2));
    }

    public static synchronized ExecutorService getExecutor() {
        int desired = resolveWorkerThreadCount();
        if (executor == null || executor.isShutdown() || configuredThreadCount != desired) {
            ExecutorService old = executor;
            configuredThreadCount = desired;
            executor = Executors.newFixedThreadPool(desired, runnable -> {
                Thread t = new Thread(runnable, "OCRAdditions-GPU-Worker-" + WORKER_SEQ.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.MIN_PRIORITY + 1));
                return t;
            });
            if (old != null && !old.isShutdown()) {
                old.shutdown();
            }
        }
        return executor;
    }

    public static void registerSession(GpuComputeSession session) {
        SESSIONS.add(session);
    }

    public static void unregisterSession(GpuComputeSession session) {
        SESSIONS.remove(session);
    }

    public static int getGlobalQueuedJobs() {
        return Math.max(0, GLOBAL_JOBS.get());
    }

    public static boolean tryAcquireGlobalSlot() {
        int limit = Config.gpuComputeMaxGlobalQueue;
        while (true) {
            int cur = GLOBAL_JOBS.get();
            if (cur >= limit) {
                return false;
            }
            if (GLOBAL_JOBS.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    public static void releaseGlobalSlot() {
        GLOBAL_JOBS.updateAndGet(v -> Math.max(0, v - 1));
    }

    /**
     * Computes optimal parallel partition count for a workload without ever blocking pool threads.
     */
    public static int choosePartitionCount(long totalWork, GpuTierSpec spec) {
        int workers = resolveWorkerThreadCount();
        int corePartitions = Math.max(1, spec.cudaCores() / 8);
        int sizePartitions = (int) Math.max(1L, Math.min(totalWork / 256L, workers));
        return Math.max(1, Math.min(workers, Math.min(corePartitions, sizePartitions)));
    }

    /**
     * Submits multiple worker partitions in parallel and notifies {@code onAllDone} without blocking any worker thread.
     */
    public static CompletableFuture<Void> submitParallelPartitions(
            int partitions,
            PartitionTask task
    ) {
        ExecutorService pool = getExecutor();
        if (partitions <= 1) {
            return CompletableFuture.runAsync(() -> task.run(0, 1), pool);
        }
        CompletableFuture<?>[] futures = new CompletableFuture<?>[partitions];
        for (int i = 0; i < partitions; i++) {
            final int idx = i;
            futures[i] = CompletableFuture.runAsync(() -> task.run(idx, partitions), pool);
        }
        return CompletableFuture.allOf(futures);
    }

    /**
     * Throttles a worker slice based on configured CPU duty cycle and GPU tier pacing floor,
     * keeping host Minecraft server TPS responsive under heavy mining load.
     */
    public static void paceSlice(
            long elapsedWorkNanos,
            int opsInSlice,
            GpuTierSpec spec,
            AtomicBoolean stopFlag
    ) {
        if (stopFlag.get() || Thread.currentThread().isInterrupted() || opsInSlice <= 0) {
            return;
        }
        double duty = Math.clamp(Config.gpuComputeDutyCycle, 0.05, 1.0);
        long dutySleepNanos = 0L;
        if (duty < 0.999 && elapsedWorkNanos > 0L) {
            dutySleepNanos = (long) (elapsedWorkNanos * ((1.0 - duty) / duty));
        }
        long floorTargetNanos = (spec.pacingFloorNanosPer4kOps() * opsInSlice) / SLICE_SIZE;
        long tierPadNanos = Math.max(0L, floorTargetNanos - elapsedWorkNanos);

        long totalSleep = Math.min(MAX_SLICE_SLEEP_NANOS, Math.max(dutySleepNanos, tierPadNanos));
        if (totalSleep >= 50_000L && !stopFlag.get()) {
            LockSupport.parkNanos(totalSleep);
        }
    }

    /**
     * Executes a signal callback on the Minecraft server thread when running inside a game server,
     * or directly if invoked outside an active MinecraftServer (e.g. unit tests).
     */
    public static void runOnServerThread(Runnable action) {
        MinecraftServer server = null;
        try {
            server = ServerLifecycleHooks.getCurrentServer();
        } catch (Throwable ignored) {
            // Not running inside a booted NeoForge server lifecycle (e.g., pure unit tests).
        }
        if (server != null && !server.isSameThread() && server.isRunning()) {
            server.execute(action);
        } else {
            action.run();
        }
    }

    /**
     * Cancels all pending GPU jobs and shuts down the shared worker pool cleanly.
     */
    public static synchronized void shutdownAll() {
        for (GpuComputeSession session : SESSIONS) {
            session.cancelAll();
        }
        SESSIONS.clear();
        GLOBAL_JOBS.set(0);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @FunctionalInterface
    public interface PartitionTask {
        void run(int partitionIndex, int partitionCount);
    }
}
