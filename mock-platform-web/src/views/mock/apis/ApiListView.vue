<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import { createApi, getProviderApis, getProviders, updateApi } from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type { ApiMutation, MockApi, Provider } from '../../../types/admin'

const route = useRoute()
const router = useRouter()
const errors = useErrorStore()
const session = useSessionStore()
const canEdit = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const saving = ref(false)
const providers = ref<Provider[]>([])
const apis = ref<MockApi[]>([])
const selectedProviderId = ref<number | null>(null)
const total = ref(0)
const page = ref(1)
const size = ref(20)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<ApiMutation>(emptyForm())

function emptyForm(): ApiMutation {
  return {
    providerId: selectedProviderId.value ?? 0,
    apiCode: '',
    apiName: '',
    httpMethod: 'POST',
    path: '/',
    contentType: 'application/json',
    owner: '',
    status: 'ENABLED',
  }
}

function queryId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null
  return Number(value)
}

async function loadProviderOptions() {
  const result = await getProviders({ page: 1, size: 200 })
  providers.value = result.records
}

async function loadApis(resetPage = false) {
  if (resetPage) page.value = 1
  if (!selectedProviderId.value) {
    apis.value = []
    total.value = 0
    return
  }
  loading.value = true
  errors.clear()
  try {
    const result = await getProviderApis(selectedProviderId.value, { page: page.value, size: size.value })
    apis.value = result.records
    total.value = result.total
  } catch {
    if (!errors.latest) errors.capture({ code: 'API_LOAD_FAILED', message: 'API 列表加载失败' })
  } finally {
    loading.value = false
  }
}

async function initialize() {
  loading.value = true
  errors.clear()
  try {
    await loadProviderOptions()
    const requestedProviderId = queryId(route.query.providerId)
    selectedProviderId.value = providers.value.some((item) => item.id === requestedProviderId)
      ? requestedProviderId
      : (providers.value[0]?.id ?? null)
    await loadApis(true)
  } catch {
    if (!errors.latest) errors.capture({ code: 'API_INITIALIZE_FAILED', message: 'API 页面初始化失败' })
  } finally {
    loading.value = false
  }
}

function changeProvider() {
  const provider = providers.value.find((item) => item.id === selectedProviderId.value)
  void router.replace({
    path: '/mock/apis',
    query: provider ? { providerId: provider.id, providerCode: provider.providerCode } : {},
  })
  void loadApis(true)
}

function openCreate() {
  editingId.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(api: MockApi) {
  editingId.value = api.id
  Object.assign(form, {
    providerId: api.providerId,
    apiCode: api.apiCode,
    apiName: api.apiName,
    httpMethod: api.httpMethod,
    path: api.path,
    contentType: api.contentType,
    owner: api.owner,
    status: api.status,
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.apiCode.trim() || !form.apiName.trim() || !form.path.trim() || !form.owner.trim()) {
    ElMessage.warning('请完整填写 API Code、名称、Path 和负责人')
    return
  }
  saving.value = true
  errors.clear()
  try {
    const payload: ApiMutation = {
      ...form,
      apiCode: form.apiCode.trim(),
      apiName: form.apiName.trim(),
      path: form.path.trim(),
      contentType: form.contentType.trim(),
      owner: form.owner.trim(),
    }
    if (editingId.value) {
      await updateApi(editingId.value, {
        apiName: payload.apiName,
        httpMethod: payload.httpMethod,
        path: payload.path,
        contentType: payload.contentType,
        owner: payload.owner,
        status: payload.status,
      })
      ElMessage.success('API 已更新')
    } else {
      await createApi(payload)
      ElMessage.success('API 已创建')
    }
    dialogVisible.value = false
    await loadApis()
  } catch {
    if (!errors.latest) errors.capture({ code: 'API_SAVE_FAILED', message: 'API 保存失败' })
  } finally {
    saving.value = false
  }
}

function enterContracts(api: MockApi) {
  const provider = providers.value.find((item) => item.id === selectedProviderId.value)
  void router.push({
    path: '/mock/contracts',
    query: {
      providerId: selectedProviderId.value,
      providerCode: provider?.providerCode,
      apiId: api.id,
      apiCode: api.apiCode,
    },
  })
}

function changeSize(nextSize: number) {
  size.value = nextSize
  void loadApis(true)
}

onMounted(initialize)
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">M1 · API CATALOG</p>
        <h2>API 管理</h2>
        <p>按 Provider 维护稳定 API 标识、请求方法和路径，并进入契约版本管理。</p>
      </div>
      <el-button
        type="primary"
        :disabled="!canEdit || !selectedProviderId"
        title="需要 MOCK_ADMIN 角色且必须先选择 Provider"
        @click="openCreate"
      >
        新增 API
      </el-button>
    </div>

    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="Provider">
          <el-select
            v-model="selectedProviderId"
            filterable
            placeholder="选择 Provider"
            style="width: 300px"
            @change="changeProvider"
          >
            <el-option
              v-for="provider in providers"
              :key="provider.id"
              :label="`${provider.providerCode} · ${provider.providerName}`"
              :value="provider.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" plain :disabled="!selectedProviderId" @click="loadApis(true)">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="apis" row-key="id">
        <el-table-column prop="apiCode" label="API Code" min-width="190" />
        <el-table-column prop="apiName" label="名称" min-width="170" />
        <el-table-column label="请求" min-width="250">
          <template #default="{ row }">
            <div class="request-path">
              <el-tag size="small" effect="plain">{{ row.httpMethod }}</el-tag>
              <code>{{ row.path }}</code>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="contentType" label="Content-Type" min-width="170" />
        <el-table-column prop="owner" label="负责人" min-width="130" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" effect="plain">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="enterContracts(row)">契约版本</el-button>
            <el-button link type="primary" :disabled="!canEdit" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="selectedProviderId ? '该 Provider 暂无 API' : '请先创建或选择 Provider'" />
        </template>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="loadApis()"
          @size-change="changeSize"
        />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑 API' : '新增 API'" width="620px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="API Code" required>
            <el-input v-model="form.apiCode" :disabled="Boolean(editingId)" maxlength="64" placeholder="例如 contract.query" />
          </el-form-item>
          <el-form-item label="API 名称" required>
            <el-input v-model="form.apiName" maxlength="128" />
          </el-form-item>
          <el-form-item label="HTTP Method" required>
            <el-select v-model="form.httpMethod">
              <el-option
                v-for="method in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']"
                :key="method"
                :label="method"
                :value="method"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="Content-Type" required>
            <el-input v-model="form.contentType" maxlength="128" />
          </el-form-item>
        </div>
        <el-form-item label="Path" required>
          <el-input v-model="form.path" maxlength="512" placeholder="/contracts/{id}" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="负责人" required>
            <el-input v-model="form.owner" maxlength="128" />
          </el-form-item>
          <el-form-item label="状态">
            <el-radio-group v-model="form.status">
              <el-radio value="ENABLED">启用</el-radio>
              <el-radio value="DISABLED">停用</el-radio>
            </el-radio-group>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canEdit" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>
