<template>
  <div style="display: grid; gap: 14px;">
    <div class="vc-card">
      <div class="vc-card-title">
        引车员排班格子表
        <span class="vc-muted" style="font-weight: 400; font-size: 12px;">
          纵轴引车员 · 横轴小时段 8:00-18:00 ｜ 点空格派单，点车牌小块改派 ｜ 每次打开都从服务端恢复格子
        </span>
        <el-date-picker
          v-model="date"
          size="small"
          type="date"
          value-format="YYYY-MM-DD"
          style="width: 150px; margin-left: auto;"
          @change="load"
        />
        <el-button size="small" @click="load">刷新</el-button>
      </div>

      <div class="vc-sched-legend">
        <span><i class="vc-dot waiting"></i>待派</span>
        <span><i class="vc-dot checking"></i>在检</span>
        <span><i class="vc-dot pass"></i>合格</span>
        <span><i class="vc-dot fail"></i>不合格</span>
        <span><i class="vc-dot conflict"></i>有冲突（悬停看原因）</span>
      </div>

      <div class="vc-sched">
        <div class="vc-sched-corner">引车员 / 时段</div>
        <div v-for="h in HOURS" :key="'h' + h" class="vc-sched-col-head">{{ h }}:00</div>

        <template v-for="row in rows" :key="row.driverId">
          <div class="vc-sched-row-head">
            <div class="vc-sched-name">
              {{ row.driverName }}
              <el-tag v-if="row.status !== 'ON_DUTY'" size="small" type="info">不在岗</el-tag>
              <el-tag v-else-if="!row.certValid" size="small" type="danger">资质过期</el-tag>
            </div>
            <div class="vc-sched-meta">
              {{ row.certLevel }} 证 ｜ 有效期至 {{ row.certExpireDate || '长期' }}
            </div>
            <div class="vc-sched-meta">
              排班 {{ shiftLabel(row.shiftHours) }} ｜ 已检 {{ row.checkedCount }} 台
            </div>
          </div>
          <div
            v-for="cell in row.cells"
            :key="row.driverId + '-' + cell.hour"
            class="vc-sched-cell"
            :class="cellClass(row, cell)"
            @click="onCell(row, cell)"
          >
            <template v-if="cell.taskId">
              <div class="vc-cell-plate">
                {{ cell.plateNo }}
                <el-tooltip v-if="cell.conflictReason" :content="cell.conflictReason" placement="top">
                  <span class="vc-cell-warn">⚠</span>
                </el-tooltip>
              </div>
              <div class="vc-cell-meta">{{ cell.stationName || '未上线' }}</div>
              <el-tag size="small" :type="cellTag(cell).type">{{ cellTag(cell).label }}</el-tag>
              <div v-if="cell.conflictReason" class="vc-cell-conflict">{{ cell.conflictReason }}</div>
            </template>
            <span v-else class="vc-cell-empty">＋</span>
          </div>
        </template>
      </div>
    </div>

    <div class="vc-card">
      <div class="vc-card-title">按引车员统计当日台次</div>
      <el-table :data="driverStats" size="small">
        <el-table-column prop="driverName" label="引车员" width="140" />
        <el-table-column prop="taskCount" label="当日台次" width="120" />
        <el-table-column label="负载">
          <template #default="{ row }">
            <el-progress
              :percentage="Math.min(100, row.taskCount * 20)"
              :stroke-width="12"
              :color="row.taskCount >= 4 ? '#d8342c' : '#01579b'"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>

  <!-- 派单：待检车辆 + 运行中的检测线 + 当前引车员小时格，一次绑定 -->
  <el-dialog v-model="assignVisible" title="派单" width="460px">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
      引车员 {{ activeDriver?.driverName }}（{{ activeDriver?.certLevel }}）｜ 时段 {{ activeCell?.hour }}:00
      ｜ 任务、小时格、车辆队列与检测线起点将一次绑定，失败不留半派发记录
    </el-alert>
    <el-form label-width="100px">
      <el-form-item label="待检车辆">
        <el-select v-model="assignForm.vehicleId" filterable style="width: 100%;" placeholder="选择当日待检车辆">
          <el-option
            v-for="v in assignable"
            :key="v.id"
            :label="v.plateNo + ' · ' + v.ownerName"
            :value="v.id"
          />
        </el-select>
        <div v-if="!assignable.length" class="vc-muted" style="font-size: 12px; margin-top: 4px;">
          当日待检队列为空，请先在「工位流水线」页建档车辆
        </div>
      </el-form-item>
      <el-form-item label="检测线">
        <el-select v-model="assignForm.lineId" style="width: 100%;">
          <el-option
            v-for="l in lines"
            :key="l.id"
            :label="l.lineNo + ' · ' + l.lineName + (l.status !== 'RUNNING' ? '（停用）' : '')"
            :value="l.id"
            :disabled="l.status !== 'RUNNING'"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="assignVisible = false">取消</el-button>
      <el-button type="primary" :loading="assigning" :disabled="!assignForm.vehicleId || !assignForm.lineId" @click="submitAssign">
        派单
      </el-button>
    </template>
  </el-dialog>

  <!-- 改派：仅未开始检测的任务可改派；冲突时返回任务当前归属 -->
  <el-dialog v-model="reassignVisible" title="改派" width="460px">
    <el-alert
      :type="reassignLocked ? 'warning' : 'info'"
      :closable="false"
      show-icon
      style="margin-bottom: 12px;"
    >
      当前任务 {{ activeCell?.taskNo }} ｜ 车辆 {{ activeCell?.plateNo }} ｜
      当前归属：{{ activeDriver?.driverName }} · {{ activeCell?.hour }}:00
      <template v-if="reassignLocked"><br />已开始检测或已结束的任务不可改派，归属仅供参考。</template>
    </el-alert>
    <el-form label-width="100px">
      <el-form-item label="改派给">
        <el-select v-model="reassignForm.driverId" style="width: 100%;" :disabled="reassignLocked">
          <el-option
            v-for="c in candidates"
            :key="c.driverId"
            :label="candidateLabel(c)"
            :value="c.driverId"
            :disabled="!c.available"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="时段">
        <el-select v-model="reassignForm.hour" style="width: 100%;" :disabled="reassignLocked" @change="loadCandidates">
          <el-option v-for="h in HOURS" :key="h" :label="h + ':00'" :value="h" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="reassignVisible = false">取消</el-button>
      <el-button type="primary" :loading="reassigning" :disabled="reassignLocked" @click="submitReassign">
        改派
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchSchedule,
  fetchDriverStats,
  fetchDriverCandidates,
  fetchLines,
  fetchAssignableVehicles,
  createTask,
  reassignTask
} from '../api'

