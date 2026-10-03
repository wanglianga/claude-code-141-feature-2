package com.campus.food.domain.enums;

/**
 * 餐次食材行类型：ORIGINAL 为原菜单（调整后仅作快照保留，不参与采购/供餐需求计算），
 * REPLACEMENT 为菜单调整后生效的替代/补充菜。
 */
public enum MealIngredientLineKind {
    ORIGINAL,
    REPLACEMENT
}
