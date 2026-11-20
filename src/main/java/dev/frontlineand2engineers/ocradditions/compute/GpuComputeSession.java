package dev.frontlineand2engineers.ocradditions.compute;

import dev.frontlineand2engineers.ocradditions.Config;
import dev.frontlineand2engineers.ocradditions.OCRAdditions;
import dev.frontlineand2engineers.ocradditions.compute.CryptoKernels.Algorithm;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.network.Connector;
import li.cil.oc.api.network.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-GraphicsCard compute controller executing user-defined parallel CUDA kernels
 * and basic vector/matrix/hash operations safely in the bounded GpuComputeEngine pool.
 */
public final class GpuComputeSession {
    private static final int MAX_COMPILED_KERNELS = 32;
    private static final int MAX_RETAINED_FINISHED_JOBS = 32;

    public enum Status {
        QUEUED,
        RUNNING,
        DONE,
        CANCELED,
        FAILED
    }

    private final AtomicInteger kernelIdSeq = new AtomicInteger(1);
    private final AtomicInteger jobIdSeq = new AtomicInteger(1);
    private final AtomicInteger activeJobs = new AtomicInteger(0);
    private final AtomicLong completedJobs = new AtomicLong(0);
    private final AtomicLong totalOps = new AtomicLong(0);
    private final Map<Integer, KernelProgram> compiledKernels = new ConcurrentHashMap<>();
    private final Map<Integer, JobRecord> jobs = new ConcurrentHashMap<>();

    private final int ocTier;
    private final Node node;

    public GpuComputeSession(int ocTier, Node node) {
        this.ocTier = ocTier;
        this.node = node;
        GpuComputeEngine.registerSession(this);
    }

    public GpuTierSpec spec() {
        return GpuTierSpec.fromOcTier(ocTier);
    }

    public Map<String, Object> getComputeInfo() {
        GpuTierSpec s = spec();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("enabled", Config.gpuComputeEnabled);
        info.put("tier", s.displayTier());
        info.put("cudaCores", s.cudaCores());
        info.put("maxBatchSize", s.maxBatchSize());
        info.put("maxArrayElements", s.maxArrayElements());
        info.put("maxOutputRows", Config.gpuComputeMaxOutputRows);
        info.put("workerThreads", GpuComputeEngine.resolveWorkerThreadCount());
        info.put("dutyCycle", Config.gpuComputeDutyCycle);
        info.put("energyPerOp", Config.gpuComputeEnergyPerOp);
        info.put("compiledKernels", compiledKernels.size());
        info.put("activeJobs", activeJobs.get());
        info.put("queuedJobsGlobal", GpuComputeEngine.getGlobalQueuedJobs());
        info.put("completedJobs", completedJobs.get());
        info.put("totalOps", totalOps.get());
        return info;
    }

    public Map<String, Object> getJobSnapshot(int jobId) {
        JobRecord job = jobs.get(jobId);
        return job != null ? job.toMap() : null;
    }

    public boolean cancelJob(int jobId) {
        JobRecord job = jobs.get(jobId);
        return job != null && job.cancel();
    }

    public int cancelAll() {
        int count = 0;
        for (JobRecord job : jobs.values()) {
            if (job.cancel()) {
                count++;
            }
        }
        return count;
    }

    public void dispose() {
        cancelAll();
        compiledKernels.clear();
        GpuComputeEngine.unregisterSession(this);
    }

    public Object[] compileKernel(String source) {
        if (compiledKernels.size() >= MAX_COMPILED_KERNELS) {
            return new Object[]{null, "max compiled kernels (" + MAX_COMPILED_KERNELS + ") reached; call dropKernel()"};
        }
        try {
            KernelProgram program = KernelProgram.compile(source);
            int id = kernelIdSeq.getAndIncrement();
            compiledKernels.put(id, program);
            return new Object[]{id};
        } catch (IllegalArgumentException e) {
            return new Object[]{null, e.getMessage()};
        }
    }

    public boolean dropKernel(int kernelId) {
        return compiledKernels.remove(kernelId) != null;
    }

