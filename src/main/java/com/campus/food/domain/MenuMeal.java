package com.campus.food.domain;

import com.campus.food.domain.enums.MealStatus;
import com.campus.food.domain.enums.MealType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** 菜单餐次（某周某日某餐的一道菜） */
@Entity
@Table(name = "menu_meals")
@Getter
@Setter
@NoArgsConstructor
public class MenuMeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "menu_id")
    private WeeklyMenu menu;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MealType mealType;

    @Column(nullable = false, length = 128)
    private String dishName;

    /**
     * 原菜名快照：菜单调整换菜后 dishName 更新为替代菜名，originalDishName 保留原菜单，
     * 供家长询问时解释变更原因；未调整过的餐次与 dishName 相同。
     */
    @Column(length = 128)
    private String originalDishName;

    /** 供餐份数 */
    @Column(nullable = false)
    private Integer servings = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MealStatus status = MealStatus.SCHEDULED;
}
