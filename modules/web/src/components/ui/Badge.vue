<template>
  <span class="ui-badge"><slot /></span>
</template>

<style scoped>
.ui-badge {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  border: 1px solid rgba(88, 185, 255, 0.35);
  background: rgba(88, 185, 255, 0.14);
  padding: 2px 9px;
  font-size: var(--fz-xs);
  color: #d6ebff;
}
</style>
