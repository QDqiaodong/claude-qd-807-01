package com.vehicle.check.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 引车员：姓名、资质（含有效期）、当日排班小时段、已检台次。
 */
@Data
@Entity
@Table(name = "vc_driver")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "driver_name", nullable = false)
    private String driverName;

    @Column(name = "cert_level", nullable = false)
    private String certLevel;

    @Column(name = "cert_no", nullable = false)
    private String certNo;

    /** 资质有效期至：为空视为长期有效；早于任务日期即过期，不可派发/改派 */
    @Column(name = "cert_expire_date")
    private LocalDate certExpireDate;

    @Column(name = "shift_hours", nullable = false)
    private String shiftHours;

    @Column(name = "checked_count", nullable = false)
    private Integer checkedCount;

    @Column(name = "status", nullable = false)
    private String status;
}
