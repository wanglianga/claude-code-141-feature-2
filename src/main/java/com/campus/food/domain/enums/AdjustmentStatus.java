package com.campus.food.domain.enums;

/** 菜单调整单状态 */
public enum AdjustmentStatus {
    DRAFT,       // 建议已生成，待采购员确认
    CONFIRMED,   // 采购员已确认调整方案
    PUBLISHED,   // 已发布（公告同步发布、库存锁定、扣罚入账）
    CANCELLED    // 已取消
}
