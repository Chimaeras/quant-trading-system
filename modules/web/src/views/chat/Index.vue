<template>
  <div class="page-wrap chat-page">
    <div class="chat-grid">
      <Panel title="会话列表" subtitle="今日 / 历史">
        <UiInput v-model="keyword" placeholder="搜索会话..." />
        <div class="session-list">
          <button
            v-for="session in filteredSessions"
            :key="session.id"
            class="session-item"
            :class="{ active: currentSessionId === session.id }"
            @click="currentSessionId = session.id"
          >
            <div>
              <p>{{ session.title }}</p>
              <small>{{ session.time }}</small>
            </div>
            <StatusChip :status="session.status" />
          </button>
        </div>
      </Panel>

      <Panel title="AI 聊天主区" subtitle="富文本 + 附件 + 工具调用">
        <div class="chat-toolbar">
          <StatusChip :status="sending ? 'running' : 'idle'" :label="sending ? '生成中' : `模型: ${model}`" />
          <UiSelect v-model="sendMode" :options="modeOptions" />
        </div>
        <div class="message-list">
          <article v-for="msg in currentMessages" :key="msg.id" class="message-item" :class="msg.role">
            <p>{{ msg.content }}</p>
            <div v-if="msg.attachments?.length" class="attachments">
              <Badge v-for="f in msg.attachments" :key="f">{{ f }}</Badge>
            </div>
          </article>
        </div>
        <div class="tool-list">
          <strong>工具调用</strong>
          <ul>
            <li v-for="tool in toolCalls" :key="tool.id">{{ tool.name }} · {{ tool.duration }}</li>
          </ul>
        </div>
        <div class="send-box">
          <textarea v-model="draft" placeholder="输入消息（支持多行）..." />
          <div class="send-actions">
            <UiButton variant="ghost">添加附件</UiButton>
            <UiButton v-if="sending && sendMode === 'stream'" variant="danger" @click="stopStream">停止</UiButton>
            <UiButton variant="primary" :loading="sending" @click="sendMessage">发送</UiButton>
          </div>
        </div>
      </Panel>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue"
import Panel from "@/components/ui/Panel.vue"
import UiInput from "@/components/ui/Input.vue"
import UiButton from "@/components/ui/Button.vue"
import Badge from "@/components/ui/Badge.vue"
import StatusChip from "@/components/ui/StatusChip.vue"
import UiSelect from "@/components/ui/Select.vue"
import { chatApi } from "@/api/chat"
import { llmApi } from "@/api/llm"
import { useToast } from "@/composables/useToast"

type SessionStatus = "success" | "running" | "failed" | "warning" | "idle"
interface ChatSession {
  id: string
  title: string
  time: string
  status: SessionStatus
}
interface ChatMsg {
  id: string
  role: "assistant" | "user"
  content: string
  attachments?: string[]
}

const keyword = ref("")
const draft = ref("")
const currentSessionId = ref("s1")
const sending = ref(false)
const model = ref("qwen2.5")
const enableStream = ref(true)
const sendMode = ref("stream")
const toast = useToast()
const modeOptions = computed(() => [
  { label: "流式", value: "stream" },
  { label: "非流式", value: "non-stream" }
])
const sessions = ref<ChatSession[]>([
  { id: "s1", title: "开盘策略讨论", time: "今天 09:12", status: "running" as const },
  { id: "s2", title: "回测结果复盘", time: "昨天 21:40", status: "success" as const },
  { id: "s3", title: "风险警报处理", time: "昨天 16:05", status: "warning" as const }
])
const messageMap = reactive<Record<string, ChatMsg[]>>({
  s1: [
  { id: "m1", role: "assistant", content: "已完成策略解析，建议降低高波动标的仓位。", attachments: ["report.md"] },
  { id: "m2", role: "user", content: "输出今日交易执行清单并附风险阈值。", attachments: [] }
  ],
  s2: [],
  s3: []
})
const toolCalls = ref([
  { id: "t1", name: "chat.completions", duration: "0ms" }
])

const filteredSessions = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return sessions.value
  return sessions.value.filter((item) => item.title.toLowerCase().includes(kw))
})

const currentMessages = computed(() => messageMap[currentSessionId.value] || [])

let abortStream: null | (() => void) = null

function nowText() {
  return new Date().toLocaleString("zh-CN", { hour12: false }).slice(5)
}

function addUserMessage(sessionId: string, text: string) {
  if (!messageMap[sessionId]) messageMap[sessionId] = []
  messageMap[sessionId].push({
    id: `u-${Date.now()}`,
    role: "user",
    content: text
  })
}

