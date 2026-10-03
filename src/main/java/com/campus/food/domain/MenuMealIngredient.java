package com.campus.food.domain;

import com.campus.food.domain.enums.MealIngredientLineKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** 餐次所需食材 */
@Entity
@Table(name = "menu_meal_ingredients")
@Getter
@Setter
@NoArgsConstructor
public class MenuMealIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "meal_id")
    private MenuMeal meal;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    /** 需要数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /**
     * 行类型：原菜单行 / 调整替代行。
     * 调整发布后原行标记 ORIGINAL 保留（不再参与需求计算），替代行为 REPLACEMENT。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MealIngredientLineKind lineKind = MealIngredientLineKind.ORIGINAL;

    /** 若为替代行，记录来源菜单调整单 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "adjustment_id")
    private MenuAdjustment adjustment;

    /**
     * 原菜单行在调整发布并被替代菜替换后置为退役（行仍保留用于追溯，
     * 但不再参与采购计划汇总、影响分析与供餐需求计算）。
     */
    @Column(nullable = false)
    private boolean retired = false;
}
