package com.vehicle.check.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.vehicle.check.domain.Task;
import com.vehicle.check.dto.DriverStatDTO;
import com.vehicle.check.dto.StationQueueDTO;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface TaskRepository extends JpaRepository<Task, Long> {

    Optional<Task> findByTaskNo(String taskNo);

    List<Task> findAllByOrderByIdAsc();

    List<Task> findByStatusOrderByIdAsc(String status);

    List<Task> findByDriverIdAndCheckDateOrderByScheduleHourAsc(Long driverId, LocalDate checkDate);

    List<Task> findByVehicleIdAndCheckDate(Long vehicleId, LocalDate checkDate);

    /** 某天的全部检测任务（排班格子表数据源） */
    List<Task> findByCheckDate(LocalDate checkDate);

    /**
     * 某引车员某日某小时段的未结束任务（CHECKING/RETEST）。
     * 正常返回 0 或 1 条；与 vc_task.uk_task_active_slot 唯一索引共同保证
     * 「同一引车员同日同小时只能有一个未结束任务」。
     */
    @Query("select t from Task t where t.driverId = :driverId and t.checkDate = :checkDate "
            + "and t.scheduleHour = :hour and t.status in ('CHECKING','RETEST')")
    List<Task> findActiveSlotTasks(@Param("driverId") Long driverId,
                                   @Param("checkDate") LocalDate checkDate,
                                   @Param("hour") Integer hour);

    boolean existsByVehicleIdAndCheckDate(Long vehicleId, LocalDate checkDate);

    long countByStatusIn(List<String> statuses);

    /** 悲观行锁：跨线确认时锁住任务，两名值班人员同时处置同一车辆时串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select t from Task t where t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);

    /**
     * 各工位当前排队车辆数（在检/复检的任务按当前工位分组）
     */
    @Query("select new com.vehicle.check.dto.StationQueueDTO(t.currentStationId, count(t)) "
            + "from Task t "
            + "where t.status in ('CHECKING', 'RETEST') and t.currentStationId is not null "
            + "group by t.currentStationId")
    List<StationQueueDTO> countQueueGroupByStation();

    /**
     * 按引车员统计当日台次
     */
    @Query("select new com.vehicle.check.dto.DriverStatDTO(t.driverId, count(t)) "
            + "from Task t "
            + "where t.checkDate = :date and t.driverId is not null "
            + "group by t.driverId")
    List<DriverStatDTO> countGroupByDriverAndDate(@Param("date") LocalDate date);
}
