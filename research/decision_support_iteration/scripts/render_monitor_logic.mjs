/**
 * MonitorView 逻辑级渲染验证（不构建前端、不启动浏览器）。
 *
 * 目的
 * ----
 * 第 4/5/6 轮只验证到「模板里有这些绑定 + 静态文案」。真正的渲染风险在于
 * **函数装配**：
 *   后端 metrics / signal → recommendRows / marketWideCoverage
 *                        → signalStatusMeta → 模板
 *
 * 手段
 * ----
 *  · 用真实 `@vue/compiler-sfc` 编译 SFC（inlineTemplate:false）；
 *  · 用 Node 的 module-resolution hook（`harness-loader.mjs`）把
 *    `@/api/watch` / `@/utils/security` / `element-plus` / `*.vue` 换成受控 stub，
 *    并把 `vue` 指向**同一份**真实运行时（保证 reactivity 一致）；
 *  · 调用组件真实 `setup()`，再直接断言其 computed 的**实际返回值**。
 *    这些绑定就是浏览器渲染时模板读到的那一批。
 *
 * 未覆盖：真实 DOM 布局、CSS、el-table 虚拟滚动。
 * 因此结论必须表述为「逻辑装配已验证」，**不是**「界面已目视确认」。
 */
import { createRequire } from 'node:module'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const WEB = path.resolve(HERE, '../../../modules/web')
const require = createRequire(path.join(WEB, 'package.json'))
const sfc = require('@vue/compiler-sfc')
const vue = await import(pathToFileURL(require.resolve('vue')).href)
const vueServer = await import(
  pathToFileURL(require.resolve('@vue/server-renderer')).href
)

const TARGET = process.env.HARNESS_TARGET || 'src/views/watch/MonitorView.vue'
// 生成物放系统临时目录：不污染仓库，也无需改动 .gitignore。
// 注意：目录名必须与 harness-loader.mjs 里独立算出的 GEN 一致（loader 在独立线程，
// 主线程的 process.env 修改传不过去）。
const OUTDIR = path.join(os.tmpdir(), 'dsh-vue-harness')
fs.rmSync(OUTDIR, { recursive: true, force: true })
fs.mkdirSync(OUTDIR, { recursive: true })
const VUE_URL = pathToFileURL(require.resolve('vue')).href
const BASE_COMPONENT = path.join(OUTDIR, 'component-base.mjs')

// ---------------------------------------------------------------- stubs
fs.writeFileSync(path.join(OUTDIR, 'stub-security.mjs'),
  'export const inferExchangeType = () => "SH";\n' +
  'export const resolveSecurityName = () => "";\n')

fs.writeFileSync(path.join(OUTDIR, 'stub-element-plus.mjs'),
  'const noop = () => {};\n' +
  'noop.close = noop; noop.success = noop; noop.error = noop; noop.warning = noop;\n' +
  'export const ElMessage = noop;\n' +
  'ElMessage.success = noop; ElMessage.error = noop; ElMessage.warning = noop; ElMessage.info = noop;\n' +
  'export const ElMessageBox = { confirm: () => Promise.resolve(true) };\n' +
  'export default { install() {} };\n')

fs.writeFileSync(path.join(OUTDIR, 'stub-component.mjs'),
  'import { defineComponent, h } from "vue";\n' +
  'export default defineComponent({ name: "Stub", setup: (_, { slots }) => () => h("div", slots.default ? slots.default() : []) });\n')

// fetchEvents 按 opts.type 分流：SIGNAL → advice；SUBMIT → []；其余 → events
fs.writeFileSync(path.join(OUTDIR, 'stub-api-watch.mjs'),
  'const F = globalThis.__HARNESS_FIXTURES__;\n' +
  'const res = (v) => () => Promise.resolve(v);\n' +
  'const log = (...a) => { if (globalThis.__HARNESS_DEBUG__) console.log("[stub]", ...a); };\n' +
  'export const fetchDays = () => { log("fetchDays"); return Promise.resolve(F.days); };\n' +
  'export const fetchState = res(F.state);\n' +
  'export const fetchMarket = res(F.market);\n' +
  'export const fetchTicks = res({ date: "2026-08-18", ticks: [] });\n' +
  'export const fetchWatchPool = res(F.pool);\n' +
  'export const addWatchPoolMember = res({});\n' +
  'export const removeWatchPoolMember = res({});\n' +
  'export function fetchEvents(_date, _cursor, opts) {\n' +
  '  const t = String((opts && opts.type) || "");\n' +
  '  log("fetchEvents", t || "(all)");\n' +
  '  if (t.includes("SIGNAL")) return Promise.resolve({ events: F.advice });\n' +
  '  if (t.includes("SUBMIT")) return Promise.resolve({ events: [] });\n' +
  '  return Promise.resolve({ events: F.events });\n' +
  '}\n')

