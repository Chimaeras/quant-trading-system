import { ref } from "vue"

type ToastType = "success" | "error" | "warning" | "info"

export interface ToastItem {
  id: number
  type: ToastType
  message: string
}

const toasts = ref<ToastItem[]>([])
let seed = 1

function push(type: ToastType, message: string, duration = 2600) {
  const id = seed++
  toasts.value.push({ id, type, message })
  window.setTimeout(() => {
    remove(id)
  }, duration)
}

function remove(id: number) {
  toasts.value = toasts.value.filter((item) => item.id !== id)
}

export function useToast() {
  return {
    toasts,
    remove,
    success: (message: string, duration?: number) => push("success", message, duration),
    error: (message: string, duration?: number) => push("error", message, duration),
    warning: (message: string, duration?: number) => push("warning", message, duration),
    info: (message: string, duration?: number) => push("info", message, duration)
  }
}
