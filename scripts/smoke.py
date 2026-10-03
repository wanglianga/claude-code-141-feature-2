#!/usr/bin/env python3
"""端到端冒烟验证：菜单->计划->订货->送货->验收->库存/退货/降级->索赔->结算->影响分析->少送菜单调整->复盘统计"""
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


def find(items, **kw):
    for it in items:
        if all(it.get(k) == v for k, v in kw.items()):
            return it
    return None


def main():
    today = date.today()
    print("== 1. 基础数据与权限 ==")
    ings = call("canteen", "GET", "/api/ingredients")
    check("食材列表 14 条", ings and len(ings) == 14)
    ing = {i["code"]: i for i in ings}
    r = call("inspector", "POST", "/api/suppliers", {"code": "X", "name": "X"}, expect=403)
    check("验收员不能新建供应商(403)", r is not None)

    menus = call("canteen", "GET", "/api/menus")
    menu = menus[0]
    check("菜单已发布", menu["status"] == "PUBLISHED")
    detail = call("canteen", "GET", f"/api/menus/{menu['id']}")
    check("菜单含 7 个餐次", len(detail["meals"]) == 7)

    print("== 2. 生成采购计划并下单 ==")
    plan = call("canteen", "POST", f"/api/plans/menus/{menu['id']}/generate", {"note": "冒烟验证"})
    check("计划已生成", plan and plan["status"] == "DRAFT")
    pd = call("canteen", "GET", f"/api/plans/{plan['id']}")
    items = pd["items"]
    check("计划明细 9 行（大米库存充足不采购，新增鸡腿）", len(items) == 9, f"got {len(items)}")
    cabbage_item = next((it for it in items
                         if it["ingredient"]["code"] == "ING007"), None)
    check("大白菜计划采购 30kg（库存低于安全线不可用）",
          cabbage_item and float(cabbage_item["plannedQty"]) == 30.0)

    expected_arrival = (datetime.combine(today + timedelta(days=1), datetime.min.time())
                        .replace(hour=8).isoformat())
    orders = call("buyer", "POST", f"/api/plans/{plan['id']}/orders",
                  {"expectedArrivalAt": expected_arrival, "remark": "冒烟"})
    check("按供应商拆成 4 张订货单", orders and len(orders) == 4, f"got {orders and len(orders)}")
    sups = call("buyer", "GET", "/api/suppliers")
    sup = {s["code"]: s for s in sups}
    meat_order = find(orders, supplier=sup["SUP002"])
    check("找到肉类供应商订货单", meat_order is not None)
    od = call("buyer", "GET", f"/api/orders/{meat_order['id']}")
    pork_oi = find(od["items"], ingredient=ing["ING004"])
    beef_oi = find(od["items"], ingredient=ing["ING005"])
    chicken_oi = find(od["items"], ingredient=ing["ING013"])
    check("订货明细含猪肉/牛肉", pork_oi is not None and beef_oi is not None)
    check("订货明细含鸡腿 35kg", chicken_oi is not None and float(chicken_oi["quantity"]) == 35.0)

    print("== 3. 送货（晚送+冷链超标+替换品类+缺检疫票） ==")
    late_arrival = (datetime.combine(today + timedelta(days=1), datetime.min.time())
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
    check("安全线预警含大白菜/鲜牛奶", {"大白菜", "鲜牛奶"} <= alert_names, str(alert_names))

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

    print("== 7. 已备餐无法拒收 -> 附条件接收+自动索赔 ==")
    meals = call("canteen", "GET", f"/api/menus/{menu['id']}")["meals"]
    rice_meal = [m for m in meals if m["meal"]["dishName"] == "米饭套餐"][0]["meal"]
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

    print("== 9. 少送鸡腿 -> 菜单调整建议/确认/公告/家长问询 ==")
    # 种子菜单所在周的周五（香酥鸡腿餐次）；到货日取明天（在原定到货时间上，不产生晚送）
    seed_monday = today - timedelta(days=today.weekday())
    if seed_monday + timedelta(days=4) < today:
        seed_monday += timedelta(days=7)
    friday = seed_monday + timedelta(days=4)

    grades = call("canteen", "GET", "/api/grades")
    check("年级 6 个", grades and len(grades) == 6, str(grades))
    check("年级用餐人数合计 2940", grades and sum(g["studentCount"] for g in grades) == 2940)

    chicken_menu_meal = [m for m in call("canteen", "GET", f"/api/menus/{menu['id']}")["meals"]
                         if m["meal"]["dishName"] == "香酥鸡腿"][0]["meal"]

    batch3 = call("buyer", "POST", "/api/deliveries", {
        "orderId": meat_order["id"], "licensePlate": "沪C24680",
        "arrivedAt": expected_arrival, "vehicleTemperature": 2,
        "items": [{"orderItemId": chicken_oi["id"], "ingredientId": ing["ING013"]["id"],
                   "deliveredQty": 20, "quarantineCertNo": "JY20261005001",
                   "productionDate": str(today), "expiryDate": str(today + timedelta(days=2))}]})
    check("鸡腿批次已登记", batch3 and batch3["batchNo"].startswith("DB"))
    bd3 = call("buyer", "GET", f"/api/deliveries/{batch3['id']}")
    di3 = bd3["items"][0]
    rec3 = call("inspector", "POST", "/api/acceptances", {
        "batchId": batch3["id"], "measuredTemp": 2, "temperatureOk": True,
        "appearanceOk": True, "samplingResult": "合格",
        "items": [{"deliveryItemId": di3["id"], "acceptedQty": 20, "rotRatio": 0.0}]})
    check("鸡腿 20kg 验收合格入库", rec3 and rec3["overallResult"] == "QUALIFIED")
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    chicken_short = [a for a in anomalies
                     if a["type"] == "SHORT_DELIVERY" and a.get("ingredient", {}) and
                     a["ingredient"]["code"] == "ING013"]
    check("识别鸡腿少送 15kg 异常", len(chicken_short) == 1, str(chicken_short))
    check("鸡腿少送扣罚 42（15*28*10%）",
          chicken_short and float(chicken_short[0]["penaltyAmount"]) == 42.0,
          str(chicken_short and chicken_short[0]["penaltyAmount"]))

    levels = call("canteen", "GET", "/api/inventory/levels")
    check("鸡腿库存 20（已到部分）",
          abs(float(find(levels, ingredient=ing["ING013"])["quantity"]) - 20.0) < 1e-6)

    suggestion = call("buyer", "POST", "/api/menu-adjustments/suggest",
                      {"anomalyId": chicken_short[0]["id"]})
    check("生成菜单调整建议(SUGGESTED)", suggestion
          and suggestion["adjustment"]["status"] == "SUGGESTED", str(suggestion))
    check("缺口数量 15kg", float(suggestion["adjustment"]["shortQty"]) == 15.0)
    check("少送货值 420", float(suggestion["adjustment"]["shortAmount"]) == 420.0)
    check("涉及 1 个餐次（周五香酥鸡腿）",
          suggestion["adjustment"]["affectedMealCount"] == 1,
          str(suggestion["adjustment"]["affectedMealCount"]))
    check("通知范围 6 个年级 2940 人",
          suggestion["adjustment"]["affectedGradeCount"] == 6
          and suggestion["adjustment"]["affectedStudentCount"] == 2940)
    check("通知范围文案含一年级至六年级", "一年级" in suggestion["notifyScope"]
          and "六年级" in suggestion["notifyScope"], suggestion["notifyScope"])

    adj_items = suggestion["items"]
    replace_row = [i for i in adj_items if i["actionType"] == "REPLACE"][0]
    supplement_row = [i for i in adj_items if i["actionType"] == "SUPPLEMENT"][0]
    check("建议改用库存鸡块补净缺口 15kg（现有20kg鸡腿仍按原菜供应）",
          replace_row["replacementIngredient"]["code"] == "ING014"
          and float(replace_row["suggestedQty"]) == 15.0
          and float(replace_row["retainedQty"]) == 20.0
          and float(replace_row["mealRequiredQty"]) == 35.0,
          str(replace_row))
    check("建议增加鸡蛋补营养 1.5kg",
          supplement_row["replacementIngredient"]["code"] == "ING006"
          and float(supplement_row["suggestedQty"]) == 1.5, str(supplement_row))
    # 净缺口营养：原鸡腿 27150kcal/2700g蛋白；鸡块 24000kcal/2550g；鸡蛋 2160kcal/199.5g
    check("调整建议营养差异 蛋白 2700->2749.5",
          abs(float(suggestion["adjustment"]["originalProtein"]) - 2700.0) < 1e-6
          and abs(float(suggestion["adjustment"]["adjustedProtein"]) - 2749.5) < 1e-6,
          str(suggestion["adjustment"]))
    check("现有库存仍按原菜供应营养留档 蛋白3600",
          abs(float(suggestion["adjustment"]["retainedProtein"]) - 3600.0) < 1e-6,
          str(suggestion["adjustment"]))

    # 非采购员（验收员）不能确认
    deny = call("inspector", "POST", f"/api/menu-adjustments/{suggestion['adjustment']['id']}/confirm",
                {"selections": []}, expect=403)
    check("验收员不能确认菜单调整(403)", deny is not None)

    # 采购员确认：鸡块 + 鸡蛋 全采纳
    confirmed = call("buyer", "POST",
                     f"/api/menu-adjustments/{suggestion['adjustment']['id']}/confirm",
                     {"remark": "改用库存鸡块并增加鸡蛋补量",
                      "selections": [
                          {"itemId": replace_row["id"], "selected": True},
                          {"itemId": supplement_row["id"], "selected": True}]})
    check("采购员确认后状态 CONFIRMED",
          confirmed and confirmed["adjustment"]["status"] == "CONFIRMED", str(confirmed))
    check("确认人为李采购",
          confirmed["adjustment"]["confirmedBy"]
          and confirmed["adjustment"]["confirmedBy"]["fullName"] == "李采购")

    levels = call("canteen", "GET", "/api/inventory/levels")
    check("鸡块库存 40-15=25",
          abs(float(find(levels, ingredient=ing["ING014"])["quantity"]) - 25.0) < 1e-6)
    check("鸡蛋库存 50-1.5=48.5",
          abs(float(find(levels, ingredient=ing["ING006"])["quantity"]) - 48.5) < 1e-6)
    check("现有鸡腿 20kg 照常领用后库存 0",
          abs(float(find(levels, ingredient=ing["ING013"])["quantity"]) - 0.0) < 1e-6)

    meal_after = [m for m in call("canteen", "GET", f"/api/menus/{menu['id']}")["meals"]
                  if m["meal"]["id"] == chicken_menu_meal["id"]][0]
    lines = meal_after["ingredients"]
    leg_normal = [l for l in lines if l["ingredient"]["code"] == "ING013" and l["flag"] == "NORMAL"]
    leg_orig = [l for l in lines if l["ingredient"]["code"] == "ING013" and l["flag"] == "ORIGINAL"]
    chunk_line = [l for l in lines if l["ingredient"]["code"] == "ING014" and l["flag"] == "SUBSTITUTE"]
    egg_line = [l for l in lines if l["ingredient"]["code"] == "ING006" and l["flag"] == "SUBSTITUTE"]
    check("原菜单保留：鸡腿正常行减量到 20", len(leg_normal) == 1
          and float(leg_normal[0]["quantity"]) == 20.0, str(lines))
    check("原菜单留档：缺额 15 标记 ORIGINAL", len(leg_orig) == 1
          and float(leg_orig[0]["quantity"]) == 15.0)
    check("替代菜鸡块 15 标记 SUBSTITUTE", len(chunk_line) == 1
          and float(chunk_line[0]["quantity"]) == 15.0)
    check("增补鸡蛋 1.5 标记 SUBSTITUTE", len(egg_line) == 1
          and float(egg_line[0]["quantity"]) == 1.5)

    ann_id = confirmed["adjustment"]["announcement"]["id"]
    ann = call("admin", "GET", f"/api/announcements/{ann_id}")
    check("生成家长公告草稿且含通知范围", ann["status"] == "DRAFT"
          and "一年级至六年级" in (ann.get("notifyScope") or "")
          and "2940" in (ann.get("notifyScope") or ""), str(ann.get("notifyScope")))
    check("公告内容说明少送与扣罚/不付款", "少送" in ann["content"] and "42" in ann["content"]
          and "不按原订货量付款" in ann["content"])
    pub = call("admin", "POST", f"/api/announcements/{ann_id}/publish")
    check("公告发布后调整单联动 PUBLISHED", pub and pub["status"] == "PUBLISHED")
    adj_after = call("buyer", "GET", f"/api/menu-adjustments/{suggestion['adjustment']['id']}")
    check("调整单已发布", adj_after["adjustment"]["status"] == "PUBLISHED")

    # 家长问询：按餐次解释变更原因
    explanation = call("admin", "GET",
                       f"/api/menu-adjustments/by-meal/{chicken_menu_meal['id']}/explanation")
    check("问询解释含供应商与少送原因",
          explanation["supplierName"] == "惠民肉联食品公司" and "少送" in explanation["reason"])
    check("问询解释含原菜/替代菜/增补",
          any(l["ingredientName"] == "鸡块" for l in explanation["replacementLines"])
          and any(l["ingredientName"] == "鸡蛋" for l in explanation["replacementLines"])
          and any(l["ingredientName"] == "鸡腿" for l in explanation["originalLines"]))
    check("问询解释含营养差异与通知范围",
          abs(float(explanation["nutrition"]["retainedCalories"]) - 36200.0) < 1e-6
          and explanation["notifyScope"]["studentCount"] == 2940
          and explanation["announcementStatus"] == "PUBLISHED")
    check("问询解释含扣罚金额 42 与少送货值 420",
          float(explanation["shortInfo"]["penaltyAmount"]) == 42.0
          and float(explanation["shortInfo"]["shortAmount"]) == 420.0)

    print("== 10. 结算（验收重量/退货/折价/索赔/扣罚/少送备忘 + 对账差异） ==")
    st = call("buyer", "POST", "/api/settlements/generate", {
        "supplierId": sup["SUP002"]["id"],
        "periodStart": str(today - timedelta(days=7)),
        "periodEnd": str(today + timedelta(days=7))})
    check("结算单已生成", st and st["status"] == "DRAFT")
    # delivered: 2850（既有批次）+ 鸡腿已到 20*28=560 = 3410（少送 15kg 不付款）
    check("验收应付 3410（少送部分不付款）", float(st["deliveredAmount"]) == 3410.0, str(st["deliveredAmount"]))
    check("退货扣减 150", float(st["returnDeduction"]) == 150.0, str(st["returnDeduction"]))
    check("降级折价扣减 37.5", float(st["downgradeDeduction"]) == 37.5, str(st["downgradeDeduction"]))
    check("索赔扣减 150", float(st["claimDeduction"]) == 150.0, str(st["claimDeduction"]))
    # 扣罚: 原 854.9 + 鸡腿少送 42 = 896.9
    check("合同扣罚 896.9（含鸡腿少送42）", float(st["penaltyDeduction"]) == 896.9, str(st["penaltyDeduction"]))
    check("最终应付 2175.6", float(st["finalAmount"]) == 2175.6, str(st["finalAmount"]))
    sd = call("buyer", "GET", f"/api/settlements/{st['id']}")
    check("结算明细含 6 类行（含少送备忘）", len({i["itemType"] for i in sd["items"]}) == 6)
    short_lines = [i for i in sd["items"] if i["itemType"] == "SHORT_DELIVERY"]
    check("少送备忘行金额均为0（不按订货量付款）",
          all(float(i["amount"]) == 0.0 for i in short_lines) and len(short_lines) == 1,
          str(short_lines))
    check("鸡腿少送备忘数量15且货值420留档（猪肉已补送不备忘）",
          float(short_lines[0]["quantity"]) == 15.0
          and short_lines[0]["ingredient"]["code"] == "ING013"
          and "420" in short_lines[0]["note"], str(short_lines))
    st2 = call("buyer", "POST", f"/api/settlements/{st['id']}/confirm",
               {"supplierInvoiceAmount": 2000})
    check("开票金额不一致 -> mismatchFlag", st2 and st2["mismatchFlag"] is True)
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    check("产生 SETTLEMENT_MISMATCH 异常",
          any(a["type"] == "SETTLEMENT_MISMATCH" for a in anomalies))

    print("== 11. 供应商看板 / 异常归因 / 复盘统计 ==")
    dash = call("buyer", "GET", f"/api/anomalies/dashboard/{sup['SUP002']['id']}")
    check("连续异常标记(近30天>=3)", dash["consecutiveAnomaly"] is True)
    check("看板含异常类型分布", len(dash["anomaliesByType"]) >= 6)
    open_anomalies = [a for a in anomalies if a["status"] == "OPEN"]
    a1, a2 = open_anomalies[0], open_anomalies[1]
    call("buyer", "POST", f"/api/anomalies/{a1['id']}/resolve",
         {"causeCategory": "SUPPLIER_PERFORMANCE", "note": "供应商冷链车故障/少送"})
    call("buyer", "POST", f"/api/anomalies/{a2['id']}/resolve",
         {"causeCategory": "WEATHER", "note": "连续高温导致腐烂"})
    stats = call("admin", "GET", "/api/stats/by-supplier")
    meat_stats = find(stats, supplierId=sup["SUP002"]["id"])
    check("按供应商统计含肉类供应商", meat_stats is not None and meat_stats["anomalyCount"] >= 9)
    cat = call("admin", "GET", "/api/stats/by-category")
    check("按品类统计含 MEAT", cat.get("MEAT", 0) >= 3, str(cat))
    insp = call("admin", "GET", "/api/stats/by-inspector")
    check("按验收员统计含王验收（3 次验收）", any(i["username"] == "inspector" and i["acceptanceCount"] == 3
                                    for i in insp), str(insp))
    cb = call("admin", "GET", "/api/stats/cause-breakdown")
    check("归因分布含 供应商履约/天气",
          cb["byCause"].get("SUPPLIER_PERFORMANCE", 0) >= 1 and cb["byCause"].get("WEATHER", 0) >= 1,
          str(cb["byCause"]))

    print("== 12. 未认证与 Swagger ==")
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