// ---------------------------------------------------------------- compile
/**
 * 擦除 TypeScript 语法。
 * `compileScript({isProd:false})` 会保留 `import type {...}` / `type X = ...` 等
 * 类型语法，而 Node 无法解析。这里用仓库自带的 TypeScript 编译器做**纯语法擦除**
 * （module: ESNext 保持 ESM 不变），不改动任何运行时语义。
 */
const ts = require('typescript')

function eraseTypes(code) {
  const out = ts.transpileModule(code, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      isolatedModules: true,
      verbatimModuleSyntax: false,
    },
    fileName: 'component.ts',
    reportDiagnostics: false,
  })
  return out.outputText
}

function compileComponent(sourceFile) {
  const src = fs.readFileSync(sourceFile, 'utf8')
  const { descriptor, errors } = sfc.parse(src, { filename: sourceFile })
  if (errors.length) throw new Error(`SFC 解析失败: ${errors[0].message}`)
  const compiled = sfc.compileScript(descriptor, {
    id: 'harness', inlineTemplate: false, isProd: false,
  })
  const out = BASE_COMPONENT
  fs.writeFileSync(out, injectHookCapture(eraseTypes(compiled.content)), 'utf8')

  // 模板编译成 SSR 渲染函数，拼成完整组件才能真正挂载并渲染。
  const tpl = sfc.compileTemplate({
    source: descriptor.template.content,
    filename: sourceFile,
    id: 'harness',
    ssr: true,
    compilerOptions: { mode: 'module', module: 'esnext' },
  })
  if (tpl.errors.length) throw new Error(`模板编译失败: ${tpl.errors[0].message || tpl.errors[0]}`)
  fs.writeFileSync(path.join(OUTDIR, 'template.mjs'), eraseTypes(tpl.code), 'utf8')
  return out
}

const compiledPath = compileComponent(path.join(WEB, TARGET))
const TEMPLATE_URL = pathToFileURL(path.join(OUTDIR, 'template.mjs')).href

let failures = 0
function check(label, cond, detail = '') {
  const ok = Boolean(cond)
  if (!ok) failures += 1
  console.log(`${ok ? 'OK  ' : 'FAIL'}  ${label}${detail ? `  -> ${detail}` : ''}`)
}

/**
 * 组件挂载后会启动 `setInterval(refresh, 5000)`（真实行为）。
 * 进程退出前该定时器可能触发一次 refresh，而它依赖浏览器 DOM。
 * 这里提供最小 document 桩，避免脚本以非零码退出（不影响被测逻辑）。
 */
if (typeof globalThis.document === 'undefined') {
  globalThis.document = {
    querySelector: () => null,
    querySelectorAll: () => [],
    createElement: () => ({ style: {}, setAttribute() {}, appendChild() {} }),
    addEventListener() {}, removeEventListener() {},
    body: { appendChild() {}, removeChild() {} },
    documentElement: { style: {} },
  }
}
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
}
if (typeof globalThis.window === 'undefined') {
  // 组件的定时刷新走 window.setTimeout / setInterval。用 **unref 过的**计时器，
  // 否则组件的 5s 轮询会让 node 进程无法退出（脚本会挂住）。
  const unref = (h) => { if (h && typeof h.unref === 'function') h.unref(); return h }
  globalThis.window = {
    setTimeout: (fn, ms) => unref(setTimeout(fn, ms)),
    clearTimeout: (h) => clearTimeout(h),
    setInterval: (fn, ms) => unref(setInterval(fn, ms)),
    clearInterval: (h) => clearInterval(h),
    addEventListener() {}, removeEventListener() {},
    matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
  }
}

