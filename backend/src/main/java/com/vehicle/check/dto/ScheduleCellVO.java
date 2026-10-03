package com.vehicle.check.dto;

import lombok.Data;

/**
 * 排班格子：某引车员某小时段上挂载的检测任务。
 * <p>
 * cellState 是派生状态（不改任务状态机）：
 * WAITING 待派（已派引车员、尚未录入任何工位检测值） /
 * CHECKING 在检（已录入工位值，任务仍在 CHECKING/RETEST） /
 * FINISHED 已结束（PASS/FAIL，按 status 区分合格/不合格）。
 * conflictReason：格子上的未结束任务当前存在的约束冲突（引车员不在岗 / 资质过期 / 时段已不在班）。
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
    /** WAITING 待派 / CHECKING 在检 / FINISHED 已结束；空格子为 null */
    private String cellState;
    private String cellStateName;
    /** 格内未结束任务的阻断/冲突原因；无冲突为 null */
    private String conflictReason;
}
