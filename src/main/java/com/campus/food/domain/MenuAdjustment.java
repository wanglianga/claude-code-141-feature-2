package com.campus.food.domain;

import com.campus.food.domain.enums.AdjustmentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 菜单调整单：因供应商少送（鸡腿）无法按原菜单供餐时，
 * 记录缺口、确认的替代方案、营养差异、家长公告通知范围与供应商扣罚。
 * 发布后原菜单与替代菜一并保留，供家长询问时解释变更原因。
 */
@Entity
@Table(name = "menu_adjustments")
@Getter
@Setter
@NoArgsConstructor
public class MenuAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String adjustmentNo;

    /** 触发调整的少送验收批次 */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private DeliveryBatch batch;

    /** 少送的食材（如鸡腿） */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "short_ingredient_id")
    private Ingredient shortIngredient;

    /** 少送数量（原订货 - 累计实送，跨批次） */
    @Column(nullable = false, precision = 14, scale = 3)
    private java.math.BigDecimal shortQty;

    /** 少送货值（扣罚基数，= 少送量 × 合同价） */
    @Column(nullable = false, precision = 14, scale = 2)
    private java.math.BigDecimal shortAmount;

    /** 建议扣罚（少送货值 10%，与 SHORT_DELIVERY 异常扣罚一致） */
    @Column(nullable = false, precision = 14, scale = 2)
    private java.math.BigDecimal penaltyAmount;

    /**
     * 关联验收时自动登记的少送异常（扣罚随该异常进入月底结算，不重复计罚）。
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "short_anomaly_id")
    private AnomalyRecord shortAnomaly;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AdjustmentStatus status = AdjustmentStatus.DRAFT;

    /** 受影响餐次数 */
    @Column(nullable = false)
    private int affectedMeals = 0;

    /** 通知范围内的年级用餐总人数 */
    @Column(nullable = false)
    private int notifiedStudents = 0;

    /** 通知的年级范围（冗余快照，如 GRADE_1~GRADE_6） */
    @Column(length = 256)
    private String notifiedGradeScope;

    private String reason;

    @Column(length = 1000)
    private String remark;

    /** 采购员确认人 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "confirmed_by")
    private AppUser confirmedBy;

    private LocalDateTime confirmedAt;

    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
