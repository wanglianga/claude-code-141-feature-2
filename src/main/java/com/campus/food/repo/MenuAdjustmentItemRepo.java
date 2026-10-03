package com.campus.food.repo;

import com.campus.food.domain.MenuAdjustmentItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuAdjustmentItemRepo extends JpaRepository<MenuAdjustmentItem, Long> {
    List<MenuAdjustmentItem> findByAdjustmentId(Long adjustmentId);
}
