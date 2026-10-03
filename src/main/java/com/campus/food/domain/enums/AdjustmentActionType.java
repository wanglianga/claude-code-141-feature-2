package com.campus.food.domain.enums;

/** 菜单调整动作类型 */
public enum AdjustmentActionType {
    REPLACE,     // 改用库存替代品（如少送鸡腿 -> 改用鸡块）
    SUPPLEMENT   // 增加其他食材补量补营养（如增加鸡蛋）
}
