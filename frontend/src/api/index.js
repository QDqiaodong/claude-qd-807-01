import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const msg = err?.response?.data?.message || err.message || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

const unwrap = (p) => p.then((d) => d?.data)

export const fetchLines = () => unwrap(http.get('/lines'))
export const fetchPipeline = () => unwrap(http.get('/lines/stations/pipeline'))
export const fetchAllStations = () => unwrap(http.get('/lines/stations'))
export const fetchStationsOfLine = (id) => unwrap(http.get(`/lines/${id}/stations`))
export const updateStationStatus = (id, status) =>
  unwrap(http.post(`/lines/stations/${id}/status`, null, { params: { status } }))

export const fetchVehicles = () => unwrap(http.get('/vehicles'))
export const fetchVehicle = (id) => unwrap(http.get(`/vehicles/${id}`))
export const createVehicle = (payload) => unwrap(http.post('/vehicles', payload))
export const fetchAssignableVehicles = (date) =>
  unwrap(http.get('/vehicles/assignable', { params: { date } }))

export const fetchTasks = (status) => unwrap(http.get('/tasks', { params: { status } }))
export const fetchTask = (id) => unwrap(http.get(`/tasks/${id}`))
export const createTask = (payload) => unwrap(http.post('/tasks', payload))
export const submitStationResult = (id, payload) =>
  unwrap(http.post(`/tasks/${id}/station-result`, payload))
export const fetchStationStats = () => unwrap(http.get('/tasks/station-stats'))
export const fetchFailRank = () => unwrap(http.get('/tasks/fail-rank'))
export const fetchOverview = () => unwrap(http.get('/tasks/overview'))

export const fetchReports = () => unwrap(http.get('/reports'))
export const fetchReport = (id) => unwrap(http.get(`/reports/${id}`))
export const issueReport = (taskId, payload) =>
  unwrap(http.post(`/reports/tasks/${taskId}/issue`, payload))
export const retestReport = (id, payload) => unwrap(http.post(`/reports/${id}/retest`, payload))
export const fetchLineReportStats = () => unwrap(http.get('/reports/line-stats'))
export const fetchFailToday = (date) => unwrap(http.get('/reports/fail-today', { params: { date } }))

export const fetchDevices = () => unwrap(http.get('/devices'))
export const fetchDevice = (id) => unwrap(http.get(`/devices/${id}`))
export const calibrateDevice = (id, payload) =>
  unwrap(http.post(`/devices/${id}/calibrate`, payload))
export const fetchCalStat = () => unwrap(http.get('/devices/cal-stat'))

export const fetchDrivers = () => unwrap(http.get('/drivers'))
export const fetchSchedule = (date) => unwrap(http.get('/drivers/schedule', { params: { date } }))
export const fetchDriverCandidates = (date, hour, excludeTaskId) =>
  unwrap(http.get('/drivers/candidates', { params: { date, hour, excludeTaskId } }))
export const assignTask = (driverId, payload) =>
  unwrap(http.post(`/drivers/${driverId}/assign`, payload))
export const reassignTask = (taskId, payload) =>
  unwrap(http.post(`/drivers/tasks/${taskId}/reassign`, payload))
export const fetchDriverStats = (date) =>
  unwrap(http.get('/drivers/stats', { params: { date } }))

// ---------------- 在检车辆跨线恢复处置台 ----------------
export const fetchRecoveryBoard = () => unwrap(http.get('/recovery/board'))
export const fetchAffectedTask = (taskId) => unwrap(http.get(`/recovery/tasks/${taskId}`))
export const confirmTransfer = (taskId, payload) =>
  unwrap(http.post(`/recovery/tasks/${taskId}/transfer`, payload))
export const keepPending = (taskId, payload) =>
  unwrap(http.post(`/recovery/tasks/${taskId}/keep-pending`, payload))

export default http
