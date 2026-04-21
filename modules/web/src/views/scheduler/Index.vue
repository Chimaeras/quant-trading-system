<template>
  <div class="page-wrap">
    <Panel title="定时任务设置" subtitle="任务卡片网格 + 启停开关 + 提示词与周期配置">
      <div class="grid-3">
        <article v-for="task in tasks" :key="task.id" class="task-card">
          <header>
            <strong>{{ task.name }}</strong>
            <UiSwitch v-model="task.enabled" />
          </header>
          <p class="dim">周期：{{ task.cron }}</p>
          <p class="dim">最近运行：{{ task.lastRun }}</p>
          <p class="dim">下次运行：{{ task.nextRun }}</p>
          <StatusChip :status="task.status" />
          <UiInput v-model="task.prompt" placeholder="提示词模板" />
        </article>
      </div>
    </Panel>
  </div>
</template>

<script setup lang="ts">
import { reactive } from "vue"
import Panel from "@/components/ui/Panel.vue"
import UiSwitch from "@/components/ui/Switch.vue"
import StatusChip from "@/components/ui/StatusChip.vue"
import UiInput from "@/components/ui/Input.vue"

const tasks = reactive([
  { id: "1", name: "盘前扫描", cron: "工作日 08:55", lastRun: "今天 08:55", nextRun: "明天 08:55", enabled: true, status: "success" as const, prompt: "总结盘前热点与风险敞口" },
  { id: "2", name: "午盘检查", cron: "工作日 11:35", lastRun: "今天 11:35", nextRun: "明天 11:35", enabled: true, status: "running" as const, prompt: "评估仓位偏离并建议调仓比例" },
  { id: "3", name: "收盘复盘", cron: "工作日 15:15", lastRun: "昨天 15:15", nextRun: "今天 15:15", enabled: false, status: "warning" as const, prompt: "生成收益归因与次日计划" }
])
</script>

<style scoped>
.task-card {
  padding: 12px;
  border-radius: var(--r-sm);
  border: 1px solid rgba(145, 196, 255, 0.2);
  background: rgba(18, 31, 52, 0.65);
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: transform var(--dur-fast), border-color var(--dur-mid);
}

.task-card:hover {
  transform: translateY(-2px);
  border-color: rgba(88, 185, 255, 0.4);
}

.task-card header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.task-card p {
  margin: 0;
  font-size: var(--fz-sm);
}
</style>
