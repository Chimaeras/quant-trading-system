<template>
  <el-card shadow="hover">
    <template #header>
      <el-input v-model="name" placeholder="策略名称" style="width: 200px" />
      <el-select v-model="language" placeholder="语言" style="width: 100px; margin-left: 12px">
        <el-option label="Python" value="python" />
      </el-select>
      <el-button type="primary" style="margin-left: 12px" @click="save">保存</el-button>
      <el-button @click="$router.push('/strategy/list')">返回</el-button>
    </template>
    <div ref="editorRef" style="height: 500px; border: 1px solid #333" />
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as monaco from 'monaco-editor'
import { strategyApi } from '@/api/strategy'

const route = useRoute()
const name = ref('新策略')
const language = ref('python')
const editorRef = ref<HTMLElement>()
let editor: monaco.editor.IStandaloneCodeEditor | null = null

const defaultCode = `# 简单均线策略示例
def initialize(context):
    pass

def handle_bar(context, bar_dict):
    pass
`

onMounted(async () => {
  if (!editorRef.value) return
  editor = monaco.editor.create(editorRef.value, {
    value: defaultCode,
    language: 'python',
    theme: 'vs-dark'
  })
  const id = Array.isArray(route.query.id) ? route.query.id[0] : route.query.id
  if (id) {
    const s = await strategyApi.getStrategy(id)
    if (s) {
      name.value = (s.name ?? s.strategy_name) ?? '新策略'
      language.value = s.language ?? 'python'
      editor?.setValue(s.content ?? defaultCode)
    }
  }
})

const save = async () => {
  try {
    await strategyApi.saveStrategy({
      id: (Array.isArray(route.query.id) ? route.query.id[0] : route.query.id) || undefined,
      name: name.value,
      language: language.value,
      content: editor?.getValue() || ''
    })
    ElMessage.success('保存成功')
  } catch {
    ElMessage.error('保存失败')
  }
}
</script>