    public String hashDirect(String algoName, String data) {
        Algorithm algo = Algorithm.parse(algoName);
        if (algo.doublePass) {
            throw new IllegalArgumentException("Use single-pass primitive algorithms: sha256, sha512, sha3_256, md5");
        }
        if (Config.gpuComputeEnergyPerOp > 0 && node instanceof Connector connector) {
            if (!connector.tryChangeBuffer(-Config.gpuComputeEnergyPerOp)) {
                throw new IllegalStateException("not enough energy");
            }
        }
        totalOps.incrementAndGet();
        return CryptoKernels.toHex(CryptoKernels.hashBytes(algo, CryptoKernels.utf8(data)));
    }

    // -------------------------------------------------------------------------
    // Generic Parallel Kernel Execution
    // -------------------------------------------------------------------------

    public Object[] runKernel(Arguments args) {
        long count = args.checkLong(0);
        GpuTierSpec s = spec();
        if (count <= 0 || count > s.maxBatchSize()) {
            return new Object[]{null, "count must be in 1.." + s.maxBatchSize() + " for GPU tier " + s.displayTier()};
        }

        KernelProgram program;
        try {
            if (args.isInteger(1) || args.isLong(1) || args.isDouble(1)) {
                int kid = args.checkInteger(1);
                program = compiledKernels.get(kid);
                if (program == null) {
                    return new Object[]{null, "unknown compiled kernel id: " + kid};
                }
            } else {
                program = KernelProgram.compile(args.checkString(1));
            }
        } catch (IllegalArgumentException e) {
            return new Object[]{null, e.getMessage()};
        }

        Map<String, Object> uniforms;
        try {
            uniforms = parseUniforms(args.count() > 2 && args.checkAny(2) != null ? args.checkTable(2) : null, s.maxArrayElements());
        } catch (IllegalArgumentException e) {
            return new Object[]{null, e.getMessage()};
        }

        KernelOptions opts;
        try {
            opts = parseOptions(args.count() > 3 && args.checkAny(3) != null ? args.checkTable(3) : null, program);
        } catch (IllegalArgumentException e) {
            return new Object[]{null, e.getMessage()};
        }

        if (opts.reduce == null && opts.whereSlot < 0 && !opts.outVars.isEmpty() && count > Config.gpuComputeMaxOutputRows) {
            return new Object[]{null, "collecting all outputs without 'where' or 'reduce' is limited to " + Config.gpuComputeMaxOutputRows + " threads"};
        }

        long estimatedOps = Math.max(1L, Math.round(count * program.opWeight()));
        Object[] gateErr = checkCapacityAndConsumeEnergy(estimatedOps);
        if (gateErr != null) {
            return gateErr;
        }

        KernelProgram.UniformBinding binding = program.bindUniforms(uniforms);
        JobRecord job = registerNewJob("kernel", count);
        int partitions = GpuComputeEngine.choosePartitionCount(count, s);

        AtomicBoolean earlyStop = new AtomicBoolean(false);
        AtomicLong threadsExecuted = new AtomicLong(0L);
        AtomicLong matchCount = new AtomicLong(0L);
        List<Object> collected = opts.reduce == null
                ? Collections.synchronizedList(new ArrayList<>())
                : null;

        double[] partialReduce = opts.reduce != null ? new double[partitions] : null;
        if (opts.reduce != null) {
            double init = switch (opts.reduce) {
                case MIN -> Double.POSITIVE_INFINITY;
                case MAX -> Double.NEGATIVE_INFINITY;
                default -> 0.0;
            };
            java.util.Arrays.fill(partialReduce, init);
        }

        job.markRunning();
        CompletableFuture<Void> future = GpuComputeEngine.submitParallelPartitions(partitions, (partIdx, partCount) -> {
            long chunkLen = count / partCount;
            long rem = count % partCount;
            long myOffset = partIdx * chunkLen + Math.min(partIdx, rem);
            long myCount = chunkLen + (partIdx < rem ? 1 : 0);
            if (myCount <= 0) return;

            Object[] vars = program.createScratchVars();
            long processed = 0L;
            double localReduce = partialReduce != null ? partialReduce[partIdx] : 0.0;

            while (processed < myCount && !job.stopFlag.get() && !earlyStop.get()) {
                int slice = (int) Math.min(GpuComputeEngine.SLICE_SIZE, myCount - processed);
                long sliceStart = System.nanoTime();
                long baseOffset = myOffset + processed;

                for (int i = 0; i < slice; i++) {
                    if ((i & 63) == 0 && (job.stopFlag.get() || earlyStop.get())) {
                        break;
                    }
                    long threadId = baseOffset + i;
                    program.execute(vars, binding, threadId, count);
                    processed++;
                    threadsExecuted.incrementAndGet();

                    boolean passed = opts.whereSlot < 0 || KernelProgram.asBool(vars[opts.whereSlot]);
                    if (!passed) {
                        continue;
                    }
                    if (opts.stopOnFirst && !earlyStop.compareAndSet(false, true)) {
                        break;
                    }

                    long currentMatches = matchCount.incrementAndGet();
                    if (opts.reduce != null) {
                        if (opts.reduce == ReduceOp.COUNT) {
                            localReduce += 1.0;
                        } else {
                            double val = KernelProgram.asDouble(vars[opts.outSlots[0]]);
                            localReduce = switch (opts.reduce) {
                                case SUM -> localReduce + val;
                                case MIN -> Math.min(localReduce, val);
                                case MAX -> Math.max(localReduce, val);
                                case COUNT -> localReduce + 1.0;
                            };
                        }
                    } else if (currentMatches <= opts.maxResults) {
                        collected.add(extractThreadOutput(threadId, vars, opts));
                    }

                    if (opts.stopOnFirst) {
                        break;
                    }
                    if (opts.whereSlot >= 0 && opts.reduce == null && currentMatches >= opts.maxResults) {
                        earlyStop.set(true);
                        break;
                    }
                }

                job.opsDone.set(threadsExecuted.get());
                long elapsed = System.nanoTime() - sliceStart;
                if (!earlyStop.get() && !job.stopFlag.get()) {
                    GpuComputeEngine.paceSlice(elapsed, Math.max(1, slice), s, job.stopFlag);
                }
            }

            if (partialReduce != null) {
                partialReduce[partIdx] = localReduce;
            }
        });

        attachCompletion(job, future, () -> {
            long executed = threadsExecuted.get();
            totalOps.addAndGet(executed);
            long durationMs = job.elapsedMs();
            long matches = matchCount.get();
            double throughput = durationMs > 0 ? (executed * 1000.0) / durationMs : executed * 1000.0;

            job.extraResult.put("threadsExecuted", executed);
            job.extraResult.put("matchCount", matches);
            job.extraResult.put("throughput", throughput);

            if (opts.reduce != null) {
                double reduced = switch (opts.reduce) {
                    case MIN -> {
                        double m = Double.POSITIVE_INFINITY;
                        for (double v : partialReduce) m = Math.min(m, v);
                        yield m == Double.POSITIVE_INFINITY ? 0.0 : m;
                    }
                    case MAX -> {
                        double m = Double.NEGATIVE_INFINITY;
                        for (double v : partialReduce) m = Math.max(m, v);
                        yield m == Double.NEGATIVE_INFINITY ? 0.0 : m;
                    }
                    default -> {
                        double sum = 0.0;
                        for (double v : partialReduce) sum += v;
                        yield sum;
                    }
                };
                job.extraResult.put("reduced", reduced);
                emitDoneSignal(job.id, "kernel", true, reduced, matches, executed, durationMs);
            } else {
                List<Object> safeCopy;
                synchronized (collected) {
                    safeCopy = new ArrayList<>(collected);
                }
                job.extraResult.put("result", safeCopy);
                emitDoneSignal(job.id, "kernel", true, matches, executed, durationMs);
            }
        });

        return new Object[]{job.id};
    }

