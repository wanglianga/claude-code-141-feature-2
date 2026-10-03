package com.campus.food.service;

import com.campus.food.domain.Ingredient;
import com.campus.food.domain.enums.AdjustmentResolution;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 菜单调整营养差异测算（纯逻辑，便于单测）：
 * 仅对“缺口部分”按人份测算——库存可覆盖的部分原菜单不变。
 * REPLACED：替代菜缺口部分营养 − 原菜缺口部分营养；
 * SUPPLEMENT：补充食材营养为纯增量（原菜按库存减量保留，额外增加鸡蛋等）。
 */
public final class MenuNutrition {

    private MenuNutrition() {
    }

    public record Diff(BigDecimal calories, BigDecimal protein, BigDecimal fat, BigDecimal carbs) {
    }

    public static Diff diffPerServing(AdjustmentResolution resolution,
                                      BigDecimal shortageQty, BigDecimal replacementQty, int servings,
                                      Ingredient original, Ingredient replacement) {
        int people = Math.max(servings, 1);
        BigDecimal gapPerServing = shortageQty.divide(BigDecimal.valueOf(people), 6, RoundingMode.HALF_UP);
        BigDecimal replPerServing = replacementQty.divide(BigDecimal.valueOf(people), 6, RoundingMode.HALF_UP);

        BigDecimal calories = replPerServing.multiply(nvl(replacement.getCaloriesPerUnit()));
        BigDecimal protein = replPerServing.multiply(nvl(replacement.getProteinPerUnit()));
        BigDecimal fat = replPerServing.multiply(nvl(replacement.getFatPerUnit()));
        BigDecimal carbs = replPerServing.multiply(nvl(replacement.getCarbsPerUnit()));

        if (resolution == AdjustmentResolution.REPLACED) {
            calories = calories.subtract(gapPerServing.multiply(nvl(original.getCaloriesPerUnit())));
            protein = protein.subtract(gapPerServing.multiply(nvl(original.getProteinPerUnit())));
            fat = fat.subtract(gapPerServing.multiply(nvl(original.getFatPerUnit())));
            carbs = carbs.subtract(gapPerServing.multiply(nvl(original.getCarbsPerUnit())));
        }
        return new Diff(scale(calories), scale(protein), scale(fat), scale(carbs));
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
