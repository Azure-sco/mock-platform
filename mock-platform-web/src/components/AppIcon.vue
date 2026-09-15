<script setup lang="ts">
defineProps<{ name: string }>()

const paths: Record<string, string> = {
  dashboard: 'M3 13h4l3-8 4 14 3-8h4',
  requests: 'M8 5h12M8 12h12M8 19h12M3 5h.01M3 12h.01M3 19h.01',
  callbacks: 'M7 7h11v11M18 7 6 19M7 3H3v4',
  flows: 'M5 3v12a4 4 0 0 0 4 4h10M15 15l4 4-4 4M5 7h14M15 3l4 4-4 4',
  providers: 'm12 3 9 5-9 5-9-5 9-5ZM3 12l9 5 9-5M3 16l9 5 9-5',
  apis: 'm8 6-6 6 6 6M16 6l6 6-6 6M14 3l-4 18',
  contracts: 'M14 3H5v18h14V8l-5-5ZM14 3v5h5M8 12h8M8 16h5',
  scenarios: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10ZM12 12h.01',
  definitions: 'M4 3h6v6H4V3ZM14 15h6v6h-6v-6ZM7 9v9h7M14 6h6M17 3v6',
  settings: 'M3 6h8M15 6h6M3 12h3M10 12h11M3 18h11M18 18h3M11 3v6M6 9v6M14 15v6',
  releases: 'M12 16V3M7 8l5-5 5 5M4 14v7h16v-7',
  approvals: 'M9 3H5v18h14V3h-4M9 2h6v4H9V2ZM8 13l3 3 5-6',
  security: 'm12 2 8 3v6c0 5-4 8-8 11-4-3-8-6-8-11V5l8-3ZM8 12l3 3 5-6',
  audits: 'M12 3a9 9 0 1 0 9 9M12 7v5l3 2M16 3h5v5',
  sidebar: 'M3 4h18v16H3V4ZM9 4v16',
  help: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM9 9a3 3 0 0 1 6 0c0 2-3 2-3 5M12 17h.01',
  empty: 'M4 4h16v16H4V4ZM4 14h5l1 2h4l1-2h5M8 8h8M8 11h5',
}
</script>

<template>
  <svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path :d="paths[name]" />
  </svg>
</template>
