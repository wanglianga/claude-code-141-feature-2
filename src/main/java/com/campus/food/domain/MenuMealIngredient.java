package com.campus.food.domain;

import com.campus.food.domain.enums.MealIngredientFlag;
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

    /** 菜单调整标记：正常行 / 被替代的原行（留档） / 调整新增的替代行 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MealIngredientFlag flag = MealIngredientFlag.NORMAL;

    /** 所属菜单调整单（ORIGINAL/SUBSTITUTE 行用于留档追溯，正常行为空） */
    @Column(name = "adjustment_id")
    private Long adjustmentId;
}
