<template>
  <div class="page-wrap">
    <Panel :title="String(route.meta?.view || route.meta?.title || '工作台')" subtitle="该页面已升级为新UI骨架，可按需继续迁移业务交互。">
      <div class="grid-3">
        <Card label="页面状态" value="已接入新主题" hint="可继续注入真实数据模块" hint-type="success" />
        <Card label="视觉规范" value="Glassmorphism" hint="深色渐变 + 高密度布局" />
        <Card label="交互规范" value="Hover / Active / Focus" hint="统一按钮和列表反馈" />
      </div>
    </Panel>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from "vue-router"
import Panel from "@/components/ui/Panel.vue"
import Card from "@/components/ui/Card.vue"

const route = useRoute()
</script>
