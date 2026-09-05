<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import {
  cancelCallbackTask,
  getCallbackAttempts,
  getCallbackTasks,
  retryCallbackTask,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import type { CallbackAttempt, CallbackTask, CallbackTaskStatus } from '../../../types/admin'
import { SubmissionCoordinator } from '../../../utils/requestControl'

const errors = useErrorStore()
const loading = ref(false)
const actionId = ref<string | null>(null)
const tasks = ref<CallbackTask[]>([])
const selected = ref<CallbackTask | null>(null)
const attempts = ref<CallbackAttempt[]>([])
const filter = reactive({ providerCode: '', apiCode: '', status: '' as CallbackTaskStatus | '' })
const submissions = new SubmissionCoordinator(() => crypto.randomUUID())

async function load() {
  loading.value = true
  errors.clear()
  try {
    tasks.value = await getCallbackTasks({
      providerCode: filter.providerCode || undefined,
      apiCode: filter.apiCode || undefined,
      status: filter.status || undefined,
    })
  } catch {
    if (!errors.latest) errors.capture({ code: 'CALLBACK_TASK_LOAD_FAILED', message: 'Callback Task 加载失败' })
  } finally {
    loading.value = false
  }
}

async function openAttempts(row: CallbackTask) {
  actionId.value = row.taskId
  errors.clear()
  try {
    selected.value = row
    attempts.value = await getCallbackAttempts(row.taskId)
  } catch {
    if (!errors.latest) errors.capture({ code: 'CALLBACK_ATTEMPT_LOAD_FAILED', message: 'Callback Attempt 加载失败' })
  } finally {
    actionId.value = null
  }
}

async function retry(row: CallbackTask) {
  if (actionId.value) return
  const operation = `callback:retry:${row.taskId}`
  const attempt = submissions.begin(operation)
  if (!attempt) return
  let succeeded = false
  try {
    await ElMessageBox.confirm(
      '人工 Retry 只授权一次额外 Attempt，不会重写历史结果或无限扩大预算。确认继续？',
      '二次确认',
      { type: 'warning' },
    )
    actionId.value = row.taskId
    await retryCallbackTask(row.taskId, attempt.key, 0)
    succeeded = true
    ElMessage.success('已授权一次人工重试')
    await load()
    await openAttempts(row)
  } catch (failure) {
    if (failure !== 'cancel' && !errors.latest) errors.capture({ code: 'CALLBACK_RETRY_FAILED', message: 'Callback 人工重试失败' })
  } finally {
    actionId.value = null
    attempt.finish(succeeded)
  }
}

async function cancel(row: CallbackTask) {
  if (actionId.value) return
  const operation = `callback:cancel:${row.taskId}`
  const attempt = submissions.begin(operation)
  if (!attempt) return
  let succeeded = false
  try {
    await ElMessageBox.confirm(
      '只能取消尚未开始发送的任务；RUNNING 任务不会被伪装成已撤回。确认继续？',
      '二次确认',
      { type: 'warning' },
    )
    actionId.value = row.taskId
    await cancelCallbackTask(row.taskId, attempt.key)
    succeeded = true
    ElMessage.success('Callback 取消请求已提交')
    await load()
  } catch (failure) {
    if (failure !== 'cancel' && !errors.latest) errors.capture({ code: 'CALLBACK_CANCEL_FAILED', message: 'Callback 取消失败' })
  } finally {
    actionId.value = null
    attempt.finish(succeeded)
  }
}

function statusType(status: CallbackTaskStatus) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'FAILED_PREPARATION' || status === 'FAILED_UNCONFIRMED') return 'danger'
  if (status === 'RUNNING' || status === 'RETRYING') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'primary'
}

function canRetry(status: CallbackTaskStatus) {
  return status === 'FAILED' || status === 'FAILED_PREPARATION' || status === 'FAILED_UNCONFIRMED'
}

function canCancel(status: CallbackTaskStatus) {
  return status === 'NEW' || status === 'RETRYING'
}

onMounted(load)
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">CALLBACK DELIVERY</p>
        <h2>回调任务</h2>
        <p>Task 固定最终 URL/Header/Payload、Release 和安全策略；Attempt 展示 lease/fencing 与确定投递语义。</p>
      </div>
    </div>
    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="Provider"><el-input v-model="filter.providerCode" clearable style="width: 150px" /></el-form-item>
        <el-form-item label="API"><el-input v-model="filter.apiCode" clearable style="width: 170px" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="filter.status" clearable style="width: 210px"><el-option v-for="status in ['NEW','RETRYING','RUNNING','SUCCESS','FAILED','FAILED_PREPARATION','FAILED_UNCONFIRMED','CANCELLED']" :key="status" :label="status" :value="status" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" plain @click="load">查询</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="tasks" row-key="taskId">
        <el-table-column prop="deliveryId" label="Delivery ID" min-width="175" show-overflow-tooltip />
        <el-table-column label="接口" min-width="185"><template #default="{ row }">{{ row.providerCode }} / {{ row.apiCode }}</template></el-table-column>
        <el-table-column label="目标" min-width="185"><template #default="{ row }">{{ row.callbackHost }}{{ row.callbackPathMasked }}</template></el-table-column>
        <el-table-column label="状态" min-width="175"><template #default="{ row }"><el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="发送预算" width="110"><template #default="{ row }">{{ row.sendAttemptCount }} / {{ row.maxRetry + 1 }}</template></el-table-column>
        <el-table-column label="准备重试" width="105"><template #default="{ row }">{{ row.preparationRetryCount }} / {{ row.maxPreparationRetry }}</template></el-table-column>
        <el-table-column prop="fencingToken" label="Fencing" width="85" />
        <el-table-column prop="lastHttpStatus" label="HTTP" width="80" />
        <el-table-column prop="nextExecuteAt" label="下次执行" min-width="175" />
        <el-table-column label="操作" width="185" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="actionId === row.taskId" @click="openAttempts(row)">Attempts</el-button>
            <el-button link type="warning" :disabled="!canRetry(row.status)" @click="retry(row)">Retry</el-button>
            <el-button link type="danger" :disabled="!canCancel(row.status)" @click="cancel(row)">取消</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无 Callback Task" /></template>
      </el-table>
    </el-card>

    <el-card v-if="selected" class="table-card" shadow="never">
      <template #header><strong>{{ selected.deliveryId }} · Attempts</strong></template>
      <el-table :data="attempts" row-key="id">
        <el-table-column prop="attemptNo" label="Attempt" width="85" />
        <el-table-column prop="sendAttemptNo" label="Send" width="75" />
        <el-table-column prop="fencingToken" label="Fencing" width="85" />
        <el-table-column prop="status" label="状态" min-width="165" />
        <el-table-column prop="deliveryCertainty" label="投递确定性" min-width="190" />
        <el-table-column prop="httpStatus" label="HTTP" width="80" />
        <el-table-column prop="startedAt" label="开始" min-width="175" />
        <el-table-column prop="completedAt" label="完成" min-width="175" />
        <el-table-column prop="resultMasked" label="结果（脱敏）" min-width="180" show-overflow-tooltip />
      </el-table>
    </el-card>
  </section>
</template>
