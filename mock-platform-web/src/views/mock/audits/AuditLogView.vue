<script setup lang="ts">
import { formatShanghaiTime } from '../../../utils/time'
import EmptyState from '../../../components/EmptyState.vue'
import PageHeader from '../../../components/PageHeader.vue'
import { onMounted, reactive, ref } from 'vue'
import { getAuditLogs } from '../../../api/admin'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import { useErrorStore } from '../../../stores/errors'
import type { AuditLog } from '../../../types/admin'

const errors = useErrorStore()
const loading = ref(false)
const records = ref<AuditLog[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, requestId: '', operator: '', action: '', objectType: '', createdRange: [] as string[] })

async function load() {
  loading.value = true
  errors.clear()
  try {
    const result = await getAuditLogs({
      page: query.page,
      size: query.size,
      requestId: query.requestId || undefined,
      operator: query.operator || undefined,
      action: query.action || undefined,
      objectType: query.objectType || undefined,
      createdFrom: query.createdRange[0] || undefined,
      createdTo: query.createdRange[1] || undefined,
    })
    records.value = result.records
    total.value = result.total
  } catch {
    records.value = []
    total.value = 0
    if (!errors.latest) errors.capture({ code: 'AUDIT_LOAD_FAILED', message: '审计日志加载失败' })
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
  <section class="management-page">
    <PageHeader description="按请求、操作者、动作和对象查询管理面不可抵赖审计；敏感内容仅展示落库前已脱敏的 JSON。">
      <template #title>审计日志</template>
      <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
    </PageHeader>

    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form class="audit-filters" label-position="top" @submit.prevent="search">
        <el-form-item label="Request ID"><el-input v-model="query.requestId" clearable /></el-form-item>
        <el-form-item label="操作者"><el-input v-model="query.operator" clearable /></el-form-item>
        <el-form-item label="动作"><el-input v-model="query.action" clearable /></el-form-item>
        <el-form-item label="对象类型"><el-input v-model="query.objectType" clearable /></el-form-item>
        <el-form-item label="时间范围" class="date-filter">
          <el-date-picker v-model="query.createdRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ssZ" start-placeholder="开始时间" end-placeholder="结束时间" />
        </el-form-item>
        <el-form-item class="filter-actions"><el-button type="primary" @click="search">查询</el-button></el-form-item>
      </el-form>
    </el-card>
    <el-card class="table-card" shadow="never">
      <el-table max-height="max(240px, calc(100vh - var(--table-offset, 230px)))" v-loading="loading" :data="records" style="width: 100%">
        <el-table-column prop="createdAt" label="时间" min-width="185" show-overflow-tooltip><template #default="{ row }">{{ formatShanghaiTime(row.createdAt) }}</template></el-table-column>
        <el-table-column prop="operator" label="操作者" min-width="135" show-overflow-tooltip />
        <el-table-column prop="action" label="动作" min-width="150" show-overflow-tooltip />
        <el-table-column label="对象" min-width="185">
          <template #default="{ row }">{{ row.objectType }} / {{ row.objectId || '—' }}</template>
        </el-table-column>
        <el-table-column prop="requestId" label="Request ID" min-width="190" show-overflow-tooltip />
        <el-table-column label="结果" width="100">
          <template #default="{ row }"><el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" effect="plain">{{ row.result }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="afterJsonMasked" label="脱敏后状态" min-width="240" show-overflow-tooltip />
        <template #empty><EmptyState description="当前筛选条件下没有审计记录" /></template>
      </el-table>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>
  </section>
</template>
