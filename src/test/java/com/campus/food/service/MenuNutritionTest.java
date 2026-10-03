package com.campus.food.service;

import com.campus.food.domain.Ingredient;
import com.campus.food.domain.enums.AdjustmentResolution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuNutritionTest {

    private Ingredient ingredient(String cal, String pro, String fat, String carb) {
        Ingredient i = new Ingredient();
        i.setCaloriesPerUnit(new BigDecimal(cal));
        i.setProteinPerUnit(new BigDecimal(pro));
        i.setFatPerUnit(new BigDecimal(fat));
        i.setCarbsPerUnit(new BigDecimal(carb));
        return i;
    }

    @Test
    void replacedByChickenNuggetMatchesSmokeExpectations() {
        // 香酥鸡腿：480 人，缺口 30kg，鸡块替代 30kg（与冒烟场景一致）
        Ingredient leg = ingredient("1810", "193", "115", "0");
        Ingredient nugget = ingredient("2200", "140", "130", "120");
        MenuNutrition.Diff d = MenuNutrition.diffPerServing(
                AdjustmentResolution.REPLACED,
                new BigDecimal("30"), new BigDecimal("30"), 480, leg, nugget);
        assertEquals(new BigDecimal("24.38"), d.calories());
        assertEquals(new BigDecimal("-3.31"), d.protein());
        assertEquals(new BigDecimal("0.94"), d.fat());
        assertEquals(new BigDecimal("7.50"), d.carbs());
    }

    @Test
    void supplementEggsIsPureIncrement() {
        // 卤鸡腿加餐：200 人，缺口 10kg，增加鸡蛋 10kg（0.05kg/人）
        Ingredient leg = ingredient("1810", "193", "115", "0");
        Ingredient egg = ingredient("1440", "133", "88", "28");
        MenuNutrition.Diff d = MenuNutrition.diffPerServing(
                AdjustmentResolution.SUPPLEMENT,
                new BigDecimal("10"), new BigDecimal("10"), 200, leg, egg);
        assertEquals(new BigDecimal("72.00"), d.calories());
        assertEquals(new BigDecimal("6.65"), d.protein());
        assertEquals(new BigDecimal("4.40"), d.fat());
        assertEquals(new BigDecimal("1.40"), d.carbs());
    }

    @Test
    void onlyGapPortionCountsWhenStockCoversPartOfRequirement() {
        // 需求 35、库存覆盖 25、缺口 10，替代 10：差异只按 10kg 缺口/人份计
        Ingredient leg = ingredient("1000", "100", "10", "0");
        Ingredient nugget = ingredient("1000", "100", "10", "0");
        MenuNutrition.Diff d = MenuNutrition.diffPerServing(
                AdjustmentResolution.REPLACED,
                new BigDecimal("10"), new BigDecimal("10"), 200, leg, nugget);
        assertEquals(new BigDecimal("0.00"), d.calories());
        assertEquals(new BigDecimal("0.00"), d.protein());
    }
}
