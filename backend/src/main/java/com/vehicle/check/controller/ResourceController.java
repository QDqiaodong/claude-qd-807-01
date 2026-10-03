package com.vehicle.check.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vehicle.check.common.ApiResponse;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.dto.AssignRequest;
import com.vehicle.check.dto.CalibrateRequest;
import com.vehicle.check.dto.CalibrateStatDTO;
import com.vehicle.check.dto.DeviceVO;
import com.vehicle.check.dto.DriverCandidateVO;
import com.vehicle.check.dto.DriverStatDTO;
import com.vehicle.check.dto.ReassignRequest;
import com.vehicle.check.dto.ScheduleCellVO;
import com.vehicle.check.dto.ScheduleRowVO;
import com.vehicle.check.service.ResourceService;

import jakarta.validation.Valid;

/**
 * 设备标定与引车员排班
 */
@RestController
@RequestMapping("/api")
@Validated
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    // ---------------- 设备 ----------------

    /** 设备台账（含下次标定剩余天数与预警级别） */
    @GetMapping("/devices")
    public ApiResponse<List<DeviceVO>> devices() {
        return ApiResponse.success(resourceService.listDevices());
    }

    @GetMapping("/devices/{id}")
    public ApiResponse<DeviceVO> device(@PathVariable Long id) {
        return ApiResponse.success(resourceService.getDevice(id));
    }

    /** 登记标定结果，自动重置倒计时 */
    @PostMapping("/devices/{id}/calibrate")
    public ApiResponse<DeviceVO> calibrate(@PathVariable Long id, @RequestBody CalibrateRequest request) {
        return ApiResponse.success("标定结果已登记，倒计时已重置", resourceService.calibrate(id, request));
    }

    /** 标定状态统计 */
    @GetMapping("/devices/cal-stat")
    public ApiResponse<CalibrateStatDTO> calStat() {
        return ApiResponse.success(resourceService.calStat());
    }

    // ---------------- 引车员 ----------------

    @GetMapping("/drivers")
    public ApiResponse<List<Driver>> drivers() {
        return ApiResponse.success(resourceService.listDrivers());
    }

    /** 排班格子表：引车员资质/在岗/小时段 + 各格任务（待派/在检/合格/不合格/冲突原因） */
    @GetMapping("/drivers/schedule")
    public ApiResponse<List<ScheduleRowVO>> schedule(@RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return ApiResponse.success(resourceService.schedule(day));
    }

    /** 候选引车员：指定日期+时段下每名引车员是否可派与阻断原因；excludeTaskId 用于改派时排除任务自身 */
    @GetMapping("/drivers/candidates")
    public ApiResponse<List<DriverCandidateVO>> candidates(@RequestParam(required = false) String date,
                                                           @RequestParam(required = false) Integer hour,
                                                           @RequestParam(required = false) Long excludeTaskId) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return ApiResponse.success(resourceService.candidates(day, hour, excludeTaskId));
    }

    /** 派单（补派）：把未指派引车员的任务挂到引车员的某个小时段 */
    @PostMapping("/drivers/{id}/assign")
    public ApiResponse<ScheduleCellVO> assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return ApiResponse.success("派单成功", resourceService.assign(id, request));
    }

    /** 改派：尚未开始检测的任务换到另一名符合约束的引车员；冲突时返回任务当前归属 */
    @PostMapping("/drivers/tasks/{taskId}/reassign")
    public ApiResponse<ScheduleCellVO> reassign(@PathVariable Long taskId,
                                                @Valid @RequestBody ReassignRequest request) {
        return ApiResponse.success("改派成功", resourceService.reassign(taskId, request));
    }

    /** 按引车员统计当日台次 */
    @GetMapping("/drivers/stats")
    public ApiResponse<List<DriverStatDTO>> driverStats(@RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return ApiResponse.success(resourceService.driverStats(day));
    }
}
