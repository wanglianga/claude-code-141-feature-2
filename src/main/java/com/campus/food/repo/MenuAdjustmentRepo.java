package com.campus.food.repo;

import com.campus.food.domain.MenuAdjustment;
import com.campus.food.domain.enums.MenuAdjustmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuAdjustmentRepo extends JpaRepository<MenuAdjustment, Long> {
    Optional<MenuAdjustment> findByAnomalyId(Long anomalyId);
    boolean existsByAnomalyId(Long anomalyId);
    List<MenuAdjustment> findByBatchIdOrderByCreatedAtDesc(Long batchId);
    List<MenuAdjustment> findByStatusOrderByCreatedAtDesc(MenuAdjustmentStatus status);
    List<MenuAdjustment> findAllByOrderByCreatedAtDesc();
}
