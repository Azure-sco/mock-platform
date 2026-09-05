<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import {
  createFlowDefinition,
  createFlowDefinitionVersion,
  getFlowDefinition,
  getFlowDefinitions,
  submitFlowDefinitionApproval,
  validateFlowDefinitionVersion,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import type {
  FlowDefinition,
  FlowDefinitionVersion,
  FlowDefinitionVersionMutation,
  JsonValue,
} from '../../../types/admin'

const errors = useErrorStore()
const loading = ref(false)
const actionId = ref<number | null>(null)
const definitions = ref<FlowDefinition[]>([])
const selected = ref<FlowDefinition | null>(null)
const versions = ref<FlowDefinitionVersion[]>([])
const createDefinitionVisible = ref(false)
const createVersionVisible = ref(false)
const definitionDraft = reactive({ providerId: 1, flowCode: '', flowName: '' })
const versionDraft = reactive({
  initialState: 'PROCESSING',
  ttlSeconds: 86400,
  participantApis: '[\n  {\n    "apiCode": "contract.create",\n    "contractVersionId": 1,\n    "role": "CREATE",\n    "createIfAbsent": true,\n    "businessKeyExtractor": { "source": "JSON_BODY", "path": "$.contractNo", "required": true, "normalize": "TRIM" }\n  }\n]',
  variables: '[]',
  transitions: '[]',
})

function parseJson(value: string, label: string): JsonValue {
  try {
    return JSON.parse(value) as JsonValue
  } catch {
    throw new Error(`${label} 不是合法 JSON`)
  }
}

async function load() {
  loading.value = true
  errors.clear()
  try {
    definitions.value = await getFlowDefinitions()
  } catch {
    if (!errors.latest) errors.capture({ code: 'FLOW_DEFINITION_LOAD_FAILED', message: 'Flow Definition 加载失败' })
  } finally {
    loading.value = false
  }
}

async function openVersions(row: FlowDefinition) {
  actionId.value = row.id
  errors.clear()
  try {
    const detail = await getFlowDefinition(row.id)
    selected.value = detail.flowDefinition
    versions.value = detail.versions
  } catch {
    if (!errors.latest) errors.capture({ code: 'FLOW_VERSION_LOAD_FAILED', message: 'Flow Definition Version 加载失败' })
  } finally {
    actionId.value = null
  }
}

async function submitDefinition() {
  if (!definitionDraft.flowCode.trim() || !definitionDraft.flowName.trim()) {
    ElMessage.warning('Flow Code 和名称不能为空')
    return
  }
  try {
    const created = await createFlowDefinition({
      providerId: definitionDraft.providerId,
      flowCode: definitionDraft.flowCode.trim(),
      flowName: definitionDraft.flowName.trim(),
    })
    createDefinitionVisible.value = false
    ElMessage.success('Flow Definition 已创建')
    await load()
    await openVersions(created)
  } catch {
    if (!errors.latest) errors.capture({ code: 'FLOW_DEFINITION_CREATE_FAILED', message: 'Flow Definition 创建失败' })
  }
}

async function submitVersion() {
  if (!selected.value) return
  let payload: FlowDefinitionVersionMutation
  try {
    payload = {
      initialState: versionDraft.initialState.trim(),
      ttlSeconds: versionDraft.ttlSeconds,
      participantApis: parseJson(versionDraft.participantApis, 'Participant API'),
      variables: parseJson(versionDraft.variables, 'Variables'),
      transitions: parseJson(versionDraft.transitions, 'Transitions'),
    }
  } catch (failure) {
    ElMessage.warning(failure instanceof Error ? failure.message : 'Flow Definition Version 配置错误')
    return
  }
  try {
    await createFlowDefinitionVersion(selected.value.id, payload)
    createVersionVisible.value = false
    ElMessage.success('不可变 Flow Definition Version 已创建')
    await openVersions(selected.value)
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'FLOW_VERSION_CREATE_FAILED', message: 'Flow Definition Version 创建失败' })
  }
}

