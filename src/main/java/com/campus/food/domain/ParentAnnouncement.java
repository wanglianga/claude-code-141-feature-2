package com.campus.food.domain;

import com.campus.food.domain.enums.AnnouncementStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 家长公告 */
@Entity
@Table(name = "parent_announcements")
@Getter
@Setter
@NoArgsConstructor
public class ParentAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联餐次（手动公告可为空） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "meal_id")
    private MenuMeal meal;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AnnouncementStatus status = AnnouncementStatus.DRAFT;

    /** 触发原因 */
    @Column(length = 256)
    private String reason;

    /** 由菜单调整单生成时关联的调整单 id */
    @Column(name = "adjustment_id")
    private Long adjustmentId;

    /** 通知范围（如“全校一~六年级 2940 名学生家长”），发布时快照留档 */
    @Column(length = 512)
    private String notifyScope;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime publishedAt;
}
