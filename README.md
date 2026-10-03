# 校园食材供应商订货验收入库与索赔服务

基于 **Java 17 + Spring Boot 3.3 + PostgreSQL** 的后端服务，覆盖校园食堂食材供应链全流程：

```
周菜单 → 采购计划 → 订货单 → 送货批次 → 验收 → 入库/退货/降级 → 索赔 → 结算 → 影响分析 → 复盘统计
                                      └ 少送导致菜单调整：调整建议 → 采购员确认 → 换菜/营养差异/家长公告/扣罚 → 家长解释
```

## 功能总览

| 模块 | 说明 |
|---|---|
| 周菜单 | 食堂按周维护餐次与食材用量，支持**分年级（一~六年级）用餐人数**，发布后生成采购计划 |
| 采购计划 | 汇总菜单需求，扣除可用库存（保留安全线）得出采购量，按品类匹配供应商 |
| 订货单 | 按计划按供应商自动拆单，记录规格、数量、合同价、质检要求、是否冷链、要求到货时间 |
| 送货批次 | 登记车牌、司机、到货时间、车厢温度；自动识别**晚送 / 冷链温度超标 / 临时替换品类** |
| 验收 | 验收员逐项核对重量、腐烂比例、检疫票、外观、抽检；合格入库、拒收退货、降级待审批；自动识别**少送（按累计送货量）/ 腐烂超标 / 票证缺失**；**食堂已备餐无法拒收**时自动转附条件接收并生成折价索赔 |
| **少送菜单调整** | 供应商少送（如鸡腿）导致无法按原菜单供餐时：按**缺口数量、库存替代品、各年级用餐人数**生成调整建议；采购员确认**改用鸡块等替代菜**或**增加鸡蛋等补充食材**后，一并记录**菜单变更、营养差异、家长公告（含通知范围）与供应商扣罚**；发布后原菜单、替代菜、营养差异、通知范围全部保留，家长询问时可解释变更原因 |
| 库存 | 库存水位 + 流水（入库/出库/退货/调整），安全线预警；菜单调整发布时替代食材自动出库 |
| 退货 | 拒收与降级驳回自动生成退货单，跟踪退货完成 |
| 索赔 | 手工登记或系统自动生成（无法拒收折价），审批/支付流转 |
| 结算 | 按实际验收重量×合同价，扣减退货、降级折价、索赔、合同扣罚生成账单；**少送批次不会按原订货量付款**；确认时录入供应商开票金额，不一致自动登记**结算差异异常** |
| 影响分析 | 验收失败批次 → 受影响餐次、替代食材（替代组→同品类）、家长公告草稿、供应商责任 |
| 复盘统计 | 按供应商/品类/验收员统计异常与拒收率；按归因区分**天气质量波动 / 供应商履约不稳 / 验收标准执行偏差** |

## 少送导致菜单调整（本版新增）

典型场景：周五午餐「香酥鸡腿」需鸡腿 30kg，供应商订货 65kg 只送 20kg（少送 45kg）。

1. **验收自动识别少送**：按订货明细累计送货量判定，生成 `SHORT_DELIVERY` 异常（少送货值 10% 扣罚），少送 20kg 正常验收入库。
2. **生成调整建议** `POST /api/menu-adjustments/batch/{batchId}/suggest`：
   - 按到货日起 7 天窗口找出需要鸡腿、状态为"已排期"的餐次，现有库存按时间顺序逐餐抵扣，算出**每个餐次的缺口**；
   - 汇总**分年级用餐人数**（一~六年级）确定通知范围与总人次；
   - 从库存中找候选：同替代组（CHICKEN，如**鸡块**）→ 同品类（POULTRY）作为"替代菜"；蛋/乳制品（如**鸡蛋、鲜牛奶**）作为"增加补充"。
3. **采购员确认** `POST /api/menu-adjustments/{id}/confirm`：逐餐选择 `REPLACED`（改用鸡块，可自定义"香酥鸡块"菜名）或 `SUPPLEMENT`（按每人 0.05kg 增加鸡蛋）或 `CANCELLED`；跨餐次用量校验不超过库存；系统按每单位营养成分测算**每份热量/蛋白质/脂肪/碳水差异**（仅对缺口部分计）。
4. **发布** `POST /api/menu-adjustments/{id}/publish`：
   - 原鸡腿食材行**退役但保留**（`ORIGINAL/retired`，不再参与采购汇总），鸡块/鸡蛋成为生效行（`REPLACEMENT`），菜名更新并保留**原菜名快照**；
   - 替代/补充食材按确认数量**出库**；
   - 每个餐次自动**发布家长公告**，写明少送原因、替代方案、每份营养差异、通知年级与人数；
   - 扣罚沿用验收时的少送异常（少送货值 10%），**不重复计罚**。
5. **家长解释** `GET /api/menu-adjustments/meals/{mealId}/explanation`：返回原菜单、现菜单、替代食材与数量、每份营养差异、通知范围（分年级人数）、公告全文、供应商少送批次与扣罚。
6. **月底结算**：少送批次只按实付 20kg×合同价付款（440 元，而非订货 65kg 的 1430 元），再减少送扣罚 99 元。

