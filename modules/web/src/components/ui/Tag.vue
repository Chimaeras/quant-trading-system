<template>
  <span class="ui-tag" :data-type="type"><slot /></span>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ type?: "default" | "success" | "warning" | "danger" }>(), {
  type: "default"
})
</script>

<style scoped>
.ui-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid rgba(145, 196, 255, 0.28);
  color: var(--text-primary);
  background: rgba(145, 196, 255, 0.12);
  font-size: var(--fz-xs);
}
.ui-tag[data-type="success"] {
  border-color: rgba(28, 214, 166, 0.4);
  background: rgba(28, 214, 166, 0.14);
}
.ui-tag[data-type="warning"] {
  border-color: rgba(244, 193, 93, 0.42);
  background: rgba(244, 193, 93, 0.16);
}
.ui-tag[data-type="danger"] {
  border-color: rgba(255, 107, 122, 0.4);
  background: rgba(255, 107, 122, 0.15);
}
</style>
