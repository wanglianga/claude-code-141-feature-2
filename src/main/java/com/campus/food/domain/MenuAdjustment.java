package com.campus.food.domain;

import com.campus.food.domain.enums.MenuAdjustmentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 菜单调整单：因供应商少送导致无法按原菜单供餐时，
 * 依据缺口数量、库存替代品与年级用餐人数生成调整建议；
 * 采购员确认后记录菜单变更、营养差异、家长公告与供应商扣罚。
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

    /** 触发来源：少送异常 */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "anomaly_id")
    private AnomalyRecord anomaly;

    /** 少送所在送货批次 */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private DeliveryBatch batch;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /** 少送的食材（如鸡腿） */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "short_ingredient_id")
    private Ingredient shortIngredient;

    /** 缺口数量（= 少送数量） */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal shortQty;

    /** 少送货值（= 缺口数量 × 合同价，结算不付、扣罚基数） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal shortAmount;

    /** 受影响餐次数 */
    @Column(nullable = false)
    private Integer affectedMealCount = 0;

    /** 涉及年级数 */
    @Column(nullable = false)
    private Integer affectedGradeCount = 0;

    /** 通知范围涉及的学生人数（年级用餐人数合计） */
    @Column(nullable = false)
    private Integer affectedStudentCount = 0;

    /** 原菜单总热量（kcal，涉及餐次合计） */
    @Column(precision = 12, scale = 2)
    private BigDecimal originalCalories;

    /** 调整后总热量（kcal） */
    @Column(precision = 12, scale = 2)
    private BigDecimal adjustedCalories;

    /** 原菜单总蛋白质（g） */
    @Column(precision = 12, scale = 2)
    private BigDecimal originalProtein;

    /** 调整后总蛋白质（g） */
    @Column(precision = 12, scale = 2)
    private BigDecimal adjustedProtein;

    /** 现有库存/已到部分仍能按原菜单供应的营养（热量 kcal，调整前后均含此项） */
    @Column(precision = 12, scale = 2)
    private BigDecimal retainedCalories;

    /** 现有库存/已到部分仍能按原菜单供应的营养（蛋白质 g） */
    @Column(precision = 12, scale = 2)
    private BigDecimal retainedProtein;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MenuAdjustmentStatus status = MenuAdjustmentStatus.SUGGESTED;

    /** 确认采购员 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "confirmed_by")
    private AppUser confirmedBy;

    private LocalDateTime confirmedAt;

    /** 确认备注（如“改用库存鸡块并增加鸡蛋补量”） */
    @Column(length = 512)
    private String confirmRemark;

    /** 关联家长公告（确认后生成草稿，校方发布） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "announcement_id")
    private ParentAnnouncement announcement;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
