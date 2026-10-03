package com.campus.food.repo;

import com.campus.food.domain.MenuAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuAdjustmentRepo extends JpaRepository<MenuAdjustment, Long> {
    Optional<MenuAdjustment> findByBatchId(Long batchId);
    List<MenuAdjustment> findAllByOrderByCreatedAtDesc();
}
