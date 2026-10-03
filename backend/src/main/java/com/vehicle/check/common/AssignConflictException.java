package com.vehicle.check.common;

/**
 * 派发/改派冲突：并发派发或改派撞同一小时格、任务归属已变化、任务已开始/结束时抛出。
 * 语义是「本次操作没有生效」，message 里带任务当前归属，前端必须刷新排班格而不是按旧快照重试。
 */
public class AssignConflictException extends BizException {

    public AssignConflictException(String message) {
        super(message);
    }
}
