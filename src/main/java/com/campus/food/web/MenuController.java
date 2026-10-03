package com.campus.food.web;

import com.campus.food.domain.MenuMeal;
import com.campus.food.domain.MenuMealIngredient;
import com.campus.food.domain.MealGradeServing;
import com.campus.food.domain.WeeklyMenu;
import com.campus.food.domain.enums.GradeLevel;
import com.campus.food.domain.enums.MealStatus;
import com.campus.food.domain.enums.MealType;
import com.campus.food.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "周菜单")
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    public record MenuReq(@NotNull LocalDate weekStart, String remark) {
    }

    public record MealReq(@NotNull LocalDate date, @NotNull MealType mealType,
                          @NotBlank String dishName, Integer servings) {
    }

    public record MealIngredientReq(@NotNull Long ingredientId, @NotNull BigDecimal quantity) {
    }

    public record MealStatusReq(@NotNull MealStatus status) {
    }

    @Operation(summary = "创建周菜单")
    @PostMapping
    @PreAuthorize("hasRole('CANTEEN')")
    public WeeklyMenu create(@Valid @RequestBody MenuReq req) {
        return menuService.createMenu(req.weekStart(), req.remark());
    }

    @Operation(summary = "菜单列表")
    @GetMapping
    public List<WeeklyMenu> list() {
        return menuService.listMenus();
    }

    @Operation(summary = "菜单详情（含餐次与食材）")
    @GetMapping("/{id}")
    public MenuService.MenuDetail detail(@PathVariable Long id) {
        return menuService.detail(id);
    }

    @Operation(summary = "添加餐次")
    @PostMapping("/{id}/meals")
    @PreAuthorize("hasRole('CANTEEN')")
    public MenuMeal addMeal(@PathVariable Long id, @Valid @RequestBody MealReq req) {
        return menuService.addMeal(id, req.date(), req.mealType(), req.dishName(), req.servings());
    }

    @Operation(summary = "餐次添加食材")
    @PostMapping("/meals/{mealId}/ingredients")
    @PreAuthorize("hasRole('CANTEEN')")
    public MenuMealIngredient addIngredient(@PathVariable Long mealId,
                                            @Valid @RequestBody MealIngredientReq req) {
        return menuService.addMealIngredient(mealId, req.ingredientId(), req.quantity());
    }

    @Operation(summary = "发布菜单")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('CANTEEN')")
    public WeeklyMenu publish(@PathVariable Long id) {
        return menuService.publish(id);
    }

    @Operation(summary = "更新餐次状态（备餐/供餐/取消）")
    @PostMapping("/meals/{mealId}/status")
    @PreAuthorize("hasRole('CANTEEN')")
    public MenuMeal updateMealStatus(@PathVariable Long mealId, @Valid @RequestBody MealStatusReq req) {
        return menuService.updateMealStatus(mealId, req.status());
    }

    @Operation(summary = "查询餐次分年级用餐人数")
    @GetMapping("/meals/{mealId}/grade-servings")
    public List<MealGradeServing> gradeServings(@PathVariable Long mealId) {
        return menuService.gradeServings(mealId);
    }

    @Operation(summary = "维护餐次分年级用餐人数（整单覆盖，如 {\"GRADE_1\":80,...}）")
    @PostMapping("/meals/{mealId}/grade-servings")
    @PreAuthorize("hasRole('CANTEEN')")
    public List<MealGradeServing> saveGradeServings(@PathVariable Long mealId,
                                                    @RequestBody Map<GradeLevel, Integer> byGrade) {
        return menuService.saveGradeServings(mealId, byGrade);
    }
}