    // -------------------------------------------------------------------------
    // Basic Vector & Matrix Math Primitives
    // -------------------------------------------------------------------------

    public Object[] launchVectorOp(Arguments args) {
        String op = args.checkString(0).trim().toLowerCase(Locale.ROOT);
        GpuTierSpec s = spec();
        double[] a = extractDoubleArray(args.checkTable(1), s.maxArrayElements());
        if (a.length == 0) {
            return new Object[]{null, "vector A must be a non-empty numeric array"};
        }

        boolean scalarMode = false;
        double scalar = 0.0;
        double[] b = null;
        if (args.isDouble(2) || args.isInteger(2) || args.isLong(2)) {
            scalarMode = true;
            scalar = args.checkDouble(2);
        } else {
            b = extractDoubleArray(args.checkTable(2), s.maxArrayElements());
            if (b.length != a.length) {
                return new Object[]{null, "vector lengths must match (" + a.length + " != " + b.length + ")"};
            }
        }

        Object[] gateErr = checkCapacityAndConsumeEnergy(Math.max(1L, a.length / 4L));
        if (gateErr != null) {
            return gateErr;
        }

        final boolean useScalar = scalarMode;
        final double bScalar = scalar;
        final double[] bArr = b;
        final int n = a.length;
        final boolean isDot = "dot".equals(op);
        final double[] out = isDot ? null : new double[n];

        int partitions = GpuComputeEngine.choosePartitionCount(n, s);
        final double[] partialDots = isDot ? new double[partitions] : null;
        JobRecord job = registerNewJob("vector", n);

        job.markRunning();
        CompletableFuture<Void> future = GpuComputeEngine.submitParallelPartitions(partitions, (partIdx, partCount) -> {
            int chunk = n / partCount;
            int rem = n % partCount;
            int start = partIdx * chunk + Math.min(partIdx, rem);
            int end = start + chunk + (partIdx < rem ? 1 : 0);

            double localDot = 0.0;
            for (int i = start; i < end && !job.stopFlag.get(); i++) {
                double av = a[i];
                double bv = useScalar ? bScalar : bArr[i];
                switch (op) {
                    case "add" -> out[i] = av + bv;
                    case "sub" -> out[i] = av - bv;
                    case "mul" -> out[i] = av * bv;
                    case "div" -> out[i] = bv == 0.0 ? 0.0 : av / bv;
                    case "pow" -> out[i] = Math.pow(av, bv);
                    case "min" -> out[i] = Math.min(av, bv);
                    case "max" -> out[i] = Math.max(av, bv);
                    case "dot" -> localDot += av * bv;
                    default -> throw new IllegalArgumentException("Unknown vector op: " + op);
                }
                job.opsDone.incrementAndGet();
            }
            if (isDot) {
                partialDots[partIdx] = localDot;
            }
        });

        attachCompletion(job, future, () -> {
            totalOps.addAndGet(n);
            if (isDot) {
                double sum = 0.0;
                for (double v : partialDots) sum += v;
                job.extraResult.put("dot", sum);
                emitDoneSignal(job.id, "vector", true, sum, job.elapsedMs());
            } else {
                List<Double> resList = new ArrayList<>(n);
                for (double v : out) resList.add(v);
                job.extraResult.put("result", resList);
                emitDoneSignal(job.id, "vector", true, n, job.elapsedMs());
            }
        });

        return new Object[]{job.id};
    }

