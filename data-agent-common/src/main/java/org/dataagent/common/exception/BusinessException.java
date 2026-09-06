package org.dataagent.common.exception;

import lombok.Getter;
import org.dataagent.common.result.BaseCode;

/**
 * 业务异常。
 *
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(BaseCode baseCode) {
        super(baseCode.getMessage());
        this.code = baseCode.getCode();
    }

    public BusinessException(BaseCode baseCode, String detail) {
        super(baseCode.getMessage() + ": " + detail);
        this.code = baseCode.getCode();
    }

    public BusinessException(BaseCode baseCode, String detail, Throwable cause) {
        super(baseCode.getMessage() + ": " + detail, cause);
        this.code = baseCode.getCode();
    }
}
