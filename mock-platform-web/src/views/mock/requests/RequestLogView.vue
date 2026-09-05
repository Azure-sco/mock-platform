<script setup lang="ts">
import { onMounted, reactive, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import { getRequestLog, getRequestLogs } from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import type { RequestLog, RequestLogQuery } from '../../../types/admin'

interface RequestFilters {
  traceId: string
  appCode: string
  providerCode: string
  apiCode: string
  scenarioId: string
  mockRequestId: string
  businessNoHmac: string
  hmacKeyVersion: string
}

const errors = useErrorStore()
const loading = ref(false)
const detailLoading = ref(false)
const logs = shallowRef<RequestLog[]>([])
const detail = shallowRef<RequestLog | null>(null)
const detailVisible = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)
const dateRange = ref<[string, string] | null>(null)
const filters = reactive<RequestFilters>(emptyFilters())

function emptyFilters(): RequestFilters {
  return {
    traceId: '',
    appCode: '',
    providerCode: '',
    apiCode: '',
    scenarioId: '',
    mockRequestId: '',
    businessNoHmac: '',
    hmacKeyVersion: '',
  }
}

function utcDayStart(value: string): string {
  return new Date(`${value}T00:00:00.000Z`).toISOString()
}

function utcDayAfter(value: string): string {
  const date = new Date(`${value}T00:00:00.000Z`)
  date.setUTCDate(date.getUTCDate() + 1)
  return date.toISOString()
}

function query(): RequestLogQuery {
  const range = dateRange.value
  return {
    page: page.value,
    size: size.value,
    traceId: filters.traceId.trim() || undefined,
    appCode: filters.appCode.trim() || undefined,
    providerCode: filters.providerCode.trim() || undefined,
    apiCode: filters.apiCode.trim() || undefined,
    scenarioId: filters.scenarioId.trim() || undefined,
    mockRequestId: filters.mockRequestId.trim() || undefined,
    businessNoHmac: filters.businessNoHmac.trim() || undefined,
    hmacKeyVersion: filters.hmacKeyVersion.trim() || undefined,
    createdFrom: range?.[0] ? utcDayStart(range[0]) : undefined,
    createdTo: range?.[1] ? utcDayAfter(range[1]) : undefined,
  }
}

