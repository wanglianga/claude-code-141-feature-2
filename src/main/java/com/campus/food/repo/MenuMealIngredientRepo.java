package com.campus.food.repo;

import com.campus.food.domain.MenuMealIngredient;
import com.campus.food.domain.enums.MealStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface MenuMealIngredientRepo extends JpaRepository<MenuMealIngredient, Long> {

    List<MenuMealIngredient> findByMealId(Long mealId);

    /** 当前生效行（原菜单未被替换 + 调整替代行），用于采购计划汇总 */
    List<MenuMealIngredient> findByMealIdAndRetiredFalse(Long mealId);

    @Query("select mmi from MenuMealIngredient mmi where mmi.meal.menu.id = :menuId and mmi.retired = false")
    List<MenuMealIngredient> findActiveByMenuId(@Param("menuId") Long menuId);

    @Query("select mmi from MenuMealIngredient mmi " +
           "where mmi.ingredient.id = :ingredientId " +
           "and mmi.retired = false " +
           "and mmi.meal.date between :from and :to " +
           "and mmi.meal.status in :statuses")
    List<MenuMealIngredient> findMealsNeeding(@Param("ingredientId") Long ingredientId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to,
                                              @Param("statuses") Collection<MealStatus> statuses);

    @Query("select count(mmi) from MenuMealIngredient mmi " +
           "where mmi.ingredient.id = :ingredientId " +
           "and mmi.retired = false " +
           "and mmi.meal.status = com.campus.food.domain.enums.MealStatus.PREPARED " +
           "and mmi.meal.date >= :date")
    long countPreparedMealsNeeding(@Param("ingredientId") Long ingredientId, @Param("date") LocalDate date);
}
