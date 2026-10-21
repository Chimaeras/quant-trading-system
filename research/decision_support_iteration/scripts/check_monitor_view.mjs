/**
 * 校验 MonitorView.vue 的 SFC 结构与 `<script setup>` 的 TypeScript 语法。
 *
 * 为什么不跑完整前端构建：本轮改动只涉及单个 .vue 文件，
 * 全量 `vite build` 属高资源操作且会覆盖 dist。这里只做
 * 「模板/脚本能否被 Vue 官方编译器解析」这一最小必要校验。
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'
import path from 'node:path'

// 本脚本位于 research/ 下，而 vue / typescript 装在 modules/web/node_modules。
// 用 createRequire 以 web 根为锚点解析依赖，避免把脚本复制进 modules/web。
const here = path.dirname(fileURLToPath(import.meta.url))
const webRoot = path.resolve(here, '../../../modules/web')
const webRequire = createRequire(path.join(webRoot, 'package.json'))

async function importFromWeb(spec) {
  const resolved = webRequire.resolve(spec)
  return import(pathToFileURL(resolved).href)
}

const { parse, compileScript, compileTemplate } = await importFromWeb('vue/compiler-sfc')
const ts = (await importFromWeb('typescript')).default

/** 需要校验的 .vue 文件（本轮改动过的全部页面）。 */
const TARGETS = [
  'src/views/watch/MonitorView.vue',
  'src/views/market/T1Candidate.vue',
]

/** 解析单个 SFC，返回 { ok, descriptor, source }；失败时打印原因并置 ok=false。 */
function checkSfc(relPath) {
  const target = path.join(webRoot, relPath)
  const source = readFileSync(target, 'utf8')
  const { descriptor, errors } = parse(source, { filename: target })
  const label = `[${relPath}]`
  if (errors.length) {
    console.error(`${label} SFC parse errors:`)
    for (const e of errors) console.error('  -', e.message ?? e)
    return { ok: false }
  }
  console.log(`${label} SFC parse: OK`)

  const script = compileScript(descriptor, { id: relPath })
  console.log(`${label} compileScript: OK (bindings:`, Object.keys(script.bindings ?? {}).length, ')')

  const sf = ts.createSourceFile(relPath + '.ts', script.content, ts.ScriptTarget.ESNext, true, ts.ScriptKind.TS)
  const diags = sf.parseDiagnostics ?? []
  if (diags.length) {
    console.error(`${label} TypeScript syntax errors:`)
    for (const d of diags) {
      const pos = sf.getLineAndCharacterOfPosition(d.start ?? 0)
      console.error(`  - line ${pos.line + 1}: ${ts.flattenDiagnosticMessageText(d.messageText, '\n')}`)
    }
    return { ok: false }
  }
  console.log(`${label} TypeScript syntax: OK`)

  if (descriptor.template) {
    const t = compileTemplate({ source: descriptor.template.content, filename: target, id: relPath })
    if (t.errors.length) {
      console.error(`${label} Template compile errors:`)
      for (const e of t.errors) console.error('  -', e.message ?? e)
      return { ok: false }
    }
    console.log(`${label} Template compile: OK`)
  }
  return { ok: true, descriptor, source }
}

const results = new Map()
let allOk = true
for (const rel of TARGETS) {
  const r = checkSfc(rel)
  results.set(rel, r)
  if (!r.ok) allOk = false
}
if (!allOk) process.exit(1)

const monitor = results.get('src/views/watch/MonitorView.vue')
const t1 = results.get('src/views/market/T1Candidate.vue')
const { descriptor, source } = monitor

if (descriptor.template) {
  // 已在上方逐个编译，这里无需重复
}

// 关键回归断言：D-2 要求这三个字段确实出现在模板里
const template = descriptor.template?.content ?? ''
const mustHave = ['signalStatus', 'holdingHorizon', 'invalidationConds', 'signalStatusMeta']
const missing = mustHave.filter((k) => !template.includes(k))
if (missing.length) {
  console.error('模板缺少 D-2 必需字段:', missing)
  process.exit(1)
}
console.log('D-2 template bindings present:', mustHave.join(', '))

// 关键回归断言：三类状态必须有**不同**的中文标签，否则「无法区分状态」的缺陷会复发
const scriptSrc = descriptor.scriptSetup?.content ?? ''
const labelAssertions = [
  ["case 'OBSERVE':", '观察中'],
  ["case 'WARMUP':", '预热不足'],
  ["case 'INVALIDATED':", '已失效'],
]
const labelProblems = []
if (!scriptSrc.includes('function signalStatusMeta')) {
  labelProblems.push('缺少 signalStatusMeta 定义')
}
for (const [caseLine, label] of labelAssertions) {
  if (!scriptSrc.includes(caseLine)) labelProblems.push(`缺少分支 ${caseLine}`)
  if (!scriptSrc.includes(label)) labelProblems.push(`缺少标签「${label}」`)
}
if (labelProblems.length) {
  console.error('signalStatusMeta 校验失败:', labelProblems)
  process.exit(1)
}
console.log('signalStatusMeta 三态标签: OK')

// D-4 回归断言：不得再出现「未扫描全市场」这种不含数值的误导性占位，
// 且必须按 coverage_scope 区分「口径不符」与「全市场池构建失败」。
// 注意：只检查**可执行代码**（剥掉注释），否则解释缺陷的注释本身会被误判。
const codeOnly = source
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
const forbidden = ['未扫描全市场']
const d4Problems = []
for (const s of forbidden) {
  if (codeOnly.includes(s)) d4Problems.push(`代码中仍存在误导性占位文案「${s}」`)
}
for (const s of ['market_wide_coverage_na_reason', 'UNIVERSE_NOT_FULL_MARKET',
                 'FULL_UNIVERSE_UNAVAILABLE', 'marketWideCoverageHint']) {
  if (!source.includes(s)) d4Problems.push(`缺少 D-4 字段/分支 ${s}`)
}
if (d4Problems.length) {
  console.error('D-4 校验失败:', d4Problems)
  process.exit(1)
}
console.log('D-4 覆盖口径文案: OK')

// P0-3 回归断言：T1Candidate.vue 不得再消费恒为 false 的 data_date_fallback，
// 且必须按 calendar_trading_day 区分「非交易日」与「日历未覆盖」
const t1Source = t1?.source ?? ''
const t1CodeOnly = t1Source
  .replace(/<!--[\s\S]*?-->/g, '') // 模板 HTML 注释
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
const p03Problems = []
if (t1CodeOnly.includes('data_date_fallback')) {
  p03Problems.push('T1Candidate.vue 代码仍在消费已删除的 data_date_fallback')
}
for (const s of ['calendar_trading_day', 'NOT_A_TRADING_DAY']) {
  if (!t1Source.includes(s)) p03Problems.push(`T1Candidate.vue 缺少 P0-3 分支 ${s}`)
}
if (p03Problems.length) {
  console.error('P0-3 校验失败:', p03Problems)
  process.exit(1)
}
console.log('P0-3 数据日口径文案: OK')

console.log('ALL CHECKS PASSED')
