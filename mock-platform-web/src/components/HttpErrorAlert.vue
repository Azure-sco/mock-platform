<script setup lang="ts">
import { useErrorStore } from '../stores/errors'

const errors = useErrorStore()
</script>

<template>
  <el-alert
    v-if="errors.latest"
    type="error"
    show-icon
    closable
    :title="`${errors.latest.code}: ${errors.latest.message}`"
    :description="errors.latest.requestId ? `Request ID: ${errors.latest.requestId}` : undefined"
    @close="errors.clear"
  />
</template>
