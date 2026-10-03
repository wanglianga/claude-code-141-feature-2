package com.campus.food.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 菜单调整通知范围明细：调整涉及的各年级及其用餐人数快照。
 * 公告发布后据此可向家长解释“通知了哪些年级、多少学生”。
 */
@Entity
@Table(name = "menu_adjustment_grades")
@Getter
@Setter
@NoArgsConstructor
public class MenuAdjustmentGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "adjustment_id")
    private MenuAdjustment adjustment;

    /** 年级名称快照（如“三年级”） */
    @Column(nullable = false, length = 32)
    private String gradeName;

    /** 该年级用餐人数快照 */
    @Column(nullable = false)
    private Integer studentCount;
}