    public Object[] launchMatrixMul(Arguments args) {
        int aRows = args.checkInteger(0);
        int aCols = args.checkInteger(1);
        int bCols = args.checkInteger(2);
        GpuTierSpec s = spec();

        if (aRows <= 0 || aCols <= 0 || bCols <= 0) {
            return new Object[]{null, "matrix dimensions must be positive"};
        }
        long totalMulOps = (long) aRows * aCols * bCols;
        if (totalMulOps > s.maxBatchSize()) {
            return new Object[]{null, "matrix ops (" + totalMulOps + ") exceed maxBatchSize (" + s.maxBatchSize() + ")"};
        }
        if ((long) aRows * bCols > s.maxArrayElements()) {
            return new Object[]{null, "output matrix exceeds max array elements (" + s.maxArrayElements() + ")"};
        }

        double[] a = extractDoubleArray(args.checkTable(3), aRows * aCols);
        double[] b = extractDoubleArray(args.checkTable(4), aCols * bCols);
        if (a.length != aRows * aCols || b.length != aCols * bCols) {
            return new Object[]{null, "flat matrix sizes do not match dimensions"};
        }

        Object[] gateErr = checkCapacityAndConsumeEnergy(Math.max(1L, totalMulOps / 4L));
        if (gateErr != null) {
            return gateErr;
        }

        double[] c = new double[aRows * bCols];
        JobRecord job = registerNewJob("matrix", totalMulOps);
        int partitions = Math.min(aRows, GpuComputeEngine.choosePartitionCount(totalMulOps, s));

        job.markRunning();
        CompletableFuture<Void> future = GpuComputeEngine.submitParallelPartitions(partitions, (partIdx, partCount) -> {
            int rowChunk = aRows / partCount;
            int rem = aRows % partCount;
            int rowStart = partIdx * rowChunk + Math.min(partIdx, rem);
            int rowEnd = rowStart + rowChunk + (partIdx < rem ? 1 : 0);

            for (int r = rowStart; r < rowEnd && !job.stopFlag.get(); r++) {
                int aRowOff = r * aCols;
                int cRowOff = r * bCols;
                for (int k = 0; k < aCols; k++) {
                    double aik = a[aRowOff + k];
                    int bRowOff = k * bCols;
                    for (int col = 0; col < bCols; col++) {
                        c[cRowOff + col] += aik * b[bRowOff + col];
                    }
                }
                job.opsDone.addAndGet((long) aCols * bCols);
            }
        });

        attachCompletion(job, future, () -> {
            totalOps.addAndGet(totalMulOps);
            List<Double> resList = new ArrayList<>(c.length);
            for (double v : c) resList.add(v);
            job.extraResult.put("rows", aRows);
            job.extraResult.put("cols", bCols);
            job.extraResult.put("result", resList);
            emitDoneSignal(job.id, "matrix", true, c.length, job.elapsedMs());
        });

        return new Object[]{job.id};
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Object[] checkCapacityAndConsumeEnergy(long estimatedOps) {
        if (!Config.gpuComputeEnabled) {
            return new Object[]{null, "gpu compute disabled in config"};
        }
        if (activeJobs.get() >= Config.gpuComputeMaxJobsPerCard) {
            return new Object[]{null, "gpu compute queue full for this card"};
        }
        if (!GpuComputeEngine.tryAcquireGlobalSlot()) {
            return new Object[]{null, "server-wide gpu compute queue full"};
        }

        double energyCost = estimatedOps * Config.gpuComputeEnergyPerOp;
        if (energyCost > 0.0 && node instanceof Connector connector) {
            if (!connector.tryChangeBuffer(-energyCost)) {
                GpuComputeEngine.releaseGlobalSlot();
                return new Object[]{null, "not enough energy"};
            }
        }
        return null;
    }

    private JobRecord registerNewJob(String type, long totalWork) {
        pruneOldJobs();
        int id = jobIdSeq.getAndIncrement();
        JobRecord job = new JobRecord(id, type, totalWork);
        jobs.put(id, job);
        activeJobs.incrementAndGet();
        return job;
    }

    private void attachCompletion(JobRecord job, CompletableFuture<Void> future, Runnable onSuccess) {
        future.whenComplete((ignored, error) -> {
            try {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    OCRAdditions.LOGGER.warn("GPU compute job {} failed", job.id, cause);
                    job.markFailed(cause.getMessage());
                    emitDoneSignal(job.id, job.type, false, cause.getMessage());
                } else if (job.stopFlag.get()) {
                    job.markCanceled();
                    emitDoneSignal(job.id, job.type, false, "canceled");
                } else {
                    onSuccess.run();
                    job.markDone();
                    completedJobs.incrementAndGet();
                }
            } finally {
                activeJobs.updateAndGet(v -> Math.max(0, v - 1));
                GpuComputeEngine.releaseGlobalSlot();
            }
        });
    }

