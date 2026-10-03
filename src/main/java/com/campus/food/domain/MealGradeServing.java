package com.campus.food.domain;

import com.campus.food.domain.enums.GradeLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 餐次分年级用餐人数（通知范围与按人份测算替代量的依据） */
@Entity
@Table(name = "meal_grade_servings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"meal_id", "grade_level"}))
@Getter
@Setter
@NoArgsConstructor
public class MealGradeServing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "meal_id")
    private MenuMeal meal;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade_level", nullable = false, length = 16)
    private GradeLevel gradeLevel;

    /** 该年级在该餐次的用餐人数 */
    @Column(nullable = false)
    private Integer students = 0;
}
