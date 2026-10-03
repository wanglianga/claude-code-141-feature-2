package com.campus.food.domain.enums;

/**
 * 餐次食材行在菜单调整中的标记：
 * NORMAL 原菜单正常行；ORIGINAL 因少送被替代的原行（原菜单保留留档，不再供应）；
 * SUBSTITUTE 调整时新增的替代/补充行。
 */
public enum MealIngredientFlag {
    NORMAL,
    ORIGINAL,
    SUBSTITUTE
}
