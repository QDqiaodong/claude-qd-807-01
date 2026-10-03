package com.vehicle.check.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.StationDict;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.TaskStationRecord;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.FailItemDTO;
import com.vehicle.check.dto.OverviewVO;
import com.vehicle.check.dto.RecordVO;
import com.vehicle.check.dto.StationStatDTO;
import com.vehicle.check.dto.StationResultRequest;
import com.vehicle.check.dto.TaskCreateRequest;
import com.vehicle.check.dto.TaskVO;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 检测任务与工位流转：顺序流转、检测值判定、总检结束、流水线推进。
 */
@Service
public class TaskFlowService {

    private static final String REDIS_CHECKED_KEY = "vc:checked:";

    private final TaskRepository taskRepository;
    private final TaskStationRecordRepository recordRepository;
    private final VehicleRepository vehicleRepository;
    private final LineRepository lineRepository;
    private final StationRepository stationRepository;
    private final DriverRepository driverRepository;
    private final StringRedisTemplate redisTemplate;

    public TaskFlowService(TaskRepository taskRepository, TaskStationRecordRepository recordRepository,
                           VehicleRepository vehicleRepository, LineRepository lineRepository,
                           StationRepository stationRepository, DriverRepository driverRepository,
                           StringRedisTemplate redisTemplate) {
        this.taskRepository = taskRepository;
        this.recordRepository = recordRepository;
        this.vehicleRepository = vehicleRepository;
        this.lineRepository = lineRepository;
        this.stationRepository = stationRepository;
        this.driverRepository = driverRepository;
        this.redisTemplate = redisTemplate;
    }

    public List<TaskVO> listTasks(String status) {
        List<Task> tasks = (status == null || status.isBlank())
                ? taskRepository.findAllByOrderByIdAsc()
                : taskRepository.findByStatusOrderByIdAsc(status);
        Map<Long, String> plateMap = vehicleRepository.findAll().stream()
                .collect(Collectors.toMap(Vehicle::getId, Vehicle::getPlateNo, (a, b) -> a));
        List<TaskVO> result = new ArrayList<>();
        for (Task task : tasks) {
            result.add(toVO(task, plateMap.getOrDefault(task.getVehicleId(), "")));
        }
        return result;
    }

