package org.dataagent.clean.pipeline.observability;

import org.dataagent.clean.pipeline.model.trace.TraceStageCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前线程正在跑哪个任务的哪个阶段。用 ThreadLocal 把「切面知道当前阶段」和
 * 「模型调用拦截器知道 Token」接起来，调用方零改动。链路严格串行，不支持并行；
 * {@link #openTask} 发现上一个任务没关会直接抛异常而非覆盖。
 *
 * <p>阶段可嵌套（CODEGEN 嵌在 SANDBOX 里等）。每个 {@link Frame} 记 {@code totalMillis}
 * （墙钟，含子阶段）与 {@code selfMillis}（扣掉子阶段）；Token 只记自身不向上累加，
 * 保证按阶段 SUM 就是任务总量。
 */
public final class TraceContext {

    private static final ThreadLocal<TaskScope> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * 开启一次任务作用域。必须与 {@link #closeTask()} 配对（try/finally）。
     *
     * @throws IllegalStateException 上一个任务作用域没关就开新的
     */
    public static TaskScope openTask(String taskCode, String traceId) {
        TaskScope existing = CURRENT.get();
        if (existing != null) {
            CURRENT.remove();
            throw new IllegalStateException(
                "上一个任务作用域未关闭就开启了新的：旧 taskCode=%s，新 taskCode=%s。"
                    .formatted(existing.taskCode, taskCode)
                    + "这说明某处漏了 finally —— 埋点会张冠李戴，比不埋点更糟");
        }
        TaskScope scope = new TaskScope(taskCode, traceId);
        CURRENT.set(scope);
        return scope;
    }

    public static void closeTask() {
        CURRENT.remove();
    }

    /** 当前作用域；不在任务里时返回 null（冒烟接口、评测层探针） */
    public static TaskScope current() {
        return CURRENT.get();
    }

    public static boolean inTask() {
        return CURRENT.get() != null;
    }

    /**
     * 把一次模型调用的用量记到当前最内层阶段上。不在任务作用域里就丢弃（该调用不
     * 属于任何任务，如 smoke 自检）。
     *
     * @param missingUsage 模型没回 usage 元数据，需单独计数以区别于「没调模型」
     */
    public static void recordModelCall(long promptTokens, long completionTokens,
                                       long totalTokens, boolean missingUsage) {
        TaskScope scope = CURRENT.get();
        if (scope == null) {
            return;
        }
        Frame frame = scope.stack.peek();
        if (frame == null) {
            // 任务里但不在任何被埋点的阶段内，挂到任务级兜底桶，不丢
            frame = scope.unattributed();
        }
        frame.modelCalls++;
        frame.promptTokens += promptTokens;
        frame.completionTokens += completionTokens;
        frame.totalTokens += totalTokens;
        if (missingUsage) {
            frame.usageMissingCalls++;
        }
    }

    /** 一次任务的作用域：一个阶段栈 + 一本按阶段收口的账 */
    public static final class TaskScope {

        private final String taskCode;
        private final String traceId;
        private final Deque<Frame> stack = new ArrayDeque<>();
        /** stageCode → 累计账，同一阶段进入多次合并成一行 */
        private final Map<String, StageTally> tallies = new LinkedHashMap<>();
        private Frame unattributedFrame;

        private TaskScope(String taskCode, String traceId) {
            this.taskCode = taskCode;
            this.traceId = traceId;
        }

        public String taskCode() {
            return taskCode;
        }

        public String traceId() {
            return traceId;
        }

        /** 当前嵌套深度：0 表示即将进入的是顶层阶段 */
        public int depth() {
            return stack.size();
        }

        Frame push(TraceStageCode stage) {
            Frame frame = new Frame(stage, stack.size());
            stack.push(frame);
            return frame;
        }

        /** 弹出栈顶阶段并结账，把墙钟耗时上报给父阶段以扣出父阶段的 selfMillis。 */
        Frame pop(long totalMillis) {
            Frame frame = stack.pop();
            frame.totalMillis = totalMillis;
            frame.selfMillis = Math.max(0, totalMillis - frame.childMillis);
            Frame parent = stack.peek();
            if (parent != null) {
                parent.childMillis += totalMillis;
            }
            tallies.computeIfAbsent(frame.stage.name(),
                key -> new StageTally(frame.stage, frame.depth)).absorb(frame);
            return frame;
        }

        private Frame unattributed() {
            if (unattributedFrame == null) {
                unattributedFrame = new Frame(TraceStageCode.UNATTRIBUTED, 0);
            }
            return unattributedFrame;
        }

        /** 结算：按阶段收口的账，供落库 */
        public List<StageTally> settle() {
            List<StageTally> result = new ArrayList<>(tallies.values());
            if (unattributedFrame != null && unattributedFrame.modelCalls > 0) {
                StageTally orphan = new StageTally(TraceStageCode.UNATTRIBUTED, 0);
                orphan.absorb(unattributedFrame);
                result.add(orphan);
            }
            return result;
        }
    }

    /** 一次阶段进入。栈上的活动帧 */
    static final class Frame {

        final TraceStageCode stage;
        final int depth;

        int modelCalls;
        int usageMissingCalls;
        long promptTokens;
        long completionTokens;
        long totalTokens;

        long totalMillis;
        long selfMillis;
        /** 子阶段墙钟耗时之和，用来从 totalMillis 里扣出 selfMillis */
        long childMillis;

        Frame(TraceStageCode stage, int depth) {
            this.stage = stage;
            this.depth = depth;
        }
    }

    /** 同一阶段在一个任务里的累计账 */
    public static final class StageTally {

        private final TraceStageCode stage;
        private final int depth;

        private int enterCount;
        private int modelCalls;
        private int usageMissingCalls;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long selfMillis;
        private long totalMillis;

        StageTally(TraceStageCode stage, int depth) {
            this.stage = stage;
            this.depth = depth;
        }

        void absorb(Frame frame) {
            enterCount++;
            modelCalls += frame.modelCalls;
            usageMissingCalls += frame.usageMissingCalls;
            promptTokens += frame.promptTokens;
            completionTokens += frame.completionTokens;
            totalTokens += frame.totalTokens;
            selfMillis += frame.selfMillis;
            totalMillis += frame.totalMillis;
        }

        public TraceStageCode stage() {
            return stage;
        }

        public int depth() {
            return depth;
        }

        public int enterCount() {
            return enterCount;
        }

        public int modelCalls() {
            return modelCalls;
        }

        public int usageMissingCalls() {
            return usageMissingCalls;
        }

        public long promptTokens() {
            return promptTokens;
        }

        public long completionTokens() {
            return completionTokens;
        }

        public long totalTokens() {
            return totalTokens;
        }

        public long selfMillis() {
            return selfMillis;
        }

        public long totalMillis() {
            return totalMillis;
        }
    }
}
