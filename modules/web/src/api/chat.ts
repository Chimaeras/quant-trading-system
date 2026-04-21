import request from "./request"

export interface ChatCompletionPayload {
  sessionId?: string
  message: string
  model?: string
  temperature?: number
  maxTokens?: number
}

export interface ChatCompletionResult {
  sessionId: string
  model: string
  answer: string
  finishReason?: string
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
}

interface StreamHandlers {
  onDelta?: (delta: string) => void
  onDone?: (answer: string) => void
  onError?: (message: string) => void
}

export const chatApi = {
  async chatCompletion(payload: ChatCompletionPayload): Promise<ChatCompletionResult> {
    const res = await request.post<{ data: ChatCompletionResult }>("/chat/completions", payload)
    return (res as { data?: ChatCompletionResult }).data as ChatCompletionResult
  },

  chatStream(payload: ChatCompletionPayload, handlers: StreamHandlers) {
    const controller = new AbortController()
    const params = new URLSearchParams({
      message: payload.message
    })
    if (payload.sessionId) params.set("sessionId", payload.sessionId)
    if (payload.model) params.set("model", payload.model)
    if (payload.temperature != null) params.set("temperature", String(payload.temperature))
    if (payload.maxTokens != null) params.set("maxTokens", String(payload.maxTokens))
    const token = localStorage.getItem("token")

    fetch(`/api/chat/stream?${params.toString()}`, {
      method: "GET",
      signal: controller.signal,
      headers: token ? { Authorization: `Bearer ${token}` } : undefined
    })
      .then(async (response) => {
        if (!response.ok || !response.body) {
          throw new Error(`流式请求失败(${response.status})`)
        }
        const reader = response.body.getReader()
        const decoder = new TextDecoder("utf-8")
        let buffer = ""
        while (true) {
          const { value, done } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          const chunks = buffer.split("\n\n")
          buffer = chunks.pop() || ""
          for (const chunk of chunks) {
            const eventLine = chunk.split("\n").find((line) => line.startsWith("event:"))
            const dataLine = chunk.split("\n").find((line) => line.startsWith("data:"))
            if (!dataLine) continue
            const event = eventLine?.slice(6).trim() || ""
            const raw = dataLine.slice(5).trim()
            let parsed: any = null
            try {
              parsed = JSON.parse(raw)
            } catch {
              parsed = { delta: raw, answer: raw, error: raw }
            }
            if (event === "delta") handlers.onDelta?.(parsed.delta || "")
            if (event === "done") handlers.onDone?.(parsed.answer || "")
            if (event === "error") handlers.onError?.(parsed.error || "流式请求失败")
          }
        }
      })
      .catch((error) => {
        if (error?.name === "AbortError") return
        handlers.onError?.(error?.message || "流式请求失败")
      })

    return () => controller.abort()
  }
}
