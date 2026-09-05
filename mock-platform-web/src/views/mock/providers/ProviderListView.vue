<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import { createProvider, getProviders, updateProvider } from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type { Provider, ProviderMutation, ResourceStatus } from '../../../types/admin'

const router = useRouter()
const errors = useErrorStore()
const session = useSessionStore()
const canEdit = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const saving = ref(false)
const providers = ref<Provider[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filters = reactive<{ keyword: string; status: '' | ResourceStatus }>({ keyword: '', status: '' })
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<ProviderMutation>(emptyForm())

function emptyForm(): ProviderMutation {
  return { providerCode: '', providerName: '', owner: '', status: 'ENABLED' }
}

async function loadProviders(resetPage = false) {
  if (resetPage) page.value = 1
  loading.value = true
  errors.clear()
  try {
    const result = await getProviders({
      page: page.value,
      size: size.value,
      keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined,
    })
    providers.value = result.records
    total.value = result.total
  } catch {
    if (!errors.latest) errors.capture({ code: 'PROVIDER_LOAD_FAILED', message: 'Provider 列表加载失败' })
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(provider: Provider) {
  editingId.value = provider.id
  Object.assign(form, {
    providerCode: provider.providerCode,
    providerName: provider.providerName,
    owner: provider.owner,
    status: provider.status,
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.providerCode.trim() || !form.providerName.trim() || !form.owner.trim()) {
    ElMessage.warning('请完整填写 Provider Code、名称和负责人')
    return
  }
  saving.value = true
  errors.clear()
  try {
    const payload: ProviderMutation = {
      providerCode: form.providerCode.trim(),
      providerName: form.providerName.trim(),
      owner: form.owner.trim(),
      status: form.status,
    }
    if (editingId.value) {
      await updateProvider(editingId.value, {
        providerName: payload.providerName,
        owner: payload.owner,
        status: payload.status,
      })
      ElMessage.success('Provider 已更新')
    } else {
      await createProvider(payload)
      ElMessage.success('Provider 已创建')
    }
    dialogVisible.value = false
    await loadProviders()
  } catch {
    if (!errors.latest) errors.capture({ code: 'PROVIDER_SAVE_FAILED', message: 'Provider 保存失败' })
  } finally {
    saving.value = false
  }
}

function enterApis(provider: Provider) {
  void router.push({
    path: '/mock/apis',
    query: { providerId: provider.id, providerCode: provider.providerCode },
  })
}

function changeSize(nextSize: number) {
  size.value = nextSize
  void loadProviders(true)
}

function resetFilters() {
  Object.assign(filters, { keyword: '', status: '' })
  void loadProviders(true)
}

onMounted(() => loadProviders())
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">M1 · RESOURCE CATALOG</p>
        <h2>Provider 管理</h2>
        <p>维护第三方服务的稳定身份；环境地址和高风险策略不在此处直接编辑。</p>
      </div>
      <el-button type="primary" :disabled="!canEdit" title="需要 MOCK_ADMIN 角色" @click="openCreate">
        新增 Provider
      </el-button>
    </div>

    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form inline @submit.prevent="loadProviders(true)">
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" clearable placeholder="Code / 名称 / 负责人" @keyup.enter="loadProviders(true)" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部" style="width: 130px">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" plain @click="loadProviders(true)">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="providers" row-key="id">
        <el-table-column prop="providerCode" label="Provider Code" min-width="170" />
        <el-table-column prop="providerName" label="名称" min-width="180" />
        <el-table-column prop="owner" label="负责人" min-width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" effect="plain">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" min-width="170">
          <template #default="{ row }">{{ row.updatedAt || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="enterApis(row)">查看 API</el-button>
            <el-button link type="primary" :disabled="!canEdit" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无 Provider，可由管理员创建第一条记录" />
        </template>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="loadProviders()"
          @size-change="changeSize"
        />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑 Provider' : '新增 Provider'" width="520px">
      <el-form label-position="top">
        <el-form-item label="Provider Code" required>
          <el-input v-model="form.providerCode" :disabled="Boolean(editingId)" maxlength="64" placeholder="例如 esign" />
        </el-form-item>
        <el-form-item label="Provider 名称" required>
          <el-input v-model="form.providerName" maxlength="128" placeholder="第三方服务名称" />
        </el-form-item>
        <el-form-item label="负责人" required>
          <el-input v-model="form.owner" maxlength="128" placeholder="团队或负责人账号" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ENABLED">启用</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canEdit" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>
