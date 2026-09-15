<script setup lang="ts">
defineProps<{ description: string }>()
</script>

<template>
  <header class="page-heading">
    <div class="page-heading-copy">
      <h1><slot name="title" /></h1>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.default" class="heading-actions"><slot /></div>
  </header>
</template>
