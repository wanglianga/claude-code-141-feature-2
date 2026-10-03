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
import java.time.LocalDateTime;
import java.util.*;

/**
 * 少送导致菜单调整：
 * 供应商少送（如鸡腿）导致无法按原菜单供餐时，按缺口数量、库存替代品与各年级用餐人数
 * 生成调整建议；采购员确认改用替代菜（鸡块）或增加补充食材（鸡蛋）后，记录菜单变更、
 * 营养差异、家长公告（含通知范围）与供应商扣罚；发布后原菜单/替代菜/差异/通知全部保留，
 * 支持家长询问时解释变更原因。
 *
 * 扣罚说明：少送异常在验收时已自动登记（少送货值 10%），月底结算按异常汇总扣罚，
 * 且结算本身按实际验收重量计价（少送批次不会按原订货量付款），调整单仅关联该异常，不重复计罚。
 */
@Service
@RequiredArgsConstructor
public class MenuAdjustmentService {

    /** 增加鸡蛋等补充食材时，按每人 0.05 单位（约 1 个鸡蛋 / 50g）测算 */
    private static final BigDecimal SUPPLEMENT_PER_STUDENT = new BigDecimal("0.05");

    private final MenuAdjustmentRepo adjustmentRepo;
    private final MenuAdjustmentItemRepo adjustmentItemRepo;
    private final MealGradeServingRepo gradeServingRepo;
    private final DeliveryBatchRepo batchRepo;
    private final AcceptanceRecordRepo recordRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final AnomalyRecordRepo anomalyRepo;
    private final MenuMealRepo mealRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final IngredientRepo ingredientRepo;
    private final StockLevelRepo stockLevelRepo;
    private final ParentAnnouncementRepo announcementRepo;
    private final AppUserRepo userRepo;
    private final InventoryService inventoryService;

    // ============================ DTO ============================

    public record GradeServingView(String gradeLevel, int students) {
    }

    public record SubstituteCandidate(Long ingredientId, String name, String unit,
                                      BigDecimal availableQty, String use) {
    }

    public record MealSuggestion(Long itemId, Long mealId, LocalDate date, String mealType,
                                 String dishName, int servings,
                                 Long originalIngredientId, String originalIngredientName,
                                 BigDecimal requiredQty, BigDecimal shortageQty,
                                 List<GradeServingView> gradeServings,
                                 List<SubstituteCandidate> candidates,
                                 String suggestedReplacementDish) {
    }

    public record Suggestion(Long adjustmentId, String adjustmentNo, String status,
                             Long batchId, String batchNo, Long supplierId, String supplierName,
                             Long shortIngredientId, String shortIngredientName, String unit,
                             BigDecimal shortQty, BigDecimal shortAmount, BigDecimal penaltyAmount,
                             String shortAnomalyNo,
                             int affectedMeals, int notifiedStudents, String notifiedGradeScope,
                             List<MealSuggestion> meals) {
    }

    public record NutritionDiff(BigDecimal calories, BigDecimal protein, BigDecimal fat, BigDecimal carbs) {
    }

    public record ItemView(Long itemId, Long mealId, LocalDate date, String mealType,
                           Integer servings, String originalDishName,
                           String originalIngredientName, BigDecimal originalQty,
                           String resolution, String replacementIngredientName,
                           BigDecimal replacementQty, String replacementDishName,
                           NutritionDiff nutritionDiffPerServing, String suggestedOptions) {
    }

    public record AdjustmentDetail(Long id, String adjustmentNo, String status,
                                   Long batchId, String batchNo, Long supplierId, String supplierName,
                                   String shortIngredientName, BigDecimal shortQty,
                                   BigDecimal shortAmount, BigDecimal penaltyAmount,
                                   String shortAnomalyNo, int affectedMeals,
                                   int notifiedStudents, String notifiedGradeScope,
                                   String reason, String remark,
                                   String confirmedBy, LocalDateTime confirmedAt,
                                   LocalDateTime publishedAt, List<ItemView> items) {
    }

