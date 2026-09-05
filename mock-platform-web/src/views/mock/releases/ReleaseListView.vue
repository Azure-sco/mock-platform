<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import {
  createRelease,
  getActiveRelease,
  getReleases,
  publishRelease,
  rollbackRelease,
  validateRelease,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type { ActiveRelease, Release, ReleaseMutation, ReleaseValidation } from '../../../types/admin'

const errors = useErrorStore()
const session = useSessionStore()
const canPublish = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const actionId = ref<string | null>(null)
const releases = ref<Release[]>([])
const active = ref<ActiveRelease | null>(null)
const appCode = ref('sample-jdk17')
const createVisible = ref(false)
const validation = ref<ReleaseValidation | null>(null)
const scenarioIds = ref('')
const draft = reactive<Omit<ReleaseMutation, 'scenarioVersionIds'>>({
  releaseCode: '',
  environment: 'TEST',
  appCode: 'sample-jdk17',
  releaseNote: '',
})

function parsedScenarioIds(): number[] {
  const ids = scenarioIds.value
    .split(/[\s,]+/)
    .filter(Boolean)
    .map(Number)
  if (!ids.length || ids.some((id) => !Number.isSafeInteger(id) || id <= 0)) {
    throw new Error('Scenario Version ID 必须是逗号分隔的正整数')
  }
  return [...new Set(ids)]
}

function payload(): ReleaseMutation {
  return { ...draft, scenarioVersionIds: parsedScenarioIds() }
}

async function load() {
  loading.value = true
  errors.clear()
  try {
    releases.value = await getReleases()
    active.value = await getActiveRelease(session.environment, appCode.value)
  } catch {
    if (!errors.latest) errors.capture({ code: 'RELEASE_LOAD_FAILED', message: '发布列表加载失败' })
  } finally {
    loading.value = false
  }
}

async function runValidation() {
  try {
    validation.value = await validateRelease(payload())
    ElMessage.success(validation.value.valid ? 'Release 校验通过' : 'Release 校验未通过')
  } catch (failure) {
    if (failure instanceof Error && failure.message.startsWith('Scenario Version')) {
      ElMessage.warning(failure.message)
    } else if (!errors.latest) {
      errors.capture({ code: 'RELEASE_VALIDATE_FAILED', message: 'Release 校验失败' })
    }
  }
}

async function submitCreate() {
  if (!validation.value?.valid) {
    ElMessage.warning('请先完成并通过 Release 校验')
    return
  }
  try {
    await createRelease(payload())
    ElMessage.success('不可变 Release 已创建')
    createVisible.value = false
    validation.value = null
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'RELEASE_CREATE_FAILED', message: 'Release 创建失败' })
  }
}

async function activate(row: Release, action: 'publish' | 'rollback') {
  try {
    await ElMessageBox.confirm(
      action === 'publish'
        ? `确认发布 ${row.releaseCode}？当前 activationVersion=${active.value?.activationVersion ?? 0}。`
        : `确认回滚到 ${row.releaseCode}？将创建新的 Activation Version，不修改历史 Release。`,
      action === 'publish' ? '发布确认' : '回滚确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  actionId.value = row.id
  errors.clear()
  try {
    const version = active.value?.activationVersion ?? 0
    const result = action === 'publish'
      ? await publishRelease(row.id, version)
      : await rollbackRelease(row.id, version)
    ElMessage.success(`Activation v${result.activationVersion} 已进入 ${result.state}`)
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'RELEASE_ACTIVATION_FAILED', message: 'Release 激活失败，请刷新权威版本后重试' })
  } finally {
    actionId.value = null
  }
}

function releaseTag(status: Release['status']) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'READY') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'primary'
}

onMounted(load)
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">M2 · IMMUTABLE RELEASE</p>
        <h2>发布与回滚</h2>
        <p>MySQL Active Release 是权威状态；Redis、Runtime Cache 与节点 ACK 均为可恢复投影。</p>
      </div>
      <el-button type="primary" :disabled="!canPublish" @click="createVisible = true">创建 Release</el-button>
    </div>
    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="Environment"><el-tag effect="plain">{{ session.environment }}</el-tag></el-form-item>
        <el-form-item label="App"><el-input v-model="appCode" style="width: 220px" /></el-form-item>
        <el-form-item><el-button type="primary" plain @click="load">查询权威状态</el-button></el-form-item>
        <el-form-item label="Active">
          <span v-if="active"><strong>#{{ active.releaseId }}</strong> · v{{ active.activationVersion }} · {{ active.state }}</span>
          <span v-else>尚未激活</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="releases" row-key="id">
        <el-table-column prop="releaseCode" label="Release" min-width="180" />
        <el-table-column prop="environment" label="环境" width="90" />
        <el-table-column prop="appCode" label="App" min-width="140" />
        <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="releaseTag(row.status)" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="checksum" label="Snapshot Checksum" min-width="250" show-overflow-tooltip><template #default="{ row }"><code>{{ row.checksum }}</code></template></el-table-column>
        <el-table-column prop="signatureKeyId" label="签名 Key" min-width="130" />
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canPublish || row.status !== 'READY'" @click="activate(row, 'publish')">发布</el-button>
            <el-button link type="warning" :loading="actionId === row.id" :disabled="!canPublish || !['READY', 'PUBLISHED'].includes(row.status)" @click="activate(row, 'rollback')">回滚</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无 Release" /></template>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="创建不可变 Release" width="680px">
      <el-alert type="warning" :closable="false" title="仅可选择已 APPROVED 的 Scenario Version；创建前必须校验。" />
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="Release Code" required><el-input v-model="draft.releaseCode" /></el-form-item>
          <el-form-item label="App Code" required><el-input v-model="draft.appCode" /></el-form-item>
          <el-form-item label="Environment" required><el-select v-model="draft.environment"><el-option label="TEST" value="TEST" /><el-option label="UAT" value="UAT" /></el-select></el-form-item>
          <el-form-item label="Scenario Version IDs" required><el-input v-model="scenarioIds" placeholder="例如 11, 12, 15" /></el-form-item>
        </div>
        <el-form-item label="发布说明"><el-input v-model="draft.releaseNote" type="textarea" :rows="3" /></el-form-item>
        <pre v-if="validation" class="json-preview">{{ JSON.stringify(validation, null, 2) }}</pre>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" plain @click="runValidation">校验</el-button>
        <el-button type="primary" :disabled="!canPublish || !validation?.valid" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>
