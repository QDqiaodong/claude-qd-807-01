package com.vehicle.check.dto;

import lombok.Data;

/**
 * 派单/改派请求：把某个检测任务挂到引车员的某个小时段
 */
@Data
public class AssignRequest {

    private Long taskId;
    private Integer scheduleHour;
}