async function loadLogs(resetPage = false) {
  if (filters.mockRequestId.trim() && !filters.appCode.trim()) {
    ElMessage.warning('按 Mock Request ID 查询时必须同时填写 App Code')
    return
  }
  if (Boolean(filters.businessNoHmac.trim()) !== Boolean(filters.hmacKeyVersion.trim())) {
    ElMessage.warning('BusinessNo HMAC 与 HMAC Key Version 必须同时填写')
    return
  }
  if (resetPage) page.value = 1
  loading.value = true
  errors.clear()
  try {
    const result = await getRequestLogs(query())
    logs.value = result.records
    total.value = result.total
  } catch {
    if (!errors.latest) errors.capture({ code: 'REQUEST_LOG_LOAD_FAILED', message: '请求记录加载失败' })
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  Object.assign(filters, emptyFilters())
  dateRange.value = null
  void loadLogs(true)
}

function createdDate(value: string): string {
  return value.slice(0, 10)
}

async function openDetail(row: RequestLog) {
  detailVisible.value = true
  detail.value = null
  detailLoading.value = true
  errors.clear()
  try {
    detail.value = await getRequestLog(row.id, createdDate(row.createdAt))
  } catch {
    if (!errors.latest) errors.capture({ code: 'REQUEST_LOG_DETAIL_FAILED', message: '请求详情加载失败' })
  } finally {
    detailLoading.value = false
  }
}

function formatJson(value: unknown): string {
  if (value === undefined || value === null || value === '') return '无记录'
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  return JSON.stringify(value, null, 2)
}

function statusType(status: number | null): 'success' | 'warning' | 'danger' | 'info' {
  if (status === null) return 'info'
  if (status >= 500) return 'danger'
  if (status >= 400) return 'warning'
  return 'success'
}

function changeSize(nextSize: number) {
  size.value = nextSize
  void loadLogs(true)
}

onMounted(() => loadLogs())
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">REQUEST OBSERVABILITY</p>
        <h2>请求记录</h2>
        <p>查询 Runtime 已持久化的脱敏请求摘要；该列表不是幂等账本或原始报文存储。</p>
      </div>
      <el-button type="primary" plain @click="loadLogs">刷新</el-button>
    </div>

    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form class="request-filters" label-position="top" @submit.prevent="loadLogs(true)">
        <el-form-item label="App Code">
          <el-input v-model="filters.appCode" clearable maxlength="128" placeholder="例如 pomp-power" />
        </el-form-item>
        <el-form-item label="Trace ID">
          <el-input v-model="filters.traceId" clearable maxlength="64" />
        </el-form-item>
        <el-form-item label="Provider Code">
          <el-input v-model="filters.providerCode" clearable maxlength="64" />
        </el-form-item>
        <el-form-item label="API Code">
          <el-input v-model="filters.apiCode" clearable maxlength="64" />
        </el-form-item>
        <el-form-item label="Mock Request ID">
          <el-input v-model="filters.mockRequestId" clearable maxlength="64" />
        </el-form-item>
        <el-form-item label="Scenario ID">
          <el-input v-model="filters.scenarioId" clearable maxlength="64" />
        </el-form-item>
        <el-form-item label="BusinessNo HMAC">
          <el-input v-model="filters.businessNoHmac" clearable maxlength="64" />
        </el-form-item>
        <el-form-item label="HMAC Key Version">
          <el-input v-model="filters.hmacKeyVersion" clearable maxlength="32" />
        </el-form-item>
        <el-form-item label="创建日期">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </el-form-item>
        <div class="filter-actions">
          <el-button type="primary" @click="loadLogs(true)">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="logs" row-key="id">
        <el-table-column prop="createdAt" label="时间" min-width="175" />
        <el-table-column prop="appCode" label="App" min-width="130" />
        <el-table-column label="Provider / API" min-width="230">
          <template #default="{ row }">
            <strong>{{ row.providerCode }}</strong>
            <small class="table-secondary">{{ row.apiCode }}</small>
          </template>
        </el-table-column>
        <el-table-column label="请求" min-width="240">
          <template #default="{ row }">
            <div class="request-path">
              <el-tag size="small" effect="plain">{{ row.httpMethod }}</el-tag>
              <code>{{ row.path }}</code>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="mockRequestId" label="Mock Request ID" min-width="210" show-overflow-tooltip />
        <el-table-column label="结果" min-width="125">
          <template #default="{ row }">
            <el-tag :type="statusType(row.httpStatus)" effect="plain">{{ row.httpStatus ?? '—' }}</el-tag>
            <small v-if="row.errorCode" class="table-secondary error-text">{{ row.errorCode }}</small>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="95">
          <template #default="{ row }">{{ row.durationMs }} ms</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="当前筛选条件下没有请求记录" />
        </template>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="loadLogs()"
          @size-change="changeSize"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="请求记录详情" size="58%">
      <div v-loading="detailLoading" class="request-detail">
        <template v-if="detail">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="Mock Request ID">{{ detail.mockRequestId }}</el-descriptions-item>
            <el-descriptions-item label="Trace ID">{{ detail.traceId || '—' }}</el-descriptions-item>
            <el-descriptions-item label="App / Environment">{{ detail.appCode }} / {{ detail.environment }}</el-descriptions-item>
            <el-descriptions-item label="Tenant / Test Account">
              {{ detail.tenantCode || '—' }} / {{ detail.testAccountMasked || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="Provider / API">{{ detail.providerCode }} / {{ detail.apiCode }}</el-descriptions-item>
            <el-descriptions-item label="HTTP">{{ detail.httpMethod }} {{ detail.path }}</el-descriptions-item>
            <el-descriptions-item label="Scenario Version">{{ detail.scenarioVersionId || '—' }}</el-descriptions-item>
            <el-descriptions-item label="Release">{{ detail.releaseId || '—' }}</el-descriptions-item>
            <el-descriptions-item label="状态 / 耗时">{{ detail.httpStatus }} / {{ detail.durationMs }} ms</el-descriptions-item>
            <el-descriptions-item label="错误码">{{ detail.errorCode || '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item>
            <el-descriptions-item label="到期时间">{{ detail.expireAt || '—' }}</el-descriptions-item>
          </el-descriptions>

          <div class="summary-grid">
            <section>
              <h3>脱敏请求摘要</h3>
              <pre class="json-preview">{{ formatJson(detail.requestSummary) }}</pre>
            </section>
            <section>
              <h3>脱敏响应摘要</h3>
              <pre class="json-preview">{{ formatJson(detail.responseSummary) }}</pre>
            </section>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="未能加载请求详情" />
      </div>
    </el-drawer>
  </section>
</template>