## 异常类型与合同扣罚规则

| 异常 | 触发时机 | 扣罚 |
|---|---|---|
| 晚送 LATE_DELIVERY | 送货登记（宽限 30 分钟） | 订单金额 5%，最低 200 |
| 冷链超标 COLD_CHAIN_BREACH | 送货登记（车厢温度超出食材冷链区间） | 冷链货值 5%，最低 300 |
| 替换品类 SUBSTITUTION | 送货登记（实送食材≠订购食材或计划外送货） | 送货金额 10% |
| 少送 SHORT_DELIVERY | 验收（按订货明细累计送货量判定；同时驱动菜单调整） | 少送金额 10% |
| 腐烂超标 HIGH_ROT_RATIO | 验收（蔬果阈值 10%，其他 5%） | 腐烂金额 5% |
| 票证缺失 MISSING_DOCUMENTS | 验收（肉类/禽肉类无检疫票） | 固定 100 |
| 已备餐无法拒收 CANNOT_REJECT_PREPARED | 验收（拒收食材已被备餐餐次需要） | 不扣罚，自动生成货值 50% 折价索赔 |
| 结算差异 SETTLEMENT_MISMATCH | 结算确认（开票金额≠系统应付） | 不扣罚，仅标记 |

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

- 应用：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html （OpenAPI 3.x 文档：/v3/api-docs，62 个路径）
- PostgreSQL：localhost:5432（food / food123 / fooddb），数据持久化在 `pgdata` volume

首次启动自动初始化演示数据（5 供应商、14 食材含**鸡腿/鸡块**与营养成分、库存、最近一个工作周菜单含**香酥鸡腿**及分年级用餐人数）。设 `APP_SEED_DEMO_DATA=false` 可关闭（环境变量对应 `app.seed-demo-data`）。

## 本地开发启动

前置：JDK 17、Maven 3.8+、PostgreSQL（创建 `fooddb` 库与 `food/food123` 用户，或用下方环境变量指向已有实例）。

```bash
# 1. 准备数据库（示例：本地 5432，或任意实例）
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/fooddb
export SPRING_DATASOURCE_USERNAME=food
export SPRING_DATASOURCE_PASSWORD=food123

# 2. 启动
mvn spring-boot:run
# 或
mvn -DskipTests package && java -jar target/food-procurement-service-1.0.0.jar
```

## 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 | 主要权限 |
|---|---|---|---|
| inspector | inspector123 | 验收员 | 创建验收记录 |
| buyer | buyer123 | 采购负责人 | 供应商/食材/下单/送货登记/降级审批/退货/索赔/结算/异常处理/**确认与发布菜单调整** |
| canteen | canteen123 | 食堂 | 周菜单/**分年级用餐人数**/生成采购计划/库存领用/发布菜单调整 |
| admin | admin123 | 校方 | 公告发布、复盘统计、全部只读 |

## 主要接口（少送菜单调整）

| 方法与路径 | 角色 | 说明 |
|---|---|---|
| `POST /api/menus/meals/{mealId}/grade-servings` | CANTEEN | 维护分年级用餐人数（整单覆盖，如 `{"GRADE_1":80,...}`） |
| `GET  /api/menus/meals/{mealId}/grade-servings` | 已认证 | 查询分年级用餐人数 |
| `POST /api/menu-adjustments/batch/{batchId}/suggest?ingredientId=` | 采购/食堂/校方 | 按少送批次生成调整建议 |
| `GET  /api/menu-adjustments` / `/{id}` | 已认证 | 调整单列表/详情 |
| `POST /api/menu-adjustments/{id}/confirm` | PROCUREMENT | 逐餐确认替代菜/增加补充（含数量与新菜名） |
| `POST /api/menu-adjustments/{id}/publish` | 采购/食堂 | 发布：换菜生效、替代食材出库、公告发布 |
| `GET  /api/menu-adjustments/meals/{mealId}/explanation` | 已认证 | 家长询问解释（原菜单/替代菜/营养差异/通知范围/扣罚/公告） |

## 验证方式

### 1. 单元测试

```bash
mvn test
```

含扣罚规则（PenaltyRulesTest 5 项）与菜单调整营养差异测算（MenuNutritionTest 3 项：鸡块替代、增加鸡蛋、仅缺口部分计）。

### 2. 端到端冒烟脚本（推荐）

应用启动后执行（脚本需**全新数据库**，重置后重启应用再跑）：

```bash
python3 scripts/smoke.py http://localhost:8080
```

