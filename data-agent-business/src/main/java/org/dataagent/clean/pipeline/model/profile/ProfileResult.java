package org.dataagent.clean.pipeline.model.profile;

import org.dataagent.clean.toolsclient.model.ProfileResponse;

/**
 * ProfilerAgent 的产出：确定性画像 + 一段自然语言归纳。
 *
 * <p>两者分开而不是合并成一个字段，是因为它们的可信度完全不同：
 * {@code profile} 是可复现的事实，{@code narrative} 是模型生成的表达。
 * 下游只应该依赖前者做判断，后者只用于展示给医生。
 *
 * @param profile   确定性画像，链路上一切判断的依据
 * @param narrative 自然语言归纳；归纳失败时是空串（降级路径，日志里有 WARN）
 */
public record ProfileResult(ProfileResponse profile, String narrative) {
}
