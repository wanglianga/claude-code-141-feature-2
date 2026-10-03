package com.campus.food.domain.enums;

/** 菜单调整单状态 */
public enum MenuAdjustmentStatus {
    SUGGESTED,   // 已生成调整建议，待采购员确认
    CONFIRMED,   // 采购员已确认并完成菜单变更
    PUBLISHED,   // 调整已随家长公告发布
    CANCELLED    // 建议取消
}
