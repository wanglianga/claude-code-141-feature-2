package com.campus.food.web;

import com.campus.food.domain.MenuAdjustment;
import com.campus.food.domain.enums.MenuAdjustmentStatus;
import com.campus.food.service.MenuAdjustmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "菜单调整（少送应对）")
@RestController
@RequestMapping("/api/menu-adjustments")
@RequiredArgsConstructor
public class MenuAdjustmentController {

    private final MenuAdjustmentService adjustmentService;

    public record SuggestReq(@NotNull Long anomalyId) {
    }

    public record SelectionReq(Long itemId, Boolean selected, BigDecimal confirmedQty) {
    }

    public record ConfirmReq(List<SelectionReq> selections, String remark) {
    }

    @Operation(summary = "按少送异常生成菜单调整建议（缺口数量/库存替代品/年级用餐人数/营养差异）")
    @PostMapping("/suggest")
    @PreAuthorize("hasAnyRole('PROCUREMENT','CANTEEN')")
    public MenuAdjustmentService.AdjustmentDetail suggest(@Valid @RequestBody SuggestReq req) {
        return adjustmentService.suggest(req.anomalyId());
    }

    @Operation(summary = "菜单调整单列表（可按状态过滤）")
    @GetMapping
    public List<MenuAdjustment> list(@RequestParam(required = false) MenuAdjustmentStatus status) {
        return adjustmentService.list(status);
    }

    @Operation(summary = "按送货批次查询菜单调整单")
    @GetMapping("/batch/{batchId}")
    public List<MenuAdjustment> byBatch(@PathVariable Long batchId) {
        return adjustmentService.listByBatch(batchId);
    }

    @Operation(summary = "菜单调整单详情（含调整动作、年级范围、扣罚金额）")
    @GetMapping("/{id}")
    public MenuAdjustmentService.AdjustmentDetail detail(@PathVariable Long id) {
        return adjustmentService.detail(id);
    }

    @Operation(summary = "采购员确认调整建议（改用鸡块/增加鸡蛋）：执行菜单变更、扣减库存、生成家长公告草稿")
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public MenuAdjustmentService.AdjustmentDetail confirm(@PathVariable Long id, Authentication auth,
                                                          @RequestBody ConfirmReq req) {
        List<MenuAdjustmentService.SelectionInput> selections = req.selections() == null ? List.of()
                : req.selections().stream()
                .map(s -> new MenuAdjustmentService.SelectionInput(s.itemId(), s.selected(), s.confirmedQty()))
                .toList();
        return adjustmentService.confirm(id, auth.getName(), selections, req.remark());
    }

    @Operation(summary = "发布菜单调整（家长公告同步发布，保留通知范围）")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    public MenuAdjustmentService.AdjustmentDetail publish(@PathVariable Long id) {
        return adjustmentService.publish(id);
    }

    @Operation(summary = "家长问询解释（原菜单/替代菜/营养差异/通知范围/变更原因）")
    @GetMapping("/{id}/explanation")
    public MenuAdjustmentService.Explanation explanation(@PathVariable Long id) {
        return adjustmentService.explanation(adjustmentService.get(id));
    }

    @Operation(summary = "按餐次查询家长问询解释")
    @GetMapping("/by-meal/{mealId}/explanation")
    public MenuAdjustmentService.Explanation explainByMeal(@PathVariable Long mealId) {
        return adjustmentService.explainByMeal(mealId);
    }
}
