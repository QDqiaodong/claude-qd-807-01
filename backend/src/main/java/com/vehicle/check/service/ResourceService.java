package com.vehicle.check.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vehicle.check.common.AssignConflictException;
import com.vehicle.check.common.BizException;
import com.vehicle.check.common.StationDict;
import com.vehicle.check.domain.Device;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.AssignRequest;
import com.vehicle.check.dto.CalibrateRequest;
import com.vehicle.check.dto.CalibrateStatDTO;
import com.vehicle.check.dto.DeviceVO;
import com.vehicle.check.dto.DriverCandidateVO;
import com.vehicle.check.dto.DriverStatDTO;
import com.vehicle.check.dto.ReassignRequest;
import com.vehicle.check.dto.ScheduleCellVO;
import com.vehicle.check.dto.ScheduleRowVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 设备标定与引车员：设备台账倒计时、登记标定结果；引车员排班格子表、派单与改派。
 * <p>
 * 派发/改派共用一套引车员约束（在岗、资质有效、小时在班、同日同小时唯一未结束任务），
 * 任务行锁（PESSIMISTIC_WRITE）+ vc_task.uk_task_active_slot 唯一索引双重防护：
 * 并发派发/改派撞同一小时格时只允许一次成功，失败者拿到任务当前归属，同一任务不会占两格。
 */
@Service
public class ResourceService {

    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;
    private final TaskRepository taskRepository;
    private final VehicleRepository vehicleRepository;
    private final StationRepository stationRepository;
    private final TaskStationRecordRepository recordRepository;

    public ResourceService(DeviceRepository deviceRepository, DriverRepository driverRepository,
                           TaskRepository taskRepository, VehicleRepository vehicleRepository,
                           StationRepository stationRepository, TaskStationRecordRepository recordRepository) {
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
        this.taskRepository = taskRepository;
        this.vehicleRepository = vehicleRepository;
        this.stationRepository = stationRepository;
        this.recordRepository = recordRepository;
    }

    // ---------------- 设备标定 ----------------

    public List<DeviceVO> listDevices() {
        LocalDate today = LocalDate.now();
        List<DeviceVO> result = new ArrayList<>();
        for (Device device : deviceRepository.findAllByOrderByNextCalDateAsc()) {
            result.add(toVO(device, today));
        }
        return result;
    }

