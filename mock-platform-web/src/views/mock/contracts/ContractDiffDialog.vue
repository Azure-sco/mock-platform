<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import { diffContract } from '../../../api/admin'
import type { ContractDiff, ContractVersion } from '../../../types/admin'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ source: ContractVersion | null; versions: ContractVersion[] }>()
const loading = ref(false)
const error = ref('')
const compareVersionId = ref<number | null>(null)
const result = shallowRef<ContractDiff | null>(null)

watch(() => [visible.value, props.source] as const, ([open, source]) => {
  if (!open || !source) return
  compareVersionId.value = props.versions.find((item) => item.id !== source.id)?.id ?? null
  result.value = null
  error.value = ''
})

async function load() {
  if (loading.value || !props.source || !compareVersionId.value) return
  loading.value = true
  error.value = ''
  try {
    result.value = await diffContract(props.source.id, compareVersionId.value)
  } catch {
    error.value = '契约差异加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="契约版本对比" width="min(760px, 92vw)">
    <div class="diff-toolbar">
      <span>v{{ source?.versionNo }} 对比</span>
      <el-select v-model="compareVersionId" style="width: 220px" @change="result = null; error = ''">
        <el-option v-for="version in versions.filter((item) => item.id !== source?.id)" :key="version.id" :label="`v${version.versionNo} · ${version.status}`" :value="version.id" />
      </el-select>
      <el-button type="primary" plain :loading="loading" @click="load">加载差异</el-button>
    </div>
    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <el-empty v-else-if="!result && !loading" description="选择比较版本后加载字段级差异" />
    <pre v-else-if="result" class="json-preview">{{ JSON.stringify(result, null, 2) }}</pre>
  </el-dialog>
</template>
