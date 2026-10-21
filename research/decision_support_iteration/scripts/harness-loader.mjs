/**
 * 模块解析钩子：把 SFC 的运行时依赖重定向到受控 stub。
 *
 * 为什么用 loader 而不是正则改写源码：
 *   正则改写 import 会改变源码结构，容易产生语法错误并掩盖真实行为；
 *   loader 只在**模块解析层**替换，组件源码一字不改。
 *
 * 缓存击穿：
 *   loader 跑在独立线程，**看不到**应用侧的 `globalThis`，所以不能靠全局变量传版本号。
 *   改为由 harness 在生成的组件里把 `?bust=N` 写进 stub 的 import 说明符，
 *   这里剥掉查询串后按路径查表，再把查询串带到解析结果上——每个场景得到独立模块实例。
 */
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const WEB = process.env.HARNESS_WEB_ROOT
const require = createRequire(path.join(WEB, 'package.json'))
const VUE_URL = pathToFileURL(require.resolve('vue')).href
const VUE_SERVER_URL = pathToFileURL(require.resolve('@vue/server-renderer')).href

/** 生成物在系统临时目录，解析不到 'vue' / 'vue/server-renderer'，这里统一改写。 */
const VUE_MAP = {
  vue: VUE_URL,
  'vue/server-renderer': VUE_SERVER_URL,
  '@vue/server-renderer': VUE_SERVER_URL,
}

/**
 * 生成物目录必须由两侧**各自独立算出同一个值**：
 * loader 跑在独立线程，主线程里 `process.env.X = ...` 不会传播过去。
 * 因此这里与 harness 一样用 `os.tmpdir()` 拼同一个固定名字。
 */
const GEN = path.join(os.tmpdir(), 'dsh-vue-harness')

const STUB_PATHS = {
  '@/api/watch': path.join(GEN, 'stub-api-watch.mjs'),
  '@/utils/security': path.join(GEN, 'stub-security.mjs'),
  'element-plus': path.join(GEN, 'stub-element-plus.mjs'),
  '@/components/WatchAccountPanel.vue': path.join(GEN, 'stub-component.mjs'),
}
const COMPONENT_STUB = STUB_PATHS['@/components/WatchAccountPanel.vue']

export function resolve(specifier, context, nextResolve) {
  const q = specifier.indexOf('?')
  const bare = q >= 0 ? specifier.slice(0, q) : specifier
  const query = q >= 0 ? specifier.slice(q) : ''

  if (VUE_MAP[bare]) return { url: VUE_MAP[bare] + query, shortCircuit: true }
  if (bare.endsWith('.vue')) {
    return { url: pathToFileURL(COMPONENT_STUB).href + query, shortCircuit: true }
  }
  if (STUB_PATHS[bare]) {
    return { url: pathToFileURL(STUB_PATHS[bare]).href + query, shortCircuit: true }
  }
  return nextResolve(specifier, context)
}
