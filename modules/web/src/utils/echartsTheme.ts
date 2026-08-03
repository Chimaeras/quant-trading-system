/**
 * ECharts 全局主题 —— 与设计令牌对齐
 *
 * 注册 'quant-dark' 主题，所有图表 init 时传 'quant-dark' 即可获得统一配色。
 * A 股惯例：红涨绿跌（upColor=红、downColor=绿）。
 */
import * as echarts from 'echarts'

const PALETTE = [
  '#58b9ff', // --chart-c1
  '#1cd6a6', // --chart-c2
  '#f0b95a', // --chart-c3
  '#c084fc', // --chart-c4
  '#ff6b7a', // --chart-c5
  '#38bdf8'  // --chart-c6
]

const theme = {
  color: PALETTE,
  backgroundColor: 'transparent',
  textStyle: {
    color: '#9db1cc',
    fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
  },
  title: {
    textStyle: { color: '#e9f1ff', fontWeight: 600 },
    subtextStyle: { color: '#6f819a' }
  },
  legend: {
    textStyle: { color: '#9db1cc' },
    inactiveColor: '#3a4a63'
  },
  tooltip: {
    backgroundColor: 'rgba(13, 22, 37, 0.92)',
    borderColor: 'rgba(145, 196, 255, 0.34)',
    borderWidth: 1,
    textStyle: { color: '#e9f1ff' },
    extraCssText: 'backdrop-filter: blur(8px); border-radius: 8px; box-shadow: 0 8px 24px rgba(0,0,0,0.4);'
  },
  grid: {
    left: 48,
    right: 24,
    top: 32,
    bottom: 40
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: 'rgba(145, 196, 255, 0.22)' } },
    axisTick: { lineStyle: { color: 'rgba(145, 196, 255, 0.16)' } },
    axisLabel: { color: '#9db1cc' },
    splitLine: { show: false, lineStyle: { color: 'rgba(145, 196, 255, 0.08)' } }
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#9db1cc' },
    splitLine: { lineStyle: { color: 'rgba(145, 196, 255, 0.08)' } }
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
export const CHART_C1 = '#58b9ff'
export const CHART_C2 = '#1cd6a6'
export const CHART_C3 = '#f0b95a'
export const CHART_C4 = '#c084fc'
export const CHART_C5 = '#ff6b7a'
export const CHART_C6 = '#38bdf8'
export const CHART_UP = '#f23645'
export const CHART_DOWN = '#089981'
