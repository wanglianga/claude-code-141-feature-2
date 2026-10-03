package com.campus.food.repo;

import com.campus.food.domain.MenuAdjustmentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuAdjustmentItemRepo extends JpaRepository<MenuAdjustmentItem, Long> {
    List<MenuAdjustmentItem> findByAdjustmentId(Long adjustmentId);

    @Query("select i from MenuAdjustmentItem i where i.meal.id = :mealId " +
           "and i.adjustment.status = com.campus.food.domain.enums.AdjustmentStatus.PUBLISHED")
    List<MenuAdjustmentItem> findPublishedByMealId(@Param("mealId") Long mealId);
}
