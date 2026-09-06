package org.dataagent.clean.pipeline.executor;

import org.dataagent.clean.pipeline.service.TaskInfo;
import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;

/**
 * 一条完整处理链路的执行器。{@code mode()} 声明负责哪种模式，{@code execute()} 干活。
 */
public interface PipelineExecutor {

    PipelineMode mode();

    CleanTaskResultVO execute(TaskInfo taskInfo);
}
