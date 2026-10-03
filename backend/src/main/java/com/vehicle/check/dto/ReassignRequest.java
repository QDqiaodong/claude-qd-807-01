package com.vehicle.check.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 改派请求：把尚未开始检测的任务改派到另一名引车员的某个小时段。
 * <p>
 * expectedDriverId 传页面快照里的当前引车员：并发改派时若服务端归属已变，
 * 后端拒绝并返回任务当前归属，避免同一任务被两个调度员来回抢占。
 */
@Data
public class ReassignRequest {

    @NotNull(message = "目标引车员不能为空")
    private Long driverId;

    /** 目标小时段 8..17；为空表示保持任务原时段，只换引车员 */
    private Integer scheduleHour;

    /** 页面快照中的当前引车员 id（可选）；与服务端当前归属不一致时拒绝并返回当前归属 */
    private Long expectedDriverId;
}