    private void emitDoneSignal(int jobId, String type, Object... payload) {
        if (node == null) return;
        Object[] args = new Object[payload.length + 3];
        args[0] = "gpu_compute_done";
        args[1] = jobId;
        args[2] = type;
        System.arraycopy(payload, 0, args, 3, payload.length);

        GpuComputeEngine.runOnServerThread(() -> {
            if (node.network() != null) {
                node.sendToReachable("computer.signal", args);
            }
        });
    }

    private void pruneOldJobs() {
        if (jobs.size() <= MAX_RETAINED_FINISHED_JOBS) return;
        jobs.values().stream()
                .filter(JobRecord::isFinished)
                .sorted(Comparator.comparingInt(j -> j.id))
                .limit(Math.max(0, jobs.size() - MAX_RETAINED_FINISHED_JOBS))
                .forEach(j -> jobs.remove(j.id));
    }

    private static Map<String, Object> parseUniforms(Map<?, ?> table, int maxArrLen) {
        Map<String, Object> uniforms = new LinkedHashMap<>();
        if (table == null || table.isEmpty()) return uniforms;

        for (Map.Entry<?, ?> entry : table.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object val = entry.getValue();
            if (val instanceof Number n) {
                uniforms.put(key, n.doubleValue());
            } else if (val instanceof Boolean b) {
                uniforms.put(key, b);
            } else if (val instanceof String str) {
                uniforms.put(key, str);
            } else if (val instanceof Map<?, ?> sub) {
                uniforms.put(key, extractUniformArray(sub, maxArrLen));
            } else {
                throw new IllegalArgumentException("Unsupported uniform type for '" + key + "'");
            }
        }
        return uniforms;
    }

