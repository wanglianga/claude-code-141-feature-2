package com.campus.food.web;

import com.campus.food.domain.ParentAnnouncement;
import com.campus.food.domain.enums.AnnouncementStatus;
import com.campus.food.repo.ParentAnnouncementRepo;
import com.campus.food.service.BusinessException;
import com.campus.food.service.MenuAdjustmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "家长公告")
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final ParentAnnouncementRepo announcementRepo;
    private final MenuAdjustmentService adjustmentService;

    public record AnnouncementReq(Long mealId, @NotBlank String title, @NotBlank String content, String reason) {
    }

    @Operation(summary = "公告列表（可按状态过滤）")
    @GetMapping
    public List<ParentAnnouncement> list(@RequestParam(required = false) AnnouncementStatus status) {
        return status == null ? announcementRepo.findAll() : announcementRepo.findByStatus(status);
    }

    @Operation(summary = "公告详情")
    @GetMapping("/{id}")
    public ParentAnnouncement get(@PathVariable Long id) {
        return announcementRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("公告不存在: " + id));
    }

    @Operation(summary = "手工创建公告")
    @PostMapping
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN','CANTEEN')")
    public ParentAnnouncement create(@Valid @RequestBody AnnouncementReq req) {
        ParentAnnouncement a = new ParentAnnouncement();
        a.setTitle(req.title());
        a.setContent(req.content());
        a.setReason(req.reason());
        return announcementRepo.save(a);
    }

    @Operation(summary = "发布公告（由菜单调整单生成的公告，同步将调整单置为已发布）")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    public ParentAnnouncement publish(@PathVariable Long id) {
        ParentAnnouncement a = get(id);
        if (a.getStatus() != AnnouncementStatus.DRAFT) {
            throw BusinessException.conflict("仅草稿可发布");
        }
        a.setStatus(AnnouncementStatus.PUBLISHED);
        a.setPublishedAt(LocalDateTime.now());
        announcementRepo.save(a);
        adjustmentService.syncPublishedIfLinked(a);
        return a;
    }

    @Operation(summary = "撤销公告")
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasRole('SCHOOL_ADMIN')")
    public ParentAnnouncement revoke(@PathVariable Long id) {
        ParentAnnouncement a = get(id);
        a.setStatus(AnnouncementStatus.REVOKED);
        return announcementRepo.save(a);
    }
}
