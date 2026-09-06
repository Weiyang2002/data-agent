package org.dataagent.clean.pipeline.observability;

import org.dataagent.clean.pipeline.model.trace.TraceStageCode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在业务方法上，声明「这个方法就是链路里的某个阶段」。计时、异常捕获、Token
 * 归集、落 {@code data_agent_task_stage} 全部由 {@link TraceStageAspect} 完成，
 * 方法体内没有埋点代码。阶段边界与方法边界一致，加密埋点粒度不需要改调用方。
 *
 * <p>「跳过某个阶段」是业务判断（没有方法被调用，切面拦不到），继续走
 * {@code TraceRecorder.skip} 的显式 API。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TraceStage {

    /** 该方法对应的阶段码 */
    TraceStageCode value();
}