    private static Object extractUniformArray(Map<?, ?> table, int maxLen) {
        if (table.isEmpty()) return new double[0];
        Object first = getLuaIndex(table, 1);
        if (first instanceof String) {
            List<String> list = new ArrayList<>();
            for (int i = 1; i <= maxLen; i++) {
                Object v = getLuaIndex(table, i);
                if (v == null) break;
                list.add(String.valueOf(v));
            }
            return list.toArray(new String[0]);
        }
        return extractDoubleArray(table, maxLen);
    }

    private enum ReduceOp {
        SUM, MIN, MAX, COUNT
    }

    private record KernelOptions(
            int whereSlot,
            boolean stopOnFirst,
            int maxResults,
            List<String> outVars,
            int[] outSlots,
            boolean singleScalarOut,
            ReduceOp reduce
    ) {
    }

    private static KernelOptions parseOptions(Map<?, ?> table, KernelProgram program) {
        int whereSlot = -1;
        boolean stopOnFirst = false;
        int maxResults = 1024;
        List<String> outVars = new ArrayList<>();
        boolean singleScalarOut = false;
        ReduceOp reduce = null;

        if (table != null && !table.isEmpty()) {
            Object w = table.get("where");
            if (w instanceof String ws && !ws.isBlank()) {
                whereSlot = program.slotOf(ws.trim());
                if (whereSlot < 0) {
                    throw new IllegalArgumentException("Unknown 'where' variable in kernel: " + ws);
                }
            }
            Object stop = table.get("stopOnFirst");
            if (stop instanceof Boolean b) {
                stopOnFirst = b;
            }
            Object maxRes = table.get("maxResults");
            if (maxRes instanceof Number n) {
                maxResults = Math.clamp(n.intValue(), 1, Config.gpuComputeMaxOutputRows);
            }
            Object red = table.get("reduce");
            if (red instanceof String rs && !rs.isBlank()) {
                reduce = switch (rs.trim().toLowerCase(Locale.ROOT)) {
                    case "sum" -> ReduceOp.SUM;
                    case "min" -> ReduceOp.MIN;
                    case "max" -> ReduceOp.MAX;
                    case "count" -> ReduceOp.COUNT;
                    default -> throw new IllegalArgumentException("Unknown reduce mode: " + rs);
                };
            }
            Object outObj = table.get("out");
            if (outObj instanceof String singleName && !singleName.isBlank()) {
                outVars.add(singleName.trim());
                singleScalarOut = true;
            } else if (outObj instanceof Map<?, ?> outList) {
                for (int i = 1; i <= 16; i++) {
                    Object item = getLuaIndex(outList, i);
                    if (item == null) break;
                    outVars.add(String.valueOf(item).trim());
                }
            }
        }

        if (outVars.isEmpty() && whereSlot < 0 && program.slotOf("result") >= 0) {
            outVars.add("result");
            singleScalarOut = true;
        }

        int[] outSlots = new int[outVars.size()];
        for (int i = 0; i < outVars.size(); i++) {
            String name = outVars.get(i);
            int slot = "id".equals(name) ? -2 : program.slotOf(name);
            if (slot == -1) {
                throw new IllegalArgumentException("Unknown 'out' variable in kernel: " + name);
            }
            outSlots[i] = slot;
        }

        if (reduce != null && reduce != ReduceOp.COUNT && outSlots.length == 0) {
            throw new IllegalArgumentException("'reduce' requires 'out' variable (e.g. out = \"val\")");
        }

        return new KernelOptions(whereSlot, stopOnFirst, maxResults, List.copyOf(outVars), outSlots, singleScalarOut, reduce);
    }

