<template>
  <div style="display: grid; gap: 14px;">
    <div class="vc-card">
      <div class="vc-card-title">
        引车员排班格子表
        <span class="vc-muted" style="font-weight: 400; font-size: 12px;">
          纵轴引车员 · 横轴小时段 8:00-18:00 ｜ 点空格派单，点已有车牌小块改派
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

      <div class="vc-sched">
        <div class="vc-sched-corner">引车员 / 时段</div>
        <div v-for="h in HOURS" :key="'h' + h" class="vc-sched-col-head">{{ h }}:00</div>

        <template v-for="row in rows" :key="row.driverId">
          <div class="vc-sched-row-head">
            <div class="vc-sched-name">{{ row.driverName }}</div>
            <div class="vc-sched-meta">
              {{ row.certLevel }} 证 ｜ 排班 {{ shiftLabel(row.shiftHours) }} ｜ 已检 {{ row.checkedCount }} 台
            </div>
            <el-tag v-if="row.status !== 'ON_DUTY'" size="small" type="info">不在岗</el-tag>
          </div>
          <div
            v-for="cell in row.cells"
            :key="row.driverId + '-' + cell.hour"
            class="vc-sched-cell"
            :class="{
              off: !inShift(row.shiftHours, cell.hour),
              filled: !!cell.taskId
            }"
            @click="onCell(row, cell)"
          >
            <template v-if="cell.taskId">
              <div class="vc-cell-plate">{{ cell.plateNo }}</div>
              <div class="vc-cell-meta">{{ cell.stationName || cell.statusName }}</div>
              <el-tag size="small" :type="tagType(cell.status)">{{ cell.statusName }}</el-tag>
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

  <!-- 派单 -->
  <el-dialog v-model="assignVisible" title="派单" width="440px">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
      引车员 {{ activeDriver?.driverName }} ｜ 时段 {{ activeCell?.hour }}:00
    </el-alert>
    <el-form label-width="100px">
      <el-form-item label="车辆">
        <el-select v-model="assignForm.vehicleId" filterable style="width: 100%;">
          <el-option
            v-for="v in assignable"
            :key="v.id"
            :label="v.plateNo + ' · ' + v.ownerName"
            :value="v.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="检测线">
        <el-select v-model="assignForm.lineId" style="width: 100%;">
          <el-option v-for="l in lines" :key="l.id" :label="l.lineNo + ' · ' + l.lineName" :value="l.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="assignVisible = false">取消</el-button>
      <el-button type="primary" @click="submitAssign">派单</el-button>
    </template>
  </el-dialog>

  <!-- 改派 -->
  <el-dialog v-model="reassignVisible" title="改派" width="440px">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
      当前任务 {{ activeCell?.taskNo }} ｜ 车辆 {{ activeCell?.plateNo }}
    </el-alert>
    <el-form label-width="100px">
      <el-form-item label="改派给">
        <el-select v-model="reassignForm.driverId" style="width: 100%;">
          <el-option v-for="d in drivers" :key="d.id" :label="d.driverName" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="时段">
        <el-select v-model="reassignForm.hour" style="width: 100%;">
          <el-option v-for="h in HOURS" :key="h" :label="h + ':00'" :value="h" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="reassignVisible = false">取消</el-button>
      <el-button type="primary" @click="submitReassign">改派</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchSchedule,
  fetchDriverStats,
  fetchDrivers,
  fetchLines,
  fetchAssignableVehicles,
  createTask,
  reassignTask
} from '../api'

const HOURS = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17]

const date = ref(new Date().toISOString().slice(0, 10))
const rows = ref([])
const driverStats = ref([])
const drivers = ref([])
const lines = ref([])
const assignable = ref([])

const assignVisible = ref(false)
const reassignVisible = ref(false)
const activeDriver = ref(null)
const activeCell = ref(null)
const assignForm = reactive({ vehicleId: null, lineId: null })
const reassignForm = reactive({ driverId: null, hour: 9 })

function shiftLabel (shiftHours) {
  if (!shiftHours) return '未排班'
  const parts = String(shiftHours).split(',')
  return parts[0] + ':00-' + (Number(parts[parts.length - 1]) + 1) + ':00'
}

function inShift (shiftHours, hour) {
  return String(shiftHours || '').split(',').map(Number).includes(hour)
}

function tagType (status) {
  if (status === 'PASS') return 'success'
  if (status === 'FAIL') return 'danger'
  if (status === 'RETEST') return 'warning'
  return 'primary'
}

async function load () {
  rows.value = await fetchSchedule(date.value)
  driverStats.value = await fetchDriverStats(date.value)
  drivers.value = await fetchDrivers()
  lines.value = await fetchLines()
  assignable.value = await fetchAssignableVehicles(date.value)
}

async function onCell (row, cell) {
  activeDriver.value = row
  activeCell.value = cell
  if (cell.taskId) {
    reassignForm.driverId = row.driverId
    reassignForm.hour = cell.hour
    reassignVisible.value = true
  } else {
    assignForm.vehicleId = assignable.value[0]?.id ?? null
    assignForm.lineId = lines.value[0]?.id ?? null
    assignVisible.value = true
  }
}

async function submitAssign () {
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
  }
}

async function submitReassign () {
  try {
    await reassignTask(activeCell.value.taskId, reassignForm.driverId, reassignForm.hour)
    reassignVisible.value = false
    ElMessage.success('改派成功')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<style scoped>
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
}

.vc-sched-meta {
  font-size: 11px;
  color: var(--vc-muted);
}

.vc-sched-cell {
  min-height: 62px;
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

.vc-cell-plate {
  font-size: 13px;
  font-weight: 700;
  color: var(--vc-primary-deep);
}

.vc-cell-meta {
  font-size: 11px;
  color: var(--vc-muted);
}

.vc-cell-empty {
  color: #c2cedb;
  font-size: 20px;
}
</style>