async function runAction(version: FlowDefinitionVersion, action: 'validate' | 'approval') {
  actionId.value = version.id
  errors.clear()
  try {
    if (action === 'validate') {
      await validateFlowDefinitionVersion(version.id)
      ElMessage.success('Flow Graph、Participant、变量和 Callback 规则校验通过')
    } else {
      await submitFlowDefinitionApproval(version.id)
      ElMessage.success('已按 checksum 提交双人审批')
    }
    if (selected.value) await openVersions(selected.value)
  } catch {
    if (!errors.latest) errors.capture({ code: 'FLOW_VERSION_ACTION_FAILED', message: 'Flow Definition Version 操作失败' })
  } finally {
    actionId.value = null
  }
}

onMounted(load)
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">M3 · FLOW DEFINITION</p>
        <h2>流程定义</h2>
        <p>Participant、业务键提取器、变量、迁移和 TTL 均以不可变 Version 管理；发布后的 Flow 固定版本与 checksum。</p>
      </div>
      <el-button type="primary" @click="createDefinitionVisible = true">新建 Flow</el-button>
    </div>
    <HttpErrorAlert />

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="definitions" row-key="id">
        <el-table-column prop="flowCode" label="Flow Code" min-width="190" />
        <el-table-column prop="flowName" label="名称" min-width="180" />
        <el-table-column prop="providerCode" label="Provider" min-width="140">
          <template #default="{ row }">{{ row.providerCode || `#${row.providerId}` }}</template>
        </el-table-column>
        <el-table-column label="草稿版本" width="100"><template #default="{ row }">v{{ row.currentDraftVersion }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="110"><template #default="{ row }"><el-tag effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" min-width="175" />
        <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link type="primary" :loading="actionId === row.id" @click="openVersions(row)">版本</el-button></template></el-table-column>
        <template #empty><el-empty description="暂无 Flow Definition" /></template>
      </el-table>
    </el-card>

    <el-card v-if="selected" class="table-card" shadow="never">
      <template #header>
        <div class="card-header-row"><strong>{{ selected.flowCode }} · 不可变版本</strong><el-button type="primary" plain @click="createVersionVisible = true">新建版本</el-button></div>
      </template>
      <el-table :data="versions" row-key="id">
        <el-table-column label="版本" width="75"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="145"><template #default="{ row }"><el-tag effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="initialState" label="初始状态" min-width="130" />
        <el-table-column prop="ttlSeconds" label="TTL(s)" width="105" />
        <el-table-column prop="checksum" label="Checksum" min-width="230" show-overflow-tooltip />
        <el-table-column prop="validationStatus" label="校验" width="110" />
        <el-table-column label="操作" width="145"><template #default="{ row }"><el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="runAction(row, 'validate')">校验</el-button><el-button link type="primary" :disabled="row.status !== 'VALIDATED'" @click="runAction(row, 'approval')">审批</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createDefinitionVisible" title="新建 Flow Definition" width="560px">
      <el-form label-position="top">
        <el-form-item label="Provider ID" required><el-input-number v-model="definitionDraft.providerId" :min="1" /></el-form-item>
        <el-form-item label="Flow Code" required><el-input v-model="definitionDraft.flowCode" placeholder="esign.contract" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="definitionDraft.flowName" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createDefinitionVisible = false">取消</el-button><el-button type="primary" @click="submitDefinition">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="createVersionVisible" title="新建不可变 Flow Version" width="820px">
      <el-form label-position="top">
        <div class="form-grid"><el-form-item label="初始状态" required><el-input v-model="versionDraft.initialState" /></el-form-item><el-form-item label="TTL（秒）" required><el-input-number v-model="versionDraft.ttlSeconds" :min="60" :max="2592000" /></el-form-item></div>
        <el-form-item label="Participant APIs JSON" required><el-input v-model="versionDraft.participantApis" type="textarea" :rows="9" spellcheck="false" /></el-form-item>
        <el-form-item label="Variables JSON"><el-input v-model="versionDraft.variables" type="textarea" :rows="4" spellcheck="false" /></el-form-item>
        <el-form-item label="Transitions JSON"><el-input v-model="versionDraft.transitions" type="textarea" :rows="8" spellcheck="false" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVersionVisible = false">取消</el-button><el-button type="primary" @click="submitVersion">创建版本</el-button></template>
    </el-dialog>
  </section>
</template>
