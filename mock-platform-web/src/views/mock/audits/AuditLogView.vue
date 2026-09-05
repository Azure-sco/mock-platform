<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { getAuditLogs } from '../../../api/admin'
import type { AuditLog } from '../../../types/admin'

const loading = ref(false)
const records = ref<AuditLog[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, requestId: '', operator: '', action: '', objectType: '' })

async function load() {
  loading.value = true
  try {
    const result = await getAuditLogs({
      page: query.page,
      size: query.size,
      requestId: query.requestId || undefined,
      operator: query.operator || undefined,
      action: query.action || undefined,
      objectType: query.objectType || undefined,
    })
    records.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  void load()
}

onMounted(load)
</script>

<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="eyebrow">MVP · IMMUTABLE AUDIT</p>
        <h2>审计日志</h2>
        <p>按请求、操作者、动作和对象查询管理面不可抵赖审计；敏感内容仅展示落库前已脱敏的 JSON。</p>
      </div>
      <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <el-form inline @submit.prevent="search">
        <el-form-item label="Request ID"><el-input v-model="query.requestId" clearable /></el-form-item>
        <el-form-item label="操作者"><el-input v-model="query.operator" clearable /></el-form-item>
        <el-form-item label="动作"><el-input v-model="query.action" clearable /></el-form-item>
        <el-form-item label="对象类型"><el-input v-model="query.objectType" clearable /></el-form-item>
        <el-form-item><el-button type="primary" @click="search">查询</el-button></el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="records" style="width: 100%">
        <el-table-column prop="createdAt" label="时间" min-width="185" />
        <el-table-column prop="operator" label="操作者" min-width="135" />
        <el-table-column prop="action" label="动作" min-width="150" />
        <el-table-column label="对象" min-width="185">
          <template #default="{ row }">{{ row.objectType }} / {{ row.objectId || '—' }}</template>
        </el-table-column>
        <el-table-column prop="requestId" label="Request ID" min-width="190" show-overflow-tooltip />
        <el-table-column label="结果" width="100">
          <template #default="{ row }"><el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" effect="plain">{{ row.result }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="afterJsonMasked" label="脱敏后状态" min-width="240" show-overflow-tooltip />
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="search"
      />
    </el-card>
  </section>
</template>
