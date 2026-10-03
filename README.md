# 机动车检测站 · 工位与检测报告管理

以「检测线工位流水 + 检测报告」为核心的机动车年检 / 安检站管理系统。

## 端口

| 服务 | 端口 |
| --- | --- |
| 前端 (Vue 3 + Element Plus) | 8287 |
| 后端 (Spring Boot 3.3.0 / Java 17) | 8387 |
| MySQL 8 | 3587 |
| Redis | 6587 |

## 启动 / 停止

```bash
./start.sh            # 首次或 --build 时构建，镜像已存在则秒起
docker compose logs -f backend
./stop.sh             # 停止
./resume.sh           # 恢复
./clean.sh            # down -v（必须带 -v，清掉 mysql 匿名卷）
./nuke.sh             # 彻底清理
```

## 模块

1. **检测线与工位** — 线号、类型（安检 / 环保 / 综检）、状态（运行 / 停用）、承接车型；工位按 登录 → 外观 → 尾气 → 制动 → 灯光 → 底盘 → 总检 顺序流转。
2. **车辆与送检** — 车牌、车主、车辆类型（小型客车 / 货车 / 危化品车）、注册日期、里程、燃料类型、送检人、预约时间。
3. **检测任务与工位流转** — 各工位结果值（尾气 CO/HC、制动率、灯光强度、底盘缺陷项），任务状态 在检 / 合格 / 不合格 / 复检。
4. **检测报告** — 报告编号、判定结论、各分项结果快照、不合格项、签发人、签发日期、复检关联。
5. **设备标定与引车员** — 设备台账与下次标定倒计时；引车员资质、当日排班小时段、已检台次。
6. **在检车辆跨线恢复处置台（/recovery）** — 当前工位故障时，值班人员在此看到全部仍在检测中的受影响车辆，并为每辆车评估候选检测线后确认转线。

## 跨线恢复处置台（模块 6）业务规则

**候选线评估（硬性闸门缺一不可，负载只在全部通过后排序）：**

1. 检测线必须处于「运行」；
2. 必须承接该车辆类型（`vc_line.accept_vehicle_types`）；
3. 从故障工位起的剩余工位顺序与故障线逐位一致（环保单工位线 L03 会因工位序列不全被排除）；
4. 接续工位实时空闲（`IDLE`）——确认提交瞬间会再复核一次；
5. 每个剩余工位的设备均适配且标定有效（超期 / 标定不合格直接排除，空载的 L06 即使负载最低也被排除）。

页面对每条候选线逐条展示「为什么推荐 / 为什么排除」、逐工位设备标定倒计时与接续排队 / 整线负载。

**确认转线的一致性保证：**

- 任务、流水线进度、最终报告共用同一条连续检测链：任务号不换、已完成工位记录与操作痕迹原样保留（含旧线工位 id、不合格结果），未完成环节从目标线**同 seqNo** 工位继续；
- 只允许一次成功：任务行锁 + 接续工位「`UPDATE ... WHERE status='IDLE'`」原子占位 + `vc_line_transfer.success_episode_key` 唯一索引三重防护。两人同时确认、或确认瞬间目标工位被占用 / 停用，失败者收到最新去向提示并留 `FAILED` 痕迹，任务指针与工位占用在同一事务内，要么全成要么全不动，不会出现旧线占位 + 新线接车的半迁移；
- 旧故障工位保持 `FAULT`，由维修解除；故障解除（改回 `IDLE`）后仍在原线等待的车辆自动离台、回原线继续；
- 无可用目标时可「保持待处置」（`PENDING` 留痕），故障前进度完整保留；
- 复检车辆的原报告编号存在 `vc_task.origin_report_no`，转线后仍保留；全部剩余工位完成前签发接口拒绝出报告。


## 数据访问技术标识：`@ColumnTransformer`

| 列 | 库内 | 实体 / 接口 |
| --- | --- | --- |
| `vc_vehicle.owner_id_no` | `AES_ENCRYPT(?, 'seed_key')` 的 VARBINARY 密文 | 明文字符串 |
| `vc_vehicle.owner_phone` | 同上 | 明文字符串 |
| `vc_task.brake_rate` | 小数 `0.6230` | 百分比 `62.30` |

读取表达式：

```java
@ColumnTransformer(
    read = "CONVERT(AES_DECRYPT(owner_id_no, 'seed_key') USING utf8mb4)",
    write = "AES_ENCRYPT(?, 'seed_key')")
private String ownerIdNo;

@ColumnTransformer(read = "brake_rate * 100", write = "? / 100")
private BigDecimal brakeRate;
```

## 手写 JPQL（`@Query`）

- `TaskStationRecordRepository.statPassRateByStation` — 按工位统计通过率
- `TaskStationRecordRepository.rankFailItems` — 不合格项排行
- `TaskRepository.countGroupByDriverAndDate` — 按引车员统计当日台次
- `TaskRepository.countQueueGroupByStation` — 各工位排队车辆数（底部流水线徽章）
- `ReportRepository.statReportByLine` — 按检测线统计报告数与不合格数
- `DeviceRepository.statCalibration` / `findDevicesOfLine` — 标定状态统计与按线取设备

## 业务规则（service 层抛 `BizException`）

- 工位必须按顺序流转：未完成前序工位不允许进入下一工位，提示卡在第几个工位。
- 任一关键项（尾气 / 制动 / 灯光 / 底盘）不合格，报告结论必须为不合格。
- 设备超过标定日期不允许签发报告，提示超期天数。
- 同一车辆当天重复建档送检会被拦截。

## 前端

底部固定「流水线进度条导航」：登录 → 外观 → 尾气 → 制动 → 灯光 → 底盘 → 总检，节点上方显示排队车辆数徽章，
一枚「当前车辆」胶囊沿节点移动（CSS 过渡动画）。四个页面分别为工位流水线、报告预览 + 判定徽章墙、
引车员排班格子表、设备标定倒计时卡。主色 `#01579b`。