    private static Object extractThreadOutput(long threadId, Object[] vars, KernelOptions opts) {
        if (opts.singleScalarOut && opts.outSlots.length == 1) {
            int s = opts.outSlots[0];
            return s == -2 ? (double) threadId : vars[s];
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", threadId);
        for (int i = 0; i < opts.outVars.size(); i++) {
            int s = opts.outSlots[i];
            row.put(opts.outVars.get(i), s == -2 ? (double) threadId : vars[s]);
        }
        return row;
    }

    private static double[] extractDoubleArray(Map<?, ?> table, int maxLen) {
        if (table == null || table.isEmpty()) return new double[0];
        List<Double> tmp = new ArrayList<>(Math.min(maxLen, table.size()));
        for (int i = 1; i <= maxLen; i++) {
            Object v = getLuaIndex(table, i);
            if (v == null) break;
            if (!(v instanceof Number num)) {
                throw new IllegalArgumentException("Expected number at index " + i);
            }
            tmp.add(num.doubleValue());
        }
        double[] arr = new double[tmp.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = tmp.get(i);
        }
        return arr;
    }

    private static Object getLuaIndex(Map<?, ?> table, int i) {
        Object v = table.get(i);
        return v != null ? v : table.get((double) i);
    }

    public static final class JobRecord {
        final int id;
        final String type;
        final long totalWork;
        final long createdNanos = System.nanoTime();
        volatile long endNanos = 0L;
        final AtomicBoolean stopFlag = new AtomicBoolean(false);
        final AtomicLong opsDone = new AtomicLong(0L);
        final Map<String, Object> extraResult = new ConcurrentHashMap<>();
        private final AtomicReference<Status> status = new AtomicReference<>(Status.QUEUED);
        volatile String errorMessage = null;

        JobRecord(int id, String type, long totalWork) {
            this.id = id;
            this.type = type;
            this.totalWork = Math.max(1L, totalWork);
        }

        void markRunning() {
            status.compareAndSet(Status.QUEUED, Status.RUNNING);
        }

        void markDone() {
            endNanos = System.nanoTime();
            status.compareAndSet(Status.RUNNING, Status.DONE);
            status.compareAndSet(Status.QUEUED, Status.DONE);
        }

        void markCanceled() {
            endNanos = System.nanoTime();
            status.set(Status.CANCELED);
        }

        void markFailed(String msg) {
            endNanos = System.nanoTime();
            errorMessage = msg != null ? msg : "compute error";
            status.set(Status.FAILED);
        }

        boolean cancel() {
            stopFlag.set(true);
            Status s = status.get();
            return s == Status.QUEUED || s == Status.RUNNING;
        }

        boolean isFinished() {
            Status s = status.get();
            return s == Status.DONE || s == Status.CANCELED || s == Status.FAILED;
        }

        long elapsedMs() {
            long end = endNanos > 0L ? endNanos : System.nanoTime();
            return Math.max(0L, (end - createdNanos) / 1_000_000L);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            Status s = status.get();
            m.put("id", id);
            m.put("type", type);
            m.put("status", s.name().toLowerCase(Locale.ROOT));
            m.put("opsDone", opsDone.get());
            m.put("totalWork", totalWork);
            m.put("progress", s == Status.DONE ? 1.0 : Math.min(1.0, (double) opsDone.get() / totalWork));
            m.put("durationMs", elapsedMs());
            if (errorMessage != null) {
                m.put("error", errorMessage);
            }
            m.putAll(extraResult);
            return m;
        }
    }
}
