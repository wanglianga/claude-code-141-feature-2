package com.campus.food.domain;

import com.campus.food.domain.enums.IngredientCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** 食材 */
@Entity
@Table(name = "ingredients")
@Getter
@Setter
@NoArgsConstructor
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IngredientCategory category;

    /** 计量单位：kg / L / 个 ... */
    @Column(nullable = false, length = 16)
    private String unit;

    /** 规格描述，如 "25kg/袋" */
    private String spec;

    /** 是否需要冷链 */
    @Column(nullable = false)
    private boolean requiresColdChain = false;

    /** 冷链温度下限（℃） */
    private BigDecimal coldChainMinTemp;

    /** 冷链温度上限（℃） */
    private BigDecimal coldChainMaxTemp;

    /** 保质期天数 */
    private Integer shelfLifeDays;

    /** 库存安全线 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal safetyStock = BigDecimal.ZERO;

    /** 参考价（生成采购计划/估价用） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal referencePrice = BigDecimal.ZERO;

    /** 替代组：同组食材可互为替代（如 LEAFY 叶菜组） */
    @Column(length = 64)
    private String substituteGroup;

    // ---- 每计量单位营养成分（用于菜单调整时的营养差异对比） ----

    /** 热量（kcal / 单位） */
    @Column(precision = 10, scale = 2)
    private BigDecimal caloriesPerUnit;

    /** 蛋白质（g / 单位） */
    @Column(precision = 10, scale = 2)
    private BigDecimal proteinPerUnit;

    /** 脂肪（g / 单位） */
    @Column(precision = 10, scale = 2)
    private BigDecimal fatPerUnit;

    /** 碳水化合物（g / 单位） */
    @Column(precision = 10, scale = 2)
    private BigDecimal carbsPerUnit;
}
