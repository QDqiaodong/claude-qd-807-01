package com.vehicle.check.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 建档并派发检测任务请求：车辆 + 检测线 +（可选）引车员与排班小时段。
 * 指定引车员时必须同时给出排班小时段，任务、小时格、车辆队列与检测线起点在同一事务内绑定。
 */
@Data
public class TaskCreateRequest {

    @NotNull(message = "车辆不能为空")
    private Long vehicleId;

    @NotNull(message = "检测线不能为空")
    private Long lineId;

    private Long driverId;
    private Integer scheduleHour;
    private LocalDate checkDate;
}
