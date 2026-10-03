package com.vehicle.check.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 派发/改派候选引车员：资质、在岗、排班与指定日期+时段的可用性。
 * 调度员据此看到「谁能派、谁不能派、阻断原因是什么」。
 */
@Data
public class DriverCandidateVO {

    private Long driverId;
    private String driverName;
    private String certLevel;
    private String certNo;
    private LocalDate certExpireDate;
    private boolean certValid;
    private String status;
    private String statusName;
    private String shiftHours;
    private List<Integer> shiftHourList = new ArrayList<>();
    private Integer checkedCount;
    /** 指定日期+时段下是否可派 */
    private boolean available;
    /** 不可派的阻断原因（不在岗 / 资质过期 / 时段不在班 / 该时段已有未结束任务）；可派为 null */
    private String blockReason;
    /** 该时段格内已有的未结束任务（占用来源） */
    private Long taskId;
    private String taskNo;
    private String plateNo;
}