    public TaskVO getTask(Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + id));
        String plateNo = vehicleRepository.findById(task.getVehicleId())
                .map(Vehicle::getPlateNo).orElse("");
        return toVO(task, plateNo);
    }

    /**
     * 建立检测任务（送检派单）。业务规则：同一车辆当天重复建档要拦截。
     */
    @Transactional
    public TaskVO createTask(TaskCreateRequest request) {
        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new BizException("车辆不存在：id=" + request.getVehicleId()));
        Line line = lineRepository.findById(request.getLineId())
                .orElseThrow(() -> new BizException("检测线不存在：id=" + request.getLineId()));
        if (!"RUNNING".equals(line.getStatus())) {
            throw new BizException("检测线「" + line.getLineName() + "（" + line.getLineNo() + "）」当前为停用状态，不能派发检测任务");
        }
        LocalDate checkDate = request.getCheckDate() == null ? LocalDate.now() : request.getCheckDate();

        List<Task> sameDay = taskRepository.findByVehicleIdAndCheckDate(vehicle.getId(), checkDate);
        if (!sameDay.isEmpty()) {
            Task exist = sameDay.get(0);
            throw new BizException("车辆「" + vehicle.getPlateNo() + "」今日（" + checkDate + "）已建档送检，任务号 "
                    + exist.getTaskNo() + "，当前在第 " + exist.getCurrentSeqNo() + " 个工位（"
                    + StationDict.stationName(stationTypeOf(exist)) + "），不可重复建档");
        }

        List<Station> stations = stationRepository.findByLineIdOrderBySeqNoAsc(line.getId());
        if (stations.isEmpty()) {
            throw new BizException("检测线「" + line.getLineNo() + "」尚未配置工位，不能派发检测任务");
        }
        Station first = stations.get(0);

        Task task = new Task();
        task.setTaskNo(nextTaskNo(checkDate));
        task.setVehicleId(vehicle.getId());
        task.setLineId(line.getId());
        task.setCurrentStationId(first.getId());
        task.setCurrentSeqNo(first.getSeqNo());
        task.setStatus("CHECKING");
        task.setDriverId(request.getDriverId());
        task.setScheduleHour(request.getScheduleHour());
        task.setCheckDate(checkDate);
        task.setRetestTimes(0);
        task.setCreateTime(LocalDateTime.now());

        if (request.getDriverId() != null) {
            Driver driver = driverRepository.findById(request.getDriverId())
                    .orElseThrow(() -> new BizException("引车员不存在：id=" + request.getDriverId()));
            if (request.getScheduleHour() != null) {
                checkDriverShift(driver, request.getScheduleHour());
            }
        }
        if (request.getScheduleHour() != null) {
            checkHour(request.getScheduleHour());
        }

        first.setStatus("OCCUPIED");
        stationRepository.save(first);
        return toVO(taskRepository.save(task), vehicle.getPlateNo());
    }

    /**
     * 工位检测项录入：按顺序流转校验 → 自动判定 → 记录 → 推进到下一工位
     */
    @Transactional
    public TaskVO submitStationResult(Long taskId, StationResultRequest request) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        Vehicle vehicle = vehicleRepository.findById(task.getVehicleId())
                .orElseThrow(() -> new BizException("任务 " + task.getTaskNo() + " 关联车辆已丢失"));
        Station station = stationRepository.findById(request.getStationId())
                .orElseThrow(() -> new BizException("工位不存在：id=" + request.getStationId()));
        if (!station.getLineId().equals(task.getLineId())) {
            throw new BizException("工位「" + station.getStationName() + "」不属于任务所在检测线，不能录入检测值");
        }
        checkStationOrder(task, station, vehicle);

        List<TaskStationRecord> history = recordRepository.findByTaskIdOrderBySeqNoAsc(taskId);
        JudgeResult judge = judge(station.getStationType(), request, history, vehicle);

        TaskStationRecord record = new TaskStationRecord();
        record.setTaskId(taskId);
        record.setStationId(station.getId());
        record.setStationType(station.getStationType());
        record.setSeqNo(station.getSeqNo());
        record.setItemName(StationDict.stationItem(station.getStationType()));
        record.setResult(judge.result);
        record.setValueText(judge.valueText);
        record.setOperatorName(request.getOperatorName() == null
                ? StationDict.stationName(station.getStationType()) + "工位" : request.getOperatorName());
        record.setDriverId(task.getDriverId());
        record.setRecordTime(LocalDateTime.now());
        recordRepository.save(record);

        applyValues(task, request, judge);
        advance(task, station, judge.result);
        return toVO(taskRepository.save(task), vehicle.getPlateNo());
    }

    // ---------------- 业务规则 ----------------

    /**
     * 工位必须按顺序流转：未完成前序工位不允许进入下一工位
     */
    private void checkStationOrder(Task task, Station station, Vehicle vehicle) {
        if (station.getSeqNo() > task.getCurrentSeqNo()) {
            String currentName = StationDict.stationName(
                    stationRepository.findById(task.getCurrentStationId()).map(Station::getStationType).orElse(""));
            throw new BizException("车辆「" + vehicle.getPlateNo() + "」尚卡在第 " + task.getCurrentSeqNo()
                    + " 个工位（" + currentName + "），需先完成该工位检测才能进入第 " + station.getSeqNo()
                    + " 个工位（" + station.getStationName() + "）");
        }
        if (station.getSeqNo() < task.getCurrentSeqNo()) {
            throw new BizException("第 " + station.getSeqNo() + " 个工位（" + station.getStationName()
                    + "）已完成检测，不可重复录入");
        }
        if (!"CHECKING".equals(task.getStatus()) && !"RETEST".equals(task.getStatus())) {
            throw new BizException("任务 " + task.getTaskNo() + " 已结束（" + StationDict.taskStatusName(task.getStatus())
                    + "），不能再录入工位检测值");
        }
    }

    private void advance(Task task, Station station, String result) {
        List<Station> stations = stationRepository.findByLineIdOrderBySeqNoAsc(task.getLineId());
        int index = -1;
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).getId().equals(station.getId())) {
                index = i;
                break;
            }
        }
        boolean hasNext = index >= 0 && index + 1 < stations.size();
        if (hasNext) {
            Station next = stations.get(index + 1);
            task.setCurrentStationId(next.getId());
            task.setCurrentSeqNo(next.getSeqNo());
            station.setStatus("IDLE");
            stationRepository.save(station);
            next.setStatus("OCCUPIED");
            stationRepository.save(next);
            task.setStatus("CHECKING");
        } else {
            task.setFinishTime(LocalDateTime.now());
            task.setStatus("PASS".equals(result) ? "PASS" : "FAIL");
            station.setStatus("IDLE");
            stationRepository.save(station);
            if (task.getDriverId() != null) {
                driverRepository.findById(task.getDriverId()).ifPresent(driver -> {
                    driver.setCheckedCount(driver.getCheckedCount() + 1);
                    driverRepository.save(driver);
                });
                redisTemplate.opsForValue().increment(REDIS_CHECKED_KEY + task.getCheckDate());
            }
        }
    }

    private void applyValues(Task task, StationResultRequest request, JudgeResult judge) {
        switch (judge.stationType) {
            case "EMISSION" -> {
                task.setCoValue(request.getCoValue());
                task.setHcValue(request.getHcValue());
            }
            case "BRAKE" -> task.setBrakeRate(request.getBrakeRate());
            case "LIGHT" -> task.setLightIntensity(request.getLightIntensity());
            case "CHASSIS" -> task.setChassisDefects(String.join("、", request.getChassisDefects()));
            default -> {
                // 登录/外观/总检无独立数值列
            }
        }
    }

    private JudgeResult judge(String stationType, StationResultRequest request,
                              List<TaskStationRecord> history, Vehicle vehicle) {
        JudgeResult result = new JudgeResult();
        result.stationType = stationType;
        switch (stationType) {
            case "LOGIN" -> {
                boolean matched = Boolean.TRUE.equals(request.getLoginMatched());
                result.result = matched ? "PASS" : "FAIL";
                result.valueText = matched
                        ? "车牌 " + vehicle.getPlateNo() + " 与行驶证一致"
                        : "车牌 " + vehicle.getPlateNo() + " 与行驶证不一致";
            }
            case "APPEARANCE" -> {
                int defects = request.getAppearanceDefects() == null ? 0 : request.getAppearanceDefects();
                result.result = defects <= StationDict.APPEARANCE_DEFECT_MAX ? "PASS" : "FAIL";
                result.valueText = "外观缺陷 " + defects + " 项（限值 " + StationDict.APPEARANCE_DEFECT_MAX + " 项）";
            }
            case "EMISSION" -> {
                BigDecimal co = request.getCoValue() == null ? BigDecimal.ZERO : request.getCoValue();
                int hc = request.getHcValue() == null ? 0 : request.getHcValue();
                result.result = co.compareTo(StationDict.CO_LIMIT) <= 0 && hc <= StationDict.HC_LIMIT ? "PASS" : "FAIL";
                result.valueText = "CO=" + co + "%，HC=" + hc + "ppm（限值 CO " + StationDict.CO_LIMIT
                        + "% / HC " + StationDict.HC_LIMIT + "ppm）";
            }
            case "BRAKE" -> {
                BigDecimal rate = request.getBrakeRate() == null ? BigDecimal.ZERO : request.getBrakeRate();
                result.result = rate.compareTo(StationDict.BRAKE_MIN) >= 0 ? "PASS" : "FAIL";
                result.valueText = "制动率 " + rate + "%（限值 ≥ " + StationDict.BRAKE_MIN + "%）";
            }
            case "LIGHT" -> {
                int intensity = request.getLightIntensity() == null ? 0 : request.getLightIntensity();
                result.result = intensity >= StationDict.LIGHT_MIN ? "PASS" : "FAIL";
                result.valueText = "灯光强度 " + intensity + "cd（限值 ≥ " + StationDict.LIGHT_MIN + "cd）";
            }
            case "CHASSIS" -> {
                List<String> defects = request.getChassisDefects() == null ? List.of() : request.getChassisDefects();
                result.result = defects.isEmpty() ? "PASS" : "FAIL";
                result.valueText = defects.isEmpty() ? "底盘缺陷 0 项" : String.join("、", defects);
            }
            case "FINAL" -> {
                List<String> failed = history.stream()
                        .filter(r -> StationDict.isKeyItem(r.getStationType()) && "FAIL".equals(r.getResult()))
                        .map(r -> StationDict.stationName(r.getStationType()))
                        .toList();
                result.result = failed.isEmpty() ? "PASS" : "FAIL";
                result.valueText = failed.isEmpty()
                        ? "全部关键项合格，同意签发"
                        : "关键项不合格 " + failed.size() + " 项（" + String.join("、", failed) + "），判定不合格";
            }
            default -> throw new BizException("未知工位类型：" + stationType);
        }
        return result;
    }

    private static class JudgeResult {
        private String stationType;
        private String result;
        private String valueText;
    }

    // ---------------- 统计 ----------------

    public List<StationStatDTO> stationStats() {
        return recordRepository.statPassRateByStation().stream()
                .peek(dto -> dto.setStationName(StationDict.stationName(dto.getStationType())))
                .sorted(Comparator.comparingInt(dto -> StationDict.seqOf(dto.getStationType())))
                .toList();
    }

    public List<FailItemDTO> failRank() {
        return recordRepository.rankFailItems().stream()
                .peek(dto -> dto.setStationName(StationDict.stationName(dto.getStationType())))
                .toList();
    }

    public Long checkedCountOfRedis(LocalDate date) {
        String value = redisTemplate.opsForValue().get(REDIS_CHECKED_KEY + date);
        return value == null ? 0L : Long.parseLong(value);
    }

    // ---------------- 工具 ----------------

    private String stationTypeOf(Task task) {
        return stationRepository.findById(task.getCurrentStationId())
                .map(Station::getStationType).orElse("LOGIN");
    }

    private String nextTaskNo(LocalDate date) {
        long count = taskRepository.count() + 1;
        return String.format("TASK-%s-%03d", date.toString().replace("-", ""), count);
    }

    private void checkHour(Integer hour) {
        if (hour == null || hour < StationDict.SHIFT_START_HOUR || hour >= StationDict.SHIFT_END_HOUR) {
            throw new BizException("排班小时段必须在 " + StationDict.SHIFT_START_HOUR + ":00 - "
                    + StationDict.SHIFT_END_HOUR + ":00 之间");
        }
    }

    private void checkDriverShift(Driver driver, Integer hour) {
        Set<String> hours = java.util.Set.of(driver.getShiftHours().split(","));
        if (!hours.contains(String.valueOf(hour))) {
            throw new BizException("引车员「" + driver.getDriverName() + "」当日排班小时段为 "
                    + formatShift(driver.getShiftHours()) + "，不包含 " + hour + ":00 时段");
        }
    }

    public static String formatShift(String shiftHours) {
        if (shiftHours == null || shiftHours.isBlank()) {
            return "未排班";
        }
        String[] parts = shiftHours.split(",");
        return parts[0] + ":00-" + (Integer.parseInt(parts[parts.length - 1]) + 1) + ":00";
    }

    private TaskVO toVO(Task task, String plateNo) {
        TaskVO vo = new TaskVO();
        vo.setId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setVehicleId(task.getVehicleId());
        vo.setPlateNo(plateNo);
        vo.setLineId(task.getLineId());
        vo.setLineNo(lineRepository.findById(task.getLineId()).map(Line::getLineNo).orElse(""));
        vo.setCurrentStationId(task.getCurrentStationId());
        vo.setCurrentStationType(stationTypeOf(task));
        vo.setCurrentStationName(StationDict.stationName(vo.getCurrentStationType()));
        vo.setCurrentSeqNo(task.getCurrentSeqNo());
        vo.setStatus(task.getStatus());
        vo.setStatusName(StationDict.taskStatusName(task.getStatus()));
        vo.setDriverId(task.getDriverId());
        vo.setDriverName(task.getDriverId() == null ? null
                : driverRepository.findById(task.getDriverId()).map(Driver::getDriverName).orElse(null));
        vo.setScheduleHour(task.getScheduleHour());
        vo.setCheckDate(task.getCheckDate());
        vo.setCoValue(task.getCoValue());
        vo.setHcValue(task.getHcValue());
        vo.setBrakeRate(task.getBrakeRate());
        vo.setLightIntensity(task.getLightIntensity());
        vo.setChassisDefects(task.getChassisDefects());
        vo.setRetestTimes(task.getRetestTimes());
        vo.setOriginReportNo(task.getOriginReportNo());
        vo.setCreateTime(task.getCreateTime());
        vo.setFinishTime(task.getFinishTime());

        vehicleRepository.findById(task.getVehicleId()).ifPresent(vehicle -> {
            vo.setOwnerName(vehicle.getOwnerName());
            vo.setVehicleType(vehicle.getVehicleType());
            vo.setVehicleTypeName(StationDict.vehicleTypeName(vehicle.getVehicleType()));
        });

        List<RecordVO> records = new ArrayList<>();
        for (TaskStationRecord record : recordRepository.findByTaskIdOrderBySeqNoAsc(task.getId())) {
            RecordVO rvo = new RecordVO();
            rvo.setId(record.getId());
            rvo.setTaskId(record.getTaskId());
            rvo.setStationId(record.getStationId());
            rvo.setStationType(record.getStationType());
            rvo.setStationName(StationDict.stationName(record.getStationType()));
            rvo.setSeqNo(record.getSeqNo());
            rvo.setItemName(record.getItemName());
            rvo.setResult(record.getResult());
            rvo.setResultName("PASS".equals(record.getResult()) ? "合格" : "不合格");
            rvo.setValueText(record.getValueText());
            rvo.setOperatorName(record.getOperatorName());
            rvo.setRecordTime(record.getRecordTime());
            records.add(rvo);
        }
        vo.setRecords(records);
        return vo;
    }

    public OverviewVO overview(long lineCount, long stationCount, long vehicleCount,
                               long reportCount, long failReportCount, long overdueDeviceCount) {
        OverviewVO vo = new OverviewVO();
        vo.setLineCount(lineCount);
        vo.setStationCount(stationCount);
        vo.setVehicleCount(vehicleCount);
        vo.setCheckingCount(taskRepository.countByStatusIn(List.of("CHECKING", "RETEST")));
        vo.setReportCount(reportCount);
        vo.setFailReportCount(failReportCount);
        vo.setOverdueDeviceCount(overdueDeviceCount);
        vo.setRedisCheckedCount(checkedCountOfRedis(LocalDate.now()));
        vo.setStationStats(stationStats());
        vo.setFailRank(failRank());
        return vo;
    }
}
