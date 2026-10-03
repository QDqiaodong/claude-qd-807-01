package com.vehicle.check.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 排班格子表一行：一名引车员 + 资质/在岗状态 + 各小时段格子
 */
@Data
public class ScheduleRowVO {

    private Long driverId;
    private String driverName;
    private String certLevel;
    private String certNo;
    /** 资质有效期至；null 表示长期有效 */
    private LocalDate certExpireDate;
    /** 排班日期当天资质是否有效 */
    private boolean certValid;
    private String shiftHours;
    /** 排班小时段数组，便于前端直接判断在班格子 */
    private List<Integer> shiftHourList = new ArrayList<>();
    private Integer checkedCount;
    private String status;
    private String statusName;
    private List<ScheduleCellVO> cells = new ArrayList<>();
}
