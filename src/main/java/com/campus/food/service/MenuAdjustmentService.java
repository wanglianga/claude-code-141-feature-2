package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 菜单调整服务（少送 -> 菜单调整）：
 * 供应商少送导致无法按原菜单供餐时，依据缺口数量、库存替代品与年级用餐人数生成调整建议；
 * 采购员确认改用替代品/增加补量食材后，一并完成菜单变更、营养差异核算、家长公告草稿与扣罚留档；
 * 公告发布后保留原菜单、替代菜、营养差异与通知范围，供后续家长问询解释变更原因。
 */
@Service
@RequiredArgsConstructor
public class MenuAdjustmentService {

    private final MenuAdjustmentRepo adjustmentRepo;
    private final MenuAdjustmentItemRepo itemRepo;
    private final MenuAdjustmentGradeRepo gradeRepo;
    private final AnomalyRecordRepo anomalyRepo;
    private final AcceptanceRecordRepo recordRepo;
    private final AcceptanceItemRepo acceptanceItemRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final MenuMealRepo mealRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final IngredientRepo ingredientRepo;
    private final StockLevelRepo stockLevelRepo;
    private final GradeLevelRepo gradeLevelRepo;
    private final ParentAnnouncementRepo announcementRepo;
    private final AppUserRepo userRepo;
    private final InventoryService inventoryService;

    public record SelectionInput(Long itemId, Boolean selected, BigDecimal confirmedQty) {
    }

    /**
     * 按少送异常生成菜单调整建议（幂等：同一异常重复调用返回已生成的建议）。
     */
    @Transactional
    public AdjustmentDetail suggest(Long anomalyId) {
        AnomalyRecord anomaly = anomalyRepo.findById(anomalyId)
                .orElseThrow(() -> BusinessException.notFound("异常不存在: " + anomalyId));
        if (anomaly.getType() != AnomalyType.SHORT_DELIVERY) {
            throw BusinessException.badRequest("仅少送异常可生成菜单调整建议，当前类型: " + anomaly.getType());
        }
        if (anomaly.getBatch() == null) {
            throw BusinessException.badRequest("该少送异常未关联送货批次，无法生成建议");
        }
        MenuAdjustment existing = adjustmentRepo.findByAnomalyId(anomalyId).orElse(null);
        if (existing != null && existing.getStatus() != MenuAdjustmentStatus.CANCELLED) {
            return detail(existing.getId());
        }

        Ingredient shortIng = Optional.ofNullable(anomaly.getIngredient()).orElseThrow(
                () -> BusinessException.badRequest("少送异常缺少食材信息，无法生成建议"));
        DeliveryBatch batch = anomaly.getBatch();

        // 缺口数量与合同价（按订货明细累计送货量复算，与验收少送判定口径一致）
        BigDecimal shortQty = BigDecimal.ZERO;
        BigDecimal unitPrice = shortIng.getReferencePrice();
        AcceptanceRecord accRecord = recordRepo.findByBatchId(batch.getId()).orElse(null);
        if (accRecord != null) {
            for (AcceptanceItem ai : acceptanceItemRepo.findByRecordId(accRecord.getId())) {
                OrderItem oi = ai.getDeliveryItem().getOrderItem();
                if (oi == null || !oi.getIngredient().getId().equals(shortIng.getId())) {
                    continue;
                }
                BigDecimal before = deliveryItemRepo.sumDeliveredExcludingBatch(oi.getId(), batch.getId());
                BigDecimal gap = oi.getQuantity().subtract(before).subtract(ai.getDeliveredQty());
                if (gap.signum() > 0) {
                    shortQty = gap;
                    unitPrice = oi.getContractPrice();
                }
                break;
            }
        }
        if (shortQty.signum() <= 0) {
            throw BusinessException.badRequest("该批次未复算出[" + shortIng.getName() + "]缺口，无需菜单调整");
        }
        BigDecimal shortAmount = shortQty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);

        // 受影响餐次：到货日起 7 天内需要该少送食材、尚未供餐/取消的餐次
        LocalDate from = batch.getArrivedAt().toLocalDate();
        LocalDate to = from.plusDays(7);
        List<MenuMealIngredient> needing = mealIngredientRepo.findMealsNeeding(
                shortIng.getId(), from, to, List.of(MealStatus.SCHEDULED, MealStatus.PREPARED));
        if (needing.isEmpty()) {
            throw BusinessException.badRequest("未来 7 天内没有需要[" + shortIng.getName() + "]的在排餐次，无需菜单调整");
        }

