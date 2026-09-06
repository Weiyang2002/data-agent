package org.dataagent.clean.toolsclient.client;

import org.dataagent.clean.support.RestClientFactorySupport;
import org.dataagent.clean.toolsclient.config.DataToolsProperties;
import org.dataagent.clean.toolsclient.model.DatasetGenerateRequest;
import org.dataagent.clean.toolsclient.model.DatasetGenerateResponse;
import org.dataagent.clean.toolsclient.model.EvalCasesResponse;
import org.dataagent.clean.toolsclient.model.ExecuteRequest;
import org.dataagent.clean.toolsclient.model.ExecuteResponse;
import org.dataagent.clean.toolsclient.model.ProfileRequest;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.dataagent.clean.toolsclient.model.ToolsHealthResponse;
import org.dataagent.clean.toolsclient.model.ValidateDistributionRequest;
import org.dataagent.clean.toolsclient.model.ValidateDistributionResponse;
import org.dataagent.clean.toolsclient.model.ValidateRowRequest;
import org.dataagent.clean.toolsclient.model.ValidateRowResponse;
import org.dataagent.common.exception.BusinessException;
import org.dataagent.common.result.BaseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Python 工具服务客户端，Java 编排层与 Python 执行层之间唯一的通道。每个请求都带
 * {@code X-Trace-Id} 头，Python 侧记进日志并原样返回，实现跨语言 trace 贯穿。
 */
@Component
public class DataToolsClient {

    private static final Logger log = LoggerFactory.getLogger(DataToolsClient.class);

    /** 链路追踪头，Python 侧会把它带进日志与响应 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    private final RestClient commonClient;

    /** 数据集生成专用客户端，超时更长（生成 + 缺陷注入是分钟级操作）。 */
    private final RestClient datasetClient;

    /** 画像：全量数据统计可能数分钟 */
    private final RestClient profileClient;

    /** 沙箱执行：读超时必须大于请求体里的 timeoutSeconds */
    private final RestClient executeClient;

    /** 确定性校验：秒级 */
    private final RestClient validateClient;

    public DataToolsClient(DataToolsProperties properties) {
        this.commonClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getReadTimeoutMs()
        );
        this.datasetClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getDatasetReadTimeoutMs()
        );
        this.profileClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getProfileReadTimeoutMs()
        );
        this.executeClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getExecuteReadTimeoutMs()
        );
        this.validateClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getValidateReadTimeoutMs()
        );
    }

    public ToolsHealthResponse health() {
        long startedNanos = System.nanoTime();
        try {
            ToolsHealthResponse response = commonClient.get()
                .uri("/health")
                .retrieve()
                .body(ToolsHealthResponse.class);
            log.info("Python 工具服务健康检查完成, status={}, costMillis={}",
                response == null ? null : response.getStatus(), elapsedMillis(startedNanos));
            return response;
        }
        catch (RestClientException exception) {
            // 转成本项目的业务异常
            throw new BusinessException(BaseCode.TOOLS_SERVICE_ERROR,
                "health 调用失败，耗时 " + elapsedMillis(startedNanos) + "ms", exception);
        }
    }

    /** 生成带 golden 的评测数据集。返回体同时就是 golden.json 的内容，直接用于评分。 */
    public DatasetGenerateResponse generateDataset(DatasetGenerateRequest request) {
        long startedNanos = System.nanoTime();
        try {
            DatasetGenerateResponse response = datasetClient.post()
                .uri("/dataset/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(DatasetGenerateResponse.class);
            log.info("评测数据集生成完成, seed={}, rows={}, 缺陷数={}, costMillis={}",
                request.getSeed(),
                response == null ? null : response.getRowCount(),
                response == null || response.getInjected() == null ? 0 : response.getInjected().size(),
                elapsedMillis(startedNanos));
            return response;
        }
        catch (RestClientException exception) {
            throw new BusinessException(BaseCode.TOOLS_SERVICE_ERROR,
                "数据集生成失败，seed=" + request.getSeed()
                    + "，耗时 " + elapsedMillis(startedNanos) + "ms", exception);
        }
    }

    /** 取评测用例目录。 */
    public EvalCasesResponse listEvalCases() {
        try {
            return commonClient.get()
                .uri("/eval/cases")
                .retrieve()
                .body(EvalCasesResponse.class);
        }
        catch (RestClientException exception) {
            throw new BusinessException(BaseCode.TOOLS_SERVICE_ERROR,
                "评测用例目录获取失败", exception);
        }
    }

    /** 数据画像。返回体只含 schema 与统计量，不含任何原始行（隐私约束执行点）。 */
    public ProfileResponse profile(String traceId, ProfileRequest request) {
        long startedNanos = System.nanoTime();
        try {
            ProfileResponse response = profileClient.post()
                .uri("/profile")
                .header(TRACE_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ProfileResponse.class);
            log.info("数据画像完成, path={}, rows={}, 异常模式={}, costMillis={}",
                request.getDatasetPath(),
                response == null ? null : response.getRowCount(),
                response == null ? 0 : response.getAnomalyPatterns().size(),
                elapsedMillis(startedNanos));
            return response;
        }
        catch (RestClientException exception) {
            throw new BusinessException(BaseCode.TOOLS_SERVICE_ERROR,
                "数据画像失败，path=" + request.getDatasetPath(), exception);
        }
    }

    /**
     * 沙箱执行。异常语义：代码被静态检查拦下或运行时报错是 HTTP 200 +
     * {@code success=false} 的正常返回；只有网络不通、Python 服务挂了才抛
     * BusinessException。混淆这两者会让自修复逻辑失灵。
     */
    public ExecuteResponse execute(String traceId, ExecuteRequest request) {
        long startedNanos = System.nanoTime();
        try {
            ExecuteResponse response = executeClient.post()
                .uri("/execute")
                .header(TRACE_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ExecuteResponse.class);
            log.info("沙箱执行返回, taskId={}, success={}, blocked={}, costMillis={}",
                request.getTaskId(),
                response == null ? null : response.getSuccess(),
                response != null && response.isBlocked(),
                elapsedMillis(startedNanos));
            return response;
        }
        catch (RestClientException exception) {
            throw new BusinessException(BaseCode.TOOLS_SERVICE_ERROR,
                "沙箱调用失败，taskId=" + request.getTaskId(), exception);
        }
    }

    /**
     * 行级校验。阈值由调用方从知识库查出后放进 {@code request.rules}，Python 侧只做
     * 比对。传空 rules 会被 Python 拒绝。
     */
    public ValidateRowResponse validateRow(String traceId, ValidateRowRequest request) {
        try {
            return validateClient.post()
                .uri("/validate/row")
                .header(TRACE_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ValidateRowResponse.class);
        }
        catch (RestClientException exception) {
            throw new BusinessException(BaseCode.TOOLS_SERVICE_ERROR,
                "行级校验失败，path=" + request.getDatasetPath(), exception);
        }
    }

    /** 分布级校验。与画像共用同一批检测器，处理前后可直接比对。 */
    public ValidateDistributionResponse validateDistribution(
            String traceId, ValidateDistributionRequest request) {
        try {
            return validateClient.post()
                .uri("/validate/distribution")
                .header(TRACE_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ValidateDistributionResponse.class);
        }
        catch (RestClientException exception) {
            throw new BusinessException(BaseCode.TOOLS_SERVICE_ERROR,
                "分布级校验失败，path=" + request.getDatasetPath(), exception);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
