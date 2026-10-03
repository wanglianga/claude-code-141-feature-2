#!/usr/bin/env python3
"""端到端冒烟验证：菜单->计划->订货->送货->验收->库存/退货/降级->索赔->结算->影响分析->复盘统计

本版新增：少送（鸡腿）导致菜单调整全链路——
少送验收 -> 调整建议（缺口/库存替代品/年级用餐人数）-> 采购员确认（鸡块替代/增加鸡蛋）
-> 发布（原菜单保留、替代菜生效、营养差异、家长公告与通知范围、供应商扣罚）
-> 家长询问解释 -> 月底结算少送批次不按原订货量付款。
"""
import json
import sys
import urllib.request
import urllib.error
import base64
from datetime import date, datetime, timedelta

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"

USERS = {
    "inspector": ("inspector", "inspector123"),
    "buyer": ("buyer", "buyer123"),
    "canteen": ("canteen", "canteen123"),
    "admin": ("admin", "admin123"),
}

PASS, FAIL = 0, 0


def call(user, method, path, body=None, expect=200):
    global PASS, FAIL
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    u, p = USERS[user]
    req.add_header("Authorization", "Basic " + base64.b64encode(f"{u}:{p}".encode()).decode())
    try:
        with urllib.request.urlopen(req) as resp:
            code, payload = resp.status, resp.read()
    except urllib.error.HTTPError as e:
        code, payload = e.code, e.read()
    obj = json.loads(payload) if payload else None
    if code != expect:
        FAIL += 1
        print(f"  [FAIL] {method} {path} -> {code} (expect {expect}): {str(obj)[:300]}")
        return None
    return obj


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name} {detail}")


def near(name, actual, expected, eps=0.02):
    check(name, actual is not None and abs(float(actual) - expected) <= eps,
          f"got {actual} expect {expected}")


def find(items, **kw):
    for it in items:
        if all(it.get(k) == v for k, v in kw.items()):
            return it
    return None


