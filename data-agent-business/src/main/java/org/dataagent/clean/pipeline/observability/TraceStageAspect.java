package org.dataagent.clean.pipeline.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.service.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * {@link TraceStage} 的切面：计时 → 归集 Token → 落 {@code data_agent_task_stage}。
 *
 */
@Aspect
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class TraceStageAspect {

    private static final Logger log = LoggerFactory.getLogger(TraceStageAspect.class);

    private final TraceRecorder traceRecorder;

    public TraceStageAspect(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @Around("@annotation(org.dataagent.clean.pipeline.observability.TraceStage)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        TraceContext.TaskScope scope = TraceContext.current();
        if (scope == null) {
            // 不在任务里（冒烟接口、评测层探针），不埋点
            return joinPoint.proceed();
        }

        TraceStage traceStage = resolveAnnotation(joinPoint);
        if (traceStage == null) {
            // 只可能发生在桥接方法上（见 resolveAnnotation），取不到阶段码就不埋点
            log.warn("匹配到 @TraceStage 却取不到注解，跳过埋点：{}", joinPoint.getSignature());
            return joinPoint.proceed();
        }
        TraceStageCode stage = traceStage.value();
        LocalDateTime startedAt = LocalDateTime.now();
        long startedNanos = System.nanoTime();
        TraceContext.Frame frame = scope.push(stage);

        String state = "SUCCESS";
        String errorMsg = null;
        try {
            return joinPoint.proceed();
        }
        catch (Throwable throwable) {
            state = "FAILED";
            errorMsg = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            throw throwable;
        }
        finally {
            long cost = (System.nanoTime() - startedNanos) / 1_000_000L;
            try {
                scope.pop(cost);
                traceRecorder.stage(scope.taskCode(), scope.traceId(), stage, frame.depth,
                    state, summarize(stage, frame), errorMsg,
                    startedAt, LocalDateTime.now(), cost);
            }
            catch (Exception exception) {
                log.warn("阶段埋点异常（不影响业务），stage={}, 原因={}",
                    stage, exception.getMessage());
            }
        }
    }

    /**
     * 从连接点上取注解，不用 {@code @Around("@annotation(traceStage)")} 的绑参写法。
     *
     * <p>四个 Agent 实现泛型接口 {@code DataAgent<I, O>}，编译器生成的桥接方法上没有
     * 注解，绑参写法会在拦截桥接方法时报「Required to bind 2 arguments, but only
     * bound 1」，且堆栈指向业务类。改为切点只用全限定名匹配，注解从
     * {@code getMostSpecificMethod} 的目标方法上取。
     */
    private TraceStage resolveAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object target = joinPoint.getTarget();
        Method specific = target == null ? method
            : AopUtils.getMostSpecificMethod(method, target.getClass());
        TraceStage annotation =
            AnnotatedElementUtils.findMergedAnnotation(specific, TraceStage.class);
        return annotation != null
            ? annotation
            : AnnotatedElementUtils.findMergedAnnotation(method, TraceStage.class);
    }

    /** 阶段摘要。只有真的调过模型才写 Token，避免纯确定性阶段出现歧义的「Token 0」。 */
    private String summarize(TraceStageCode stage, TraceContext.Frame frame) {
        if (frame.modelCalls == 0) {
            return stage.getLabel();
        }
        String text = "%s｜模型调用 %d 次，Token %d（入 %d / 出 %d）".formatted(
            stage.getLabel(), frame.modelCalls, frame.totalTokens,
            frame.promptTokens, frame.completionTokens);
        if (frame.usageMissingCalls > 0) {
            // 不静默降级：这个阶段的 Token 是下界
            text += "｜其中 %d 次调用模型未返回 usage，本行 Token 是下界"
                .formatted(frame.usageMissingCalls);
        }
        return text;
    }
}
