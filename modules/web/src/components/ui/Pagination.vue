<template>
  <div class="pager">
    <button :disabled="currentPage <= 1" @click="setPage(currentPage - 1)">上一页</button>
    <span>{{ currentPage }} / {{ totalPages }}</span>
    <button :disabled="currentPage >= totalPages" @click="setPage(currentPage + 1)">下一页</button>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue"

const props = defineProps<{ currentPage: number; pageSize: number; total: number }>()
const emit = defineEmits<{ (e: "update:currentPage", value: number): void }>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.pageSize))))

function setPage(page: number) {
  emit("update:currentPage", Math.min(totalPages.value, Math.max(1, page)))
}
</script>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

button {
  border: 1px solid rgba(145, 196, 255, 0.3);
  background: rgba(88, 185, 255, 0.1);
  color: var(--text-primary);
  border-radius: var(--r-xs);
  height: 30px;
  padding: 0 12px;
  cursor: pointer;
}

button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
