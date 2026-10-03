package com.campus.food.domain;

import com.campus.food.domain.enums.AdjustmentResolution;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 菜单调整明细：一个受影响餐次一行。
 * 保留原菜名、确认的替代菜、替代食材与数量、营养差异（每份），
 * 发布后供家长询问时解释变更原因。
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

    /** 该餐次用餐总人数（年级人数汇总） */
    @Column(nullable = false)
    private Integer servings = 0;

    /** 原菜单食材（鸡腿） */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "original_ingredient_id")
    private Ingredient originalIngredient;

    /** 原菜单用量（该餐次） */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal originalQty;

    /** 扣除可用库存后仍缺的数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal shortageQty;

    /** 原菜名（快照） */
    @Column(nullable = false, length = 128)
    private String originalDishName;

    /** 处理方式（建议生成时为空，待采购员确认） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private AdjustmentResolution resolution;

    /** 确认替代/补充的食材（鸡块或鸡蛋）；取消时为空 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "replacement_ingredient_id")
    private Ingredient replacementIngredient;

    /** 替代食材数量（该餐次，按库存可用量与年级人数测算） */
    @Column(precision = 14, scale = 3)
    private BigDecimal replacementQty;

    /** 确认后的新菜名（如“香酥鸡块”）；取消时为空 */
    @Column(length = 128)
    private String replacementDishName;

    /** 系统建议的候选食材名称（生成建议时快照，便于采购员对照） */
    @Column(length = 512)
    private String suggestedOptions;

    // ---- 营养差异（每份，替代后 - 原菜单；正值表示增加） ----

    /** 热量差（kcal/份） */
    @Column(precision = 10, scale = 2)
    private BigDecimal caloriesDiff;

    /** 蛋白质差（g/份） */
    @Column(precision = 10, scale = 2)
    private BigDecimal proteinDiff;

    /** 脂肪差（g/份） */
    @Column(precision = 10, scale = 2)
    private BigDecimal fatDiff;

    /** 碳水差（g/份） */
    @Column(precision = 10, scale = 2)
    private BigDecimal carbsDiff;
}
