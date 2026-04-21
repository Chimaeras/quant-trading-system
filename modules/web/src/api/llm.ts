import request from "./request"

export interface LlmConfig {
  baseUrl: string
  apiKey?: string
  hasApiKey?: boolean
  model: string
  temperature: number
  maxTokens: number
  enableStream: boolean
}

export const llmApi = {
  async getConfig(): Promise<LlmConfig> {
    const res = await request.get<{ data: LlmConfig }>("/llm/config")
    return (res as { data?: LlmConfig }).data as LlmConfig
  },

  async saveConfig(payload: Partial<LlmConfig>) {
    const res = await request.post<{ data: LlmConfig }>("/llm/config", payload)
    return (res as { data?: LlmConfig }).data as LlmConfig
  }
}
