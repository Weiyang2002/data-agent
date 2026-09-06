package org.dataagent.clean.support;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * RestClient 构造工具。不同端点的耗时差异极大（{@code /health} 几毫秒，
 * {@code /execute} 可能几分钟），按端点分别构造带不同超时的实例。
 */
public final class RestClientFactorySupport {

    private RestClientFactorySupport() {
    }

    public static RestClient create(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        if (connectTimeoutMs > 0) {
            requestFactory.setConnectTimeout(connectTimeoutMs);
        }
        if (readTimeoutMs > 0) {
            requestFactory.setReadTimeout(readTimeoutMs);
        }

        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }
}
