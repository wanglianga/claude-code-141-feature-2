package com.campus.food.repo;

import com.campus.food.domain.MealGradeServing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MealGradeServingRepo extends JpaRepository<MealGradeServing, Long> {
    List<MealGradeServing> findByMealId(Long mealId);
    void deleteByMealId(Long mealId);
}
