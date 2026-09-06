/**
 * 后端返回形状：{ code, message, data, traceId }，code === "0" 为成功。判成败一律
 * 看 code，不看 data 是否为空。
 */

const SUCCESS = '0'

export class ApiError extends Error {
  constructor(message, { code, httpStatus, traceId } = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
    this.traceId = traceId
  }
}

async function request(path, { method = 'GET', body, signal } = {}) {
  let response
  try {
    response = await fetch(path, {
      method,
      signal,
      headers: body ? { 'Content-Type': 'application/json; charset=utf-8' } : undefined,
      body: body ? JSON.stringify(body) : undefined
    })
  }
  catch (error) {
    if (error.name === 'AbortError') throw error
    // 后端没起来时最常见的失败，单独说清楚
    throw new ApiError(`连不上后端（${path}）。确认 java -jar 已启动在 9090 端口。`, {})
  }

  const text = await response.text()
  let payload = null
  if (text) {
    try { payload = JSON.parse(text) }
    catch { /* 非 JSON 响应，下面按 HTTP 状态处理 */ }
  }

  if (!payload) {
    throw new ApiError(`HTTP ${response.status}：响应不是 JSON。${text.slice(0, 200)}`,
      { httpStatus: response.status })
  }
  if (payload.code !== SUCCESS) {
    throw new ApiError(payload.message || `业务失败，code=${payload.code}`,
      { code: payload.code, httpStatus: response.status, traceId: payload.traceId })
  }
  return payload.data
}

export const api = {
  // ── 自检 ──
  smokeAll: () => request('/api/smoke/all'),

  // ── 知识库（SQL 精确检索，无向量库）──
  knowledgeCount: () => request('/api/task/knowledge/count'),
  knowledgeProbe: (column, ruleType) =>
    request(`/api/task/knowledge/probe?column=${encodeURIComponent(column)}`
          + `&ruleType=${encodeURIComponent(ruleType)}`),

  // ── 处理任务 ──
  /** 完整链路耗时较长，不设超时，交给调用方用 AbortController 取消 */
  runTask: (requirement, datasetPath, signal) =>
    request('/api/task/run', { method: 'POST', body: { requirement, datasetPath }, signal }),
  taskTrace: (taskCode) => request(`/api/task/${encodeURIComponent(taskCode)}/trace`),

  // ── 数据集 / 用例 ──
  evalCases: () => request('/api/eval/cases'),
  fullDataset: ({ rows, patients, seed }, signal) =>
    request(`/api/eval/dataset/full?rows=${rows}&patients=${patients}&seed=${seed}`,
      { method: 'POST', signal }),

  // ── 评测 ──
  evalRuns: () => request('/api/eval/runs'),
  evalReport: (runCode) => request(`/api/eval/run/${encodeURIComponent(runCode)}`),
  failureCategories: () => request('/api/eval/failure-categories')
}
