package com.campus.food.domain;

import com.campus.food.domain.enums.AnomalyStatus;
import com.campus.food.domain.enums.AnomalyType;
import com.campus.food.domain.enums.CauseCategory;
import com.campus.food.domain.enums.IngredientCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 异常记录 */
@Entity
@Table(name = "anomaly_records")
@Getter
@Setter
@NoArgsConstructor
public class AnomalyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String anomalyNo;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    private PurchaseOrder order;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private DeliveryBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AnomalyType type;

    /** 涉及品类（冗余，便于按品类复盘） */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private IngredientCategory category;

    /** 涉及食材（少送等按食材定位的异常冗余记录，菜单调整建议据此生成） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AnomalyStatus status = AnomalyStatus.OPEN;

    @Column(length = 512)
    private String description;

    /** 建议合同扣罚金额（结算时汇总） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal penaltyAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDateTime detectedAt = LocalDateTime.now();

    private LocalDateTime resolvedAt;

    /** 复盘归因 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CauseCategory causeCategory;

    @Column(length = 512)
    private String handlingNote;
}
