import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 为什么用 dev-server 代理，而不是在 Java 侧加 @CrossOrigin / CorsConfigurer：
 *
 * 后端一行不改，是这个前端模块的硬约束。加 CORS 配置意味着为了一个演示页面
 * 去动生产服务的安全边界，而代理只活在开发机上，跟着 vite 一起消失。
 *
 * ★ 本机有全局代理（HTTP_PROXY=http://127.0.0.1:15236），会把 localhost 请求
 *   变成空 503（见 CLAUDE.md）。vite 的 proxy 基于 node http，默认不读环境变量，
 *   所以这里不受影响；但如果哪天换了实现，先怀疑这一条。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:9090',
        changeOrigin: true,
        // 评测轮次是十几分钟的长请求，默认超时会在中途掐断
        timeout: 30 * 60 * 1000,
        proxyTimeout: 30 * 60 * 1000
      }
    }
  }
})
