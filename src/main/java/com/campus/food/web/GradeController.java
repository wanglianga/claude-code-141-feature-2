package com.campus.food.web;

import com.campus.food.domain.GradeLevel;
import com.campus.food.repo.GradeLevelRepo;
import com.campus.food.service.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "年级用餐人数")
@RestController
@RequestMapping("/api/grades")
@RequiredArgsConstructor
public class GradeController {

    private final GradeLevelRepo gradeRepo;

    public record GradeReq(@NotNull Integer studentCount, Boolean active) {
    }

    @Operation(summary = "年级与用餐人数列表")
    @GetMapping
    public List<GradeLevel> list() {
        return gradeRepo.findAllByOrderBySortOrderAsc();
    }

    @Operation(summary = "维护年级用餐人数/是否供餐")
    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('CANTEEN','SCHOOL_ADMIN')")
    public GradeLevel update(@PathVariable Long id, @Valid @RequestBody GradeReq req) {
        GradeLevel g = gradeRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("年级不存在: " + id));
        g.setStudentCount(req.studentCount());
        if (req.active() != null) {
            g.setActive(req.active());
        }
        return gradeRepo.save(g);
    }
}