async function sendMessage() {
  const text = draft.value.trim()
  if (!text || sending.value) return
  const sid = currentSessionId.value
  addUserMessage(sid, text)
  draft.value = ""
  sending.value = true
  sessions.value = sessions.value.map((s) => (s.id === sid ? { ...s, status: "running", time: nowText() } : s))
  const start = Date.now()

  if (sendMode.value === "stream" && enableStream.value) {
    const assistantId = `a-${Date.now()}`
    if (!messageMap[sid]) messageMap[sid] = []
    messageMap[sid].push({ id: assistantId, role: "assistant", content: "" })
    abortStream = chatApi.chatStream(
      { sessionId: sid, message: text, model: model.value },
      {
        onDelta(delta) {
          const msg = messageMap[sid].find((m) => m.id === assistantId)
          if (msg) msg.content += delta
        },
        onDone(answer) {
          const msg = messageMap[sid].find((m) => m.id === assistantId)
          if (msg && !msg.content) msg.content = answer || "(无输出)"
          finishCall("success", Date.now() - start)
        },
        onError(message) {
          toast.error(message || "流式调用失败")
          finishCall("failed", Date.now() - start)
        }
      }
    )
    return
  }

  try {
    const res = await chatApi.chatCompletion({
      sessionId: sid,
      message: text,
      model: model.value
    })
    if (!messageMap[sid]) messageMap[sid] = []
    messageMap[sid].push({
      id: `a-${Date.now()}`,
      role: "assistant",
      content: res.answer || "(无输出)"
    })
    finishCall("success", Date.now() - start)
  } catch (error: any) {
    toast.error(error?.message || "非流式调用失败")
    finishCall("failed", Date.now() - start)
  }
}

function stopStream() {
  if (abortStream) {
    abortStream()
    abortStream = null
    finishCall("warning", 0)
    toast.info("已停止本次流式生成")
  }
}

function finishCall(status: SessionStatus, duration: number) {
  sessions.value = sessions.value.map((s) => (s.id === currentSessionId.value ? { ...s, status, time: nowText() } : s))
  toolCalls.value = [{ id: `t-${Date.now()}`, name: sendMode.value === "stream" ? "chat.stream" : "chat.completions", duration: `${duration}ms` }]
  sending.value = false
  abortStream = null
}

onMounted(async () => {
  try {
    const cfg = await llmApi.getConfig()
    model.value = cfg.model || model.value
    enableStream.value = Boolean(cfg.enableStream)
    if (!enableStream.value) sendMode.value = "non-stream"
  } catch {
    // keep defaults
  }
})
</script>

<style scoped>
.chat-grid {
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: var(--sp-4);
}
.chat-toolbar {
  margin-bottom: 10px;
  display: grid;
  grid-template-columns: 1fr 140px;
  gap: 10px;
  align-items: center;
}
.session-list {
  margin-top: var(--sp-3);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.session-item {
  border: 1px solid rgba(145, 196, 255, 0.2);
  background: rgba(18, 31, 52, 0.6);
  border-radius: var(--r-sm);
  color: var(--text-primary);
  text-align: left;
  display: flex;
  justify-content: space-between;
  padding: 10px;
  cursor: pointer;
}
.session-item.active {
  border-color: rgba(88, 185, 255, 0.6);
  background: rgba(88, 185, 255, 0.15);
}
.session-item p {
  margin: 0;
}
.session-item small {
  color: var(--text-secondary);
}
.message-list {
  max-height: 360px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.message-item {
  border: 1px solid rgba(145, 196, 255, 0.18);
  border-radius: var(--r-sm);
  padding: 10px 12px;
  background: rgba(14, 24, 40, 0.78);
}
.message-item.user {
  border-color: rgba(28, 214, 166, 0.3);
}
.message-item p {
  margin: 0;
  line-height: 1.55;
}
.attachments {
  margin-top: 8px;
  display: flex;
  gap: 6px;
}
.tool-list {
  margin-top: var(--sp-3);
  padding: 10px;
  border-radius: var(--r-sm);
  background: rgba(88, 185, 255, 0.08);
}
.tool-list ul {
  margin: 8px 0 0;
  padding-left: 18px;
}
.send-box {
  margin-top: var(--sp-3);
  border: 1px solid rgba(145, 196, 255, 0.2);
  border-radius: var(--r-sm);
  padding: 10px;
}
.send-box textarea {
  width: 100%;
  min-height: 100px;
  background: transparent;
  border: none;
  color: var(--text-primary);
  resize: vertical;
}
.send-box textarea:focus {
  outline: none;
}
.send-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
@media (max-width: 1199px) {
  .chat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