    public record DecisionInput(Long itemId, AdjustmentResolution resolution,
                                Long replacementIngredientId, BigDecimal replacementQty,
                                String replacementDishName) {
    }

    public record GradeExplanation(String gradeLevel, int students) {
    }

    public record MealExplanation(Long mealId, LocalDate date, String mealType,
                                  String currentDishName, String originalDishName, int servings,
                                  String adjustmentNo, String status, String reason,
                                  String originalIngredientName, BigDecimal originalQty,
                                  String resolution, String replacementIngredientName,
                                  BigDecimal replacementQty, String replacementDishName,
                                  NutritionDiff nutritionDiffPerServing,
                                  List<GradeExplanation> notifiedGrades, int notifiedStudents,
                                  String announcementTitle, String announcementContent,
                                  String announcementStatus, LocalDateTime publishedAt,
                                  Long supplierId, String supplierName, String batchNo,
                                  String shortAnomalyNo, BigDecimal shortQty, BigDecimal penaltyAmount) {
    }

    // ============================ 生成调整建议 ============================

    /** 按少送批次生成菜单调整建议（缺口 / 库存替代品 / 年级人数）。已生成草稿则复用。 */
    @Transactional
    public Suggestion suggest(Long batchId, Long ingredientId) {
        DeliveryBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> BusinessException.notFound("送货批次不存在: " + batchId));
        recordRepo.findByBatchId(batchId)
                .orElseThrow(() -> BusinessException.badRequest("该批次尚未验收，无法生成菜单调整建议"));