/**
 * 注入 fixture → 载入编译产物 → 调用真实 setup() → 执行其注册的 onMounted
 * → 等待 refresh() 落地。
 *
 * 为什么需要注入 `onMounted`：
 *   没有活动组件实例时 `onMounted` 只发警告且不注册，`refresh()` 永不执行，
 *   所有绑定都是空值（本 harness 第一版即如此）。`vue` 是只读模块命名空间，
 *   无法替换其导出；因此把编译产物里的 vue 具名导入**改写为本地包装函数**，
 *   让真正被调用的 `onMounted` 把回调交给 harness 手动执行。
 *   组件源码一字未改。
 */
/**
 * 把 `import { a, b } from 'vue'` 改写为从绝对路径导入 + 本地包装 onMounted。
 * onMounted 在**调用时**读取全局捕获钩子，因此 capture 数组可以随时切换。
 */
function injectHookCapture(code) {
  let importLine = ''
  // 组件编译产物可能有多条来自 'vue' 的 import（编译器自注入 + 源码 import），
  // 必须全部改写，否则源码那条仍会拿到真正的 onMounted。
  const stripped = code.replace(
    /^import\s*\{([^}]*)\}\s*from\s*['"]vue['"];?$/gm,
    (_m, names) => {
      const list = names.split(',').map((s) => s.trim()).filter(Boolean)
      for (const n of list) {
        const [orig, alias] = n.split(/\s+as\s+/).map((x) => x.trim())
        if (orig === 'onMounted') continue
        importLine += importLine ? ', ' : ''
        importLine += orig === (alias || orig) ? orig : `${orig} as ${alias || orig}`
      }
      return ''
    },
  )
  const wrapper = `
const __harnessOnMounted = (fn) => {
  const sink = globalThis.__VUE_HARNESS_ON_MOUNTED__
  if (sink) sink(fn)
  return fn
};
const onMounted = __harnessOnMounted;`
  return `import { ${importLine} } from ${JSON.stringify(VUE_URL)};\n`
    + wrapper + '\n' + stripped
}

