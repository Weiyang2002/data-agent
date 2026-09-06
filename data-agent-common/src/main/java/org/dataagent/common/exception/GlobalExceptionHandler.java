package org.dataagent.common.exception;

import org.dataagent.common.result.ApiResponse;
import org.dataagent.common.result.BaseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * <p>Controller 不写 try-catch，业务代码只管抛异常，异常处理逻辑集中一份，
 * 保证出口格式一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 可预期的业务失败：只打 warn，不打堆栈，避免日志噪音 */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException exception) {
        log.warn("业务异常 code={}, message={}", exception.getCode(), exception.getMessage());
        return ApiResponse.fail(exception.getCode(), exception.getMessage());
    }

    /** @Valid 校验失败 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ApiResponse<Void> handleValidation(BindException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .orElse("参数校验失败");
        log.warn("参数校验失败: {}", detail);
        return ApiResponse.fail(BaseCode.PARAM_INVALID.getCode(), detail);
    }

    /** 兜底：不可预期的异常，打完整堆栈 */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception exception) {
        log.error("未预期异常", exception);
        return ApiResponse.fail(BaseCode.INTERNAL_ERROR);
    }
}
