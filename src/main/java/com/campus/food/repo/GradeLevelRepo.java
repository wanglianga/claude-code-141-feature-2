package com.campus.food.repo;

import com.campus.food.domain.GradeLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GradeLevelRepo extends JpaRepository<GradeLevel, Long> {
    Optional<GradeLevel> findByName(String name);
    List<GradeLevel> findByActiveTrueOrderBySortOrderAsc();
    List<GradeLevel> findAllByOrderBySortOrderAsc();
}
