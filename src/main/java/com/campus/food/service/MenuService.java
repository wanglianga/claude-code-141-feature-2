package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.GradeLevel;
import com.campus.food.domain.enums.MealStatus;
import com.campus.food.domain.enums.MealType;
import com.campus.food.domain.enums.MenuStatus;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 周菜单服务 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final WeeklyMenuRepo menuRepo;
    private final MenuMealRepo mealRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final IngredientRepo ingredientRepo;
    private final MealGradeServingRepo gradeServingRepo;

    @Transactional
    public WeeklyMenu createMenu(LocalDate weekStart, String remark) {
        menuRepo.findByWeekStart(weekStart).ifPresent(m -> {
            throw BusinessException.conflict("该周菜单已存在: " + weekStart);
        });
        WeeklyMenu menu = new WeeklyMenu();
        menu.setWeekStart(weekStart);
        menu.setRemark(remark);
        return menuRepo.save(menu);
    }

    public List<WeeklyMenu> listMenus() {
        return menuRepo.findAll();
    }

    public WeeklyMenu getMenu(Long id) {
        return menuRepo.findById(id).orElseThrow(() -> BusinessException.notFound("菜单不存在: " + id));
    }

    @Transactional
    public MenuMeal addMeal(Long menuId, LocalDate date, MealType mealType, String dishName, Integer servings) {
        WeeklyMenu menu = getMenu(menuId);
        if (menu.getStatus() == MenuStatus.COMPLETED) {
            throw BusinessException.conflict("菜单已结束，不能新增餐次");
        }
        if (date.isBefore(menu.getWeekStart()) || date.isAfter(menu.getWeekStart().plusDays(6))) {
            throw BusinessException.badRequest("餐次日期不在本周范围内: " + date);
        }
        MenuMeal meal = new MenuMeal();
        meal.setMenu(menu);
        meal.setDate(date);
        meal.setMealType(mealType);
        meal.setDishName(dishName);
        meal.setOriginalDishName(dishName);
        meal.setServings(servings == null ? 0 : servings);
        return mealRepo.save(meal);
    }

    @Transactional
    public MenuMealIngredient addMealIngredient(Long mealId, Long ingredientId, BigDecimal quantity) {
        MenuMeal meal = mealRepo.findById(mealId)
                .orElseThrow(() -> BusinessException.notFound("餐次不存在: " + mealId));
        Ingredient ingredient = ingredientRepo.findById(ingredientId)
                .orElseThrow(() -> BusinessException.notFound("食材不存在: " + ingredientId));
        if (quantity == null || quantity.signum() <= 0) {
            throw BusinessException.badRequest("用量必须大于 0");
        }
        MenuMealIngredient mmi = new MenuMealIngredient();
        mmi.setMeal(meal);
        mmi.setIngredient(ingredient);
        mmi.setQuantity(quantity);
        return mealIngredientRepo.save(mmi);
    }

    @Transactional
    public WeeklyMenu publish(Long menuId) {
        WeeklyMenu menu = getMenu(menuId);
        if (menu.getStatus() != MenuStatus.DRAFT) {
            throw BusinessException.conflict("仅草稿状态可发布");
        }
        if (mealRepo.findByMenuIdOrderByDateAsc(menuId).isEmpty()) {
            throw BusinessException.badRequest("菜单没有任何餐次，不能发布");
        }
        menu.setStatus(MenuStatus.PUBLISHED);
        return menuRepo.save(menu);
    }

    /** 餐次状态流转：备餐/供餐/取消 */
    @Transactional
    public MenuMeal updateMealStatus(Long mealId, MealStatus status) {
        MenuMeal meal = mealRepo.findById(mealId)
                .orElseThrow(() -> BusinessException.notFound("餐次不存在: " + mealId));
        meal.setStatus(status);
        return mealRepo.save(meal);
    }

    /** 菜单详情（含餐次与食材） */
    public MenuDetail detail(Long menuId) {
        WeeklyMenu menu = getMenu(menuId);
        List<MealDetail> meals = mealRepo.findByMenuIdOrderByDateAsc(menuId).stream()
                .map(meal -> new MealDetail(meal,
                        mealIngredientRepo.findByMealId(meal.getId()),
                        gradeServingRepo.findByMealId(meal.getId())))
                .toList();
        return new MenuDetail(menu, meals);
    }

    /** 维护某餐次分年级用餐人数（整单覆盖；省略的年级按 0 处理） */
    @Transactional
    public List<MealGradeServing> saveGradeServings(Long mealId, Map<GradeLevel, Integer> byGrade) {
        MenuMeal meal = mealRepo.findById(mealId)
                .orElseThrow(() -> BusinessException.notFound("餐次不存在: " + mealId));
        if (byGrade == null || byGrade.isEmpty()) {
            throw BusinessException.badRequest("年级用餐人数不能为空");
        }
        gradeServingRepo.deleteByMealId(mealId);
        gradeServingRepo.flush();
        int total = 0;
        for (Map.Entry<GradeLevel, Integer> e : byGrade.entrySet()) {
            int students = e.getValue() == null ? 0 : e.getValue();
            if (students < 0) {
                throw BusinessException.badRequest("用餐人数不能为负: " + e.getKey());
            }
            if (students == 0) {
                continue;
            }
            MealGradeServing row = new MealGradeServing();
            row.setMeal(meal);
            row.setGradeLevel(e.getKey());
            row.setStudents(students);
            gradeServingRepo.save(row);
            total += students;
        }
        // 同步餐次总份数，供供餐统计使用
        meal.setServings(total);
        mealRepo.save(meal);
        return gradeServingRepo.findByMealId(mealId);
    }

    public List<MealGradeServing> gradeServings(Long mealId) {
        return gradeServingRepo.findByMealId(mealId);
    }

    public record MealDetail(MenuMeal meal, List<MenuMealIngredient> ingredients,
                             List<MealGradeServing> gradeServings) {
    }

    public record MenuDetail(WeeklyMenu menu, List<MealDetail> meals) {
    }
}
