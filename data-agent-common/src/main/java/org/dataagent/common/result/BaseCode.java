package org.dataagent.common.result;

/**
 * 业务状态码。
 *
 * <p>分段约定，便于日志检索时一眼看出问题出在哪一层：
 * <ul>
 *   <li>0      成功</li>
 *   <li>A0xxx  调用方错误（参数、状态非法）</li>
 *   <li>B0xxx  本服务内部错误</li>
 *   <li>C0xxx  依赖方错误（Python 工具服务、大模型网关、数据库）</li>
 * </ul>
 */
public enum BaseCode {

    SUCCESS("0", "success"),

    PARAM_INVALID("A0001", "请求参数不合法"),
    TASK_STATE_ILLEGAL("A0002", "任务当前状态不允许该操作"),

    INTERNAL_ERROR("B0001", "服务内部错误"),
    PROMPT_TEMPLATE_ERROR("B0002", "Prompt 模板渲染失败"),

    TOOLS_SERVICE_ERROR("C0001", "Python 工具服务调用失败"),
    MODEL_GATEWAY_ERROR("C0002", "大模型网关调用失败"),
    ;

    private final String code;
    private final String message;

    BaseCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
