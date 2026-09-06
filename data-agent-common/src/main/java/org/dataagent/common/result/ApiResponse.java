package org.dataagent.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回体。
 *
 * <p>所有 Controller 出口统一走此结构，调用方只解析一种形状。失败时 data 为 null，
 * 成败以 code 判断，不以 data 是否为空判断。
 */
@Data
public class ApiResponse<T> implements Serializable {

    /** 业务状态码，"0" 表示成功 */
    private String code;

    /** 提示信息，成功时通常为 "success" */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    /** 链路追踪 ID，贯穿 Java 与 Python，便于按任务回放 */
    private String traceId;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(BaseCode.SUCCESS.getCode());
        response.setMessage(BaseCode.SUCCESS.getMessage());
        response.setData(data);
        return response;
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    public static <T> ApiResponse<T> fail(BaseCode baseCode) {
        return fail(baseCode.getCode(), baseCode.getMessage());
    }
}
