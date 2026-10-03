package com.vehicle.check.dto;

import lombok.Data;

/**
 * 排班格子：某引车员某小时段上挂载的检测任务
 */
@Data
public class ScheduleCellVO {

    private Integer hour;
    private String hourLabel;
    private Long taskId;
    private String taskNo;
    private String plateNo;
    private String status;
    private String statusName;
    private String stationName;
}
