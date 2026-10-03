package com.campus.food.domain;

import com.campus.food.domain.enums.AdjustmentActionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 菜单调整明细行：每个受影响餐次的一个调整动作。
 * REPLACE 表示改用库存替代品（鸡腿 -> 鸡块）；
 * SUPPLEMENT 表示增加其他食材补量补营养（增加鸡蛋）。
 * 同时快照原菜单与替代菜的用量、热量、蛋白质，作为营养差异与家长问询的留档。
 */
@Entity
@Table(name = "menu_adjustment_items")
@Getter
@Setter
@NoArgsConstructor
public class MenuAdjustmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "adjustment_id")
    private MenuAdjustment adjustment;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "meal_id")
    private MenuMeal meal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AdjustmentActionType actionType;

    /** 原食材（REPLACE 时为被替代的少送食材；SUPPLEMENT 时为空，表示纯增补） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "original_ingredient_id")
    private Ingredient originalIngredient;

    /** 原菜单用量 */
    @Column(precision = 14, scale = 3)
    private BigDecimal originalQty;

    /** 该餐次原菜单对少送食材的总需求 */
    @Column(precision = 14, scale = 3)
    private BigDecimal mealRequiredQty;

    /** 现有库存（含已到部分）仍能按原菜单供应的数量 */
    @Column(precision = 14, scale = 3)
    private BigDecimal retainedQty;

    /** 替代/增补食材（鸡块 / 鸡蛋） */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "replacement_ingredient_id")
    private Ingredient replacementIngredient;

    /** 建议数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal suggestedQty;

    /** 确认时实际采用数量（采购员可调整） */
    @Column(precision = 14, scale = 3)
    private BigDecimal confirmedQty;

    /** 建议时该替代品可用库存量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal availableStock = BigDecimal.ZERO;

    /** 原菜热量（kcal） */
    @Column(precision = 12, scale = 2)
    private BigDecimal originalCalories;

    /** 替代菜热量（kcal） */
    @Column(precision = 12, scale = 2)
    private BigDecimal replacementCalories;

    /** 原菜蛋白质（g） */
    @Column(precision = 12, scale = 2)
    private BigDecimal originalProtein;

    /** 替代菜蛋白质（g） */
    @Column(precision = 12, scale = 2)
    private BigDecimal replacementProtein;

    /** 采购员是否采纳该建议行 */
    @Column(nullable = false)
    private boolean selected = false;
}
