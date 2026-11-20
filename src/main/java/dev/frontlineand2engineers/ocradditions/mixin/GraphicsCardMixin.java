package dev.frontlineand2engineers.ocradditions.mixin;

import dev.frontlineand2engineers.ocradditions.compute.GpuComputeSession;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Node;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Adds general-purpose CUDA-style parallel kernel execution and basic math/hash primitives
 * to all OpenComputers Graphics Cards, APUs, and Quad GPUs.
 */
@Mixin(targets = "li.cil.oc.server.component.GraphicsCard", remap = false)
public abstract class GraphicsCardMixin {
    @Shadow
    public abstract int tier();

    @Unique
    private GpuComputeSession ocradditions$compute;

    @Unique
    private GpuComputeSession ocradditions$session() {
        if (ocradditions$compute == null) {
            ocradditions$compute = new GpuComputeSession(tier(), ((Environment) (Object) this).node());
        }
        return ocradditions$compute;
    }

    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void ocradditions$onDisconnect(Node disconnectedNode, CallbackInfo ci) {
        if (ocradditions$compute != null && disconnectedNode == ((Environment) (Object) this).node()) {
            ocradditions$compute.dispose();
            ocradditions$compute = null;
        }
    }

    @Callback(direct = true, doc = "function():table -- Returns CUDA compute specs and statistics for this GPU.")
    public Object[] getComputeInfo(Context context, Arguments args) {
        return new Object[]{ocradditions$session().getComputeInfo()};
    }

    @Callback(doc = "function(source:string):number -- Compiles a CUDA kernel expression and returns its kernelId.")
    public Object[] compileKernel(Context context, Arguments args) {
        return ocradditions$session().compileKernel(args.checkString(0));
    }

    @Callback(doc = "function(kernelId:number):boolean -- Frees a previously compiled kernel.")
    public Object[] dropKernel(Context context, Arguments args) {
        return new Object[]{ocradditions$session().dropKernel(args.checkInteger(0))};
    }

    @Callback(doc = "function(count:number, kernelOrId:string|number[, params:table, options:table]):number -- Runs a compiled or inline kernel across parallel GPU threads. Emits 'gpu_compute_done'.")
    public Object[] runKernel(Context context, Arguments args) {
        return ocradditions$session().runKernel(args);
    }

    @Callback(direct = true, doc = "function(jobId:number):table|nil -- Returns status, progress, and results of an async compute job.")
    public Object[] getComputeJob(Context context, Arguments args) {
        int jobId = args.checkInteger(0);
        Map<String, Object> snap = ocradditions$session().getJobSnapshot(jobId);
        if (snap == null) {
            return new Object[]{null, "unknown compute job"};
        }
        return new Object[]{snap};
    }

    @Callback(doc = "function(jobId:number):boolean -- Cancels a queued or running GPU compute job.")
    public Object[] cancelComputeJob(Context context, Arguments args) {
        return new Object[]{ocradditions$session().cancelJob(args.checkInteger(0))};
    }

    @Callback(doc = "function():number -- Cancels all queued or running compute jobs on this GPU.")
    public Object[] cancelAllCompute(Context context, Arguments args) {
        return new Object[]{ocradditions$session().cancelAll()};
    }

    @Callback(direct = true, doc = "function(algorithm:string, data:string):string -- Single-pass hash primitive ('sha256', 'sha512', 'sha3_256', 'md5').")
    public Object[] hash(Context context, Arguments args) {
        String algo = args.checkString(0);
        String data = args.checkString(1);
        try {
            return new Object[]{ocradditions$session().hashDirect(algo, data)};
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Object[]{null, e.getMessage()};
        }
    }

    @Callback(doc = "function(op:string, a:table, b:table|number):number -- Parallel vector ops ('add','sub','mul','div','pow','min','max','dot'). Emits 'gpu_compute_done'.")
    public Object[] vectorOp(Context context, Arguments args) {
        return ocradditions$session().launchVectorOp(args);
    }

    @Callback(doc = "function(aRows:number, aCols:number, bCols:number, aFlat:table, bFlat:table):number -- Parallel row-major matrix multiplication C = A * B. Emits 'gpu_compute_done'.")
    public Object[] matrixMul(Context context, Arguments args) {
        return ocradditions$session().launchMatrixMul(args);
    }
}
