package com.vehicle.check;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vehicle.check.common.AssignConflictException;
import com.vehicle.check.common.GlobalExceptionHandler;
import com.vehicle.check.controller.ResourceController;
import com.vehicle.check.dto.DriverCandidateVO;
import com.vehicle.check.dto.ReassignRequest;
import com.vehicle.check.dto.ScheduleCellVO;
import com.vehicle.check.dto.ScheduleRowVO;
import com.vehicle.check.service.ResourceService;

/**
 * 引车员排班接口：排班格子表、候选引车员阻断原因、派单/改派的参数校验与冲突归属返回。
 */
class ResourceControllerTest {

    private MockMvc mvc;
    private ResourceService resourceService;

    @BeforeEach
    void setUp() {
        resourceService = mock(ResourceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ResourceController(resourceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void schedule_returns_rows_with_cert_and_cells() throws Exception {
        ScheduleRowVO row = new ScheduleRowVO();
        row.setDriverId(1L);
        row.setDriverName("张引车");
        row.setCertLevel("A1");
        row.setCertValid(true);
        row.setStatus("ON_DUTY");
        row.setStatusName("在岗");
        when(resourceService.schedule(LocalDate.of(2026, 10, 3))).thenReturn(List.of(row));

        mvc.perform(get("/api/drivers/schedule").param("date", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data[0].driverName").value("张引车"))
                .andExpect(jsonPath("$.data[0].certValid").value(true))
                .andExpect(jsonPath("$.data[0].statusName").value("在岗"));
    }

    @Test
    void candidates_returns_block_reason() throws Exception {
        DriverCandidateVO vo = new DriverCandidateVO();
        vo.setDriverId(6L);
        vo.setDriverName("周引车");
        vo.setAvailable(false);
        vo.setBlockReason("当日不在岗；资质已于 2026-09-20 过期");
        when(resourceService.candidates(LocalDate.of(2026, 10, 3), 9, null)).thenReturn(List.of(vo));

        mvc.perform(get("/api/drivers/candidates").param("date", "2026-10-03").param("hour", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].available").value(false))
                .andExpect(jsonPath("$.data[0].blockReason").value("当日不在岗；资质已于 2026-09-20 过期"));
    }

    @Test
    void assign_missing_fields_returns_400() throws Exception {
        mvc.perform(post("/api/drivers/2/assign")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("检测任务不能为空")));
    }

    @Test
    void reassign_missing_driver_returns_400() throws Exception {
        mvc.perform(post("/api/drivers/tasks/7/reassign")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("目标引车员不能为空")));
    }

    @Test
    void reassign_conflict_returns_current_owner() throws Exception {
        when(resourceService.reassign(eq(7L), any(ReassignRequest.class)))
                .thenThrow(new AssignConflictException(
                        "任务 TASK-0007 的归属已发生变化，本次改派未生效；任务当前归属：引车员「李引车」 · 10:00 时段"));

        mvc.perform(post("/api/drivers/tasks/7/reassign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":3,\"scheduleHour\":11,\"expectedDriverId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message")
                        .value(containsString("任务当前归属：引车员「李引车」 · 10:00 时段")));
    }

    @Test
    void reassign_success_returns_new_cell() throws Exception {
        ScheduleCellVO cell = new ScheduleCellVO();
        cell.setTaskId(7L);
        cell.setTaskNo("TASK-0007");
        cell.setHour(13);
        cell.setHourLabel("13:00");
        cell.setPlateNo("京A·Q8801");
        when(resourceService.reassign(eq(7L), any(ReassignRequest.class))).thenReturn(cell);

        mvc.perform(post("/api/drivers/tasks/7/reassign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":3,\"scheduleHour\":13}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.taskNo").value("TASK-0007"))
                .andExpect(jsonPath("$.data.hour").value(13));
    }
}