        // 少送后现有库存（已到部分验收入库）仍可按原菜单供应的数量；超出部分才需要净缺口替代
        BigDecimal ownAvail = stockLevelRepo.findByIngredientId(shortIng.getId())
                .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);

        // 净缺口分配用的替代食材库存（多餐次共享，逐次扣减）
        Map<Long, BigDecimal> runningStock = new HashMap<>();
        for (StockLevel sl : stockLevelRepo.findAll()) {
            runningStock.put(sl.getIngredient().getId(), sl.getQuantity());
        }

        MenuAdjustment adj = new MenuAdjustment();
        adj.setAdjustmentNo(NoGen.next("MA"));
        adj.setAnomaly(anomaly);
        adj.setBatch(batch);
        adj.setSupplier(anomaly.getSupplier());
        adj.setShortIngredient(shortIng);
        adj.setShortQty(shortQty);
        adj.setShortAmount(shortAmount);
        adj.setStatus(MenuAdjustmentStatus.SUGGESTED);
        adjustmentRepo.save(adj);

        BigDecimal originalCal = BigDecimal.ZERO;   // 涉及餐次原菜单全部营养
        BigDecimal originalPro = BigDecimal.ZERO;
        BigDecimal retainedCal = BigDecimal.ZERO;   // 现有库存仍可按原菜单供应的营养
        BigDecimal retainedPro = BigDecimal.ZERO;
        BigDecimal adjustedCal = BigDecimal.ZERO;   // = retained + 替代 + 增补
        BigDecimal adjustedPro = BigDecimal.ZERO;

        List<Ingredient> groupCandidates = groupCandidates(shortIng);
        List<Ingredient> categoryCandidates = categoryCandidates(shortIng, groupCandidates);
        List<Ingredient> allReplaceCandidates = new ArrayList<>();
        allReplaceCandidates.addAll(groupCandidates);
        allReplaceCandidates.addAll(categoryCandidates);
        for (MenuMealIngredient mmi : needing.stream()
                .sorted(Comparator.comparing(x -> x.getMeal().getDate())).toList()) {
            MenuMeal meal = mmi.getMeal();
            BigDecimal need = mmi.getQuantity();

            // 先用现有少送食材库存按原菜单供应
            BigDecimal retained = ownAvail.min(need);
            ownAvail = ownAvail.subtract(retained);
            BigDecimal mealRetainedCal = nz(calories(shortIng, retained));
            BigDecimal mealRetainedPro = nz(protein(shortIng, retained));
            retainedCal = retainedCal.add(mealRetainedCal);
            retainedPro = retainedPro.add(mealRetainedPro);

            BigDecimal gap = need.subtract(retained);
            if (gap.signum() <= 0) {
                continue; // 现有库存足以按原菜单供应该餐次，无需调整动作
            }
            // 营养对比只针对净缺口替代部分（现有库存仍按原菜供应的 retained 两期相同，单列参考）
            originalCal = originalCal.add(nz(calories(shortIng, gap)));
            originalPro = originalPro.add(nz(protein(shortIng, gap)));

            // 1) 库存替代品补足净缺口：先同替代组（鸡块），组内不足再回退同品类（其他肉类）
            BigDecimal covered = BigDecimal.ZERO;
            BigDecimal replaceCal = BigDecimal.ZERO;
            BigDecimal replacePro = BigDecimal.ZERO;
            for (List<Ingredient> phase : List.of(groupCandidates, categoryCandidates)) {
                for (Ingredient cand : phase) {
                    if (covered.compareTo(gap) >= 0) {
                        break;
                    }
                    BigDecimal avail = runningStock.getOrDefault(cand.getId(), BigDecimal.ZERO);
                    if (avail.signum() <= 0) {
                        continue;
                    }
                    BigDecimal take = gap.subtract(covered).min(avail);
                    BigDecimal availAtSuggest = stockLevelRepo.findByIngredientId(cand.getId())
                            .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);

                    MenuAdjustmentItem row = new MenuAdjustmentItem();
                    row.setAdjustment(adj);
                    row.setMeal(meal);
                    row.setActionType(AdjustmentActionType.REPLACE);
                    row.setOriginalIngredient(shortIng);
                    row.setOriginalQty(take);
                    row.setMealRequiredQty(need);
                    row.setRetainedQty(retained);
                    row.setReplacementIngredient(cand);
                    row.setSuggestedQty(take);
                    row.setAvailableStock(availAtSuggest);
                    row.setOriginalCalories(calories(shortIng, take));
                    row.setReplacementCalories(calories(cand, take));
                    row.setOriginalProtein(protein(shortIng, take));
                    row.setReplacementProtein(protein(cand, take));
                    itemRepo.save(row);

                    runningStock.merge(cand.getId(), take.negate(), BigDecimal::add);
                    covered = covered.add(take);
                    replaceCal = replaceCal.add(nz(calories(cand, take)));
                    replacePro = replacePro.add(nz(protein(cand, take)));
                }
                if (covered.compareTo(gap) >= 0) {
                    break;
                }
            }

            // 完全无库存替代品可补的餐次不产生动作（留待紧急采购），营养差异中如实体现缺口
            if (covered.signum() <= 0) {
                continue;
            }
            adjustedCal = adjustedCal.add(replaceCal);
            adjustedPro = adjustedPro.add(replacePro);

            // 2) 营养缺口增补（优先补蛋白质，选单位蛋白质最高的有库存食材，如鸡蛋）
            BigDecimal gapOrigCal = nz(calories(shortIng, gap));
            BigDecimal gapOrigPro = nz(protein(shortIng, gap));
            BigDecimal proteinGap = gapOrigPro.subtract(replacePro);
            BigDecimal calorieGap = gapOrigCal.subtract(replaceCal);
            Ingredient supplement = pickSupplement(shortIng, allReplaceCandidates, runningStock, proteinGap);
            if (supplement != null) {
                BigDecimal perPro = nz(supplement.getProteinPerUnit());
                BigDecimal needQty;
                if (proteinGap.signum() > 0 && perPro.signum() > 0) {
                    needQty = roundHalf(proteinGap.divide(perPro, 3, RoundingMode.HALF_UP));
                } else {
                    BigDecimal perCal = nz(supplement.getCaloriesPerUnit());
                    needQty = perCal.signum() > 0
                            ? roundHalf(calorieGap.divide(perCal, 3, RoundingMode.HALF_UP)) : BigDecimal.ZERO;
                }
                BigDecimal avail = runningStock.getOrDefault(supplement.getId(), BigDecimal.ZERO);
                BigDecimal take = needQty.min(avail);
                if (take.signum() > 0) {
                    MenuAdjustmentItem row = new MenuAdjustmentItem();
                    row.setAdjustment(adj);
                    row.setMeal(meal);
                    row.setActionType(AdjustmentActionType.SUPPLEMENT);
                    row.setOriginalIngredient(null);
                    row.setOriginalQty(null);
                    row.setMealRequiredQty(need);
                    row.setRetainedQty(retained);
                    row.setReplacementIngredient(supplement);
                    row.setSuggestedQty(take);
                    row.setAvailableStock(avail);
                    row.setOriginalCalories(null);
                    row.setReplacementCalories(calories(supplement, take));
                    row.setOriginalProtein(null);
                    row.setReplacementProtein(protein(supplement, take));
                    itemRepo.save(row);
                    runningStock.merge(supplement.getId(), take.negate(), BigDecimal::add);
                    adjustedCal = adjustedCal.add(nz(calories(supplement, take)));
                    adjustedPro = adjustedPro.add(nz(protein(supplement, take)));
                }
            }
        }

        if (itemRepo.findByAdjustmentId(adj.getId()).isEmpty()) {
            adjustmentRepo.delete(adj);
            throw BusinessException.badRequest("库存中没有可用于替代[" + shortIng.getName() + "]的食材，无法生成可执行建议");
        }

        // 3) 通知范围：全校各年级（在排餐次为全校供餐）
        List<GradeLevel> grades = gradeLevelRepo.findByActiveTrueOrderBySortOrderAsc();
        int students = 0;
        for (GradeLevel g : grades) {
            MenuAdjustmentGrade mg = new MenuAdjustmentGrade();
            mg.setAdjustment(adj);
            mg.setGradeName(g.getName());
            mg.setStudentCount(g.getStudentCount());
            gradeRepo.save(mg);
            students += g.getStudentCount();
        }

        Set<Long> mealIds = new HashSet<>();
        for (MenuAdjustmentItem it : itemRepo.findByAdjustmentId(adj.getId())) {
            mealIds.add(it.getMeal().getId());
        }
        adj.setAffectedMealCount(mealIds.size());
        adj.setAffectedGradeCount(grades.size());
        adj.setAffectedStudentCount(students);
        adj.setOriginalCalories(scale(originalCal));
        adj.setOriginalProtein(scale(originalPro));
        adj.setAdjustedCalories(scale(adjustedCal));
        adj.setAdjustedProtein(scale(adjustedPro));
        adj.setRetainedCalories(scale(retainedCal));
        adj.setRetainedProtein(scale(retainedPro));
        adjustmentRepo.save(adj);
        return detail(adj.getId());
    }

    /** 采购员确认调整建议：按选择执行菜单变更、扣减库存、生成家长公告草稿 */
    @Transactional
    public AdjustmentDetail confirm(Long adjustmentId, String username,
                                    List<SelectionInput> selections, String remark) {
        MenuAdjustment adj = get(adjustmentId);
        if (adj.getStatus() != MenuAdjustmentStatus.SUGGESTED) {
            throw BusinessException.conflict("仅待确认的调整建议可确认，当前状态: " + adj.getStatus());
        }
        AppUser buyer = userRepo.findByUsername(username)
                .orElseThrow(() -> BusinessException.notFound("用户不存在: " + username));
        List<MenuAdjustmentItem> rows = itemRepo.findByAdjustmentId(adjustmentId);
        Map<Long, SelectionInput> selMap = new HashMap<>();
        if (selections != null) {
            for (SelectionInput s : selections) {
                selMap.put(s.itemId(), s);
            }
        }

        // 第一遍：校验并落定每条选择
        List<MenuAdjustmentItem> chosenRows = new ArrayList<>();
        for (MenuAdjustmentItem row : rows) {
            SelectionInput sel = selMap.get(row.getId());
            boolean selected = sel != null && Boolean.TRUE.equals(sel.selected());
            BigDecimal qty = sel != null && sel.confirmedQty() != null ? sel.confirmedQty() : row.getSuggestedQty();
            if (qty.signum() <= 0) {
                throw BusinessException.badRequest("确认数量必须大于 0（调整明细 " + row.getId() + "）");
            }
            if (qty.compareTo(row.getAvailableStock()) > 0) {
                throw BusinessException.badRequest("[" + row.getReplacementIngredient().getName()
                        + "] 确认用量 " + qty + " 超过建议时可用库存 " + row.getAvailableStock());
            }
            if (selected) {
                row.setSelected(true);
                row.setConfirmedQty(qty);
                chosenRows.add(row);
            } else {
                row.setSelected(false);
                row.setConfirmedQty(null);
            }
            itemRepo.save(row);
        }
        if (chosenRows.isEmpty()) {
            throw BusinessException.badRequest("至少确认一条调整动作");
        }

        BigDecimal originalCal = BigDecimal.ZERO;
        BigDecimal originalPro = BigDecimal.ZERO;
        BigDecimal retainedCal = BigDecimal.ZERO;
        BigDecimal retainedPro = BigDecimal.ZERO;
        BigDecimal adjustedCal = BigDecimal.ZERO;
        BigDecimal adjustedPro = BigDecimal.ZERO;
        Set<Long> handledMeals = new HashSet<>();

        for (MenuAdjustmentItem row : chosenRows) {
            MenuMeal meal = row.getMeal();
            BigDecimal qty = row.getConfirmedQty();

            if (row.getActionType() == AdjustmentActionType.REPLACE) {
                originalCal = originalCal.add(nz(row.getOriginalCalories()));
                originalPro = originalPro.add(nz(row.getOriginalProtein()));

                // 每餐只处理一次原菜单行：retained 部分减量后仍按原菜供应，净缺口部分留档为 ORIGINAL
                if (handledMeals.add(meal.getId())) {
                    BigDecimal retained = nz(row.getRetainedQty());
                    BigDecimal gapTotal = rows.stream()
                            .filter(r -> r.getMeal().getId().equals(meal.getId())
                                    && r.getActionType() == AdjustmentActionType.REPLACE
                                    && r.isSelected())
                            .map(MenuAdjustmentItem::getOriginalQty)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    MenuMealIngredient orig = mealIngredientRepo.findByMealId(meal.getId()).stream()
                            .filter(m -> m.getIngredient().getId().equals(row.getOriginalIngredient().getId())
                                    && m.getFlag() == MealIngredientFlag.NORMAL)
                            .findFirst()
                            .orElseThrow(() -> BusinessException.conflict(
                                    "餐次原食材行已被其他调整占用: " + row.getOriginalIngredient().getName()));
                    if (retained.signum() > 0) {
                        // 现有库存仍供应的部分保留为正常行（减量），净缺口留档
                        orig.setQuantity(retained);
                        mealIngredientRepo.save(orig);
                        MenuMealIngredient archived = new MenuMealIngredient();
                        archived.setMeal(meal);
                        archived.setIngredient(row.getOriginalIngredient());
                        archived.setQuantity(gapTotal);
                        archived.setFlag(MealIngredientFlag.ORIGINAL);
                        archived.setAdjustmentId(adj.getId());
                        mealIngredientRepo.save(archived);
                        // 现有鸡腿照常供餐，按实领用量出库
                        inventoryService.change(row.getOriginalIngredient().getId(), retained.negate(),
                                InventoryTxnType.OUT, "MENU_ADJUSTMENT", adj.getId(),
                                "菜单调整" + adj.getAdjustmentNo() + " 现有库存["
                                        + row.getOriginalIngredient().getName() + "]按原菜单领用 " + retained);
                    } else {
                        orig.setFlag(MealIngredientFlag.ORIGINAL);
                        orig.setAdjustmentId(adj.getId());
                        mealIngredientRepo.save(orig);
                    }
                    retainedCal = retainedCal.add(nz(calories(row.getOriginalIngredient(), retained)));
                    retainedPro = retainedPro.add(nz(protein(row.getOriginalIngredient(), retained)));
                }

                addSubstituteMealLine(meal, row.getReplacementIngredient(), qty, adj.getId());
                inventoryService.change(row.getReplacementIngredient().getId(), qty.negate(),
                        InventoryTxnType.OUT, "MENU_ADJUSTMENT", adj.getId(),
                        "菜单调整" + adj.getAdjustmentNo() + " 替代[" + row.getOriginalIngredient().getName()
                                + "]领用[" + row.getReplacementIngredient().getName() + "]");
            } else {
                addSubstituteMealLine(meal, row.getReplacementIngredient(), qty, adj.getId());
                inventoryService.change(row.getReplacementIngredient().getId(), qty.negate(),
                        InventoryTxnType.OUT, "MENU_ADJUSTMENT", adj.getId(),
                        "菜单调整" + adj.getAdjustmentNo() + " 增补["
                                + row.getReplacementIngredient().getName() + "]");
            }
            adjustedCal = adjustedCal.add(nz(calories(row.getReplacementIngredient(), qty)));
            adjustedPro = adjustedPro.add(nz(protein(row.getReplacementIngredient(), qty)));
        }

        adj.setOriginalCalories(scale(originalCal));
        adj.setOriginalProtein(scale(originalPro));
        adj.setRetainedCalories(scale(retainedCal));
        adj.setRetainedProtein(scale(retainedPro));
        adj.setAdjustedCalories(scale(adjustedCal));
        adj.setAdjustedProtein(scale(adjustedPro));
        adj.setStatus(MenuAdjustmentStatus.CONFIRMED);
        adj.setConfirmedBy(buyer);
        adj.setConfirmedAt(java.time.LocalDateTime.now());
        adj.setConfirmRemark(remark);

        ParentAnnouncement announcement = buildAnnouncement(adj, rows);
        announcementRepo.save(announcement);
        adj.setAnnouncement(announcement);
        adjustmentRepo.save(adj);
        return detail(adj.getId());
    }

    /** 校方发布家长公告，调整单同步置为已发布，通知范围随公告留档 */
    @Transactional
    public AdjustmentDetail publish(Long adjustmentId) {
        MenuAdjustment adj = get(adjustmentId);
        if (adj.getStatus() != MenuAdjustmentStatus.CONFIRMED) {
            throw BusinessException.conflict("仅已确认的调整单可发布，当前状态: " + adj.getStatus());
        }
        ParentAnnouncement a = adj.getAnnouncement();
        if (a == null) {
            throw BusinessException.conflict("调整单缺少家长公告草稿");
        }
        if (a.getStatus() == AnnouncementStatus.DRAFT) {
            a.setStatus(AnnouncementStatus.PUBLISHED);
            a.setPublishedAt(java.time.LocalDateTime.now());
            a.setNotifyScope(scopeText(adj));
            announcementRepo.save(a);
        }
        adj.setStatus(MenuAdjustmentStatus.PUBLISHED);
        adjustmentRepo.save(adj);
        return detail(adj.getId());
    }

    /** 通用公告发布入口联动：若公告来自菜单调整单，同步调整单状态 */
    @Transactional
    public void syncPublishedIfLinked(ParentAnnouncement announcement) {
        if (announcement.getAdjustmentId() == null) {
            return;
        }
        adjustmentRepo.findById(announcement.getAdjustmentId()).ifPresent(adj -> {
            if (adj.getStatus() == MenuAdjustmentStatus.CONFIRMED) {
                adj.setStatus(MenuAdjustmentStatus.PUBLISHED);
                adjustmentRepo.save(adj);
            }
        });
    }

    /** 家长问询：按餐次解释菜单调整原因（原菜单/替代菜/营养差异/通知范围） */
    @Transactional(readOnly = true)
    public Explanation explainByMeal(Long mealId) {
        MenuMeal meal = mealRepo.findById(mealId)
                .orElseThrow(() -> BusinessException.notFound("餐次不存在: " + mealId));
        Long adjId = mealIngredientRepo.findByMealId(mealId).stream()
                .map(MenuMealIngredient::getAdjustmentId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("该餐次没有菜单调整记录"));
        return explanation(get(adjId));
    }

    @Transactional(readOnly = true)
    public Explanation explanation(MenuAdjustment adj) {
        List<MenuAdjustmentItem> rows = itemRepo.findByAdjustmentId(adj.getId()).stream()
                .filter(MenuAdjustmentItem::isSelected)
                .sorted(Comparator.comparing((MenuAdjustmentItem r) -> r.getMeal().getDate())
                        .thenComparing(r -> r.getActionType()))
                .toList();

        List<MealLineView> originalLines = new ArrayList<>();
        List<MealLineView> replacementLines = new ArrayList<>();
        Set<Long> shownMeals = new HashSet<>();
        for (MenuAdjustmentItem r : rows) {
            if (r.getActionType() == AdjustmentActionType.REPLACE) {
                String note = "原菜单缺额（因少送留档，未供应）";
                if (nz(r.getRetainedQty()).signum() > 0 && shownMeals.add(r.getMeal().getId())) {
                    note = "原菜单缺额（另有现有库存 " + r.getRetainedQty()
                            + r.getOriginalIngredient().getUnit() + " 仍按原菜供应）";
                }
                originalLines.add(new MealLineView(r.getMeal().getId(), r.getMeal().getDate(),
                        r.getMeal().getMealType(), r.getMeal().getDishName(),
                        r.getOriginalIngredient().getName(), r.getOriginalIngredient().getUnit(),
                        r.getOriginalQty(), r.getMealRequiredQty(), r.getRetainedQty(), note));
            }
            replacementLines.add(new MealLineView(r.getMeal().getId(), r.getMeal().getDate(),
                    r.getMeal().getMealType(), r.getMeal().getDishName(),
                    r.getReplacementIngredient().getName(), r.getReplacementIngredient().getUnit(),
                    r.getConfirmedQty(), r.getMealRequiredQty(), r.getRetainedQty(),
                    r.getActionType() == AdjustmentActionType.REPLACE ? "库存替代菜" : "增补补量"));
        }

        BigDecimal calDiff = nz(adj.getAdjustedCalories()).subtract(nz(adj.getOriginalCalories()));
        BigDecimal proDiff = nz(adj.getAdjustedProtein()).subtract(nz(adj.getOriginalProtein()));
        List<GradeView> grades = gradeRepo.findByAdjustmentId(adj.getId()).stream()
                .map(g -> new GradeView(g.getGradeName(), g.getStudentCount()))
                .toList();

        AnomalyRecord anomaly = adj.getAnomaly();
        ParentAnnouncement a = adj.getAnnouncement();
        return new Explanation(
                adj.getId(), adj.getAdjustmentNo(), adj.getStatus(),
                adj.getBatch().getBatchNo(), adj.getSupplier().getName(),
                anomaly.getAnomalyNo(), anomaly.getDescription(),
                new ShortInfo(adj.getShortIngredient().getName(), adj.getShortQty(),
                        adj.getShortIngredient().getUnit(), adj.getShortAmount(),
                        anomaly.getPenaltyAmount()),
                originalLines, replacementLines,
                new Nutrition(nz(adj.getOriginalCalories()), nz(adj.getOriginalProtein()),
                        nz(adj.getAdjustedCalories()), nz(adj.getAdjustedProtein()), calDiff, proDiff,
                        nz(adj.getRetainedCalories()), nz(adj.getRetainedProtein())),
                new NotifyScope(grades, adj.getAffectedStudentCount(), scopeText(adj)),
                a == null ? null : a.getTitle(), a == null ? null : a.getStatus(),
                a == null ? null : a.getContent(),
                adj.getConfirmedBy() == null ? null : adj.getConfirmedBy().getFullName(),
                adj.getConfirmRemark());
    }

    public List<MenuAdjustment> list(MenuAdjustmentStatus status) {
        if (status != null) {
            return adjustmentRepo.findByStatusOrderByCreatedAtDesc(status);
        }
        return adjustmentRepo.findAllByOrderByCreatedAtDesc();
    }

    public List<MenuAdjustment> listByBatch(Long batchId) {
        return adjustmentRepo.findByBatchIdOrderByCreatedAtDesc(batchId);
    }

    public MenuAdjustment get(Long id) {
        return adjustmentRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("菜单调整单不存在: " + id));
    }

    @Transactional(readOnly = true)
    public AdjustmentDetail detail(Long id) {
        MenuAdjustment adj = get(id);
        List<MenuAdjustmentItem> items = itemRepo.findByAdjustmentId(adj.getId()).stream()
                .sorted(Comparator.comparing((MenuAdjustmentItem r) -> r.getMeal().getDate())
                        .thenComparing(r -> r.getActionType()))
                .toList();
        List<MenuAdjustmentGrade> grades = gradeRepo.findByAdjustmentId(adj.getId());
        return new AdjustmentDetail(adj, items, grades, adj.getAnomaly().getPenaltyAmount(), scopeText(adj));
    }

    // ---------- 内部方法 ----------

    private void addSubstituteMealLine(MenuMeal meal, Ingredient ing, BigDecimal qty, Long adjId) {
        MenuMealIngredient line = new MenuMealIngredient();
        line.setMeal(meal);
        line.setIngredient(ing);
        line.setQuantity(qty);
        line.setFlag(MealIngredientFlag.SUBSTITUTE);
        line.setAdjustmentId(adjId);
        mealIngredientRepo.save(line);
    }

    private ParentAnnouncement buildAnnouncement(MenuAdjustment adj, List<MenuAdjustmentItem> rows) {
        List<MenuAdjustmentItem> used = rows.stream().filter(MenuAdjustmentItem::isSelected).toList();
        MenuAdjustmentItem first = used.get(0);
        StringBuilder body = new StringBuilder();
        body.append("各位家长：因供应商【").append(adj.getSupplier().getName()).append("】少送【")
                .append(adj.getShortIngredient().getName()).append("】")
                .append(adj.getShortQty()).append(adj.getShortIngredient().getUnit())
                .append("（少送货值 ¥").append(adj.getShortAmount())
                .append("，按合同扣罚 ¥").append(adj.getAnomaly().getPenaltyAmount())
                .append("，月底结算时少送部分不按原订货量付款），学校无法完全按原菜单供餐。经采购负责人确认，作如下调整：\n");
        Set<Long> shownMeals = new HashSet<>();
        for (MenuAdjustmentItem r : used) {
            body.append("· ").append(r.getMeal().getDate()).append(" ")
                    .append(r.getMeal().getMealType()).append("「").append(r.getMeal().getDishName())
                    .append("」：");
            if (r.getActionType() == AdjustmentActionType.REPLACE) {
                body.append("原用【").append(r.getOriginalIngredient().getName()).append("】")
                        .append(r.getMealRequiredQty()).append(r.getOriginalIngredient().getUnit());
                if (nz(r.getRetainedQty()).signum() > 0 && shownMeals.add(r.getMeal().getId())) {
                    body.append("，其中现有库存 ").append(r.getRetainedQty())
                            .append(r.getOriginalIngredient().getUnit()).append(" 仍按原菜供应");
                }
                body.append("，缺额 ").append(r.getOriginalQty()).append(r.getOriginalIngredient().getUnit())
                        .append(" 改用库存【").append(r.getReplacementIngredient().getName()).append("】")
                        .append(r.getConfirmedQty()).append(r.getReplacementIngredient().getUnit());
            } else {
                body.append("增加【").append(r.getReplacementIngredient().getName()).append("】")
                        .append(r.getConfirmedQty()).append(r.getReplacementIngredient().getUnit()).append("补量");
            }
            body.append("；\n");
        }
        BigDecimal calDiff = nz(adj.getAdjustedCalories()).subtract(nz(adj.getOriginalCalories()));
        BigDecimal proDiff = nz(adj.getAdjustedProtein()).subtract(nz(adj.getOriginalProtein()));
        body.append("营养对比（缺额替代部分）：原菜 热量 ").append(trim(adj.getOriginalCalories()))
                .append(" kcal、蛋白质 ").append(trim(adj.getOriginalProtein())).append(" g；调整后 热量 ")
                .append(trim(adj.getAdjustedCalories())).append(" kcal、蛋白质 ")
                .append(trim(adj.getAdjustedProtein())).append(" g；差异 热量 ")
                .append(signed(calDiff)).append(" kcal、蛋白质 ").append(signed(proDiff)).append(" g。\n");
        body.append("（现有库存仍按原菜供应部分：热量 ").append(trim(adj.getRetainedCalories()))
                .append(" kcal、蛋白质 ").append(trim(adj.getRetainedProtein())).append(" g，调整前后一致。）\n");
        body.append("变更原因：").append(adj.getAnomaly().getDescription()).append("。\n");
        body.append("通知范围：").append(scopeText(adj)).append("（共 ").append(adj.getAffectedStudentCount())
                .append(" 名学生）。学生用餐安全不受影响，特此告知。");

        ParentAnnouncement a = new ParentAnnouncement();
        a.setMeal(first.getMeal());
        a.setAdjustmentId(adj.getId());
        a.setTitle("【供餐调整通知】" + first.getMeal().getDate() + " " + first.getMeal().getMealType()
                + " " + first.getMeal().getDishName() + " 食材调整");
        a.setContent(body.toString());
        a.setReason("供应商少送[" + adj.getShortIngredient().getName() + "]，菜单调整");
        a.setNotifyScope(scopeText(adj) + "（共 " + adj.getAffectedStudentCount() + " 名学生）");
        return a;
    }

    private String scopeText(MenuAdjustment adj) {
        List<MenuAdjustmentGrade> gs = gradeRepo.findByAdjustmentId(adj.getId());
        if (gs.isEmpty()) {
            return "全校";
        }
        String first = gs.get(0).getGradeName();
        String last = gs.get(gs.size() - 1).getGradeName();
        return "全校" + first + "至" + last;
    }

    /** 同替代组候选（如 CHICKEN 组内的鸡块），按建议时点库存降序 */
    private List<Ingredient> groupCandidates(Ingredient shortIng) {
        List<Ingredient> ordered = new ArrayList<>();
        if (shortIng.getSubstituteGroup() != null && !shortIng.getSubstituteGroup().isBlank()) {
            ordered.addAll(ingredientRepo.findBySubstituteGroup(shortIng.getSubstituteGroup()).stream()
                    .filter(c -> !c.getId().equals(shortIng.getId()))
                    .toList());
        }
        ordered.sort(Comparator.comparing(
                (Ingredient c) -> stockLevelRepo.findByIngredientId(c.getId())
                        .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO)).reversed());
        return ordered;
    }

    /** 同品类回退候选（替代组之外，如其他肉类），按库存降序 */
    private List<Ingredient> categoryCandidates(Ingredient shortIng, List<Ingredient> groupCandidates) {
        Set<Long> inGroup = new HashSet<>();
        inGroup.add(shortIng.getId());
        groupCandidates.forEach(c -> inGroup.add(c.getId()));
        return ingredientRepo.findByCategory(shortIng.getCategory()).stream()
                .filter(c -> !inGroup.contains(c.getId()))
                .sorted(Comparator.comparing(
                        (Ingredient c) -> stockLevelRepo.findByIngredientId(c.getId())
                                .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO)).reversed())
                .toList();
    }

    /** 增补食材：有库存、有营养数据、非少送食材本身/非替代候选中，单位蛋白质最高者 */
    private Ingredient pickSupplement(Ingredient shortIng, List<Ingredient> replaceCandidates,
                                      Map<Long, BigDecimal> runningStock, BigDecimal proteinGap) {
        Set<Long> exclude = new HashSet<>();
        exclude.add(shortIng.getId());
        replaceCandidates.forEach(c -> exclude.add(c.getId()));
        return ingredientRepo.findAll().stream()
                .filter(c -> !exclude.contains(c.getId()))
                .filter(c -> runningStock.getOrDefault(c.getId(), BigDecimal.ZERO).signum() > 0)
                .filter(c -> nz(c.getProteinPerUnit()).signum() > 0 || nz(c.getCaloriesPerUnit()).signum() > 0)
                .max(Comparator.comparing(c -> nz(c.getProteinPerUnit())))
                .orElse(null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal calories(Ingredient ing, BigDecimal qty) {
        return ing.getCaloriesPerUnit() == null ? null
                : ing.getCaloriesPerUnit().multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal protein(Ingredient ing, BigDecimal qty) {
        return ing.getProteinPerUnit() == null ? null
                : ing.getProteinPerUnit().multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    /** 向上取整到 0.5 个单位，保证补量不低于理论缺口 */
    private static BigDecimal roundHalf(BigDecimal v) {
        if (v.signum() <= 0) {
            return new BigDecimal("0.5");
        }
        return v.multiply(new BigDecimal("2")).setScale(0, RoundingMode.CEILING)
                .divide(new BigDecimal("2"), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String trim(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String signed(BigDecimal v) {
        return v.signum() > 0 ? "+" + trim(v) : trim(v);
    }

    // ---------- 视图对象 ----------

    public record AdjustmentDetail(MenuAdjustment adjustment, List<MenuAdjustmentItem> items,
                                   List<MenuAdjustmentGrade> grades, BigDecimal penaltyAmount,
                                   String notifyScope) {
    }

    public record MealLineView(Long mealId, LocalDate date, MealType mealType, String dishName,
                               String ingredientName, String unit, BigDecimal quantity,
                               BigDecimal mealRequiredQty, BigDecimal retainedQty, String note) {
    }

    public record GradeView(String gradeName, Integer studentCount) {
    }

    public record ShortInfo(String ingredientName, BigDecimal shortQty, String unit,
                            BigDecimal shortAmount, BigDecimal penaltyAmount) {
    }

    public record Nutrition(BigDecimal originalCalories, BigDecimal originalProtein,
                            BigDecimal adjustedCalories, BigDecimal adjustedProtein,
                            BigDecimal calorieDiff, BigDecimal proteinDiff,
                            BigDecimal retainedCalories, BigDecimal retainedProtein) {
    }

    public record NotifyScope(List<GradeView> grades, Integer studentCount, String scopeText) {
    }

    public record Explanation(Long adjustmentId, String adjustmentNo, MenuAdjustmentStatus status,
                              String batchNo, String supplierName, String anomalyNo, String reason,
                              ShortInfo shortInfo, List<MealLineView> originalLines,
                              List<MealLineView> replacementLines, Nutrition nutrition,
                              NotifyScope notifyScope, String announcementTitle,
                              AnnouncementStatus announcementStatus, String announcementContent,
                              String confirmedBy, String confirmRemark) {
    }
}