const HOURS = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17]

function todayStr () {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

const date = ref(todayStr())
const rows = ref([])
const driverStats = ref([])
const lines = ref([])
const assignable = ref([])
const candidates = ref([])

const assignVisible = ref(false)
const reassignVisible = ref(false)
const assigning = ref(false)
const reassigning = ref(false)
const activeDriver = ref(null)
const activeCell = ref(null)
const assignForm = reactive({ vehicleId: null, lineId: null })
const reassignForm = reactive({ driverId: null, hour: 9 })

// 已开始检测 / 已结束的任务锁定改派（服务端同样拒绝，这里提前置灰）
const reassignLocked = computed(() => activeCell.value && activeCell.value.cellState !== 'WAITING')

function shiftLabel (shiftHours) {
  if (!shiftHours) return '未排班'
  const parts = String(shiftHours).split(',')
  return parts[0] + ':00-' + (Number(parts[parts.length - 1]) + 1) + ':00'
}

function inShift (shiftHours, hour) {
  return String(shiftHours || '').split(',').map(Number).includes(hour)
}

function cellTag (cell) {
  if (cell.cellState === 'WAITING') return { label: '待派', type: 'warning' }
  if (cell.cellState === 'FINISHED') {
    return cell.status === 'PASS'
      ? { label: '合格', type: 'success' }
      : { label: '不合格', type: 'danger' }
  }
  if (cell.status === 'RETEST') return { label: '复检', type: 'warning' }
  return { label: '在检', type: 'primary' }
}

function cellClass (row, cell) {
  return {
    off: !inShift(row.shiftHours, cell.hour),
    filled: !!cell.taskId,
    waiting: cell.cellState === 'WAITING',
    checking: cell.cellState === 'CHECKING',
    pass: cell.cellState === 'FINISHED' && cell.status === 'PASS',
    fail: cell.cellState === 'FINISHED' && cell.status === 'FAIL',
    conflict: !!cell.conflictReason
  }
}

function candidateLabel (c) {
  const base = c.driverName + '（' + c.certLevel + ' · ' + (c.statusName || '') + '）'
  return c.available ? base : base + ' — ' + c.blockReason
}

async function load () {
  rows.value = await fetchSchedule(date.value)
  driverStats.value = await fetchDriverStats(date.value)
  lines.value = await fetchLines()
  assignable.value = await fetchAssignableVehicles(date.value)
}

async function loadCandidates () {
  candidates.value = await fetchDriverCandidates(date.value, reassignForm.hour, activeCell.value?.taskId)
}

async function onCell (row, cell) {
  activeDriver.value = row
  activeCell.value = cell
  if (cell.taskId) {
    reassignForm.driverId = row.driverId
    reassignForm.hour = cell.hour
    reassignVisible.value = true
    await loadCandidates()
    return
  }
  if (!inShift(row.shiftHours, cell.hour)) return
  if (row.status !== 'ON_DUTY') {
    ElMessage.warning('引车员「' + row.driverName + '」当日不在岗，不能派发')
    return
  }
  if (!row.certValid) {
    ElMessage.warning('引车员「' + row.driverName + '」资质已于 ' + row.certExpireDate + ' 过期，不能派发')
    return
  }
  assignForm.vehicleId = assignable.value[0]?.id ?? null
  assignForm.lineId = (lines.value.find((l) => l.status === 'RUNNING') || lines.value[0])?.id ?? null
  assignVisible.value = true
}

async function submitAssign () {
  if (assigning.value) return
  assigning.value = true
  try {
    const task = await createTask({
      vehicleId: assignForm.vehicleId,
      lineId: assignForm.lineId,
      driverId: activeDriver.value.driverId,
      scheduleHour: activeCell.value.hour,
      checkDate: date.value
    })
    assignVisible.value = false
    ElMessage.success('派单成功：' + task.taskNo)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
    await load()
  } finally {
    assigning.value = false
  }
}

async function submitReassign () {
  if (reassigning.value) return
  reassigning.value = true
  try {
    const cell = await reassignTask(activeCell.value.taskId, {
      driverId: reassignForm.driverId,
      scheduleHour: reassignForm.hour,
      expectedDriverId: activeDriver.value.driverId
    })
    reassignVisible.value = false
    ElMessage.success('改派成功：' + cell.taskNo + ' → ' + cell.hourLabel)
    await load()
  } catch (e) {
    // 冲突提示里带任务当前归属；刷新格子恢复服务端真实状态
    ElMessage.error(e.message)
    await load()
  } finally {
    reassigning.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.vc-sched-legend {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--vc-muted);
  margin-bottom: 10px;
  align-items: center;
}

.vc-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 3px;
  margin-right: 5px;
}

.vc-dot.waiting { background: #e6a23c; }
.vc-dot.checking { background: #01579b; }
.vc-dot.pass { background: #2ba471; }
.vc-dot.fail { background: #d8342c; }
.vc-dot.conflict { background: #fff; border: 2px solid #d8342c; }

.vc-sched {
  display: grid;
  grid-template-columns: 190px repeat(10, 1fr);
  gap: 4px;
}

.vc-sched-corner,
.vc-sched-col-head {
  background: #eaf2fa;
  color: var(--vc-primary-deep);
  font-size: 12px;
  font-weight: 700;
  text-align: center;
  padding: 6px 4px;
  border-radius: 4px;
}

.vc-sched-corner {
  text-align: left;
  padding-left: 10px;
}

.vc-sched-row-head {
  border: 1px solid var(--vc-border);
  border-radius: 6px;
  padding: 8px 10px;
  background: #fafcfe;
  display: flex;
  flex-direction: column;
  gap: 2px;
  justify-content: center;
}

.vc-sched-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--vc-primary-deep);
  display: flex;
  align-items: center;
  gap: 6px;
}

.vc-sched-meta {
  font-size: 11px;
  color: var(--vc-muted);
}

.vc-sched-cell {
  min-height: 68px;
  border: 1px dashed #d3dfe9;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  cursor: pointer;
  background: #fff;
  transition: all 0.18s ease;
  padding: 4px;
}

.vc-sched-cell.off {
  background: repeating-linear-gradient(45deg, #f5f7f9 0 8px, #eef2f6 8px 16px);
  cursor: not-allowed;
}

.vc-sched-cell:hover {
  border-color: var(--vc-primary);
  box-shadow: 0 4px 10px rgba(1, 87, 155, 0.12);
}

.vc-sched-cell.filled {
  border-style: solid;
  border-color: #b9d2e8;
  background: linear-gradient(180deg, #f2f8fd 0%, #e6f0f8 100%);
}

.vc-sched-cell.filled.waiting {
  border-color: #e6c88f;
  background: linear-gradient(180deg, #fdf6ec 0%, #faeeda 100%);
}

.vc-sched-cell.filled.pass {
  border-color: #a8d8c2;
  background: linear-gradient(180deg, #f0f9f4 0%, #e1f3ea 100%);
}

.vc-sched-cell.filled.fail {
  border-color: #efb3ae;
  background: linear-gradient(180deg, #fef0ef 0%, #fde3e1 100%);
}

.vc-sched-cell.conflict {
  border: 2px solid #d8342c;
}

.vc-cell-plate {
  font-size: 13px;
  font-weight: 700;
  color: var(--vc-primary-deep);
  display: flex;
  align-items: center;
  gap: 3px;
}

.vc-cell-warn {
  color: #d8342c;
  font-size: 12px;
}

.vc-cell-meta {
  font-size: 11px;
  color: var(--vc-muted);
}

.vc-cell-conflict {
  font-size: 10px;
  color: #d8342c;
  text-align: center;
  line-height: 1.2;
}

.vc-cell-empty {
  color: #c2cedb;
  font-size: 20px;
}
</style>