        MenuAdjustment existing = adjustmentRepo.findByBatchId(batchId).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == AdjustmentStatus.CANCELLED) {
                throw BusinessException.conflict("该批次的菜单调整单已取消");
            }
            return toSuggestion(existing);
        }

        // 该批次验收登记的少送异常（定位少送食材）
        AnomalyRecord shortAnomaly = anomalyRepo.findByBatchId(batchId).stream()
                .filter(a -> a.getType() == AnomalyType.SHORT_DELIVERY && a.getIngredient() != null)
                .filter(a -> ingredientId == null || ingredientId.equals(a.getIngredient().getId()))
                .findFirst()
                .orElseThrow(() -> BusinessException.badRequest("该批次没有少送（SHORT_DELIVERY）记录，无需调整菜单"));
        Ingredient shortIng = shortAnomaly.getIngredient();

        // 缺口数量 = 订货量 - 跨批次累计实送量
        DeliveryItem shortDi = deliveryItemRepo.findByBatchId(batchId).stream()
                .filter(di -> di.getIngredient().getId().equals(shortIng.getId())
                        && di.getOrderItem() != null)
                .findFirst()
                .orElseThrow(() -> BusinessException.badRequest("少送食材缺少订货明细，无法核算缺口"));
        OrderItem oi = shortDi.getOrderItem();
        BigDecimal deliveredBefore = deliveryItemRepo.sumDeliveredExcludingBatch(oi.getId(), batchId);
        BigDecimal cumulativeDelivered = deliveredBefore.add(shortDi.getDeliveredQty());
        BigDecimal shortQty = oi.getQuantity().subtract(cumulativeDelivered).max(BigDecimal.ZERO);
        if (shortQty.signum() <= 0) {
            throw BusinessException.badRequest("[" + shortIng.getName() + "] 累计送货已满足订货量，无缺口");
        }
        BigDecimal price = oi.getContractPrice();
        BigDecimal shortAmount = shortQty.multiply(price).setScale(2, RoundingMode.HALF_UP);

        // 受影响餐次：到货日起 7 天内排期待供、需要少送食材的餐次；库存按时间顺序逐餐扣减
        LocalDate from = batch.getArrivedAt().toLocalDate();
        LocalDate to = from.plusDays(7);
        BigDecimal remainingStock = stockLevelRepo.findByIngredientId(shortIng.getId())
                .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);

        Map<Long, List<MenuMealIngredient>> linesByMeal = new LinkedHashMap<>();
        for (MenuMealIngredient mmi : mealIngredientRepo.findMealsNeeding(shortIng.getId(), from, to,
                List.of(MealStatus.SCHEDULED))) {
            linesByMeal.computeIfAbsent(mmi.getMeal().getId(), k -> new ArrayList<>()).add(mmi);
        }
        List<List<MenuMealIngredient>> orderedLines = linesByMeal.values().stream()
                .sorted(Comparator.comparing(l -> l.get(0).getMeal().getDate()))
                .toList();

        MenuAdjustment adj = new MenuAdjustment();
        adj.setAdjustmentNo(NoGen.next("MA"));
        adj.setBatch(batch);
        adj.setShortIngredient(shortIng);
        adj.setShortQty(shortQty);
        adj.setShortAmount(shortAmount);
        adj.setPenaltyAmount(shortAnomaly.getPenaltyAmount());
        adj.setShortAnomaly(shortAnomaly);
        adj.setReason("供应商[" + batch.getOrder().getSupplier().getName() + "]少送"
                + shortIng.getName() + " " + shortQty + shortIng.getUnit()
                + "，无法按原菜单供餐，系统按缺口/库存替代品/年级用餐人数生成调整建议");
        adjustmentRepo.save(adj);

        Set<GradeLevel> scopeGrades = new LinkedHashSet<>();
        int totalStudents = 0;

        for (List<MenuMealIngredient> lines : orderedLines) {
            MenuMeal meal = lines.get(0).getMeal();
            BigDecimal required = lines.stream().map(MenuMealIngredient::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal allocated = remainingStock.min(required);
            remainingStock = remainingStock.subtract(allocated);
            BigDecimal shortage = required.subtract(allocated);
            if (shortage.signum() <= 0) {
                continue; // 现有库存可满足该餐次
            }

            List<MealGradeServing> gradeRows = gradeServingRepo.findByMealId(meal.getId());
            int servings = gradeRows.stream().map(MealGradeServing::getStudents).reduce(0, Integer::sum);
            if (servings == 0) {
                servings = meal.getServings() == null ? 0 : meal.getServings();
            }
            gradeRows.forEach(g -> scopeGrades.add(g.getGradeLevel()));
            totalStudents += servings;

            // 候选：同替代组 -> 同品类（有库存）= 替代菜；乳制品/蛋类（有库存）= 增加补充
            List<SubstituteCandidate> candidates = substituteCandidates(shortIng, shortage);

            MenuAdjustmentItem item = new MenuAdjustmentItem();
            item.setAdjustment(adj);
            item.setMeal(meal);
            item.setServings(servings);
            item.setOriginalIngredient(shortIng);
            item.setOriginalQty(required.setScale(3, RoundingMode.HALF_UP));
            item.setShortageQty(shortage.setScale(3, RoundingMode.HALF_UP));
            item.setOriginalDishName(meal.getDishName());
            item.setResolution(null); // 待采购员确认
            item.setSuggestedOptions(candidates.stream()
                    .map(c -> c.name() + "(" + c.use() + ",可用" + c.availableQty() + c.unit() + ")")
                    .reduce((a, b) -> a + "；" + b).orElse("无库存候选"));
            adjustmentItemRepo.save(item);
        }

        if (adjustmentItemRepo.findByAdjustmentId(adj.getId()).isEmpty()) {
            adjustmentRepo.delete(adj);
            throw BusinessException.badRequest("库存可满足未来 7 天全部餐次，无需调整菜单");
        }

        adj.setAffectedMeals(adjustmentItemRepo.findByAdjustmentId(adj.getId()).size());
        adj.setNotifiedStudents(totalStudents);
        adj.setNotifiedGradeScope(scopeGrades.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(null));
        adjustmentRepo.save(adj);
        return toSuggestion(adj);
    }

    /** 替代候选：同替代组优先，其次同品类（仅算库存>0）；再补蛋类/乳制品作为“增加”候选 */
    private List<SubstituteCandidate> substituteCandidates(Ingredient shortIng, BigDecimal shortage) {
        LinkedHashMap<Long, SubstituteCandidate> out = new LinkedHashMap<>();
        List<Ingredient> pool = new ArrayList<>();
        if (shortIng.getSubstituteGroup() != null && !shortIng.getSubstituteGroup().isBlank()) {
            pool.addAll(ingredientRepo.findBySubstituteGroup(shortIng.getSubstituteGroup()));
        }
        pool.removeIf(c -> c.getId().equals(shortIng.getId()));
        if (pool.isEmpty()) {
            pool.addAll(ingredientRepo.findByCategory(shortIng.getCategory()));
        }
        for (Ingredient c : pool) {
            if (c.getId().equals(shortIng.getId())) {
                continue;
            }
            BigDecimal qty = stockLevelRepo.findByIngredientId(c.getId())
                    .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);
            if (qty.signum() > 0) {
                out.put(c.getId(), new SubstituteCandidate(c.getId(), c.getName(), c.getUnit(),
                        qty.setScale(3, RoundingMode.HALF_UP), "REPLACED"));
            }
        }
        // 增加鸡蛋等蛋白补充（蛋/乳制品类有库存），与替代候选不重复
        for (Ingredient c : ingredientRepo.findByCategory(IngredientCategory.DAIRY)) {
            if (c.getId().equals(shortIng.getId()) || out.containsKey(c.getId())) {
                continue;
            }
            BigDecimal qty = stockLevelRepo.findByIngredientId(c.getId())
                    .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);
            if (qty.signum() > 0) {
                out.put(c.getId(), new SubstituteCandidate(c.getId(), c.getName(), c.getUnit(),
                        qty.setScale(3, RoundingMode.HALF_UP), "SUPPLEMENT"));
            }
        }
        return new ArrayList<>(out.values());
    }

    // ============================ 采购员确认 ============================

    @Transactional
    public AdjustmentDetail confirm(Long adjustmentId, String buyerUsername, List<DecisionInput> decisions) {
        MenuAdjustment adj = get(adjustmentId);
        if (adj.getStatus() != AdjustmentStatus.DRAFT) {
            throw BusinessException.conflict("仅草稿状态调整单可确认，当前状态: " + adj.getStatus());
        }
        if (decisions == null || decisions.isEmpty()) {
            throw BusinessException.badRequest("必须为每个受影响餐次给出处理决定");
        }
        AppUser buyer = userRepo.findByUsername(buyerUsername)
                .orElseThrow(() -> BusinessException.notFound("用户不存在: " + buyerUsername));
        List<MenuAdjustmentItem> items = adjustmentItemRepo.findByAdjustmentId(adjustmentId);
        Map<Long, MenuAdjustmentItem> itemMap = new LinkedHashMap<>();
        items.forEach(i -> itemMap.put(i.getId(), i));

        // 同一替代食材跨餐次累计用量，确认时不得超过当前库存
        Map<Long, BigDecimal> usedByIngredient = new HashMap<>();
        for (DecisionInput d : decisions) {
            MenuAdjustmentItem item = itemMap.get(d.itemId());
            if (item == null) {
                throw BusinessException.badRequest("调整明细不属于该调整单: " + d.itemId());
            }
            if (d.resolution() == null) {
                throw BusinessException.badRequest("必须选择处理方式（改用替代菜/增加补充/取消）");
            }
            if (d.resolution() == AdjustmentResolution.CANCELLED) {
                item.setResolution(AdjustmentResolution.CANCELLED);
                adjustmentItemRepo.save(item);
                continue;
            }
            if (d.replacementIngredientId() == null) {
                throw BusinessException.badRequest("[" + item.getOriginalDishName() + "] 未选择替代/补充食材");
            }
            Ingredient repl = ingredientRepo.findById(d.replacementIngredientId())
                    .orElseThrow(() -> BusinessException.notFound("食材不存在: " + d.replacementIngredientId()));
            // 必须是建议候选之一
            boolean valid = substituteCandidates(adj.getShortIngredient(), item.getOriginalQty()).stream()
                    .anyMatch(c -> c.ingredientId().equals(repl.getId())
                            && c.use().equals(d.resolution().name()));
            if (!valid) {
                throw BusinessException.badRequest("[" + repl.getName() + "] 不在系统建议的"
                        + (d.resolution() == AdjustmentResolution.SUPPLEMENT ? "补充" : "替代") + "候选中");
            }
            BigDecimal qty = resolveQty(item, d, repl);
            BigDecimal cum = usedByIngredient.merge(repl.getId(), qty, BigDecimal::add);
            BigDecimal available = stockLevelRepo.findByIngredientId(repl.getId())
                    .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);
            if (cum.compareTo(available) > 0) {
                throw BusinessException.conflict("[" + repl.getName() + "] 库存不足：各餐次合计需 "
                        + cum + repl.getUnit() + "，当前库存 " + available + repl.getUnit());
            }
            item.setResolution(d.resolution());
            item.setReplacementIngredient(repl);
            item.setReplacementQty(qty);
            item.setReplacementDishName(dishName(item, d, repl));
            applyNutritionDiff(item, repl, qty);
            adjustmentItemRepo.save(item);
        }
        if (decisions.size() != items.size()) {
            throw BusinessException.badRequest("存在未处理的受影响餐次（共 " + items.size()
                    + " 个，收到决定 " + decisions.size() + " 个）");
        }

        adj.setStatus(AdjustmentStatus.CONFIRMED);
        adj.setConfirmedBy(buyer);
        adj.setConfirmedAt(LocalDateTime.now());
        adjustmentRepo.save(adj);
        return detail(adjustmentId);
    }

    private BigDecimal resolveQty(MenuAdjustmentItem item, DecisionInput d, Ingredient repl) {
        BigDecimal qty;
        if (d.replacementQty() != null && d.replacementQty().signum() > 0) {
            qty = d.replacementQty();
        } else if (d.resolution() == AdjustmentResolution.SUPPLEMENT) {
            qty = SUPPLEMENT_PER_STUDENT.multiply(BigDecimal.valueOf(item.getServings()));
        } else {
            // 等重量替代缺口（受库存可用量约束）
            BigDecimal available = stockLevelRepo.findByIngredientId(repl.getId())
                    .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);
            qty = item.getShortageQty().min(available);
        }
        return qty.setScale(3, RoundingMode.HALF_UP);
    }

    private String dishName(MenuAdjustmentItem item, DecisionInput d, Ingredient repl) {
        if (d.replacementDishName() != null && !d.replacementDishName().isBlank()) {
            return d.replacementDishName();
        }
        if (d.resolution() == AdjustmentResolution.SUPPLEMENT) {
            return item.getOriginalDishName() + "（增加" + repl.getName() + "）";
        }
        String orig = item.getOriginalDishName();
        String origIng = item.getOriginalIngredient().getName();
        return orig.contains(origIng) ? orig.replace(origIng, repl.getName())
                : orig + "（" + repl.getName() + "替代）";
    }

    /**
     * 每份营养差异：仅按缺口部分测算（库存能覆盖的部分原菜单不变）。
     * REPLACED 为“替代菜缺口部分 - 原菜缺口部分”；SUPPLEMENT 为补充食材的纯增量。
     */
    private void applyNutritionDiff(MenuAdjustmentItem item, Ingredient repl, BigDecimal replQty) {
        MenuNutrition.Diff d = MenuNutrition.diffPerServing(
                item.getResolution(), item.getShortageQty(), replQty, item.getServings(),
                item.getOriginalIngredient(), repl);
        item.setCaloriesDiff(d.calories());
        item.setProteinDiff(d.protein());
        item.setFatDiff(d.fat());
        item.setCarbsDiff(d.carbs());
    }

    // ============================ 发布调整 ============================

    /** 发布：菜单换菜（原行退役保留、替代菜生效）、替代食材出库、家长公告按通知范围发布。 */
    @Transactional
    public AdjustmentDetail publish(Long adjustmentId) {
        MenuAdjustment adj = get(adjustmentId);
        if (adj.getStatus() != AdjustmentStatus.CONFIRMED) {
            throw BusinessException.conflict("仅采购员已确认的调整单可发布，当前状态: " + adj.getStatus());
        }
        List<MenuAdjustmentItem> items = adjustmentItemRepo.findByAdjustmentId(adjustmentId);

        for (MenuAdjustmentItem item : items) {
            MenuMeal meal = item.getMeal();
            if (meal.getOriginalDishName() == null) {
                meal.setOriginalDishName(meal.getDishName()); // 首次调整前快照原菜名
            }

            List<MenuMealIngredient> origLines = mealIngredientRepo.findByMealIdAndRetiredFalse(meal.getId())
                    .stream()
                    .filter(l -> l.getIngredient().getId().equals(item.getOriginalIngredient().getId()))
                    .toList();

            if (item.getResolution() == AdjustmentResolution.CANCELLED) {
                origLines.forEach(l -> l.setRetired(true));
                meal.setStatus(MealStatus.CANCELLED);
                mealRepo.save(meal);
                origLines.forEach(mealIngredientRepo::save);
                continue;
            }

            Ingredient repl = item.getReplacementIngredient();
            if (item.getResolution() == AdjustmentResolution.REPLACED) {
                origLines.forEach(l -> l.setRetired(true));
            }
            // REPLACED 改菜名；SUPPLEMENT 原菜保留并在菜名标注“增加XX”
            meal.setDishName(item.getReplacementDishName());
            origLines.forEach(mealIngredientRepo::save);
            mealRepo.save(meal);

            // 替代/补充菜成为生效行
            MenuMealIngredient replLine = new MenuMealIngredient();
            replLine.setMeal(meal);
            replLine.setIngredient(repl);
            replLine.setQuantity(item.getReplacementQty());
            replLine.setLineKind(MealIngredientLineKind.REPLACEMENT);
            replLine.setAdjustment(adj);
            mealIngredientRepo.save(replLine);

            // 替代食材出库（用于调整餐次）
            inventoryService.change(repl.getId(), item.getReplacementQty().negate(), InventoryTxnType.OUT,
                    "MENU_ADJUSTMENT", adj.getId(),
                    "菜单调整 " + adj.getAdjustmentNo() + " " + meal.getDate() + " "
                            + meal.getMealType() + " " + item.getResolution()
                            + " " + repl.getName());

            publishAnnouncement(adj, item);
        }

        adj.setStatus(AdjustmentStatus.PUBLISHED);
        adj.setPublishedAt(LocalDateTime.now());
        adjustmentRepo.save(adj);
        return detail(adjustmentId);
    }

    /** 按餐次生成并发布家长公告（含变更原因、营养差异、通知范围） */
    private void publishAnnouncement(MenuAdjustment adj, MenuAdjustmentItem item) {
        MenuMeal meal = item.getMeal();
        ParentAnnouncement a = announcementRepo.findByMealId(meal.getId()).stream()
                .filter(x -> x.getAdjustment() != null && x.getAdjustment().getId().equals(adj.getId()))
                .findFirst().orElseGet(ParentAnnouncement::new);

        List<MealGradeServing> grades = gradeServingRepo.findByMealId(meal.getId());
        String scope = grades.stream().map(g -> g.getGradeLevel().name())
                .reduce((x, y) -> x + "," + y).orElse(null);
        int students = grades.stream().map(MealGradeServing::getStudents).reduce(0, Integer::sum);
        if (students == 0) {
            students = item.getServings();
        }

        String action = item.getResolution() == AdjustmentResolution.SUPPLEMENT
                ? "原菜品保留，并增加「" + item.getReplacementIngredient().getName() + "」补充供应"
                : "改用「" + item.getReplacementDishName() + "」（替代食材："
                        + item.getReplacementIngredient().getName() + "）";
        NutritionDiff d = diffOf(item);
        String nutrition = String.format(
                "经测算，调整后每份热量 %+.0fkcal、蛋白质 %+.1fg、脂肪 %+.1fg、碳水 %+.1fg",
                d.calories(), d.protein(), d.fat(), d.carbs());

        a.setMeal(meal);
        a.setAdjustment(adj);
        a.setTitle("【供餐调整通知】" + meal.getDate() + " " + meal.getMealType()
                + " " + item.getOriginalDishName() + " 调整说明");
        a.setContent("各位家长：因供应商[" + adj.getBatch().getOrder().getSupplier().getName()
                + "]少送食材[" + adj.getShortIngredient().getName() + "]（少送 "
                + adj.getShortQty() + adj.getShortIngredient().getUnit() + "），"
                + meal.getDate() + " " + meal.getMealType() + " 原菜品「" + item.getOriginalDishName()
                + "」无法按原菜单供应。经采购负责人确认，" + action + "。" + nutrition
                + "，学生用餐安全与营养均衡不受影响。特此告知，敬请谅解。");
        a.setReason("供应商少送" + adj.getShortIngredient().getName() + "，菜单调整 " + adj.getAdjustmentNo());
        a.setNotifyGradeScope(scope);
        a.setNotifiedStudents(students);
        a.setStatus(AnnouncementStatus.PUBLISHED);
        a.setPublishedAt(LocalDateTime.now());
        announcementRepo.save(a);
    }

    // ============================ 查询 / 家长解释 ============================

    public List<AdjustmentDetail> list() {
        return adjustmentRepo.findAllByOrderByCreatedAtDesc().stream().map(this::detailOf).toList();
    }

    public AdjustmentDetail detail(Long id) {
        return detailOf(get(id));
    }

    public MenuAdjustment get(Long id) {
        return adjustmentRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("菜单调整单不存在: " + id));
    }

    /** 家长询问：解释某餐次的菜单变更原因（原菜单/替代菜/营养差异/通知范围/少送扣罚） */
    @Transactional(readOnly = true)
    public MealExplanation explain(Long mealId) {
        MenuMeal meal = mealRepo.findById(mealId)
                .orElseThrow(() -> BusinessException.notFound("餐次不存在: " + mealId));
        MenuAdjustmentItem item = adjustmentItemRepo.findPublishedByMealId(mealId).stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.badRequest("该餐次没有已发布的菜单调整记录"));
        MenuAdjustment adj = item.getAdjustment();

        List<GradeExplanation> grades = gradeServingRepo.findByMealId(mealId).stream()
                .map(g -> new GradeExplanation(g.getGradeLevel().name(), g.getStudents()))
                .toList();
        int mealStudents = grades.stream().mapToInt(GradeExplanation::students).sum();
        if (mealStudents == 0) {
            mealStudents = item.getServings();
        }
        ParentAnnouncement ann = announcementRepo.findByMealId(mealId).stream()
                .filter(a -> a.getAdjustment() != null && a.getAdjustment().getId().equals(adj.getId()))
                .findFirst().orElse(null);
        Supplier supplier = adj.getBatch().getOrder().getSupplier();

        return new MealExplanation(
                meal.getId(), meal.getDate(), meal.getMealType().name(),
                meal.getDishName(),
                meal.getOriginalDishName() != null ? meal.getOriginalDishName() : item.getOriginalDishName(),
                mealStudents, adj.getAdjustmentNo(), adj.getStatus().name(), adj.getReason(),
                item.getOriginalIngredient().getName(), item.getOriginalQty(),
                item.getResolution().name(),
                item.getReplacementIngredient() == null ? null : item.getReplacementIngredient().getName(),
                item.getReplacementQty(), item.getReplacementDishName(),
                diffOf(item),
                grades, mealStudents,
                ann == null ? null : ann.getTitle(),
                ann == null ? null : ann.getContent(),
                ann == null ? null : ann.getStatus().name(),
                ann == null ? null : ann.getPublishedAt(),
                supplier.getId(), supplier.getName(), adj.getBatch().getBatchNo(),
                adj.getShortAnomaly() == null ? null : adj.getShortAnomaly().getAnomalyNo(),
                adj.getShortQty(), adj.getPenaltyAmount());
    }

    // ============================ 组装 ============================

    private Suggestion toSuggestion(MenuAdjustment adj) {
        List<MealSuggestion> meals = new ArrayList<>();
        for (MenuAdjustmentItem item : adjustmentItemRepo.findByAdjustmentId(adj.getId())) {
            MenuMeal meal = item.getMeal();
            List<GradeServingView> gradeViews = gradeServingRepo.findByMealId(meal.getId()).stream()
                    .map(g -> new GradeServingView(g.getGradeLevel().name(), g.getStudents()))
                    .toList();
            List<SubstituteCandidate> candidates = item.getResolution() == null
                    ? substituteCandidates(adj.getShortIngredient(), item.getOriginalQty())
                    : List.of();
            meals.add(new MealSuggestion(item.getId(), meal.getId(), meal.getDate(),
                    meal.getMealType().name(), meal.getDishName(), item.getServings(),
                    item.getOriginalIngredient().getId(), item.getOriginalIngredient().getName(),
                    item.getOriginalQty(), item.getShortageQty(), gradeViews, candidates,
                    suggestedDishName(item, candidates)));
        }
        Supplier supplier = adj.getBatch().getOrder().getSupplier();
        return new Suggestion(adj.getId(), adj.getAdjustmentNo(), adj.getStatus().name(),
                adj.getBatch().getId(), adj.getBatch().getBatchNo(), supplier.getId(), supplier.getName(),
                adj.getShortIngredient().getId(), adj.getShortIngredient().getName(),
                adj.getShortIngredient().getUnit(),
                adj.getShortQty(), adj.getShortAmount(), adj.getPenaltyAmount(),
                adj.getShortAnomaly() == null ? null : adj.getShortAnomaly().getAnomalyNo(),
                adj.getAffectedMeals(), adj.getNotifiedStudents(), adj.getNotifiedGradeScope(), meals);
    }

    private String suggestedDishName(MenuAdjustmentItem item, List<SubstituteCandidate> candidates) {
        return candidates.stream().filter(c -> c.use().equals("REPLACED")).findFirst()
                .map(c -> {
                    String orig = item.getOriginalDishName();
                    String ing = item.getOriginalIngredient().getName();
                    return orig.contains(ing) ? orig.replace(ing, c.name()) : orig + "（" + c.name() + "替代）";
                }).orElse(null);
    }

    private AdjustmentDetail detailOf(MenuAdjustment adj) {
        List<ItemView> items = adjustmentItemRepo.findByAdjustmentId(adj.getId()).stream()
                .map(i -> new ItemView(
                        i.getId(), i.getMeal().getId(), i.getMeal().getDate(),
                        i.getMeal().getMealType().name(), i.getServings(), i.getOriginalDishName(),
                        i.getOriginalIngredient().getName(), i.getOriginalQty(),
                        i.getResolution() == null ? null : i.getResolution().name(),
                        i.getReplacementIngredient() == null ? null : i.getReplacementIngredient().getName(),
                        i.getReplacementQty(), i.getReplacementDishName(),
                        diffOf(i), i.getSuggestedOptions()))
                .toList();
        Supplier supplier = adj.getBatch().getOrder().getSupplier();
        return new AdjustmentDetail(
                adj.getId(), adj.getAdjustmentNo(), adj.getStatus().name(),
                adj.getBatch().getId(), adj.getBatch().getBatchNo(),
                supplier.getId(), supplier.getName(),
                adj.getShortIngredient().getName(), adj.getShortQty(), adj.getShortAmount(),
                adj.getPenaltyAmount(),
                adj.getShortAnomaly() == null ? null : adj.getShortAnomaly().getAnomalyNo(),
                adj.getAffectedMeals(), adj.getNotifiedStudents(), adj.getNotifiedGradeScope(),
                adj.getReason(), adj.getRemark(),
                adj.getConfirmedBy() == null ? null : adj.getConfirmedBy().getUsername(),
                adj.getConfirmedAt(), adj.getPublishedAt(), items);
    }

    private NutritionDiff diffOf(MenuAdjustmentItem i) {
        if (i.getCaloriesDiff() == null) {
            return null;
        }
        return new NutritionDiff(i.getCaloriesDiff(), i.getProteinDiff(), i.getFatDiff(), i.getCarbsDiff());
    }
}
