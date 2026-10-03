package com.vehicle.check;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.GlobalExceptionHandler;
import com.vehicle.check.controller.TaskController;
import com.vehicle.check.dto.TaskCreateRequest;
import com.vehicle.check.dto.TaskVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.ReportRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.VehicleRepository;
import com.vehicle.check.service.ResourceService;
import com.vehicle.check.service.TaskFlowService;

/**
 * 建档派发接口：请求校验与业务拒绝的统一返回。
 */
class TaskControllerTest {

    private MockMvc mvc;
    private TaskFlowService taskFlowService;

    @BeforeEach
    void setUp() {
        taskFlowService = mock(TaskFlowService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(
                        taskFlowService,
                        mock(ResourceService.class),
                        mock(LineRepository.class),
                        mock(StationRepository.class),
                        mock(VehicleRepository.class),
                        mock(ReportRepository.class),
                        mock(DeviceRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void create_missing_vehicle_returns_400() throws Exception {
        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":1,\"driverId\":1,\"scheduleHour\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("车辆不能为空")));
    }

    @Test
    void create_biz_reject_returns_400_with_reason() throws Exception {
        when(taskFlowService.createTask(any(TaskCreateRequest.class)))
                .thenThrow(new BizException("检测线「危化品车专线（L05）」当前为停用状态，不能派发检测任务"));

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":5,\"lineId\":5,\"driverId\":1,\"scheduleHour\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("停用状态")));
    }

    @Test
    void create_success_returns_task() throws Exception {
        TaskVO vo = new TaskVO();
        vo.setId(12L);
        vo.setTaskNo("TASK-20261003-012");
        vo.setDriverId(1L);
        vo.setScheduleHour(9);
        when(taskFlowService.createTask(any(TaskCreateRequest.class))).thenReturn(vo);

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":11,\"lineId\":1,\"driverId\":1,\"scheduleHour\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.taskNo").value("TASK-20261003-012"))
                .andExpect(jsonPath("$.data.scheduleHour").value(9));
    }
}
