<template>
  <div class="page-wrap settings-page">
    <Panel title="模型配置">
      <div class="form-grid">
        <label>
          <span>Provider</span>
          <UiSelect v-model="modelForm.provider" :options="providerOptions" />
        </label>
        <label>
          <span>Base URL</span>
          <UiInput v-model="modelForm.baseUrl" placeholder="http://127.0.0.1:11434/v1" />
        </label>
        <label>
          <span>API Key</span>
          <UiInput v-model="modelForm.apiKey" placeholder="留空表示不修改" />
        </label>
        <label>
          <span>Model</span>
          <UiInput v-model="modelForm.model" />
        </label>
        <label>
          <span>Temperature</span>
          <UiInput v-model="modelForm.temperature" />
        </label>
        <label>
          <span>Max Tokens</span>
          <UiInput v-model="modelForm.maxTokens" />
        </label>
        <label class="switch-line">
          <span>启用流式返回</span>
          <UiSwitch v-model="modelForm.enableStream" />
        </label>
      </div>
      <UiButton variant="primary" :loading="saving" @click="saveModelConfig">保存配置</UiButton>
    </Panel>

    <Panel title="技能管理">
      <div class="grid-2">
        <article v-for="skill in skills" :key="skill.name" class="skill-card">
          <div>
            <h4>{{ skill.name }}</h4>
            <p>{{ skill.desc }}</p>
          </div>
          <div class="skill-actions">
            <StatusChip :status="skill.enabled ? 'success' : 'idle'" :label="skill.enabled ? '启用' : '停用'" />
            <UiSwitch v-model="skill.enabled" />
          </div>
        </article>
      </div>
    </Panel>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue"
import Panel from "@/components/ui/Panel.vue"
import UiInput from "@/components/ui/Input.vue"
import UiSelect from "@/components/ui/Select.vue"
import UiButton from "@/components/ui/Button.vue"
import UiSwitch from "@/components/ui/Switch.vue"
import StatusChip from "@/components/ui/StatusChip.vue"
import { llmApi } from "@/api/llm"
import { useToast } from "@/composables/useToast"

const providerOptions = [
  { label: "OpenAI Compatible", value: "openai-compatible" },
  { label: "本地模型", value: "local" }
]
const toast = useToast()
const saving = ref(false)

const modelForm = reactive({
  provider: "local",
  baseUrl: "",
  apiKey: "",
  model: "qwen2.5",
  temperature: "0.3",
  maxTokens: "1024",
  enableStream: true
})

const skills = reactive([
  { name: "risk-scanner", desc: "盘中风险扫描与告警", enabled: true },
  { name: "news-impact", desc: "公告新闻影响分析", enabled: true },
  { name: "signal-builder", desc: "信号构建与解释", enabled: false }
])

async function loadConfig() {
  try {
    const cfg = await llmApi.getConfig()
    modelForm.baseUrl = cfg.baseUrl || ""
    modelForm.model = cfg.model || modelForm.model
    modelForm.temperature = String(cfg.temperature ?? 0.3)
    modelForm.maxTokens = String(cfg.maxTokens ?? 1024)
    modelForm.enableStream = Boolean(cfg.enableStream)
  } catch (error: any) {
    toast.warning(error?.message || "读取模型配置失败")
  }
}

async function saveModelConfig() {
  saving.value = true
  try {
    await llmApi.saveConfig({
      baseUrl: modelForm.baseUrl.trim(),
      apiKey: modelForm.apiKey.trim(),
      model: modelForm.model.trim(),
      temperature: Number(modelForm.temperature || 0.3),
      maxTokens: Number(modelForm.maxTokens || 1024),
      enableStream: modelForm.enableStream
    })
    modelForm.apiKey = ""
    toast.success("模型配置已保存")
    await loadConfig()
  } catch (error: any) {
    toast.error(error?.message || "保存模型配置失败")
  } finally {
    saving.value = false
  }
}

onMounted(loadConfig)
</script>

<style scoped>
.settings-page {
  gap: var(--sp-4);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: var(--sp-3);
}
.form-grid label {
  display: grid;
  gap: 6px;
}
.form-grid span {
  color: var(--text-secondary);
  font-size: var(--fz-xs);
}
.switch-line {
  display: flex !important;
  align-items: center;
  justify-content: space-between;
  border: 1px solid rgba(145, 196, 255, 0.2);
  border-radius: var(--r-sm);
  padding: 8px 10px;
}
.skill-card {
  border: 1px solid rgba(145, 196, 255, 0.2);
  border-radius: var(--r-sm);
  background: rgba(18, 31, 52, 0.65);
  padding: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.skill-card h4 {
  margin: 0;
}
.skill-card p {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: var(--fz-sm);
}
.skill-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
@media (max-width: 767px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
