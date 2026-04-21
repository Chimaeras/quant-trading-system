<template>
  <div class="table-wrap">
    <div v-if="loading" class="table-state">加载中...</div>
    <table class="ui-table">
      <thead>
        <tr>
          <th
            v-for="col in columns"
            :key="String(col.key)"
            :style="{ width: col.width ? `${col.width}px` : undefined, textAlign: col.align || 'left' }"
          >
            {{ col.title }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, rowIndex) in rows" :key="rowKey ? String(row[rowKey]) : rowIndex">
          <td
            v-for="col in columns"
            :key="String(col.key)"
            :style="{ textAlign: col.align || 'left' }"
          >
            <slot :name="`cell-${String(col.key)}`" :row="row">
              {{ row[col.key] }}
            </slot>
          </td>
        </tr>
        <tr v-if="!loading && rows.length === 0">
          <td :colspan="columns.length" class="empty">{{ emptyText }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup lang="ts" generic="T extends Record<string, any>">
withDefaults(defineProps<{
  columns: Array<{ key: keyof T; title: string; width?: number; align?: "left" | "center" | "right" }>
  rows: T[]
  rowKey?: keyof T
  loading?: boolean
  emptyText?: string
}>(), {
  loading: false,
  emptyText: "暂无数据"
})
</script>

<style scoped>
.table-wrap {
  position: relative;
  overflow: auto;
}

.table-state {
  position: absolute;
  right: 12px;
  top: 8px;
  font-size: var(--fz-xs);
  color: var(--text-secondary);
}

.ui-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 760px;
}

.ui-table thead th {
  text-align: left;
  font-size: var(--fz-xs);
  color: var(--text-secondary);
  font-weight: 500;
  position: sticky;
  top: 0;
  background: rgba(8, 15, 28, 0.88);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid rgba(145, 196, 255, 0.2);
  padding: 10px 12px;
}

.ui-table td {
  padding: 11px 12px;
  border-bottom: 1px solid rgba(145, 196, 255, 0.09);
  font-size: var(--fz-sm);
}

.ui-table tbody tr {
  transition: background var(--dur-fast), transform var(--dur-fast);
}

.ui-table tbody tr:hover {
  background: rgba(88, 185, 255, 0.08);
  transform: translateY(-1px);
}

.empty {
  text-align: center !important;
  color: var(--text-secondary);
  padding: 20px !important;
}
</style>
