<script setup lang="ts">
import { onMounted, reactive, ref, shallowRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import {
  deleteFlow,
  getFlowEvents,
  getFlowInstances,
  resetFlow,
  transitionFlow,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type { FlowEvent, FlowInstance, FlowInstanceStatus } from '../../../types/admin'

const errors = useErrorStore()
const session = useSessionStore()
const loading = ref(false)
const actionKey = ref<string | null>(null)
const flows = ref<FlowInstance[]>([])
const selected = shallowRef<FlowInstance | null>(null)
const events = ref<FlowEvent[]>([])
const transitionVisible = ref(false)
const transitionId = ref('')
const filter = reactive({
  appCode: '',
  providerCode: '',
  flowCode: '',
  status: '' as FlowInstanceStatus | '',
})

async function load() {
  loading.value = true
  errors.clear()
  try {
    flows.value = await getFlowInstances({
      environment: session.environment,
      appCode: filter.appCode || undefined,
      providerCode: filter.providerCode || undefined,
      flowCode: filter.flowCode || undefined,
      status: filter.status || undefined,
    })
  } catch {
    if (!errors.latest) errors.capture({ code: 'FLOW_INSTANCE_LOAD_FAILED', message: 'Flow Instance 加载失败' })
  } finally {
    loading.value = false
  }
}

async function openEvents(row: FlowInstance) {
  actionKey.value = row.flowKey
  errors.clear()
  try {
    selected.value = row
    events.value = await getFlowEvents(row.flowKey)
  } catch {
    if (!errors.latest) errors.capture({ code: 'FLOW_EVENT_LOAD_FAILED', message: 'Flow Event 加载失败' })
  } finally {
    actionKey.value = null
  }
}

async function manualTransition() {
  if (!selected.value || !transitionId.value.trim()) {
    ElMessage.warning('Transition ID 不能为空')
    return
  }
  const flow = selected.value
  try {
    await ElMessageBox.confirm('人工迁移会改变 Flow 权威状态并写同步审计，确认继续？', '二次确认', { type: 'warning' })
    actionKey.value = flow.flowKey
    await transitionFlow(flow.flowKey, transitionId.value.trim(), crypto.randomUUID())
    transitionVisible.value = false
    ElMessage.success('人工迁移已提交')
    await load()
    await openEvents(flow)
  } catch (failure) {
    if (failure !== 'cancel' && !errors.latest) errors.capture({ code: 'FLOW_TRANSITION_FAILED', message: '人工迁移失败' })
  } finally {
    actionKey.value = null
  }
}

async function runLifecycle(row: FlowInstance, action: 'reset' | 'delete') {
  try {
    await ElMessageBox.confirm(
      action === 'reset'
        ? 'Reset 将取消未开始的 Callback、递增 generation，并按当前 Active Release 重建，确认继续？'
        : 'Delete 将取消未开始的 Callback、递增 generation 并逻辑删除，确认继续？',
      '二次确认',
      { type: 'warning', confirmButtonText: '确认执行' },
    )
    actionKey.value = row.flowKey
    const requestId = crypto.randomUUID()
    if (action === 'reset') await resetFlow(row.flowKey, requestId, false)
    else await deleteFlow(row.flowKey, requestId)
    ElMessage.success(action === 'reset' ? 'Flow 已 Reset' : 'Flow 已逻辑删除')
    await load()
    if (selected.value?.flowKey === row.flowKey) await openEvents(row)
  } catch (failure) {
    if (failure !== 'cancel' && !errors.latest) {
      errors.capture({ code: 'FLOW_LIFECYCLE_FAILED', message: 'Flow 生命周期操作失败；RUNNING Callback 会返回 BUSY' })
    }
  } finally {
    actionKey.value = null
  }
}

function statusType(status: FlowInstanceStatus) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'EXPIRED') return 'warning'
  return 'info'
}

onMounted(load)
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">M3 · FLOW INSTANCE</p>
        <h2>流程实例</h2>
        <p>实例固定 Release、Flow Definition Version、checksum 和 generation；Reset/Delete 与 Callback 共享严格锁序。</p>
      </div>
    </div>
    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="App"><el-input v-model="filter.appCode" clearable style="width: 150px" /></el-form-item>
        <el-form-item label="Provider"><el-input v-model="filter.providerCode" clearable style="width: 145px" /></el-form-item>
        <el-form-item label="Flow"><el-input v-model="filter.flowCode" clearable style="width: 170px" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="filter.status" clearable style="width: 130px"><el-option label="ACTIVE" value="ACTIVE" /><el-option label="EXPIRED" value="EXPIRED" /><el-option label="DELETED" value="DELETED" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" plain @click="load">查询</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="flows" row-key="flowKey">
        <el-table-column prop="businessNoMasked" label="业务单号" min-width="130" />
        <el-table-column prop="flowCode" label="Flow" min-width="155" />
        <el-table-column prop="appCode" label="App" min-width="130" />
        <el-table-column prop="providerCode" label="Provider" min-width="120" />
        <el-table-column prop="currentState" label="当前状态" min-width="125" />
        <el-table-column label="状态" width="105"><template #default="{ row }"><el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="Generation" width="105"><template #default="{ row }">g{{ row.generation }}</template></el-table-column>
        <el-table-column prop="queryCount" label="查询次数" width="95" />
        <el-table-column prop="releaseId" label="固定 Release" min-width="150" show-overflow-tooltip />
        <el-table-column prop="expireAt" label="过期时间" min-width="175" />
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="actionKey === row.flowKey" @click="openEvents(row)">事件</el-button>
            <el-button link type="primary" :disabled="row.status !== 'ACTIVE'" @click="selected = row; transitionVisible = true">推进</el-button>
            <el-button link type="warning" @click="runLifecycle(row, 'reset')">Reset</el-button>
            <el-button link type="danger" :disabled="row.status === 'DELETED'" @click="runLifecycle(row, 'delete')">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无 Flow Instance" /></template>
      </el-table>
    </el-card>

    <el-card v-if="selected" class="table-card" shadow="never">
      <template #header><strong>{{ selected.flowCode }} / {{ selected.businessNoMasked }} · Generation {{ selected.generation }} 事件</strong></template>
      <el-table :data="events" row-key="eventId">
        <el-table-column prop="eventAt" label="时间" min-width="175" />
        <el-table-column prop="sourceType" label="来源" width="90" />
        <el-table-column prop="eventType" label="事件" min-width="120" />
        <el-table-column prop="transitionId" label="Transition" min-width="145" />
        <el-table-column label="状态" min-width="160"><template #default="{ row }">{{ row.fromState || '—' }} → {{ row.toState || '—' }}</template></el-table-column>
        <el-table-column prop="queryCount" label="Query Count" width="105" />
        <el-table-column prop="operator" label="操作人" min-width="120" />
      </el-table>
    </el-card>

    <el-dialog v-model="transitionVisible" title="人工推进 Flow" width="520px">
      <el-alert title="操作要求 mock:flow:transition 权限、Idempotency-Key、二次确认和同步审计。" type="warning" :closable="false" />
      <el-form label-position="top" style="margin-top: 16px"><el-form-item label="Transition ID" required><el-input v-model="transitionId" /></el-form-item></el-form>
      <template #footer><el-button @click="transitionVisible = false">取消</el-button><el-button type="primary" @click="manualTransition">二次确认并推进</el-button></template>
    </el-dialog>
  </section>
</template>
