package com.vehicle.check.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 派单/补派请求：把某个检测任务挂到引车员的某个小时段
 */
@Data
public class AssignRequest {

    @NotNull(message = "检测任务不能为空")
    private Long taskId;

    @NotNull(message = "排班小时段不能为空")
    private Integer scheduleHour;
}
