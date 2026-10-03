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

    /** 由菜单调整自动生成时关联调整单 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "adjustment_id")
    private MenuAdjustment adjustment;

    /** 通知范围（年级，如 ["GRADE_1",...,"GRADE_6"]，存逗号分隔） */
    @Column(length = 256)
    private String notifyGradeScope;

    /** 通知范围内的学生人数 */
    private Integer notifiedStudents;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime publishedAt;
}
