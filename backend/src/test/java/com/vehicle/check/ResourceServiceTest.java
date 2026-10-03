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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.vehicle.check.common.AssignConflictException;
import com.vehicle.check.common.BizException;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.AssignRequest;
import com.vehicle.check.dto.ReassignRequest;
import com.vehicle.check.dto.ScheduleCellVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;
import com.vehicle.check.service.ResourceService;

/**
 * 引车员派单 / 改派规则：在岗、资质、小时段、小时格唯一、幂等与并发冲突归属。
 */
@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private DriverRepository driverRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private StationRepository stationRepository;
    @Mock
    private TaskStationRecordRepository recordRepository;

    @InjectMocks
    private ResourceService resourceService;

    // ---------------- 派单（补派） ----------------

    @Test
    void assign_idempotent_returns_current_cell_without_save() {
        Driver driver = driver(2, "李引车", "ON_DUTY", "10,11,12", null);
        Task task = task(7, "CHECKING", 2L, 10);
        when(driverRepository.findById(2L)).thenReturn(Optional.of(driver));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100, "京A·Q8801")));

        ScheduleCellVO cell = resourceService.assign(2L, assignRequest(7L, 10));

        assertEquals("TASK-0007", cell.getTaskNo());
        assertEquals(10, cell.getHour());
        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void assign_finished_task_rejected() {
        Driver driver = driver(3, "王引车", "ON_DUTY", "13,14,15", null);
        Task task = task(7, "PASS", null, null);
        when(driverRepository.findById(3L)).thenReturn(Optional.of(driver));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));

        BizException e = assertThrows(BizException.class,
                () -> resourceService.assign(3L, assignRequest(7L, 13)));
        assertTrue(e.getMessage().contains("已结束"));
    }

    @Test
    void assign_task_with_driver_rejected_and_returns_current_owner() {
        Driver target = driver(3, "王引车", "ON_DUTY", "13,14,15", null);
        Driver current = driver(2, "李引车", "ON_DUTY", "10,11,12", null);
        Task task = task(7, "CHECKING", 2L, 10);
        when(driverRepository.findById(3L)).thenReturn(Optional.of(target));
        when(driverRepository.findById(2L)).thenReturn(Optional.of(current));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));

        AssignConflictException e = assertThrows(AssignConflictException.class,
                () -> resourceService.assign(3L, assignRequest(7L, 13)));
        assertTrue(e.getMessage().contains("已派给引车员"));
        assertTrue(e.getMessage().contains("任务当前归属：引车员「李引车」 · 10:00 时段"));
    }

    @Test
    void assign_off_duty_driver_rejected() {
        Driver driver = driver(6, "周引车", "OFF_DUTY", "8,9,10,11", null);
        Task task = task(7, "CHECKING", null, null);
        when(driverRepository.findById(6L)).thenReturn(Optional.of(driver));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));

        BizException e = assertThrows(BizException.class,
                () -> resourceService.assign(6L, assignRequest(7L, 9)));
        assertTrue(e.getMessage().contains("不在岗"));
    }

    @Test
    void assign_cert_expired_driver_rejected() {
        Driver driver = driver(5, "孙引车", "ON_DUTY", "15,16,17", LocalDate.of(2026, 9, 30));
        Task task = task(7, "CHECKING", null, null);
        when(driverRepository.findById(5L)).thenReturn(Optional.of(driver));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));

        BizException e = assertThrows(BizException.class,
                () -> resourceService.assign(5L, assignRequest(7L, 15)));
        assertTrue(e.getMessage().contains("资质已于 2026-09-30 过期"));
    }

    @Test
    void assign_hour_out_of_range_rejected() {
        Driver driver = driver(1, "张引车", "ON_DUTY", "8,9,10,11,12", null);
        Task task = task(7, "CHECKING", null, null);
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));

        BizException e = assertThrows(BizException.class,
                () -> resourceService.assign(1L, assignRequest(7L, 18)));
        assertTrue(e.getMessage().contains("8:00 - 18:00"));
    }

    @Test
    void assign_hour_not_in_shift_rejected() {
        Driver driver = driver(1, "张引车", "ON_DUTY", "8,9,10,11,12", null);
        Task task = task(7, "CHECKING", null, null);
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));

        BizException e = assertThrows(BizException.class,
                () -> resourceService.assign(1L, assignRequest(7L, 15)));
        assertTrue(e.getMessage().contains("不包含 15:00 时段"));
    }

    @Test
    void assign_slot_occupied_by_unfinished_task_rejected() {
        Driver driver = driver(1, "张引车", "ON_DUTY", "8,9,10,11,12", null);
        Task task = task(7, "CHECKING", null, null);
        Task occupying = task(9, "CHECKING", 1L, 9);
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(taskRepository.findActiveSlotTasks(1L, DAY, 9)).thenReturn(List.of(occupying));
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100, "京A·Q8801")));

        BizException e = assertThrows(BizException.class,
                () -> resourceService.assign(1L, assignRequest(7L, 9)));
        assertTrue(e.getMessage().contains("同一引车员同日同小时只能有一个未结束任务"));
    }

    @Test
    void assign_success_binds_driver_and_hour() {
        Driver driver = driver(1, "张引车", "ON_DUTY", "8,9,10,11,12", null);
        Task task = task(7, "CHECKING", null, null);
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(taskRepository.findActiveSlotTasks(1L, DAY, 9)).thenReturn(List.of());
        when(taskRepository.saveAndFlush(task)).thenReturn(task);
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100, "京A·Q8801")));

        ScheduleCellVO cell = resourceService.assign(1L, assignRequest(7L, 9));

        assertEquals(1L, task.getDriverId());
        assertEquals(9, task.getScheduleHour());
        assertEquals("京A·Q8801", cell.getPlateNo());
        verify(taskRepository).saveAndFlush(task);
    }

    @Test
    void assign_unique_index_conflict_becomes_assign_conflict() {
        Driver driver = driver(1, "张引车", "ON_DUTY", "8,9,10,11,12", null);
        Task task = task(7, "CHECKING", null, null);
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(taskRepository.findActiveSlotTasks(1L, DAY, 9)).thenReturn(List.of());
        when(taskRepository.saveAndFlush(task))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry '1-2026-10-03-9'"));

        AssignConflictException e = assertThrows(AssignConflictException.class,
                () -> resourceService.assign(1L, assignRequest(7L, 9)));
        assertTrue(e.getMessage().contains("本次派单未生效"));
    }

    // ---------------- 改派 ----------------

    @Test
    void reassign_idempotent_returns_current_owner_without_save() {
        Task task = task(7, "CHECKING", 2L, 10);
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100, "京A·Q8801")));

        ScheduleCellVO cell = resourceService.reassign(7L, reassignRequest(2L, 10, 2L));

        assertEquals("TASK-0007", cell.getTaskNo());
        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void reassign_expected_driver_mismatch_returns_current_owner() {
        Task task = task(7, "CHECKING", 2L, 10);
        Driver current = driver(2, "李引车", "ON_DUTY", "10,11,12", null);
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(driverRepository.findById(2L)).thenReturn(Optional.of(current));

        AssignConflictException e = assertThrows(AssignConflictException.class,
                () -> resourceService.reassign(7L, reassignRequest(3L, 11, 1L)));
        assertTrue(e.getMessage().contains("归属已发生变化"));
        assertTrue(e.getMessage().contains("任务当前归属：引车员「李引车」 · 10:00 时段"));
        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void reassign_finished_task_rejected() {
        Task task = task(7, "FAIL", 2L, 10);
        Driver current = driver(2, "李引车", "ON_DUTY", "10,11,12", null);
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(driverRepository.findById(2L)).thenReturn(Optional.of(current));

        AssignConflictException e = assertThrows(AssignConflictException.class,
                () -> resourceService.reassign(7L, reassignRequest(3L, 11, 2L)));
        assertTrue(e.getMessage().contains("已结束"));
        assertTrue(e.getMessage().contains("不可改派"));
    }

    @Test
    void reassign_started_task_rejected() {
        Task task = task(7, "CHECKING", 2L, 10);
        Driver current = driver(2, "李引车", "ON_DUTY", "10,11,12", null);
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(recordRepository.existsByTaskId(7L)).thenReturn(true);
        when(driverRepository.findById(2L)).thenReturn(Optional.of(current));

        AssignConflictException e = assertThrows(AssignConflictException.class,
                () -> resourceService.reassign(7L, reassignRequest(3L, 11, 2L)));
        assertTrue(e.getMessage().contains("已开始检测"));
        assertTrue(e.getMessage().contains("不可改派"));
    }

    @Test
    void reassign_success_moves_cell_and_keeps_task_identity() {
        Task task = task(7, "CHECKING", 2L, 10);
        Driver current = driver(2, "李引车", "ON_DUTY", "10,11,12", null);
        Driver target = driver(3, "王引车", "ON_DUTY", "13,14,15,16,17", LocalDate.of(2026, 12, 31));
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(recordRepository.existsByTaskId(7L)).thenReturn(false);
        when(driverRepository.findById(3L)).thenReturn(Optional.of(target));
        when(driverRepository.findById(2L)).thenReturn(Optional.of(current));
        when(taskRepository.findActiveSlotTasks(3L, DAY, 13)).thenReturn(List.of());
        when(taskRepository.saveAndFlush(task)).thenReturn(task);
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100, "京A·Q8801")));

        ScheduleCellVO cell = resourceService.reassign(7L, reassignRequest(3L, 13, 2L));

        // 原格释放、新格占用：只改引车员与小时段
        assertEquals(3L, task.getDriverId());
        assertEquals(13, task.getScheduleHour());
        // 任务号、当前工位、检测结果不动
        assertEquals("TASK-0007", task.getTaskNo());
        assertEquals(5L, task.getCurrentStationId());
        assertEquals(1, task.getCurrentSeqNo());
        assertEquals(13, cell.getHour());
        verify(taskRepository).saveAndFlush(task);
    }

    @Test
    void reassign_default_hour_keeps_original_slot() {
        Task task = task(7, "CHECKING", 2L, 10);
        Driver current = driver(2, "李引车", "ON_DUTY", "10,11,12", null);
        Driver target = driver(1, "张引车", "ON_DUTY", "8,9,10,11,12", null);
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(recordRepository.existsByTaskId(7L)).thenReturn(false);
        when(driverRepository.findById(1L)).thenReturn(Optional.of(target));
        when(driverRepository.findById(2L)).thenReturn(Optional.of(current));
        when(taskRepository.findActiveSlotTasks(1L, DAY, 10)).thenReturn(List.of());
        when(taskRepository.saveAndFlush(task)).thenReturn(task);
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100, "京A·Q8801")));

        ScheduleCellVO cell = resourceService.reassign(7L, reassignRequest(1L, null, 2L));

        assertEquals(1L, task.getDriverId());
        assertEquals(10, task.getScheduleHour());
        assertEquals(10, cell.getHour());
    }

    @Test
    void reassign_target_slot_occupied_rejected() {
        Task task = task(7, "CHECKING", 2L, 10);
        Driver target = driver(1, "张引车", "ON_DUTY", "8,9,10,11,12", null);
        Task occupying = task(9, "CHECKING", 1L, 9);
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(recordRepository.existsByTaskId(7L)).thenReturn(false);
        when(driverRepository.findById(1L)).thenReturn(Optional.of(target));
        when(taskRepository.findActiveSlotTasks(1L, DAY, 9)).thenReturn(List.of(occupying));
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle(100, "京A·Q8801")));

        BizException e = assertThrows(BizException.class,
                () -> resourceService.reassign(7L, reassignRequest(1L, 9, 2L)));
        assertTrue(e.getMessage().contains("同一引车员同日同小时只能有一个未结束任务"));
        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void reassign_unique_index_conflict_returns_current_owner() {
        Task task = task(7, "CHECKING", 2L, 10);
        Driver current = driver(2, "李引车", "ON_DUTY", "10,11,12", null);
        Driver target = driver(3, "王引车", "ON_DUTY", "13,14,15,16,17", null);
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(recordRepository.existsByTaskId(7L)).thenReturn(false);
        when(driverRepository.findById(3L)).thenReturn(Optional.of(target));
        when(driverRepository.findById(2L)).thenReturn(Optional.of(current));
        when(taskRepository.findActiveSlotTasks(3L, DAY, 13)).thenReturn(List.of());
        when(taskRepository.saveAndFlush(task))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry '3-2026-10-03-13'"));

        AssignConflictException e = assertThrows(AssignConflictException.class,
                () -> resourceService.reassign(7L, reassignRequest(3L, 13, 2L)));
        assertTrue(e.getMessage().contains("本次改派未生效"));
        assertTrue(e.getMessage().contains("任务当前归属：引车员「李引车」 · 10:00 时段"));
    }

    // ---------------- 工具 ----------------

    private static AssignRequest assignRequest(Long taskId, Integer hour) {
        AssignRequest request = new AssignRequest();
        request.setTaskId(taskId);
        request.setScheduleHour(hour);
        return request;
    }

    private static ReassignRequest reassignRequest(Long driverId, Integer hour, Long expectedDriverId) {
        ReassignRequest request = new ReassignRequest();
        request.setDriverId(driverId);
        request.setScheduleHour(hour);
        request.setExpectedDriverId(expectedDriverId);
        return request;
    }

    private static Driver driver(long id, String name, String status, String shift, LocalDate certExpire) {
        Driver driver = new Driver();
        driver.setId(id);
        driver.setDriverName(name);
        driver.setCertLevel("A1");
        driver.setCertNo("YCY-TEST-" + id);
        driver.setCertExpireDate(certExpire);
        driver.setShiftHours(shift);
        driver.setCheckedCount(0);
        driver.setStatus(status);
        return driver;
    }

    private static Task task(long id, String status, Long driverId, Integer hour) {
        Task task = new Task();
        task.setId(id);
        task.setTaskNo("TASK-" + String.format("%04d", id));
        task.setVehicleId(100L);
        task.setLineId(1L);
        task.setCurrentStationId(5L);
        task.setCurrentSeqNo(1);
        task.setStatus(status);
        task.setDriverId(driverId);
        task.setScheduleHour(hour);
        task.setCheckDate(DAY);
        task.setRetestTimes(0);
        return task;
    }

    private static Vehicle vehicle(long id, String plateNo) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(id);
        vehicle.setPlateNo(plateNo);
        return vehicle;
    }
}
