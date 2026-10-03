package com.vehicle.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.vehicle.check.common.BizException;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.TaskCreateRequest;
import com.vehicle.check.dto.TaskVO;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;
import com.vehicle.check.service.TaskFlowService;

/**
 * 建档派发（createTask）：非运行检测线 / 无工位 / 当天已建档 / 引车员约束 / 起点工位原子占位，
 * 以及「任务 + 小时格 + 车辆队列 + 检测线起点」同事务绑定。
 */
@ExtendWith(MockitoExtension.class)
class TaskFlowDispatchTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskStationRecordRepository recordRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private LineRepository lineRepository;
    @Mock
    private StationRepository stationRepository;
    @Mock
    private DriverRepository driverRepository;
    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private TaskFlowService taskFlowService;

    @Test
    void create_line_stopped_rejected() {
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100)));
        when(lineRepository.findById(5L)).thenReturn(Optional.of(line(5, "STOPPED")));

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 5L, 1L, 9)));
        assertTrue(e.getMessage().contains("停用状态"));
        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_vehicle_already_filed_today_rejected() {
        Task exist = task(3, "TASK-OLD", 10L);
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100)));
        when(lineRepository.findById(1L)).thenReturn(Optional.of(line(1, "RUNNING")));
        when(taskRepository.findByVehicleIdAndCheckDate(100L, DAY)).thenReturn(List.of(exist));
        when(stationRepository.findById(10L)).thenReturn(Optional.of(station(10, 1, "LOGIN", 1)));

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 1L, 1L, 9)));
        assertTrue(e.getMessage().contains("已建档送检"));
        assertTrue(e.getMessage().contains("TASK-OLD"));
        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_line_without_station_rejected() {
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100)));
        when(lineRepository.findById(1L)).thenReturn(Optional.of(line(1, "RUNNING")));
        when(taskRepository.findByVehicleIdAndCheckDate(100L, DAY)).thenReturn(List.of());
        when(stationRepository.findByLineIdOrderBySeqNoAsc(1L)).thenReturn(List.of());

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 1L, 1L, 9)));
        assertTrue(e.getMessage().contains("尚未配置工位"));
    }

    @Test
    void create_driver_without_hour_rejected() {
        stubBasePath();
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver(1, "ON_DUTY", "8,9,10", null)));

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 1L, 1L, null)));
        assertTrue(e.getMessage().contains("必须指定排班小时段"));
    }

    @Test
    void create_hour_out_of_range_rejected() {
        stubBasePath();
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver(1, "ON_DUTY", "8,9,10", null)));

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 1L, 1L, 20)));
        assertTrue(e.getMessage().contains("8:00 - 18:00"));
    }

    @Test
    void create_off_duty_driver_rejected() {
        stubBasePath();
        when(driverRepository.findById(6L)).thenReturn(Optional.of(driver(6, "OFF_DUTY", "8,9,10,11", null)));

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 1L, 6L, 9)));
        assertTrue(e.getMessage().contains("不在岗"));
    }

    @Test
    void create_cert_expired_driver_rejected() {
        stubBasePath();
        when(driverRepository.findById(5L))
                .thenReturn(Optional.of(driver(5, "ON_DUTY", "15,16,17", LocalDate.of(2026, 9, 30))));

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 1L, 5L, 15)));
        assertTrue(e.getMessage().contains("资质已于 2026-09-30 过期"));
    }

    @Test
    void create_hour_not_in_shift_rejected() {
        stubBasePath();
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver(1, "ON_DUTY", "8,9,10,11,12", null)));

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 1L, 1L, 15)));
        assertTrue(e.getMessage().contains("不包含 15:00 时段"));
    }

    @Test
    void create_slot_occupied_rejected() {
        stubBasePath();
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver(1, "ON_DUTY", "8,9,10,11,12", null)));
        when(taskRepository.findActiveSlotTasks(1L, DAY, 9))
                .thenReturn(List.of(task(8, "TASK-BUSY", 10L)));

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 1L, 1L, 9)));
        assertTrue(e.getMessage().contains("同一引车员同日同小时只能有一个未结束任务"));
        verify(stationRepository, never()).claimStation(any());
    }

    @Test
    void create_start_station_claim_failed_rolls_back() {
        stubBasePath();
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver(1, "ON_DUTY", "8,9,10,11,12", null)));
        when(taskRepository.findActiveSlotTasks(1L, DAY, 9)).thenReturn(List.of());
        when(stationRepository.claimStation(10L)).thenReturn(0);

        BizException e = assertThrows(BizException.class,
                () -> taskFlowService.createTask(request(100L, 1L, 1L, 9)));
        assertTrue(e.getMessage().contains("起点工位"));
        // 占位失败 → 整个派发不成立，不能留下任务记录（半派发）
        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_success_binds_task_cell_queue_and_start_station() {
        stubBasePath();
        Driver driver = driver(1, "ON_DUTY", "8,9,10,11,12", null);
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(taskRepository.findActiveSlotTasks(1L, DAY, 9)).thenReturn(List.of());
        when(stationRepository.claimStation(10L)).thenReturn(1);
        when(taskRepository.count()).thenReturn(11L);
        when(taskRepository.saveAndFlush(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stationRepository.findById(10L)).thenReturn(Optional.of(station(10, 1, "LOGIN", 1)));
        when(recordRepository.findByTaskIdOrderBySeqNoAsc(any())).thenReturn(List.of());

        TaskVO vo = taskFlowService.createTask(request(100L, 1L, 1L, 9));

        // 任务 + 小时格 + 检测线起点一次绑定
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).saveAndFlush(captor.capture());
        Task saved = captor.getValue();
        assertEquals("TASK-20261003-012", saved.getTaskNo());
        assertEquals(1L, saved.getDriverId());
        assertEquals(9, saved.getScheduleHour());
        assertEquals(10L, saved.getCurrentStationId());
        assertEquals(1, saved.getCurrentSeqNo());
        assertEquals("CHECKING", saved.getStatus());
        // 起点工位走了原子占位
        verify(stationRepository).claimStation(10L);
        assertEquals("TASK-20261003-012", vo.getTaskNo());
        assertEquals("张引车", vo.getDriverName());
    }

    @Test
    void create_without_driver_still_binds_start_station() {
        stubBasePath();
        when(stationRepository.claimStation(10L)).thenReturn(1);
        when(taskRepository.count()).thenReturn(0L);
        when(taskRepository.saveAndFlush(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stationRepository.findById(10L)).thenReturn(Optional.of(station(10, 1, "LOGIN", 1)));
        when(recordRepository.findByTaskIdOrderBySeqNoAsc(any())).thenReturn(List.of());

        TaskVO vo = taskFlowService.createTask(request(100L, 1L, null, null));

        assertEquals("TASK-20261003-001", vo.getTaskNo());
        assertEquals(10L, vo.getCurrentStationId());
        verify(stationRepository).claimStation(10L);
    }

    // ---------------- 工具 ----------------

    /** 车辆存在 + 运行中的检测线 + 当天未建档 + 起点工位 LOGIN(idle) */
    private void stubBasePath() {
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100)));
        when(lineRepository.findById(1L)).thenReturn(Optional.of(line(1, "RUNNING")));
        when(taskRepository.findByVehicleIdAndCheckDate(100L, DAY)).thenReturn(List.of());
        when(stationRepository.findByLineIdOrderBySeqNoAsc(1L))
                .thenReturn(List.of(station(10, 1, "LOGIN", 1), station(11, 1, "APPEARANCE", 2)));
    }

    private static TaskCreateRequest request(Long vehicleId, Long lineId, Long driverId, Integer hour) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setVehicleId(vehicleId);
        request.setLineId(lineId);
        request.setDriverId(driverId);
        request.setScheduleHour(hour);
        request.setCheckDate(DAY);
        return request;
    }

    private static Vehicle vehicle(long id) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(id);
        vehicle.setPlateNo("京A·Q8801");
        return vehicle;
    }

    private static Line line(long id, String status) {
        Line line = new Line();
        line.setId(id);
        line.setLineNo("L0" + id);
        line.setLineName("安检" + id + "线");
        line.setStatus(status);
        return line;
    }

    private static Station station(long id, long lineId, String type, int seq) {
        Station station = new Station();
        station.setId(id);
        station.setLineId(lineId);
        station.setStationType(type);
        station.setStationName("登录");
        station.setSeqNo(seq);
        station.setStatus("IDLE");
        return station;
    }

    private static Driver driver(long id, String status, String shift, LocalDate certExpire) {
        Driver driver = new Driver();
        driver.setId(id);
        driver.setDriverName("张引车");
        driver.setCertLevel("A1");
        driver.setCertNo("YCY-TEST-" + id);
        driver.setCertExpireDate(certExpire);
        driver.setShiftHours(shift);
        driver.setCheckedCount(0);
        driver.setStatus(status);
        return driver;
    }

    private static Task task(long id, String taskNo, Long stationId) {
        Task task = new Task();
        task.setId(id);
        task.setTaskNo(taskNo);
        task.setVehicleId(100L);
        task.setLineId(1L);
        task.setCurrentStationId(stationId);
        task.setCurrentSeqNo(1);
        task.setStatus("CHECKING");
        task.setCheckDate(DAY);
        task.setRetestTimes(0);
        return task;
    }
}
