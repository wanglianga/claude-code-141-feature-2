package com.campus.food.web;

import com.campus.food.service.MenuAdjustmentService;
import com.campus.food.service.MenuAdjustmentService.AdjustmentDetail;
import com.campus.food.service.MenuAdjustmentService.DecisionInput;
import com.campus.food.service.MenuAdjustmentService.MealExplanation;
import com.campus.food.service.MenuAdjustmentService.Suggestion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Tag(name = "少送菜单调整")
@RestController
@RequestMapping("/api/menu-adjustments")
@RequiredArgsConstructor
public class MenuAdjustmentController {

    private final MenuAdjustmentService adjustmentService;

    public record ConfirmReq(@NotEmpty @Valid List<DecisionInput> decisions) {
    }

    @Operation(summary = "按少送批次生成菜单调整建议（缺口数量/库存替代品/年级用餐人数）")
    @PostMapping("/batch/{batchId}/suggest")
    @PreAuthorize("hasAnyRole('PROCUREMENT','CANTEEN','SCHOOL_ADMIN')")
    public Suggestion suggest(@PathVariable Long batchId,
                              @RequestParam(required = false) Long ingredientId) {
        return adjustmentService.suggest(batchId, ingredientId);
    }

    @Operation(summary = "调整单列表")
    @GetMapping
    public List<AdjustmentDetail> list() {
        return adjustmentService.list();
    }

    @Operation(summary = "调整单详情（原菜单/替代菜/营养差异/通知范围/扣罚）")
    @GetMapping("/{id}")
    public AdjustmentDetail detail(@PathVariable Long id) {
        return adjustmentService.detail(id);
    }

    @Operation(summary = "采购员确认调整方案（改用鸡块等替代菜，或增加鸡蛋等补充）")
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public AdjustmentDetail confirm(@PathVariable Long id, @Valid @RequestBody ConfirmReq req,
                                    Principal principal) {
        return adjustmentService.confirm(id, principal.getName(), req.decisions());
    }

    @Operation(summary = "发布菜单调整（换菜生效、替代食材出库、家长公告按通知范围发布）")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('PROCUREMENT','CANTEEN')")
    public AdjustmentDetail publish(@PathVariable Long id) {
        return adjustmentService.publish(id);
    }

    @Operation(summary = "家长询问：解释某餐次菜单变更原因（原菜单/替代菜/营养差异/通知范围/少送扣罚）")
    @GetMapping("/meals/{mealId}/explanation")
    public MealExplanation explain(@PathVariable Long mealId) {
        return adjustmentService.explain(mealId);
    }
}
