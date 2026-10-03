package com.campus.food.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 年级用餐人数（用于菜单调整时估算通知范围与供餐份数） */
@Entity
@Table(name = "grade_levels")
@Getter
@Setter
@NoArgsConstructor
public class GradeLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 年级名称：一年级 ~ 六年级 */
    @Column(nullable = false, unique = true, length = 32)
    private String name;

    /** 排序序号 1~6 */
    @Column(nullable = false)
    private Integer sortOrder;

    /** 该年级在校用餐人数 */
    @Column(nullable = false)
    private Integer studentCount = 0;

    /** 是否参与本次供餐（默认参与） */
    @Column(nullable = false)
    private boolean active = true;
}
