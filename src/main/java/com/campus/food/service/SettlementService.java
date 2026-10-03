package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 结算服务：按实际验收重量、退货、降级折价、索赔与合同扣罚生成账单；
 * 确认时核对供应商开票金额，不一致则登记结算差异异常。
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepo settlementRepo;
    private final SettlementItemRepo settlementItemRepo;
    private final SupplierRepo supplierRepo;
    private final AcceptanceItemRepo acceptanceItemRepo;
    private final AcceptanceRecordRepo recordRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final ClaimRepo claimRepo;
    private final AnomalyRecordRepo anomalyRepo;
    private final AnomalyService anomalyService;

    @Transactional
    public Settlement generate(Long supplierId, LocalDate start, LocalDate end) {
        Supplier supplier = supplierRepo.findById(supplierId)
                .orElseThrow(() -> BusinessException.notFound("供应商不存在: " + supplierId));
        if (start == null || end == null || end.isBefore(start)) {
            throw BusinessException.badRequest("结算周期不合法");
        }
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.atTime(LocalTime.MAX);

        Settlement st = new Settlement();
        st.setBillNo(NoGen.next("ST"));
        st.setSupplier(supplier);
        st.setPeriodStart(start);
        st.setPeriodEnd(end);
        settlementRepo.save(st);

        BigDecimal delivered = BigDecimal.ZERO;
        BigDecimal returnDed = BigDecimal.ZERO;
        BigDecimal downgradeDed = BigDecimal.ZERO;

        // 1) 验收明细：按实际验收重量×合同价
        List<AcceptanceItem> items = acceptanceItemRepo.findForSupplierPeriod(supplierId, from, to);
        for (AcceptanceItem ai : items) {
            OrderItem oi = ai.getDeliveryItem().getOrderItem();
            BigDecimal price = oi != null ? oi.getContractPrice() : ai.getIngredient().getReferencePrice();

            if (ai.getAcceptedQty().signum() > 0) {
                BigDecimal amt = ai.getAcceptedQty().multiply(price);
                delivered = delivered.add(amt);
                addLine(st, SettlementItemType.DELIVERED, ai.getIngredient(), ai,
                        ai.getAcceptedQty(), price, amt, "验收合格");
            }
            if (ai.getDowngradedQty().signum() > 0 && ai.getDowngradeStatus() == DowngradeStatus.APPROVED) {
                BigDecimal full = ai.getDowngradedQty().multiply(price);
                BigDecimal rate = ai.getDiscountRate() == null ? new BigDecimal("0.20") : ai.getDiscountRate();
                BigDecimal ded = full.multiply(rate);
                delivered = delivered.add(full);
                downgradeDed = downgradeDed.add(ded);
                addLine(st, SettlementItemType.DELIVERED, ai.getIngredient(), ai,
                        ai.getDowngradedQty(), price, full, "降级使用（全价基准）");
                addLine(st, SettlementItemType.DOWNGRADE, ai.getIngredient(), ai,
                        ai.getDowngradedQty(), price, ded.negate(), "降级折价扣减，折价率 " + rate);
            }
            if (ai.getRejectedQty().signum() > 0) {
                BigDecimal ded = ai.getRejectedQty().multiply(price);
                returnDed = returnDed.add(ded);
                addLine(st, SettlementItemType.RETURN, ai.getIngredient(), ai,
                        ai.getRejectedQty(), price, ded.negate(), "拒收退货扣减");
            }

            // 少送备忘：按订货明细累计送货量复算缺口，缺口货值仅留档、金额 0——少送批次不按原订货量付款
            OrderItem oiForShort = ai.getDeliveryItem().getOrderItem();
            if (oiForShort != null) {
                BigDecimal before = deliveryItemRepo
                        .sumDeliveredExcludingBatch(oiForShort.getId(), ai.getRecord().getBatch().getId());
                BigDecimal shortQty = oiForShort.getQuantity().subtract(before)
                        .subtract(ai.getDeliveredQty());
                if (shortQty.signum() > 0) {
                    addLine(st, SettlementItemType.SHORT_DELIVERY, ai.getIngredient(), ai,
                            shortQty, price, BigDecimal.ZERO,
                            "少送 " + shortQty + ai.getIngredient().getUnit() + " 不予付款（货值 "
                                    + scale(shortQty.multiply(price)) + " 仅留档，按原订货量应付中不含此项）");
                }
            }
        }

        // 2) 索赔扣减（周期内已批准/已支付）
        BigDecimal claimDed = BigDecimal.ZERO;
        List<Claim> claims = claimRepo.findBySupplierIdAndStatusInAndCreatedAtBetween(
                supplierId, List.of(ClaimStatus.APPROVED, ClaimStatus.PAID), from, to);
        for (Claim c : claims) {
            claimDed = claimDed.add(c.getAmount());
            addLine(st, SettlementItemType.CLAIM, null, null, null, null,
                    c.getAmount().negate(), "索赔 " + c.getClaimNo() + " " + c.getClaimType());
        }

        // 3) 合同扣罚（周期内登记的异常）
        BigDecimal penaltyDed = BigDecimal.ZERO;
        List<AnomalyRecord> anomalies = anomalyRepo.findBySupplierIdAndDetectedAtBetween(supplierId, from, to);
        for (AnomalyRecord a : anomalies) {
            if (a.getPenaltyAmount().signum() <= 0) {
                continue;
            }
            penaltyDed = penaltyDed.add(a.getPenaltyAmount());
            addLine(st, SettlementItemType.PENALTY, null, null, null, null,
                    a.getPenaltyAmount().negate(), "扣罚 " + a.getAnomalyNo() + " " + a.getType());
        }

        st.setDeliveredAmount(scale(delivered));
        st.setReturnDeduction(scale(returnDed));
        st.setDowngradeDeduction(scale(downgradeDed));
        st.setClaimDeduction(scale(claimDed));
        st.setPenaltyDeduction(scale(penaltyDed));
        st.setFinalAmount(scale(delivered.subtract(returnDed).subtract(downgradeDed)
                .subtract(claimDed).subtract(penaltyDed)));
        return settlementRepo.save(st);
    }

    private void addLine(Settlement st, SettlementItemType type, Ingredient ingredient, AcceptanceItem ai,
                         BigDecimal qty, BigDecimal price, BigDecimal amount, String note) {
        SettlementItem line = new SettlementItem();
        line.setSettlement(st);
        line.setItemType(type);
        line.setIngredient(ingredient);
        line.setAcceptanceItem(ai);
        line.setQuantity(qty);
        line.setUnitPrice(price);
        line.setAmount(scale(amount));
        line.setNote(note);
        settlementItemRepo.save(line);
    }

    /** 确认结算：录入供应商开票金额，不一致则产生结算差异异常 */
    @Transactional
    public Settlement confirm(Long id, BigDecimal supplierInvoiceAmount) {
        Settlement st = get(id);
        if (st.getStatus() != SettlementStatus.DRAFT) {
            throw BusinessException.conflict("仅草稿状态可确认");
        }
        st.setSupplierInvoiceAmount(supplierInvoiceAmount);
        if (supplierInvoiceAmount != null
                && supplierInvoiceAmount.subtract(st.getFinalAmount()).abs().compareTo(new BigDecimal("0.01")) > 0) {
            st.setMismatchFlag(true);
            anomalyService.create(st.getSupplier(), null, null, AnomalyType.SETTLEMENT_MISMATCH, null,
                    "结算单 " + st.getBillNo() + " 系统应付 " + st.getFinalAmount()
                            + "，供应商开票 " + supplierInvoiceAmount + "，金额不一致",
                    null);
        }
        st.setStatus(SettlementStatus.CONFIRMED);
        st.setConfirmedAt(LocalDateTime.now());
        return settlementRepo.save(st);
    }

    @Transactional
    public Settlement markPaid(Long id) {
        Settlement st = get(id);
        if (st.getStatus() != SettlementStatus.CONFIRMED) {
            throw BusinessException.conflict("仅已确认结算单可标记支付");
        }
        st.setStatus(SettlementStatus.PAID);
        return settlementRepo.save(st);
    }

    public Settlement get(Long id) {
        return settlementRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("结算单不存在: " + id));
    }

    public SettlementDetail detail(Long id) {
        return new SettlementDetail(get(id), settlementItemRepo.findBySettlementId(id));
    }

    public List<Settlement> list(Long supplierId) {
        return supplierId != null ? settlementRepo.findBySupplierId(supplierId) : settlementRepo.findAll();
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public record SettlementDetail(Settlement settlement, List<SettlementItem> items) {
    }
}