def main():
    today = date.today()
    tomorrow = today + timedelta(days=1)
    print("== 1. 基础数据与权限 ==")
    ings = call("canteen", "GET", "/api/ingredients")
    check("食材列表 14 条", ings and len(ings) == 14, f"got {ings and len(ings)}")
    ing = {i["code"]: i for i in ings}
    check("新增鸡腿/鸡块同属 POULTRY 且同替代组 CHICKEN",
          ing["ING013"]["category"] == "POULTRY" and ing["ING014"]["category"] == "POULTRY"
          and ing["ING013"]["substituteGroup"] == "CHICKEN"
          and ing["ING014"]["substituteGroup"] == "CHICKEN")
    check("食材带营养成分", float(ing["ING013"]["proteinPerUnit"]) == 193.0)
    r = call("inspector", "POST", "/api/suppliers", {"code": "X", "name": "X"}, expect=403)
    check("验收员不能新建供应商(403)", r is not None)
    sups = call("buyer", "GET", "/api/suppliers")
    check("供应商 5 家", sups and len(sups) == 5, f"got {sups and len(sups)}")
    sup = {s["code"]: s for s in sups}

    menus = call("canteen", "GET", "/api/menus")
    menu = menus[0]
    check("菜单已发布", menu["status"] == "PUBLISHED")
    detail = call("canteen", "GET", f"/api/menus/{menu['id']}")
    check("菜单含 7 个餐次", len(detail["meals"]) == 7, f"got {len(detail['meals'])}")
    leg_meal = [m for m in detail["meals"] if m["meal"]["dishName"] == "香酥鸡腿"][0]["meal"]
    gs = call("canteen", "GET", f"/api/menus/meals/{leg_meal['id']}/grade-servings")
    check("香酥鸡腿含一~六年级人数", gs and len(gs) == 6
          and sum(g["students"] for g in gs) == 480, str(gs))

    print("== 1b. 本周菜单追加周四“卤鸡腿加餐”（演示同批少送调整两种方案） ==")
    seed_monday = menu["weekStart"]
    dinner_leg = call("canteen", "POST", f"/api/menus/{menu['id']}/meals", {
        "date": str(date.fromisoformat(seed_monday) + timedelta(days=3)),
        "mealType": "DINNER", "dishName": "卤鸡腿加餐", "servings": 200})
    call("canteen", "POST", f"/api/menus/meals/{dinner_leg['id']}/ingredients",
         {"ingredientId": ing["ING013"]["id"], "quantity": 35})
    gs_dinner = call("canteen", "POST", f"/api/menus/meals/{dinner_leg['id']}/grade-servings",
                     {"GRADE_1": 34, "GRADE_2": 34, "GRADE_3": 34, "GRADE_4": 32,
                      "GRADE_5": 33, "GRADE_6": 33})
    check("卤鸡腿加餐 200 人（一~六年级）", gs_dinner and sum(g["students"] for g in gs_dinner) == 200,
          str(gs_dinner))
    check("菜单现含 8 个餐次", len(call("canteen", "GET", f"/api/menus/{menu['id']}")["meals"]) == 8)

    print("== 2. 生成采购计划并下单 ==")
    plan = call("canteen", "POST", f"/api/plans/menus/{menu['id']}/generate", {"note": "冒烟验证"})
    check("计划已生成", plan and plan["status"] == "DRAFT")
    pd = call("canteen", "GET", f"/api/plans/{plan['id']}")
    items = pd["items"]
    check("计划明细 9 行", len(items) == 9, f"got {len(items)}")
    leg_item = find(items, ingredient=ing["ING013"])
    check("鸡腿计划采购 65kg（明日35+周五30，库存5低于安全线20不可用）",
          leg_item and float(leg_item["plannedQty"]) == 65.0, str(leg_item and leg_item["plannedQty"]))

    expected_arrival = (datetime.combine(tomorrow, datetime.min.time())
                        .replace(hour=8).isoformat())
    orders = call("buyer", "POST", f"/api/plans/{plan['id']}/orders",
                  {"expectedArrivalAt": expected_arrival, "remark": "冒烟"})
    check("按供应商拆成 5 张订货单", orders and len(orders) == 5, f"got {orders and len(orders)}")
    meat_order = find(orders, supplier=sup["SUP002"])
    poultry_order = find(orders, supplier=sup["SUP005"])
    check("找到肉类/禽肉供应商订货单", meat_order is not None and poultry_order is not None)
    od = call("buyer", "GET", f"/api/orders/{meat_order['id']}")
    pork_oi = find(od["items"], ingredient=ing["ING004"])
    beef_oi = find(od["items"], ingredient=ing["ING005"])
    check("订货明细含猪肉/牛肉", pork_oi is not None and beef_oi is not None)
    pod = call("buyer", "GET", f"/api/orders/{poultry_order['id']}")
    leg_oi = find(pod["items"], ingredient=ing["ING013"])
    check("禽肉订单鸡腿订货 65kg @22", leg_oi and float(leg_oi["quantity"]) == 65.0)

    print("== 3. 送货（晚送+冷链超标+替换品类+缺检疫票） ==")
    late_arrival = (datetime.combine(tomorrow, datetime.min.time())
                    .replace(hour=10).isoformat())
    batch = call("buyer", "POST", "/api/deliveries", {
        "orderId": meat_order["id"], "licensePlate": "沪A12345", "driverName": "司机甲",
        "arrivedAt": late_arrival, "vehicleTemperature": 10,
        "items": [
            {"orderItemId": pork_oi["id"], "ingredientId": ing["ING004"]["id"], "deliveredQty": 30,
             "productionBatchNo": "PC20261001", "productionDate": str(today - timedelta(days=1)),
             "expiryDate": str(today + timedelta(days=2))},
            {"orderItemId": beef_oi["id"], "ingredientId": ing["ING005"]["id"], "deliveredQty": 25,
             "productionBatchNo": "NC20261001", "quarantineCertNo": "JY20261003001",
             "productionDate": str(today - timedelta(days=1)),
             "expiryDate": str(today + timedelta(days=2))},
            {"ingredientId": ing["ING004"]["id"], "deliveredQty": 10,
             "productionBatchNo": "PC20261002", "quarantineCertNo": "JY20261003002",
             "productionDate": str(today), "expiryDate": str(today + timedelta(days=3))}
        ]})
    check("批次已登记", batch and batch["batchNo"].startswith("DB"))
    bd = call("buyer", "GET", f"/api/deliveries/{batch['id']}")
    check("送货明细 3 行", len(bd["items"]) == 3)
    pork_di = [i for i in bd["items"] if i.get("orderItem") and i["orderItem"]["id"] == pork_oi["id"]][0]
    beef_di = [i for i in bd["items"] if i.get("orderItem") and i["orderItem"]["id"] == beef_oi["id"]][0]
    sub_di = [i for i in bd["items"] if i.get("orderItem") is None][0]

    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    types = {a["type"] for a in anomalies}
    check("自动识别 晚送/冷链/替换 异常",
          {"LATE_DELIVERY", "COLD_CHAIN_BREACH", "SUBSTITUTION"} <= types, str(types))

    print("== 4. 验收（部分拒收+降级+腐烂超标+缺票证+少送） ==")
    rec = call("inspector", "POST", "/api/acceptances", {
        "batchId": batch["id"], "measuredTemp": 10, "temperatureOk": False,
        "appearanceOk": True, "samplingResult": "农残抽检合格", "notes": "冷链车故障",
        "items": [
            {"deliveryItemId": pork_di["id"], "acceptedQty": 20, "rejectedQty": 5,
             "downgradedQty": 5, "rotRatio": 0.12, "rejectReason": "部分腐烂变色"},
            {"deliveryItemId": beef_di["id"], "acceptedQty": 25, "rotRatio": 0.02},
            {"deliveryItemId": sub_di["id"], "acceptedQty": 10, "rotRatio": 0.0}
        ]})
    check("验收完成(部分合格)", rec and rec["overallResult"] == "PARTIAL_QUALIFIED")
    rd = call("inspector", "GET", f"/api/acceptances/{rec['id']}")
    check("验收明细 3 行", len(rd["items"]) == 3)
    pork_ai = [i for i in rd["items"] if i["deliveryItem"]["id"] == pork_di["id"]][0]
    check("猪肉 5kg 降级待审批", pork_ai and pork_ai["downgradeStatus"] == "PENDING")

    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    types = {a["type"] for a in anomalies}
    check("自动识别 少送/腐烂/缺票证 异常",
          {"SHORT_DELIVERY", "HIGH_ROT_RATIO", "MISSING_DOCUMENTS"} <= types, str(types))

    levels = call("canteen", "GET", "/api/inventory/levels")
    pork_lv = find(levels, ingredient=ing["ING004"])
    check("猪肉库存 = 20(原) + 20(合格) + 10(替换) = 50",
          pork_lv and float(pork_lv["quantity"]) == 50.0, str(pork_lv and pork_lv["quantity"]))
    alerts = call("canteen", "GET", "/api/inventory/safety-alerts")
    alert_names = {a["ingredient"]["name"] for a in alerts}
    check("安全线预警含大白菜/鲜牛奶/鸡腿", {"大白菜", "鲜牛奶", "鸡腿"} <= alert_names, str(alert_names))

    print("== 5. 降级审批 / 退货 ==")
    call("buyer", "POST", f"/api/acceptances/items/{pork_ai['id']}/downgrade-approval",
         {"approve": True, "discountRate": 0.25})
    levels = call("canteen", "GET", "/api/inventory/levels")
    pork_lv = find(levels, ingredient=ing["ING004"])
    check("降级批准后猪肉库存 55", pork_lv and float(pork_lv["quantity"]) == 55.0)
    returns = call("buyer", "GET", f"/api/returns?supplierId={sup['SUP002']['id']}")
    check("生成退货单 5kg 猪肉", returns and float(returns[0]["quantity"]) == 5.0)
    call("buyer", "POST", f"/api/returns/{returns[0]['id']}/complete")
    returns = call("buyer", "GET", f"/api/returns?supplierId={sup['SUP002']['id']}")
    check("退货已完成", returns[0]["status"] == "COMPLETED")

    print("== 6. 影响分析（受影响餐次/替代食材/家长公告/供应商责任） ==")
    impact = call("admin", "GET", f"/api/impact/batch/{batch['id']}")
    check("有影响食材(猪肉)", impact and len(impact["impacts"]) >= 1)
    pork_impact = impact["impacts"][0]
    check("受影响餐次 >= 2（白菜猪肉炖粉条/米饭套餐）",
          len(pork_impact["affectedMeals"]) >= 2, str(pork_impact["affectedMeals"]))
    check("替代食材含牛肉", any(s["name"] == "牛肉" for s in pork_impact["substitutes"]))
    check("自动生成家长公告草稿", len(impact["announcements"]) >= 1)
    check("供应商责任含异常与索赔信息", impact["supplierResponsibility"]["supplierName"] == "惠民肉联食品公司")
    ann = call("admin", "GET", "/api/announcements?status=DRAFT")
    check("公告草稿存在", len(ann) >= 1)
    pub = call("admin", "POST", f"/api/announcements/{ann[0]['id']}/publish")
    check("公告已发布", pub and pub["status"] == "PUBLISHED")

    print("== 6b. 鸡腿少送 -> 菜单调整建议（缺口/库存替代品/年级人数） ==")
    # 在要求到货时间（明日 8:00）之前到校，避免晚送；影响窗口从到货日起覆盖周四/周五两个鸡腿餐
    leg_arrival = (datetime.combine(tomorrow, datetime.min.time())
                   .replace(hour=7, minute=30).isoformat())
    leg_batch = call("buyer", "POST", "/api/deliveries", {
        "orderId": poultry_order["id"], "licensePlate": "沪C24680", "driverName": "司机乙",
        "arrivedAt": leg_arrival, "vehicleTemperature": 2,
        "items": [
            {"orderItemId": leg_oi["id"], "ingredientId": ing["ING013"]["id"], "deliveredQty": 20,
             "productionBatchNo": "LEG20261004", "quarantineCertNo": "JY20261004009",
             "productionDate": str(today), "expiryDate": str(today + timedelta(days=2))}
        ]})
    check("鸡腿批次已登记", leg_batch is not None)
    leg_bd = call("buyer", "GET", f"/api/deliveries/{leg_batch['id']}")
    leg_di = leg_bd["items"][0]
    anomalies5 = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP005']['id']}")
    check("送货环节禽肉供应商无异常", anomalies5 == [])
    leg_rec = call("inspector", "POST", "/api/acceptances", {
        "batchId": leg_batch["id"], "measuredTemp": 2, "temperatureOk": True,
        "appearanceOk": True, "samplingResult": "检疫票齐全",
        "items": [{"deliveryItemId": leg_di["id"], "acceptedQty": 20, "rotRatio": 0.0}]})
    check("鸡腿 20kg 验收合格入库", leg_rec and leg_rec["overallResult"] == "QUALIFIED")
    anomalies5 = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP005']['id']}")
    short_a = [a for a in anomalies5 if a["type"] == "SHORT_DELIVERY"]
    check("验收自动识别鸡腿少送异常", len(short_a) == 1 and short_a[0]["ingredient"]["name"] == "鸡腿")
    near("少送 45kg 货值 990 扣罚 10%=99", short_a[0]["penaltyAmount"], 99.0)

    sug = call("buyer", "POST", f"/api/menu-adjustments/batch/{leg_batch['id']}/suggest", {})
    check("生成菜单调整建议(草稿)", sug and sug["status"] == "DRAFT")
    near("调整单记录少送数量 45kg", sug["shortQty"], 45.0)
    near("少送货值 990", sug["shortAmount"], 990.0)
    near("扣罚 99 随少送异常结算（不重复计罚）", sug["penaltyAmount"], 99.0)
    check("关联少送异常单号", bool(sug["shortAnomalyNo"]))
    check("通知范围覆盖一~六年级", sug["notifiedGradeScope"] == "GRADE_1,GRADE_2,GRADE_3,GRADE_4,GRADE_5,GRADE_6")
    check("受影响 2 个餐次、通知 680 人次",
          sug["affectedMeals"] == 2 and sug["notifiedStudents"] == 680,
          f"{sug['affectedMeals']}/{sug['notifiedStudents']}")
    m_dinner = [m for m in sug["meals"] if m["mealType"] == "DINNER"][0]
    m_lunch = [m for m in sug["meals"] if m["dishName"] == "香酥鸡腿"][0]
    near("明日卤鸡腿加餐缺口 10kg（库存25覆盖35中的25）", m_dinner["shortageQty"], 10.0)
    near("周五香酥鸡腿缺口 30kg", m_lunch["shortageQty"], 30.0)
    check("建议含各年级用餐人数", len(m_lunch["gradeServings"]) == 6
          and sum(g["students"] for g in m_lunch["gradeServings"]) == 480)
    cand = {c["name"]: c for c in m_lunch["candidates"]}
    check("库存替代品含鸡块(REPLACED)、鸡蛋与鲜牛奶(SUPPLEMENT)",
          cand.get("鸡块", {}).get("use") == "REPLACED"
          and cand.get("鸡蛋", {}).get("use") == "SUPPLEMENT"
          and cand.get("鲜牛奶", {}).get("use") == "SUPPLEMENT", str(cand))
    near("鸡块可用库存 40kg", cand["鸡块"]["availableQty"], 40.0)
    check("系统建议菜名为香酥鸡块", m_lunch["suggestedReplacementDish"] == "香酥鸡块")

    print("== 6c. 采购员确认（增加鸡蛋 / 改用鸡块）并发布 ==")
    forbidden = call("canteen", "POST", f"/api/menu-adjustments/{sug['adjustmentId']}/confirm",
                     {"decisions": [{"itemId": m_dinner["itemId"], "resolution": "CANCELLED"}]},
                     expect=403)
    check("食堂角色不能确认调整方案(403)", forbidden is not None)
    confirmed = call("buyer", "POST", f"/api/menu-adjustments/{sug['adjustmentId']}/confirm", {
        "decisions": [
            {"itemId": m_dinner["itemId"], "resolution": "SUPPLEMENT",
             "replacementIngredientId": ing["ING006"]["id"]},
            {"itemId": m_lunch["itemId"], "resolution": "REPLACED",
             "replacementIngredientId": ing["ING014"]["id"], "replacementDishName": "香酥鸡块"}
        ]})
    check("采购员确认后状态 CONFIRMED", confirmed and confirmed["status"] == "CONFIRMED")
    check("确认人为 buyer", confirmed["confirmedBy"] == "buyer")
    ci_dinner = [i for i in confirmed["items"] if i["mealType"] == "DINNER"][0]
    ci_lunch = [i for i in confirmed["items"] if i["originalDishName"] == "香酥鸡腿"][0]
    near("增加鸡蛋 = 200人 × 0.05kg = 10kg", ci_dinner["replacementQty"], 10.0)
    check("加餐保留原菜并标注增加鸡蛋",
          ci_dinner["resolution"] == "SUPPLEMENT" and ci_dinner["replacementIngredientName"] == "鸡蛋"
          and "增加鸡蛋" in ci_dinner["replacementDishName"])
    near("改用鸡块 30kg", ci_lunch["replacementQty"], 30.0)
    check("周五改为香酥鸡块", ci_lunch["resolution"] == "REPLACED"
          and ci_lunch["replacementDishName"] == "香酥鸡块")
    nd = ci_lunch["nutritionDiffPerServing"]
    near("鸡块替代：每份热量 +24.38kcal", nd["calories"], 24.38, eps=0.05)
    near("鸡块替代：每份蛋白质 -3.31g", nd["protein"], -3.31, eps=0.05)
    near("鸡块替代：每份脂肪 +0.94g", nd["fat"], 0.94, eps=0.05)
    near("鸡块替代：每份碳水 +7.50g", nd["carbs"], 7.50, eps=0.05)
    nd2 = ci_dinner["nutritionDiffPerServing"]
    near("增加鸡蛋：每份热量 +72kcal", nd2["calories"], 72.0, eps=0.05)
    near("增加鸡蛋：每份蛋白质 +6.65g", nd2["protein"], 6.65, eps=0.05)

    published = call("buyer", "POST", f"/api/menu-adjustments/{sug['adjustmentId']}/publish", {})
    check("调整已发布", published and published["status"] == "PUBLISHED" and published["publishedAt"])

    # 家长公告与通知范围
    all_pub = call("admin", "GET", "/api/announcements?status=PUBLISHED")
    adj_anns = [a for a in all_pub if a.get("adjustment")]
    check("调整自动发布 2 份家长公告", len(adj_anns) == 2, str([a["title"] for a in adj_anns]))
    ann_lunch = [a for a in adj_anns if "香酥鸡腿" in a["title"]][0]
    check("公告说明少送原因与替代方案",
          "少送" in ann_lunch["content"] and "香酥鸡块" in ann_lunch["content"]
          and "鸡块" in ann_lunch["content"])
    check("公告带通知范围与人数（480人，一~六年级）",
          ann_lunch["notifiedStudents"] == 480
          and ann_lunch["notifyGradeScope"] == "GRADE_1,GRADE_2,GRADE_3,GRADE_4,GRADE_5,GRADE_6")

    # 原菜单保留、替代菜生效
    md2 = call("canteen", "GET", f"/api/menus/{menu['id']}")
    lunch_now = [m for m in md2["meals"] if m["meal"]["id"] == m_lunch["mealId"]][0]
    check("原菜名保留且现菜名已改为香酥鸡块",
          lunch_now["meal"]["originalDishName"] == "香酥鸡腿"
          and lunch_now["meal"]["dishName"] == "香酥鸡块")
    retired_leg = [l for l in lunch_now["ingredients"]
                   if l["ingredient"]["id"] == ing["ING013"]["id"]]
    repl_nug = [l for l in lunch_now["ingredients"]
                if l["ingredient"]["id"] == ing["ING014"]["id"]]
    check("原鸡腿行退役保留（追溯）", len(retired_leg) == 1 and retired_leg[0]["retired"] is True
          and retired_leg[0]["lineKind"] == "ORIGINAL")
    check("鸡块替代行生效", len(repl_nug) == 1 and repl_nug[0]["retired"] is False
          and repl_nug[0]["lineKind"] == "REPLACEMENT" and float(repl_nug[0]["quantity"]) == 30.0)
    dinner_now = [m for m in md2["meals"] if m["meal"]["id"] == m_dinner["mealId"]][0]
    check("增加鸡蛋餐次原菜保留、新增鸡蛋行",
          dinner_now["meal"]["dishName"] == "卤鸡腿加餐（增加鸡蛋）"
          and any(l["ingredient"]["id"] == ing["ING006"]["id"] and l["lineKind"] == "REPLACEMENT"
                  for l in dinner_now["ingredients"]))

    # 替代/补充食材库存扣减
    levels = call("canteen", "GET", "/api/inventory/levels")
    nug_lv = find(levels, ingredient=ing["ING014"])
    egg_lv = find(levels, ingredient=ing["ING006"])
    check("鸡块库存 40 - 30 = 10", nug_lv and float(nug_lv["quantity"]) == 10.0,
          str(nug_lv and nug_lv["quantity"]))
    check("鸡蛋库存 50 - 10 = 40", egg_lv and float(egg_lv["quantity"]) == 40.0,
          str(egg_lv and egg_lv["quantity"]))

    print("== 6d. 家长询问：解释菜单变更原因 ==")
    expl = call("admin", "GET", f"/api/menu-adjustments/meals/{m_lunch['mealId']}/explanation")
    check("解释含原菜单与现菜单", expl and expl["originalDishName"] == "香酥鸡腿"
          and expl["currentDishName"] == "香酥鸡块")
    check("解释含替代食材与营养差异",
          expl["replacementIngredientName"] == "鸡块" and expl["nutritionDiffPerServing"] is not None)
    check("解释含通知范围（6 年级 480 人）",
          len(expl["notifiedGrades"]) == 6 and expl["notifiedStudents"] == 480)
    check("解释含供应商少送与扣罚信息",
          expl["supplierName"] == "乡禽鸡肉制品配送公司" and bool(expl["shortAnomalyNo"])
          and float(expl["penaltyAmount"]) == 99.0 and float(expl["shortQty"]) == 45.0)
    check("解释附家长公告全文与发布时间",
          bool(expl["announcementContent"]) and expl["announcementStatus"] == "PUBLISHED"
          and expl["publishedAt"])

    print("== 7. 已备餐无法拒收 -> 附条件接收+自动索赔 ==")
    meals = call("canteen", "GET", f"/api/menus/{menu['id']}")["meals"]
    rice_meal = [m for m in meals if m["meal"].get("originalDishName") == "米饭套餐"][0]["meal"]
    call("canteen", "POST", f"/api/menus/meals/{rice_meal['id']}/status", {"status": "PREPARED"})
    batch2 = call("buyer", "POST", "/api/deliveries", {
        "orderId": meat_order["id"], "licensePlate": "沪B67890",
        "arrivedAt": (datetime.combine(today + timedelta(days=2), datetime.min.time())
                      .replace(hour=7).isoformat()),
        "vehicleTemperature": 2,
        "items": [{"orderItemId": pork_oi["id"], "ingredientId": ing["ING004"]["id"],
                   "deliveredQty": 10, "quarantineCertNo": "JY20261004001",
                   "productionDate": str(today), "expiryDate": str(today + timedelta(days=3))}]})
    bd2 = call("buyer", "GET", f"/api/deliveries/{batch2['id']}")
    di2 = bd2["items"][0]
    rec2 = call("inspector", "POST", "/api/acceptances", {
        "batchId": batch2["id"], "measuredTemp": 2, "temperatureOk": True,
        "appearanceOk": False, "samplingResult": "感官异常",
        "items": [{"deliveryItemId": di2["id"], "rejectedQty": 10, "rotRatio": 0.3,
                   "rejectReason": "大面积变质"}]})
    check("已备餐时拒收转附条件接收", rec2 is not None)
    rd2 = call("inspector", "GET", f"/api/acceptances/{rec2['id']}")
    ai2 = rd2["items"][0]
    check("验收明细为 CONDITIONAL_ACCEPT 且全部入库",
          ai2["result"] == "CONDITIONAL_ACCEPT" and float(ai2["acceptedQty"]) == 10.0)
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    check("产生 CANNOT_REJECT_PREPARED 异常",
          any(a["type"] == "CANNOT_REJECT_PREPARED" for a in anomalies))
    claims = call("buyer", "GET", f"/api/claims?supplierId={sup['SUP002']['id']}")
    check("自动生成无法拒收折价索赔", any("无法拒收" in (c["claimType"] or "") for c in claims))

    print("== 8. 索赔 ==")
    claim = call("buyer", "POST", "/api/claims", {
        "supplierId": sup["SUP002"]["id"], "batchId": batch["id"],
        "claimType": "冷链断裂质量索赔", "amount": 150, "reason": "冷链车故障导致部分腐烂"})
    check("索赔已登记", claim and claim["status"] == "PENDING")
    call("buyer", "POST", f"/api/claims/{claim['id']}/approve")
    claim = call("buyer", "GET", f"/api/claims/{claim['id']}")
    check("索赔已批准", claim["status"] == "APPROVED")

    print("== 9. 结算（肉类供应商：验收重量/退货/折价/索赔/扣罚 + 对账差异） ==")
    st = call("buyer", "POST", "/api/settlements/generate", {
        "supplierId": sup["SUP002"]["id"],
        "periodStart": str(today - timedelta(days=7)),
        "periodEnd": str(today + timedelta(days=7))})
    check("结算单已生成", st and st["status"] == "DRAFT")
    # delivered: 20*30 + 5*30(降级全价) + 25*60 + 10*30(替换) + 10*30(附条件) = 2850
    check("验收应付 2850", float(st["deliveredAmount"]) == 2850.0, str(st["deliveredAmount"]))
    check("退货扣减 150", float(st["returnDeduction"]) == 150.0, str(st["returnDeduction"]))
    check("降级折价扣减 37.5", float(st["downgradeDeduction"]) == 37.5, str(st["downgradeDeduction"]))
    check("索赔扣减 150", float(st["claimDeduction"]) == 150.0, str(st["claimDeduction"]))
    # 扣罚: 批次1(晚送200+冷链300+替换30+少送15+腐烂5.4+缺票证100) + 批次2(晚送200+腐烂4.5) = 854.9
    check("合同扣罚 854.9", float(st["penaltyDeduction"]) == 854.9, str(st["penaltyDeduction"]))
    check("最终应付 1657.6", float(st["finalAmount"]) == 1657.6, str(st["finalAmount"]))
    sd = call("buyer", "GET", f"/api/settlements/{st['id']}")
    check("结算明细含 5 类行", len({i["itemType"] for i in sd["items"]}) == 5)
    st2 = call("buyer", "POST", f"/api/settlements/{st['id']}/confirm",
               {"supplierInvoiceAmount": 2000})
    check("开票金额不一致 -> mismatchFlag", st2 and st2["mismatchFlag"] is True)
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    check("产生 SETTLEMENT_MISMATCH 异常",
          any(a["type"] == "SETTLEMENT_MISMATCH" for a in anomalies))

    print("== 9b. 禽肉供应商结算：少送批次按实送 20kg 付款，不按原订货量 65kg ==")
    st5 = call("buyer", "POST", "/api/settlements/generate", {
        "supplierId": sup["SUP005"]["id"],
        "periodStart": str(today - timedelta(days=7)),
        "periodEnd": str(today + timedelta(days=7))})
    check("禽肉结算单已生成", st5 is not None)
    check("仅按实付 20kg 计价 = 440（绝非订货 65kg 的 1430）",
          float(st5["deliveredAmount"]) == 440.0, str(st5["deliveredAmount"]))
    check("少送扣罚 99（少送货值 10%）", float(st5["penaltyDeduction"]) == 99.0,
          str(st5["penaltyDeduction"]))
    check("禽肉最终应付 341", float(st5["finalAmount"]) == 341.0, str(st5["finalAmount"]))
    sd5 = call("buyer", "GET", f"/api/settlements/{st5['id']}")
    delivered_lines = [i for i in sd5["items"] if i["itemType"] == "DELIVERED"]
    check("验收应付仅一行 20kg 鸡腿", len(delivered_lines) == 1
          and float(delivered_lines[0]["quantity"]) == 20.0
          and delivered_lines[0]["ingredient"]["name"] == "鸡腿", str(delivered_lines))
    penalty_lines = [i for i in sd5["items"] if i["itemType"] == "PENALTY"]
    check("扣罚行对应少送异常", len(penalty_lines) == 1 and "SHORT_DELIVERY" in penalty_lines[0]["note"])

    print("== 10. 供应商看板 / 异常归因 / 复盘统计 ==")
    dash = call("buyer", "GET", f"/api/anomalies/dashboard/{sup['SUP002']['id']}")
    check("连续异常标记(近30天>=3)", dash["consecutiveAnomaly"] is True)
    check("看板含异常类型分布", len(dash["anomaliesByType"]) >= 6)
    open_anomalies = [a for a in anomalies if a["status"] == "OPEN"]
    a1, a2 = open_anomalies[0], open_anomalies[1]
    call("buyer", "POST", f"/api/anomalies/{a1['id']}/resolve",
         {"causeCategory": "SUPPLIER_PERFORMANCE", "note": "供应商冷链车故障"})
    call("buyer", "POST", f"/api/anomalies/{a2['id']}/resolve",
         {"causeCategory": "WEATHER", "note": "连续高温导致腐烂"})
    stats = call("admin", "GET", "/api/stats/by-supplier")
    meat_stats = find(stats, supplierId=sup["SUP002"]["id"])
    check("按供应商统计含肉类供应商", meat_stats is not None and meat_stats["anomalyCount"] >= 8)
    check("按供应商统计含禽肉供应商(1条少送)",
          find(stats, supplierId=sup["SUP005"]["id"]) is not None
          and find(stats, supplierId=sup["SUP005"]["id"])["anomalyCount"] == 1)
    cat = call("admin", "GET", "/api/stats/by-category")
    check("按品类统计含 MEAT", cat.get("MEAT", 0) >= 3, str(cat))
    check("按品类统计含 POULTRY 少送 1 条", cat.get("POULTRY", 0) == 1, str(cat))
    insp = call("admin", "GET", "/api/stats/by-inspector")
    check("按验收员统计含王验收（3 次验收）", any(i["username"] == "inspector" and i["acceptanceCount"] == 3
                                    for i in insp), str(insp))
    cb = call("admin", "GET", "/api/stats/cause-breakdown")
    check("归因分布含 供应商履约/天气",
          cb["byCause"].get("SUPPLIER_PERFORMANCE", 0) >= 1 and cb["byCause"].get("WEATHER", 0) >= 1,
          str(cb["byCause"]))

    print("== 11. 未认证与 Swagger ==")
    import urllib.request as u
    try:
        u.urlopen(BASE + "/api/ingredients")
        check("未认证访问被拒(401)", False)
    except urllib.error.HTTPError as e:
        check("未认证访问被拒(401)", e.code == 401)
    try:
        code = u.urlopen(BASE + "/v3/api-docs").status
        check("OpenAPI 文档可访问", code == 200)
    except Exception:
        check("OpenAPI 文档可访问", False)

    print(f"\n结果: PASS={PASS} FAIL={FAIL}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
