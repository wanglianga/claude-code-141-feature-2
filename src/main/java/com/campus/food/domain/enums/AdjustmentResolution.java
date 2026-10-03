package com.campus.food.domain.enums;

/** 菜单调整行的处理方式 */
public enum AdjustmentResolution {
    REPLACED,    // 改用替代食材（如鸡腿 -> 鸡块）
    SUPPLEMENT,  // 增加食材补充（如增加鸡蛋）
    CANCELLED    // 无可用方案，该餐次该道菜取消
}
