package com.campus.food.repo;

import com.campus.food.domain.MenuAdjustmentGrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuAdjustmentGradeRepo extends JpaRepository<MenuAdjustmentGrade, Long> {
    List<MenuAdjustmentGrade> findByAdjustmentId(Long adjustmentId);
}
