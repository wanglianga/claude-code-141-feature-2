package com.campus.food.config;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 首次启动时初始化演示数据（用户/供应商/食材/库存/本周菜单/分年级用餐人数） */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final AppUserRepo userRepo;
    private final SupplierRepo supplierRepo;
    private final IngredientRepo ingredientRepo;
    private final StockLevelRepo stockLevelRepo;
    private final WeeklyMenuRepo menuRepo;
    private final MenuMealRepo mealRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final MealGradeServingRepo gradeServingRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed-demo-data:true}")
    private boolean seedDemoData;

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedDemoData || userRepo.count() > 0) {
            return;
        }
        log.info("初始化演示数据...");

        // 用户
        user("inspector", "inspector123", "王验收", Role.INSPECTOR);
        user("buyer", "buyer123", "李采购", Role.PROCUREMENT);
        user("canteen", "canteen123", "赵食堂", Role.CANTEEN);
        user("admin", "admin123", "陈校长", Role.SCHOOL_ADMIN);

        // 供应商
        supplier("SUP001", "绿源蔬菜合作社", "刘经理", "13800000001",
                "VEGETABLE,FRUIT", false, "HT-2026-001");
        supplier("SUP002", "惠民肉联食品公司", "张经理", "13800000002",
                "MEAT", true, "HT-2026-002");
        supplier("SUP003", "康源乳业配送中心", "孙经理", "13800000003",
                "DAIRY", true, "HT-2026-003");
        supplier("SUP004", "丰收粮油调味批发部", "周经理", "13800000004",
                "GRAIN_OIL,CONDIMENT", false, "HT-2026-004");
        // 鸡肉制品供应商（鸡腿/鸡块，冷链）
        supplier("SUP005", "乡禽鸡肉制品配送公司", "吴经理", "13800000005",
                "POULTRY", true, "HT-2026-005");

        // 食材（营养值为每计量单位：kcal / 蛋白质g / 脂肪g / 碳水g）
        Ingredient rice = ingredient("ING001", "大米", IngredientCategory.GRAIN_OIL, "kg", "25kg/袋",
                false, null, null, 180, "200", "6.00", null,
                "3460", "70", "8", "770");
        Ingredient flour = ingredient("ING002", "面粉", IngredientCategory.GRAIN_OIL, "kg", "25kg/袋",
                false, null, null, 180, "100", "5.00", null,
                "3500", "110", "15", "730");
        ingredient("ING003", "食用油", IngredientCategory.GRAIN_OIL, "L", "10L/桶",
                false, null, null, 365, "60", "12.00", null,
                "8840", "0", "1000", "0");
        Ingredient pork = ingredient("ING004", "猪肉", IngredientCategory.MEAT, "kg", "冷鲜",
                true, "-2", "4", 3, "50", "30.00", "PORK",
                "3950", "130", "370", "0");
        Ingredient beef = ingredient("ING005", "牛肉", IngredientCategory.MEAT, "kg", "冷鲜",
                true, "-2", "4", 3, "30", "60.00", null,
                "2500", "260", "150", "0");
        Ingredient egg = ingredient("ING006", "鸡蛋", IngredientCategory.DAIRY, "kg", "箱装",
                false, null, null, 15, "40", "10.00", null,
                "1440", "133", "88", "28");
        Ingredient cabbage = ingredient("ING007", "大白菜", IngredientCategory.VEGETABLE, "kg", "散装",
                false, null, null, 5, "80", "3.00", "LEAFY",
                "170", "15", "2", "32");
        Ingredient spinach = ingredient("ING008", "菠菜", IngredientCategory.VEGETABLE, "kg", "散装",
                false, null, null, 3, "40", "5.00", "LEAFY",
                "280", "26", "3", "45");
        Ingredient apple = ingredient("ING009", "苹果", IngredientCategory.FRUIT, "kg", "筐装",
                false, null, null, 15, "50", "8.00", null,
                "520", "3", "2", "138");
        Ingredient milk = ingredient("ING010", "鲜牛奶", IngredientCategory.DAIRY, "L", "250ml/盒",
                true, "0", "6", 7, "100", "4.00", null,
                "540", "30", "32", "50");
        ingredient("ING011", "食盐", IngredientCategory.CONDIMENT, "kg", "500g/袋",
                false, null, null, 720, "20", "2.00", null,
                "0", "0", "0", "0");
        ingredient("ING012", "酱油", IngredientCategory.CONDIMENT, "L", "1.9L/桶",
                false, null, null, 540, "30", "6.00", null,
                "630", "56", "6", "100");
        // 鸡腿（本周菜单使用，演示供应商少送导致菜单调整）；鸡块与鸡腿同 CHICKEN 替代组且有库存
        Ingredient chickenLeg = ingredient("ING013", "鸡腿", IngredientCategory.POULTRY, "kg", "冷鲜",
                true, "-2", "4", 3, "20", "22.00", "CHICKEN",
                "1810", "193", "115", "0");
        Ingredient chickenNugget = ingredient("ING014", "鸡块", IngredientCategory.POULTRY, "kg", "速冻",
                true, "-18", "-15", 180, "15", "18.00", "CHICKEN",
                "2200", "140", "130", "120");

        // 初始库存（大白菜、鸡腿故意低于安全线，演示安全线预警与少送缺口）
        stock(rice, "300");
        stock(flour, "120");
        stock(pork, "20");
        stock(beef, "10");
        stock(egg, "50");
        stock(cabbage, "50");
        stock(spinach, "60");
        stock(apple, "80");
        stock(milk, "60");
        stock(chickenLeg, "5");
        stock(chickenNugget, "40");

        // 菜单（周一 ~ 周五）；若本周工作日已全部过去（周末启动），则种下一周的菜单
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        if (monday.plusDays(4).isBefore(LocalDate.now())) {
            monday = monday.plusWeeks(1);
        }
        WeeklyMenu menu = new WeeklyMenu();
        menu.setWeekStart(monday);
        menu.setStatus(MenuStatus.PUBLISHED);
        menu.setRemark("秋季学期第6周菜单");
        menuRepo.save(menu);

        // 一~六年级用餐人数（合计即餐次总份数）
        MenuMeal m1 = meal(menu, monday, MealType.LUNCH, "白菜猪肉炖粉条", 500);
        mmi(m1, cabbage, "30");
        mmi(m1, pork, "20");
        grades(m1, 84, 80, 82, 78, 90, 86);

        MenuMeal m2 = meal(menu, monday.plusDays(1), MealType.LUNCH, "红烧牛肉面", 480);
        mmi(m2, beef, "25");
        mmi(m2, flour, "40");
        grades(m2, 80, 76, 80, 76, 86, 82);

        MenuMeal m3 = meal(menu, monday.plusDays(2), MealType.BREAKFAST, "牛奶鸡蛋早餐", 500);
        mmi(m3, milk, "120");
        mmi(m3, egg, "30");
        grades(m3, 84, 80, 82, 78, 90, 86);

        MenuMeal m4 = meal(menu, monday.plusDays(2), MealType.LUNCH, "菠菜炒蛋", 460);
        mmi(m4, spinach, "25");
        mmi(m4, egg, "20");
        grades(m4, 76, 74, 78, 72, 84, 76);

        MenuMeal m5 = meal(menu, monday.plusDays(3), MealType.LUNCH, "米饭套餐", 500);
        mmi(m5, rice, "80");
        mmi(m5, pork, "15");
        grades(m5, 84, 80, 82, 78, 90, 86);

        MenuMeal m6 = meal(menu, monday.plusDays(4), MealType.LUNCH, "苹果加餐", 500);
        mmi(m6, apple, "60");
        grades(m6, 84, 80, 82, 78, 90, 86);

        MenuMeal m7 = meal(menu, monday.plusDays(4), MealType.LUNCH, "香酥鸡腿", 480);
        mmi(m7, chickenLeg, "30");
        grades(m7, 80, 78, 80, 76, 88, 78);

        log.info("演示数据初始化完成：5 供应商 / 14 食材 / 本周菜单 {} 起（含分年级用餐人数）", monday);
    }

    private void user(String username, String rawPassword, String fullName, Role role) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setFullName(fullName);
        u.setRole(role);
        userRepo.save(u);
    }

    private Supplier supplier(String code, String name, String contact, String phone,
                              String categories, boolean coldChain, String contractNo) {
        Supplier s = new Supplier();
        s.setCode(code);
        s.setName(name);
        s.setContactPerson(contact);
        s.setPhone(phone);
        s.setCategories(categories);
        s.setColdChainCapable(coldChain);
        s.setContractNo(contractNo);
        return supplierRepo.save(s);
    }

    private Ingredient ingredient(String code, String name, IngredientCategory category, String unit,
                                  String spec, boolean coldChain, String minTemp, String maxTemp,
                                  Integer shelfLifeDays, String safetyStock, String refPrice,
                                  String substituteGroup,
                                  String calories, String protein, String fat, String carbs) {
        Ingredient i = new Ingredient();
        i.setCode(code);
        i.setName(name);
        i.setCategory(category);
        i.setUnit(unit);
        i.setSpec(spec);
        i.setRequiresColdChain(coldChain);
        i.setColdChainMinTemp(minTemp == null ? null : new BigDecimal(minTemp));
        i.setColdChainMaxTemp(maxTemp == null ? null : new BigDecimal(maxTemp));
        i.setShelfLifeDays(shelfLifeDays);
        i.setSafetyStock(new BigDecimal(safetyStock));
        i.setReferencePrice(new BigDecimal(refPrice));
        i.setSubstituteGroup(substituteGroup);
        i.setCaloriesPerUnit(new BigDecimal(calories));
        i.setProteinPerUnit(new BigDecimal(protein));
        i.setFatPerUnit(new BigDecimal(fat));
        i.setCarbsPerUnit(new BigDecimal(carbs));
        return ingredientRepo.save(i);
    }

    private void stock(Ingredient ingredient, String qty) {
        StockLevel s = new StockLevel();
        s.setIngredient(ingredient);
        s.setQuantity(new BigDecimal(qty));
        s.setUpdatedAt(LocalDateTime.now());
        stockLevelRepo.save(s);
    }

    private MenuMeal meal(WeeklyMenu menu, LocalDate date, MealType type, String dish, int servings) {
        MenuMeal m = new MenuMeal();
        m.setMenu(menu);
        m.setDate(date);
        m.setMealType(type);
        m.setDishName(dish);
        m.setOriginalDishName(dish);
        m.setServings(servings);
        return mealRepo.save(m);
    }

    private void mmi(MenuMeal meal, Ingredient ingredient, String qty) {
        MenuMealIngredient mmi = new MenuMealIngredient();
        mmi.setMeal(meal);
        mmi.setIngredient(ingredient);
        mmi.setQuantity(new BigDecimal(qty));
        mealIngredientRepo.save(mmi);
    }

    /** 维护一~六年级用餐人数 */
    private void grades(MenuMeal meal, int g1, int g2, int g3, int g4, int g5, int g6) {
        int[] counts = {g1, g2, g3, g4, g5, g6};
        GradeLevel[] grades = GradeLevel.values();
        int total = 0;
        for (int k = 0; k < counts.length; k++) {
            MealGradeServing row = new MealGradeServing();
            row.setMeal(meal);
            row.setGradeLevel(grades[k]);
            row.setStudents(counts[k]);
            gradeServingRepo.save(row);
            total += counts[k];
        }
        meal.setServings(total);
        mealRepo.save(meal);
    }
}
