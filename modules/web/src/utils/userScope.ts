import { clearSecurityNameCache } from './security'

/** 研究任务页自动轮询的 task_id。 */
export const TRACKED_TASK_KEY = 'quant_tracked_task'
/** 选股页上次查看的快照标识。 */
export const FOCUS_SNAPSHOT_KEY = 'quant_stock_picker_focus_snapshot'

/**
 * 与登录用户绑定、必须跨会话隔离的本地键。
 * 登出只清 token 时这些键会保留，下一个登录者会继承上一用户的 task_id / 快照。
 */
export const USER_SCOPED_STORAGE_KEYS: readonly string[] = [TRACKED_TASK_KEY, FOCUS_SNAPSHOT_KEY]

/**
 * 清理用户级本地状态：localStorage 键 + 模块级缓存。
 * Pinia store 需由调用方（持有 active pinia 的组件）另行 reset。
 */
export function clearUserScopedState(): void {
  for (const key of USER_SCOPED_STORAGE_KEYS) {
    localStorage.removeItem(key)
  }
  clearSecurityNameCache()
}