脚本以 4 个角色走完整流程并断言 **114 项**检查：权限拦截 → 菜单（含分年级人数）/计划/拆单 → 送货（晚送+冷链+替换）→ 验收（少送/腐烂/缺票证/降级）→ 库存与安全线 → 降级审批/退货 → 影响分析与家长公告 → **鸡腿少送 45kg → 调整建议（缺口/鸡块·鸡蛋候选/680 人次通知范围）→ 采购员确认（增加鸡蛋 10kg + 改用鸡块 30kg"香酥鸡块"）→ 营养差异逐项断言 → 发布（原菜保留、替代行生效、出库、2 份公告与通知范围）→ 家长解释 → 禽肉供应商结算（仅按实付 20kg 付款 440 + 扣罚 99，而非订货 65kg 的 1430）** → 已备餐无法拒收 → 索赔 → 结算（金额逐项断言+对账差异）→ 供应商看板/异常归因/复盘统计 → 未认证拦截与 OpenAPI 文档。

### 3. 手工体验（curl 示例）

```bash
# 维护餐次分年级用餐人数
curl -u canteen:canteen123 -X POST http://localhost:8080/api/menus/meals/7/grade-servings \
  -H 'Content-Type: application/json' \
  -d '{"GRADE_1":80,"GRADE_2":78,"GRADE_3":80,"GRADE_4":76,"GRADE_5":88,"GRADE_6":78}'
# 少送批次生成菜单调整建议（batchId 为鸡腿少送批次）
curl -u buyer:buyer123 -X POST http://localhost:8080/api/menu-adjustments/batch/1/suggest -d '{}'
# 采购员确认：itemId 取自建议，REPLACED 改用鸡块 / SUPPLEMENT 增加鸡蛋
curl -u buyer:buyer123 -X POST http://localhost:8080/api/menu-adjustments/1/confirm -H 'Content-Type: application/json' -d '{
  "decisions":[{"itemId":1,"resolution":"REPLACED","replacementIngredientId":14,"replacementDishName":"香酥鸡块"}]}'
# 发布后家长询问
curl -u admin:admin123 http://localhost:8080/api/menu-adjustments/meals/7/explanation
```

全部接口见 Swagger UI。

## 实际验证结论（本环境已执行）

- `mvn test`：**8/8 通过**（PenaltyRulesTest 5 + MenuNutritionTest 3）
- `mvn package`：构建成功
- 本地 PostgreSQL 15（用户态实例，端口 5433，全新 fooddb）启动应用：正常，演示数据自动初始化（5 供应商 / 14 食材 / 含香酥鸡腿与分年级人数的周菜单）
- `python3 scripts/smoke.py`：**114/114 全部通过**
- Swagger UI `/swagger-ui/index.html` 返回 200，`/v3/api-docs` 返回 OpenAPI 3.0.1 文档（62 个路径，含"少送菜单调整"分组）
- 注：本环境无 Docker 且系统 PostgreSQL 为 peer 认证，`docker compose up --build` 未实际执行；Compose 配置与本地验证使用相同的镜像构建路径（Dockerfile 多阶段构建）与连接参数

## 项目结构

```
src/main/java/com/campus/food/
├── config/        # 安全、OpenAPI、全局异常、演示数据初始化
├── domain/        # 25 个 JPA 实体 + enums/ 23 个枚举
├── repo/          # 25 个 Spring Data JPA 仓库
├── service/       # 12 个业务服务（菜单/计划/送货/验收/库存/退货/索赔/结算/异常/影响/统计/菜单调整）
│                  #   + MenuNutrition 营养差异测算、PenaltyRules 扣罚规则、NoGen 单号
└── web/           # 16 个 REST 控制器
scripts/smoke.py   # 端到端冒烟验证脚本（114 项断言）
Dockerfile         # 多阶段构建（maven 构建 → jre 运行）
docker-compose.yml # app + postgres 一键启动
```

## 关键设计

- **一致性**：验收在一个事务内联动库存流水、退货单、异常与索赔；结算金额完全由验收明细、退货、折价、索赔、扣罚推导，可追溯到行级明细
- **少送判定**：按订货明细的累计送货量（跨批次）判定，避免分批送货误报；异常带具体食材，菜单调整据此精确定位
- **少送菜单调整**：建议/确认/发布三态分离；缺口按库存逐餐抵扣，替代量受库存约束并自动出库；原菜行退役保留（`retired`）使采购计划、影响分析、备餐判定自动只认真实生效菜单，同时完整保留原菜单快照供家长解释
- **营养差异**：食材带每单位热量/蛋白质/脂肪/碳水，调整仅对缺口部分按人份计算差异（`MenuNutrition` 纯函数可单测）
- **通知范围**：餐次分年级人数驱动公告的年级范围与总人次，家长解释接口可完整还原"为什么换菜、换成什么、营养差多少、通知了谁、供应商怎么罚"
- **扣罚不重复**：菜单调整关联验收已生成的 SHORT_DELIVERY 异常，月底结算按该异常汇总少送货值 10% 扣罚，且结算本身按实际验收重量计价（少送不按原订货量付款）
- **无法拒收**：拒收数量若被"已备餐"餐次需要，自动转附条件接收（照常入库）+ 异常 + 货值 50% 折价索赔
- **降级使用**：验收登记降级数量 → 采购负责人审批 → 批准按折价率入库并参与折价结算，驳回自动转退货
- **复盘归因**：异常关闭时必须归因（天气/供应商履约/验收执行偏差），统计接口按供应商、品类、验收员三个维度输出