/** 每个场景一份独立模块：把 ?bust=N 写进 stub 说明符，击穿 ESM 模块缓存。 */
async function mount(fixtures) {
  globalThis.__HARNESS_FIXTURES__ = fixtures
  const bust = (globalThis.__HARNESS_BUST__ = (globalThis.__HARNESS_BUST__ || 0) + 1)

  // 每个场景一份独立模块：把 ?bust=N 写进 stub 说明符，击穿 ESM 模块缓存。
  const base = fs.readFileSync(BASE_COMPONENT, 'utf8')
  const busted = base
    .replace(/(['"])(@\/[^'"?]+|element-plus)\1/g, (_m, q, spec) => `${q}${spec}?bust=${bust}${q}`)
  const perScenario = path.join(OUTDIR, `component-${bust}.mjs`)
  fs.writeFileSync(perScenario, busted, 'utf8')

  const mod = await import(pathToFileURL(perScenario).href)
  const comp = mod.default

  const captured = []
  globalThis.__VUE_HARNESS_ON_MOUNTED__ = (fn) => captured.push(fn)
  let state
  try {
    state = comp.setup({}, { expose() {} }) || {}
  } finally {
    globalThis.__VUE_HARNESS_ON_MOUNTED__ = null
  }
  for (const fn of captured) fn()
  for (let i = 0; i < 80; i += 1) await Promise.resolve()

  // 供 SSR 外壳渲染用的布局辅助绑定（浏览器里由 ResizeObserver 回填）
  state.hostHeights = { recommend: 200, pool: 320, events: 320, submits: 180 }
  state.__html = await renderToHtml(state)

  if (process.env.HARNESS_DEBUG) {
    console.log('[harness] captured onMounted =', captured.length)
    console.log('[harness] selectedDate =', JSON.stringify(unref(state.selectedDate)))
    console.log('[harness] events.len   =', unref(state.events)?.length)
    console.log('[harness] advice.len   =', unref(state.adviceEvents)?.length)
    console.log('[harness] lastCycle    =', unref(state.lastCycle) ? 'yes' : 'null')
    console.log('[harness] rows[0].status =',
      unref(state.recommendRows)?.[0]?.signalStatusLabel ?? '<no row>')
    console.log('[harness] html.len     =', state.__html.length)
  }
  return state
}

/** 用真实 setup 绑定 + 真实编译模板做一次 SSR 渲染，得到可断言的 HTML。 */
async function renderToHtml(state) {
  const setupUrl = pathToFileURL(compiledPath).href
  const code = `
import _sfc_main from ${JSON.stringify(setupUrl)}
import { ssrRender } from ${JSON.stringify(TEMPLATE_URL)}
const _provided = globalThis.__HARNESS_STATE__
const wrapped = {
  __name: 'HarnessShell',
  setup() {
    _sfc_main.ssrRender = ssrRender
    return _provided
  },
  ssrRender
}
export default wrapped
`
  const fullPath = path.join(OUTDIR, 'full-component.mjs')
  fs.writeFileSync(fullPath, code, 'utf8')
  globalThis.__HARNESS_STATE__ = state
  const m = await import(pathToFileURL(fullPath).href + `?v=${Math.random()}`)
  const app = vue.createSSRApp(m.default)
  app.config.warnHandler = () => {}
  try {
    return await vueServer.renderToString(app)
  } catch (exc) {
    return `<render-error>${exc && exc.message}</render-error>`
  }
}

function unref(v) {
  return v && typeof v === 'object' && 'value' in v ? v.value : v
}

function ev(seq, type, code, detail, cycle = 1) {
  return {
    seq, type, code, name: '', cycle,
    ts: '2026-08-18T10:05:00+08:00', date: '2026-08-18', detail,
  }
}

const CYCLE_BASE = {
  seq: 99, type: 'CYCLE', code: '', name: '', cycle: 7,
  ts: '2026-08-18T10:05:00+08:00', date: '2026-08-18',
  detail: { phase: 'end', status: 'OK', live: true, metrics: {} },
}
function cycle(metrics) {
  const c = JSON.parse(JSON.stringify(CYCLE_BASE))
  c.detail.metrics = metrics
  return c
}

const ADVICE = [
  ev(10, 'SIGNAL', '600519', {
    action: 'buy', strategy: 'intraday_rules_v1',
    strategy_version: 'intraday_rules_v1:abc123',
    bar_ts: '2026-08-18T10:00:00+08:00',
    valid_until: '2099-01-01T15:00:00+08:00',
    signal_status: 'OBSERVE', holding_horizon: 'INTRADAY_EOD',
    quality_status: 'VALID',
    invalidation_conditions: ['下一根 bar 收盘', '行情过期或缺失', '当日收盘'],
    reason_codes: ['MA_CROSS'],
  }),
  ev(11, 'SIZING', '600519', { final_qty: 100, last: 10.5 }, 1),
  ev(12, 'RISK_GATE', '600519', { result: 'PASS', qty: 100, limit_price: 10.5 }, 1),
]

const BASE = {
  days: { days: [{ date: '2026-08-18', event_count: 5 }] },
  state: { date: '2026-08-18' },
  market: { batch: { coverage: 0.99, received_at: '2026-08-18T10:05:00+08:00' }, quotes: [], active_pool: [] },
  pool: { available: true, items: [{ code: '600519', name: '贵州茅台' }] },
}

function fx(metrics, advice = ADVICE) {
  return { ...BASE, advice, events: [...advice, cycle(metrics)] }
}

const val = (s, k) => (s[k] && typeof s[k] === 'object' && 'value' in s[k] ? s[k].value : s[k])

async function main() {
  // ---- A: OBSERVE + 全市场覆盖 ----
  const A = await mount(fx({
    universe_source: 'full_market', universe_count: 5462,
    coverage_scope: 'FULL_MARKET', market_wide_coverage: 0.9983,
    market_wide_coverage_na_reason: null, quote_coverage: 0.9983,
  }))
  const rowsA = val(A, 'recommendRows')
  check('A: SIGNAL(buy) 被解析为 1 行建议', Array.isArray(rowsA) && rowsA.length === 1,
    `len=${rowsA && rowsA.length}`)
  if (rowsA && rowsA.length) {
    const r = rowsA[0]
    check('A: signal_status=OBSERVE → 「观察中」', r.signalStatusLabel === '观察中', r.signalStatusLabel)
    check('A: 持有期口径 = INTRADAY_EOD', r.holdingHorizon === 'INTRADAY_EOD', r.holdingHorizon)
    check('A: 三条失效条件均渲染',
      r.invalidationConds.includes('当日收盘') && r.invalidationConds.includes('行情过期或缺失'),
      r.invalidationConds)
  }
  check('A: 全市场覆盖 → 百分比', val(A, 'marketWideCoverage') === '99.83%',
    val(A, 'marketWideCoverage'))
  check('A: 覆盖提示 = 口径=全市场', val(A, 'marketWideCoverageHint') === '口径=全市场',
    val(A, 'marketWideCoverageHint'))

  // ---- B: 池内模式 ----
  const B = await mount(fx({
    universe_source: 'watch_pool', universe_count: 100,
    coverage_scope: 'OBSERVATION_POOL', market_wide_coverage: null,
    market_wide_coverage_na_reason: 'UNIVERSE_NOT_FULL_MARKET', quote_coverage: 0.97,
  }))
  check('B: 池内模式 → 「不适用（池内模式）」',
    val(B, 'marketWideCoverage') === '不适用（池内模式）', val(B, 'marketWideCoverage'))
  check('B: 提示给出池内真实覆盖率 97.00%',
    String(val(B, 'marketWideCoverageHint')).includes('97.00%'), val(B, 'marketWideCoverageHint'))

  // ---- C: 全市场池构建失败 ----
  const C = await mount(fx({
    universe_source: 'full_market', universe_count: 0,
    coverage_scope: 'FULL_MARKET', market_wide_coverage: null,
    market_wide_coverage_na_reason: 'FULL_UNIVERSE_UNAVAILABLE', quote_coverage: null,
  }))
  check('C: 取数失败 → 「不可用（全市场池构建失败）」',
    val(C, 'marketWideCoverage') === '不可用（全市场池构建失败）', val(C, 'marketWideCoverage'))
  check('C: 取数失败不伪装成池内模式',
    val(C, 'marketWideCoverage') !== '不适用（池内模式）')

  // ---- D: 旧快照缺字段 ----
  const D = await mount(fx({ universe_source: 'watch_pool', universe_count: 100 }))
  check('D: 缺字段 → 「不适用（口径未标注）」',
    val(D, 'marketWideCoverage') === '不适用（口径未标注）', val(D, 'marketWideCoverage'))

  // ---- E: 三类信号状态可区分 ----
  for (const [status, label] of [['WARMUP', '预热不足'], ['INVALIDATED', '已失效'],
    ['OBSERVE', '观察中'], ['', '观察中']]) {
    const advice = [ev(20, 'SIGNAL', '600519', {
      action: 'buy', strategy_version: 'v1', bar_ts: '2026-08-18T10:00:00+08:00',
      valid_until: '2099-01-01T15:00:00+08:00', quality_status: 'VALID',
      signal_status: status, holding_horizon: 'UNVALIDATED', invalidation_conditions: [],
    })]
    const S = await mount(fx({ universe_source: 't1', universe_count: 100 }, advice))
    const rows = val(S, 'recommendRows')
    const got = rows && rows.length ? rows[0].signalStatusLabel : '<无行>'
    check(`E: signal_status=${status || '(空)'} -> ${label}`, got === label, got)
  }

  // ---- F: 不可执行信号必须给出口径说明 ----
  const adviceF = [ev(30, 'SIGNAL', '600519', {
    action: 'buy', strategy_version: 'v1', bar_ts: '2026-08-18T10:00:00+08:00',
    valid_until: '2099-01-01T15:00:00+08:00', quality_status: 'VALID',
    signal_status: 'WARMUP', holding_horizon: 'UNVALIDATED', invalidation_conditions: [],
  })]
  const F = await mount(fx({ universe_source: 't1', universe_count: 100 }, adviceF))
  const rowF = val(F, 'recommendRows')[0]
  check('F: WARMUP 无失效条件时给出口径说明',
    Boolean(rowF.invalidationConds) && rowF.invalidationConds !== '—', rowF.invalidationConds)
  check('F: WARMUP 持有期口径 = UNVALIDATED', rowF.holdingHorizon === 'UNVALIDATED',
    rowF.holdingHorizon)

  console.log()
  if (failures) {
    console.log(`RESULT: ${failures} 项断言失败`)
    process.exitCode = 1
    return
  }
  console.log('RESULT: ALL LOGIC ASSERTIONS PASSED（逻辑装配已验证，非界面目视确认）')
}

await main()
