<script setup lang="ts">
import AppIcon from './AppIcon.vue'

defineProps<{ description: string }>()
</script>

<template>
  <el-empty class="empty-state" :image-size="36" :description="description">
    <template #image><AppIcon name="empty" /></template>
  </el-empty>
</template>
