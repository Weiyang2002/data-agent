package org.dataagent.clean.toolsclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱执行响应。
 *
 * <p>Python 侧对静态检查拦截返回 HTTP 200 + {@code success=false} +
 * {@code blockedReason}，不是 4xx：代码被拦是正常业务结果，ExecutorAgent 拿
 * {@link #blockedDetail} 做自修复，不能当作「调用失败」重试同一段代码。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecuteResponse {

    private Boolean success;

    private Integer exitCode;

    private String stdout;

    private String stderr;

    private Long durationMs;

    /** 非空表示被静态检查拦截，代码未执行；与「执行了但失败」是两回事 */
    private String blockedReason;

    /**
     * 全部违规项。
     *
     * <p>Python 侧一次性返回所有违规而非遇到第一条即停，使自修复能一轮改完，减少
     * 模型调用次数。
     */
    private List<String> blockedDetail = new ArrayList<>();

    private String outputPath;

    private Long outputRowCount;

    private Integer outputColumnCount;

    private Boolean timedOut;

    /**
     * 内存上限是否真的生效。
     *
     * <p>Windows 上 {@code resource.setrlimit} 不存在，此值为 false。如实返回：M6
     * 容器化之前这是一处已知且显式的降级（CLAUDE.md 不变量 7）。
     */
    private Boolean memoryLimitEnforced;

    public boolean isBlocked() {
        return blockedReason != null && !blockedReason.isBlank();
    }

    public boolean isSuccessful() {
        return Boolean.TRUE.equals(success);
    }

    /** 拼给自修复 Prompt 的失败原因。拦截原因优先于运行期报错（更具体、更可操作） */
    public String failureText() {
        if (isBlocked()) {
            return blockedReason + "\n" + String.join("\n", blockedDetail);
        }
        if (Boolean.TRUE.equals(timedOut)) {
            return "执行超时被强制终止。请检查是否存在全表逐行循环或死循环，改用向量化写法。";
        }
        return stderr == null ? "未知失败" : stderr;
    }
}