    public DeviceVO getDevice(Long id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new BizException("设备不存在：id=" + id));
        return toVO(device, LocalDate.now());
    }

    /**
     * 登记标定结果：标定机构、结论、下次标定日期，并自动重置倒计时
     */
    @Transactional
    public DeviceVO calibrate(Long id, CalibrateRequest request) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new BizException("设备不存在：id=" + id));
        if (request.getCalOrg() == null || request.getCalOrg().isBlank()) {
            throw new BizException("标定机构不能为空");
        }
        if (!Set.of("PASS", "FAIL").contains(request.getCalResult())) {
            throw new BizException("标定结论只能是 合格(PASS) / 不合格(FAIL)");
        }
        LocalDate today = LocalDate.now();
        LocalDate next = request.getNextCalDate();
        if (next == null) {
            throw new BizException("下次标定日期不能为空");
        }
        if (!next.isAfter(today)) {
            throw new BizException("下次标定日期必须晚于今天（" + today + "）");
        }
        if ("FAIL".equals(request.getCalResult())) {
            throw new BizException("设备「" + device.getDeviceName() + "」标定结论为不合格，需停用整改后再登记合格结论");
        }

        device.setCalOrg(request.getCalOrg().trim());
        device.setCalResult("PASS");
        device.setLastCalDate(request.getLastCalDate() == null ? today : request.getLastCalDate());
        device.setNextCalDate(next);
        device.setStatus(remainDays(next, today) <= StationDict.CAL_DUE_DAYS ? "DUE" : "NORMAL");
        return toVO(deviceRepository.save(device), today);
    }

    public CalibrateStatDTO calStat() {
        LocalDate today = LocalDate.now();
        return deviceRepository.statCalibration(today, today.plusDays(StationDict.CAL_DUE_DAYS));
    }

    // ---------------- 引车员排班 ----------------

    /**
     * 排班格子表：纵轴引车员（资质/在岗/排班）、横轴小时段（8:00-18:00），
     * 格子里塞检测任务车牌小块，并区分 待派 / 在检 / 合格 / 不合格 与冲突原因。
     * 页面每次打开都从服务端重建格子，刷新即恢复服务端真实状态。
     */
    public List<ScheduleRowVO> schedule(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        Map<Long, String> plateMap = vehicleRepository.findAll().stream()
                .collect(Collectors.toMap(Vehicle::getId, Vehicle::getPlateNo, (a, b) -> a));
        Map<Long, String> stationMap = stationRepository.findAll().stream()
                .collect(Collectors.toMap(Station::getId, Station::getStationName, (a, b) -> a));

        List<Task> dayTasks = taskRepository.findByCheckDate(day);
        Set<Long> startedIds = startedTaskIds(dayTasks);

        List<ScheduleRowVO> rows = new ArrayList<>();
        for (Driver driver : driverRepository.findAllByOrderByIdAsc()) {
            ScheduleRowVO row = new ScheduleRowVO();
            row.setDriverId(driver.getId());
            row.setDriverName(driver.getDriverName());
            row.setCertLevel(driver.getCertLevel());
            row.setCertNo(driver.getCertNo());
            row.setCertExpireDate(driver.getCertExpireDate());
            row.setCertValid(certValid(driver, day));
            row.setShiftHours(driver.getShiftHours());
            row.setShiftHourList(shiftHourList(driver.getShiftHours()));
            row.setCheckedCount(driver.getCheckedCount());
            row.setStatus(driver.getStatus());
            row.setStatusName("ON_DUTY".equals(driver.getStatus()) ? "在岗" : "休息");

            List<ScheduleCellVO> cells = new ArrayList<>();
            for (int hour = StationDict.SHIFT_START_HOUR; hour < StationDict.SHIFT_END_HOUR; hour++) {
                final int slot = hour;
                ScheduleCellVO cell = new ScheduleCellVO();
                cell.setHour(hour);
                cell.setHourLabel(hour + ":00");
                List<Task> hit = dayTasks.stream()
                        .filter(task -> driver.getId().equals(task.getDriverId()))
                        .filter(task -> task.getScheduleHour() != null && task.getScheduleHour() == slot)
                        .toList();
                if (!hit.isEmpty()) {
                    Task task = hit.get(0);
                    fillCellTask(cell, task, plateMap, stationMap);
                    fillCellState(cell, task, startedIds.contains(task.getId()));
                    // 已结束任务是历史定格，不再计算冲突；未结束任务实时核对引车员约束
                    if (!"FINISHED".equals(cell.getCellState())) {
                        cell.setConflictReason(cellConflict(driver, day, hour));
                    }
                }
                cells.add(cell);
            }
            row.setCells(cells);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 候选引车员：指定日期 + 时段下每个引车员是否可派、阻断原因、该时段格内已有任务。
     * 派单/改派对话框据此展示「谁能派、谁不能派、为什么」；
     * excludeTaskId 用于改派场景把任务自身从小时格占用检查中排除。
     */
    public List<DriverCandidateVO> candidates(LocalDate date, Integer hour, Long excludeTaskId) {
        LocalDate day = date == null ? LocalDate.now() : date;
        Map<Long, String> plateMap = vehicleRepository.findAll().stream()
                .collect(Collectors.toMap(Vehicle::getId, Vehicle::getPlateNo, (a, b) -> a));

        List<DriverCandidateVO> result = new ArrayList<>();
        for (Driver driver : driverRepository.findAllByOrderByIdAsc()) {
            DriverCandidateVO vo = new DriverCandidateVO();
            vo.setDriverId(driver.getId());
            vo.setDriverName(driver.getDriverName());
            vo.setCertLevel(driver.getCertLevel());
            vo.setCertNo(driver.getCertNo());
            vo.setCertExpireDate(driver.getCertExpireDate());
            vo.setCertValid(certValid(driver, day));
            vo.setStatus(driver.getStatus());
            vo.setStatusName("ON_DUTY".equals(driver.getStatus()) ? "在岗" : "休息");
            vo.setShiftHours(driver.getShiftHours());
            vo.setShiftHourList(shiftHourList(driver.getShiftHours()));
            vo.setCheckedCount(driver.getCheckedCount());

            List<String> reasons = new ArrayList<>();
            if (!"ON_DUTY".equals(driver.getStatus())) {
                reasons.add("当日不在岗");
            }
            if (!vo.isCertValid()) {
                reasons.add("资质已于 " + driver.getCertExpireDate() + " 过期");
            }
            if (hour != null) {
                if (hour < StationDict.SHIFT_START_HOUR || hour >= StationDict.SHIFT_END_HOUR) {
                    reasons.add("时段超出 " + StationDict.SHIFT_START_HOUR + ":00-"
                            + StationDict.SHIFT_END_HOUR + ":00");
                } else if (!vo.getShiftHourList().contains(hour)) {
                    reasons.add("该时段不在当班排班内（排班 " + TaskFlowService.formatShift(driver.getShiftHours()) + "）");
                } else {
                    List<Task> active = taskRepository.findActiveSlotTasks(driver.getId(), day, hour).stream()
                            .filter(t -> !t.getId().equals(excludeTaskId))
                            .toList();
                    if (!active.isEmpty()) {
                        Task occupying = active.get(0);
                        String plate = plateMap.getOrDefault(occupying.getVehicleId(), "");
                        vo.setTaskId(occupying.getId());
                        vo.setTaskNo(occupying.getTaskNo());
                        vo.setPlateNo(plate);
                        reasons.add("该时段已有未结束任务 " + occupying.getTaskNo() + "（" + plate + "）");
                    }
                }
            }
            vo.setAvailable(reasons.isEmpty());
            vo.setBlockReason(reasons.isEmpty() ? null : String.join("；", reasons));
            result.add(vo);
        }
        return result;
    }

    /**
     * 派单（补派）：把「尚未指派引车员」的检测任务挂到引车员的某个小时段。
     * 已有引车员的任务必须走改派，避免绕过「已开始检测不可改派」的规则。
     */
    @Transactional
    public ScheduleCellVO assign(Long driverId, AssignRequest request) {
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> new BizException("引车员不存在：id=" + driverId));
        Task task = taskRepository.findByIdForUpdate(request.getTaskId())
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + request.getTaskId()));

        // 幂等：重复点击 → 任务已在该引车员此时段，直接返回当前格，不产生第二条记录
        if (driverId.equals(task.getDriverId()) && Objects.equals(task.getScheduleHour(), request.getScheduleHour())) {
            return toCell(task, request.getScheduleHour());
        }
        if (isFinished(task)) {
            throw new BizException("任务 " + task.getTaskNo() + " 已结束（"
                    + StationDict.taskStatusName(task.getStatus()) + "），不能再派发引车员");
        }
        if (task.getDriverId() != null) {
            throw new AssignConflictException("任务 " + task.getTaskNo() + " 已派给引车员，如需调整请使用改派；"
                    + currentOwnerText(task));
        }
        checkDriverAssignable(driver, task.getCheckDate(), request.getScheduleHour(), task.getId());

        task.setDriverId(driverId);
        task.setScheduleHour(request.getScheduleHour());
        saveGuardingSlot(task, "引车员「" + driver.getDriverName() + "」在 " + request.getScheduleHour()
                + ":00 时段的小时格刚被并发请求占用，本次派单未生效，请刷新排班后重试");
        return toCell(task, request.getScheduleHour());
    }

    /**
     * 改派：把「尚未开始检测」的任务换到另一名引车员的某个小时段。
     * 原格随任务行更新自动释放，新格只允许一次成功（行锁 + 唯一索引）；
     * 并发改派/重复点击时返回任务当前归属；已开始或已结束的任务拒绝改派，
     * 且任何情况下都不改变检测结果、当前工位与任务号。
     */
    @Transactional
    public ScheduleCellVO reassign(Long taskId, ReassignRequest request) {
        Task task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        Integer hour = request.getScheduleHour() == null ? task.getScheduleHour() : request.getScheduleHour();
        if (hour == null) {
            throw new BizException("任务 " + task.getTaskNo() + " 当前没有排班小时段，改派时必须指定目标时段");
        }
        Long targetDriverId = request.getDriverId();

        // 幂等：重复点击 / 并发重复改派到同一引车员同一时段 → 直接返回任务当前归属
        if (targetDriverId.equals(task.getDriverId()) && hour.equals(task.getScheduleHour())) {
            return toCell(task, hour);
        }
        // 并发改派：页面快照里的原引车员与服务端当前归属已不一致 → 拒绝并告知当前归属
        if (request.getExpectedDriverId() != null && !request.getExpectedDriverId().equals(task.getDriverId())) {
            throw new AssignConflictException("任务 " + task.getTaskNo() + " 的归属已发生变化，本次改派未生效；"
                    + currentOwnerText(task));
        }
        if (isFinished(task)) {
            throw new AssignConflictException("任务 " + task.getTaskNo() + " 已结束（"
                    + StationDict.taskStatusName(task.getStatus()) + "），不可改派；" + currentOwnerText(task));
        }
        if (recordRepository.existsByTaskId(taskId)) {
            throw new AssignConflictException("任务 " + task.getTaskNo() + " 已开始检测（当前第 "
                    + task.getCurrentSeqNo() + " 工位），不可改派；" + currentOwnerText(task));
        }
        Driver target = driverRepository.findById(targetDriverId)
                .orElseThrow(() -> new BizException("引车员不存在：id=" + targetDriverId));
        checkDriverAssignable(target, task.getCheckDate(), hour, task.getId());

        // 唯一索引兜底提示要用「改派前」的归属：冲突时本次操作未生效，归属仍是旧值
        String ownerBefore = currentOwnerText(task);
        // 只改引车员与小时格：任务号、当前工位、各工位检测结果原样保留
        task.setDriverId(targetDriverId);
        task.setScheduleHour(hour);
        saveGuardingSlot(task, "目标小时格刚被并发请求占用，本次改派未生效；" + ownerBefore);
        return toCell(task, hour);
    }

    public List<DriverStatDTO> driverStats(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        Map<Long, String> nameMap = driverRepository.findAll().stream()
                .collect(Collectors.toMap(Driver::getId, Driver::getDriverName, (a, b) -> a));
        return taskRepository.countGroupByDriverAndDate(day).stream()
                .peek(dto -> dto.setDriverName(nameMap.getOrDefault(dto.getDriverId(), "未派单")))
                .sorted((a, b) -> Long.compare(b.getTaskCount(), a.getTaskCount()))
                .toList();
    }

    public List<Driver> listDrivers() {
        return driverRepository.findAllByOrderByIdAsc();
    }

    // ---------------- 派发/改派共用规则 ----------------

    /**
     * 引车员可派校验：在岗 → 资质有效 → 小时 8:00-18:00 → 小时在班 → 同日同小时无其他未结束任务。
     * 供派单、改派共用；检测线/车辆侧规则在建档派发（TaskFlowService.createTask）中校验。
     */
    private void checkDriverAssignable(Driver driver, LocalDate day, Integer hour, Long excludeTaskId) {
        if (!"ON_DUTY".equals(driver.getStatus())) {
            throw new BizException("引车员「" + driver.getDriverName() + "」当日不在岗，不能派发");
        }
        if (!certValid(driver, day)) {
            throw new BizException("引车员「" + driver.getDriverName() + "」资质已于 "
                    + driver.getCertExpireDate() + " 过期，不能派发");
        }
        checkHour(hour);
        if (!shiftHourList(driver.getShiftHours()).contains(hour)) {
            throw new BizException("引车员「" + driver.getDriverName() + "」当日排班小时段为 "
                    + TaskFlowService.formatShift(driver.getShiftHours())
                    + "，不包含 " + hour + ":00 时段");
        }
        Task occupying = taskRepository.findActiveSlotTasks(driver.getId(), day, hour).stream()
                .filter(t -> !t.getId().equals(excludeTaskId))
                .findFirst().orElse(null);
        if (occupying != null) {
            String plate = vehicleRepository.findById(occupying.getVehicleId())
                    .map(Vehicle::getPlateNo).orElse("");
            throw new BizException("引车员「" + driver.getDriverName() + "」在 " + hour + ":00 时段已有未结束任务 "
                    + occupying.getTaskNo() + "（" + plate + "），同一引车员同日同小时只能有一个未结束任务");
        }
    }

    /**
     * 保存并立即落库：vc_task.uk_task_active_slot 唯一索引在并发撞格时兜底，
     * 冲突即抛业务异常，整个事务回滚，不留半派发记录。
     */
    private void saveGuardingSlot(Task task, String conflictMessage) {
        try {
            taskRepository.saveAndFlush(task);
        } catch (DataIntegrityViolationException e) {
            throw new AssignConflictException(conflictMessage);
        }
    }

    private static boolean isFinished(Task task) {
        return "PASS".equals(task.getStatus()) || "FAIL".equals(task.getStatus());
    }

    /** 任务当前归属描述：并发冲突响应里必须带上，前端展示后刷新格子 */
    private String currentOwnerText(Task task) {
        String owner = task.getDriverId() == null ? "未派单"
                : "引车员「" + driverRepository.findById(task.getDriverId())
                        .map(Driver::getDriverName).orElse("id=" + task.getDriverId()) + "」";
        String hour = task.getScheduleHour() == null ? "未排时段" : task.getScheduleHour() + ":00 时段";
        return "任务当前归属：" + owner + " · " + hour;
    }

    private ScheduleCellVO toCell(Task task, Integer hour) {
        ScheduleCellVO cell = new ScheduleCellVO();
        cell.setHour(hour);
        cell.setHourLabel(hour + ":00");
        cell.setTaskId(task.getId());
        cell.setTaskNo(task.getTaskNo());
        cell.setPlateNo(vehicleRepository.findById(task.getVehicleId()).map(Vehicle::getPlateNo).orElse(""));
        cell.setStatus(task.getStatus());
        cell.setStatusName(StationDict.taskStatusName(task.getStatus()));
        return cell;
    }

    private void fillCellTask(ScheduleCellVO cell, Task task,
                              Map<Long, String> plateMap, Map<Long, String> stationMap) {
        cell.setTaskId(task.getId());
        cell.setTaskNo(task.getTaskNo());
        cell.setPlateNo(plateMap.getOrDefault(task.getVehicleId(), ""));
        cell.setStatus(task.getStatus());
        cell.setStatusName(StationDict.taskStatusName(task.getStatus()));
        cell.setStationName(task.getCurrentStationId() == null ? ""
                : stationMap.getOrDefault(task.getCurrentStationId(), ""));
    }

    /** 格子派生状态：已结束 FINISHED ＞ 已开始检测 CHECKING ＞ 待派 WAITING */
    private void fillCellState(ScheduleCellVO cell, Task task, boolean started) {
        if (isFinished(task)) {
            cell.setCellState("FINISHED");
            cell.setCellStateName("已结束");
        } else if (started) {
            cell.setCellState("CHECKING");
            cell.setCellStateName("在检");
        } else {
            cell.setCellState("WAITING");
            cell.setCellStateName("待派");
        }
    }

    /** 格内未结束任务的冲突原因：引车员不在岗 / 资质在排班日已过期 / 任务时段已不在当班排班内 */
    private String cellConflict(Driver driver, LocalDate day, Integer hour) {
        List<String> reasons = new ArrayList<>();
        if (!"ON_DUTY".equals(driver.getStatus())) {
            reasons.add("引车员当前不在岗");
        }
        if (!certValid(driver, day)) {
            reasons.add("资质已于 " + driver.getCertExpireDate() + " 过期");
        }
        if (!shiftHourList(driver.getShiftHours()).contains(hour)) {
            reasons.add("该时段已不在当班排班内");
        }
        return reasons.isEmpty() ? null : String.join("；", reasons);
    }

    private Set<Long> startedTaskIds(List<Task> dayTasks) {
        List<Long> ids = dayTasks.stream().map(Task::getId).toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(recordRepository.findStartedTaskIds(ids));
    }

    /** 资质在指定日期是否有效：无有效期视为长期有效 */
    static boolean certValid(Driver driver, LocalDate day) {
        return driver.getCertExpireDate() == null || !driver.getCertExpireDate().isBefore(day);
    }

    static List<Integer> shiftHourList(String shiftHours) {
        if (shiftHours == null || shiftHours.isBlank()) {
            return List.of();
        }
        return Arrays.stream(shiftHours.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }

    // ---------------- 工具 ----------------

    private void checkHour(Integer hour) {
        if (hour == null || hour < StationDict.SHIFT_START_HOUR || hour >= StationDict.SHIFT_END_HOUR) {
            throw new BizException("排班小时段必须在 " + StationDict.SHIFT_START_HOUR + ":00 - "
                    + StationDict.SHIFT_END_HOUR + ":00 之间");
        }
    }

    private static long remainDays(LocalDate nextCalDate, LocalDate today) {
        return ChronoUnit.DAYS.between(today, nextCalDate);
    }

    private DeviceVO toVO(Device device, LocalDate today) {
        DeviceVO vo = new DeviceVO();
        vo.setId(device.getId());
        vo.setDeviceName(device.getDeviceName());
        vo.setModel(device.getModel());
        vo.setDeviceType(device.getDeviceType());
        vo.setDeviceTypeName(StationDict.stationName(device.getDeviceType()));
        vo.setLastCalDate(device.getLastCalDate());
        vo.setNextCalDate(device.getNextCalDate());
        vo.setCalOrg(device.getCalOrg());
        vo.setCalResult(device.getCalResult());
        long remain = remainDays(device.getNextCalDate(), today);
        vo.setRemainDays(remain);
        vo.setLevel(remain < 0 ? "OVERDUE" : (remain <= StationDict.CAL_DUE_DAYS ? "DUE" : "NORMAL"));
        vo.setStatus(vo.getLevel());
        vo.setStatusName(StationDict.deviceStatusName(vo.getLevel()));
        return vo;
    }
}
