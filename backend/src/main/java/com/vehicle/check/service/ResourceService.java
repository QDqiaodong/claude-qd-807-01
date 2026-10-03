package com.vehicle.check.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.vehicle.check.dto.DriverStatDTO;
import com.vehicle.check.dto.ScheduleCellVO;
import com.vehicle.check.dto.ScheduleRowVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 设备标定与引车员：设备台账倒计时、登记标定结果；引车员排班格子表、派单与改派。
 */
@Service
public class ResourceService {

    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;
    private final TaskRepository taskRepository;
    private final VehicleRepository vehicleRepository;
    private final StationRepository stationRepository;

    public ResourceService(DeviceRepository deviceRepository, DriverRepository driverRepository,
                           TaskRepository taskRepository, VehicleRepository vehicleRepository,
                           StationRepository stationRepository) {
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
        this.taskRepository = taskRepository;
        this.vehicleRepository = vehicleRepository;
        this.stationRepository = stationRepository;
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
     * 排班格子表：纵轴引车员、横轴小时段（8:00-18:00），格子里塞检测任务车牌小块
     */
    public List<ScheduleRowVO> schedule(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        Map<Long, String> plateMap = vehicleRepository.findAll().stream()
                .collect(Collectors.toMap(Vehicle::getId, Vehicle::getPlateNo, (a, b) -> a));
        Map<Long, String> stationMap = stationRepository.findAll().stream()
                .collect(Collectors.toMap(Station::getId, Station::getStationName, (a, b) -> a));

        List<Task> dayTasks = taskRepository.findAll().stream()
                .filter(task -> day.equals(task.getCheckDate()))
                .toList();

        List<ScheduleRowVO> rows = new ArrayList<>();
        for (Driver driver : driverRepository.findAllByOrderByIdAsc()) {
            ScheduleRowVO row = new ScheduleRowVO();
            row.setDriverId(driver.getId());
            row.setDriverName(driver.getDriverName());
            row.setCertLevel(driver.getCertLevel());
            row.setCertNo(driver.getCertNo());
            row.setShiftHours(driver.getShiftHours());
            row.setCheckedCount(driver.getCheckedCount());
            row.setStatus(driver.getStatus());

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
                    cell.setTaskId(task.getId());
                    cell.setTaskNo(task.getTaskNo());
                    cell.setPlateNo(plateMap.getOrDefault(task.getVehicleId(), ""));
                    cell.setStatus(task.getStatus());
                    cell.setStatusName(StationDict.taskStatusName(task.getStatus()));
                    cell.setStationName(task.getCurrentStationId() == null ? ""
                            : stationMap.getOrDefault(task.getCurrentStationId(), ""));
                }
                cells.add(cell);
            }
            row.setCells(cells);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 派单：把某个检测任务挂到引车员的某个小时段
     */
    @Transactional
    public ScheduleCellVO assign(Long driverId, AssignRequest request) {
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> new BizException("引车员不存在：id=" + driverId));
        if (!"ON_DUTY".equals(driver.getStatus())) {
            throw new BizException("引车员「" + driver.getDriverName() + "」当日不在岗，不能派单");
        }
        Task task = taskRepository.findById(request.getTaskId())
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + request.getTaskId()));
        checkHour(request.getScheduleHour());
        Set<String> hours = Set.of(driver.getShiftHours().split(","));
        if (!hours.contains(String.valueOf(request.getScheduleHour()))) {
            throw new BizException("引车员「" + driver.getDriverName() + "」当日排班小时段为 "
                    + TaskFlowService.formatShift(driver.getShiftHours())
                    + "，不包含 " + request.getScheduleHour() + ":00 时段");
        }
        long occupied = taskRepository.findAll().stream()
                .filter(t -> day(t).equals(day(task)))
                .filter(t -> driverId.equals(t.getDriverId()))
                .filter(t -> t.getScheduleHour() != null && t.getScheduleHour().equals(request.getScheduleHour()))
                .filter(t -> !t.getId().equals(task.getId()))
                .count();
        if (occupied > 0) {
            throw new BizException("引车员「" + driver.getDriverName() + "」在 " + request.getScheduleHour()
                    + ":00 时段已有在检任务，不能重复派单");
        }
        task.setDriverId(driverId);
        task.setScheduleHour(request.getScheduleHour());
        taskRepository.save(task);

        ScheduleCellVO cell = new ScheduleCellVO();
        cell.setHour(request.getScheduleHour());
        cell.setHourLabel(request.getScheduleHour() + ":00");
        cell.setTaskId(task.getId());
        cell.setTaskNo(task.getTaskNo());
        cell.setPlateNo(vehicleRepository.findById(task.getVehicleId()).map(Vehicle::getPlateNo).orElse(""));
        cell.setStatus(task.getStatus());
        cell.setStatusName(StationDict.taskStatusName(task.getStatus()));
        return cell;
    }

    /**
     * 改派：把已有格子上的任务换到另一名引车员的某个小时段
     */
    @Transactional
    public ScheduleCellVO reassign(Long taskId, Long driverId, Integer hour) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        AssignRequest request = new AssignRequest();
        request.setTaskId(taskId);
        request.setScheduleHour(hour == null ? task.getScheduleHour() : hour);
        return assign(driverId, request);
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

    // ---------------- 工具 ----------------

    private static LocalDate day(Task task) {
        return task.getCheckDate();
    }

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
