/**
 * ECharts 全局主题与按需注册 —— 与设计令牌对齐（Codex 暗色蓝系）
 *
 * 仅引入项目实际使用的图表/组件/渲染器，替代全量 `import * as echarts from 'echarts'`。
 * 视图统一从此模块导入 `echarts` 命名空间与色常量。
 *
 * 注册 'quant-dark' 主题，所有图表 init 时传 'quant-dark' 即可获得统一配色。
 * A 股惯例：红涨绿跌（upColor=红、downColor=绿）—— 业务强制，保持不变。
 */
import * as echarts from 'echarts/core'
import { BarChart, LineChart, CandlestickChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  DataZoomComponent,
  LegendComponent,
  TitleComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart,
  LineChart,
  CandlestickChart,
  GridComponent,
  TooltipComponent,
  DataZoomComponent,
  LegendComponent,
  TitleComponent,
  CanvasRenderer
])

export { echarts }

const PALETTE = [
  '#60a5fa', // --chart-c1
  '#22d3ee', // --chart-c2
  '#fbbf24', // --chart-c3
  '#c084fc', // --chart-c4
  '#fb7185', // --chart-c5
  '#34d399'  // --chart-c6
]

const theme = {
  color: PALETTE,
  backgroundColor: 'transparent',
  textStyle: {
    color: '#94a3b8',
    fontFamily: '"Segoe UI", "SF Pro Text", -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  },
  title: {
    textStyle: { color: '#e5edf7', fontWeight: 700 },
    subtextStyle: { color: '#64748b' }
  },
  legend: {
    textStyle: { color: '#94a3b8' },
    inactiveColor: '#334155'
  },
  tooltip: {
    backgroundColor: 'rgba(15, 23, 42, 0.94)',
    borderColor: 'rgba(148, 163, 184, 0.26)',
    borderWidth: 1,
    textStyle: { color: '#e5edf7' },
    extraCssText: 'backdrop-filter: blur(8px); border-radius: 10px; box-shadow: 0 10px 30px rgba(0,0,0,0.45);'
  },
  grid: {
    left: 48,
    right: 24,
    top: 32,
    bottom: 40
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.26)' } },
    axisTick: { lineStyle: { color: 'rgba(148, 163, 184, 0.18)' } },
    axisLabel: { color: '#94a3b8' },
    splitLine: { show: false, lineStyle: { color: 'rgba(148, 163, 184, 0.08)' } }
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#94a3b8' },
    splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.08)' } }
  },
  candlestick: {
    itemStyle: {
      color: '#f23645',        // 阳线（涨）红
      color0: '#089981',       // 阴线（跌）绿
      borderColor: '#f23645',
      borderColor0: '#089981'
    }
  },
  line: {
    lineStyle: { width: 2 },
    symbolSize: 6,
    symbol: 'circle'
  },
  bar: {
    itemStyle: { borderRadius: [4, 4, 0, 0] }
  }
}

let registered = false

/** 注册全局 ECharts 主题（幂等） */
export function registerEChartsTheme(): void {
  if (registered) return
  echarts.registerTheme('quant-dark', theme)
  registered = true
}

/** init 时传入的主题名；若未注册则返回 undefined 走默认 */
export const CHART_THEME = 'quant-dark'

/** 图表色常量（供 setOption 内联使用，与主题调色板一致） */
export const CHART_C1 = '#60a5fa'
export const CHART_C2 = '#22d3ee'
export const CHART_C3 = '#fbbf24'
export const CHART_C4 = '#c084fc'
export const CHART_C5 = '#fb7185'
export const CHART_C6 = '#34d399'
export const CHART_UP = '#f23645'
export const CHART_DOWN = '#089981'

// 图表模块首次被某个懒加载页面引入时再注册，避免应用入口提前加载整套 ECharts。
registerEChartsTheme()
