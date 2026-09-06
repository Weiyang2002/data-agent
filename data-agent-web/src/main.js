import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import './styles/theme.css'

import Overview from './views/Overview.vue'
import Knowledge from './views/Knowledge.vue'
import TaskRun from './views/TaskRun.vue'
import TaskTrace from './views/TaskTrace.vue'
import EvalRuns from './views/EvalRuns.vue'
import EvalReport from './views/EvalReport.vue'

/** hash 路由：产物丢进 Spring static 目录时后端不需要配 forward 规则。 */
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: Overview, meta: { title: '总览' } },
    { path: '/knowledge', component: Knowledge, meta: { title: '知识库检索' } },
    { path: '/task', component: TaskRun, meta: { title: '处理任务' } },
    { path: '/trace/:taskCode', component: TaskTrace, props: true, meta: { title: '链路与 Token' } },
    { path: '/eval', component: EvalRuns, meta: { title: '评测序列' } },
    { path: '/eval/:runCode', component: EvalReport, props: true, meta: { title: '评测报告' } },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ],
  scrollBehavior: () => ({ top: 0 })
})

createApp(App).use(router).mount('#app')
