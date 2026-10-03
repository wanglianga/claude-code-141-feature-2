package com.campus.food.domain.enums;

/** 结算明细行类型 */
public enum SettlementItemType {
    DELIVERED,      // 验收合格应付
    RETURN,         // 退货扣减
    DOWNGRADE,      // 降级折价扣减
    CLAIM,          // 索赔扣减
    PENALTY,        // 合同扣罚
    SHORT_DELIVERY  // 少送备忘（数量与货值仅留档，金额 0：少送部分不按订货量付款）
}
