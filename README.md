# 校园食材供应商订货验收入库与索赔服务

基于 **Java 17 + Spring Boot 3.3 + PostgreSQL** 的后端服务，覆盖校园食堂食材供应链全流程：

```
周菜单 → 采购计划 → 订货单 → 送货批次 → 验收 → 入库/退货/降级 → 索赔 → 结算 → 影响分析 → 复盘统计
                                          └─ 少送 → 菜单调整建议 → 采购确认 → 菜单变更/营养差异/家长公告/供应商扣罚 → 家长问询
```

## 功能总览

| 模块 | 说明 |
|---|---|
| 周菜单 | 食堂按周维护餐次与食材用量，发布后生成采购计划 |
| 采购计划 | 汇总菜单需求，扣除可用库存（保留安全线）得出采购量，按品类匹配供应商 |
| 订货单 | 按计划按供应商自动拆单，记录规格、数量、合同价、质检要求、是否冷链、要求到货时间 |
| 送货批次 | 登记车牌、司机、到货时间、车厢温度；自动识别**晚送 / 冷链温度超标 / 临时替换品类** |
| 验收 | 验收员逐项核对重量、腐烂比例、检疫票、外观、抽检；合格入库、拒收退货、降级待审批；自动识别**少送（按累计送货量）/ 腐烂超标 / 票证缺失**；**食堂已备餐无法拒收**时自动转附条件接收并生成折价索赔 |
| 库存 | 库存水位 + 流水（入库/出库/退货/调整），安全线预警 |
| 退货 | 拒收与降级驳回自动生成退货单，跟踪退货完成 |
| 索赔 | 手工登记或系统自动生成（无法拒收折价），审批/支付流转 |
| **少送菜单调整** | 供应商少送导致无法按原菜单供餐时，依据**缺口数量、库存替代品、年级用餐人数**生成调整建议；采购员确认**改用库存替代品（鸡腿→鸡块）/增加其他食材补量（鸡蛋）**后，一并记录菜单变更、**营养差异**、**家长公告（含通知范围）**与**供应商扣罚**；月底结算少送批次不按原订货量付款；家长后续问询可解释变更原因 |
| 年级用餐人数 | 维护一~六年级用餐人数，作为调整建议的供餐份数与通知范围依据 |
| 结算 | 按实际验收重量×合同价，扣减退货、降级折价、索赔、合同扣罚生成账单；少送数量生成**金额 0 的备忘行**（货值留档、不予付款）；确认时录入供应商开票金额，不一致自动登记**结算差异异常** |
| 影响分析 | 验收失败批次 → 受影响餐次、替代食材（替代组→同品类）、家长公告草稿、供应商责任 |
| 复盘统计 | 按供应商/品类/验收员统计异常与拒收率；按归因区分**天气质量波动 / 供应商履约不稳 / 验收标准执行偏差** |

## 少送菜单调整业务链路

1. 验收自动识别**少送异常**（按订货明细跨批次累计送货量判定），少送货值 = 缺口数量 × 合同价，按 10% 计合同扣罚。
2. `POST /api/menu-adjustments/suggest` 按少送异常生成调整建议：
   - **缺口数量**：复算累计送货量得到净缺口；已到部分验收入库后仍按原菜供应（retained），只对净缺口做替代；
   - **库存替代品**：同替代组优先（`CHICKEN` 组：鸡腿↔鸡块），组内不足回退同品类，按库存逐餐分配；
   - **营养增补**：对替代后蛋白质/热量缺口，自动建议增加其他有库存高营养食材（如鸡蛋，取整到 0.5 单位）；
   - **年级用餐人数**：汇总各年级人数给出通知范围（如全校一~六年级 2940 名学生）；
   - 同步输出原菜/替代菜的**热量与蛋白质差异**。
3. `POST /api/menu-adjustments/{id}/confirm`（采购负责人）：勾选并可调整数量后确认——
   - 执行菜单变更：原菜单行**保留留档**（净缺额部分标记 `ORIGINAL`，现有库存仍供部分减量保留为正常行），新增替代/增补行标记 `SUBSTITUTE`；
   - 按确认数量扣减库存（`MENU_ADJUSTMENT` 出库流水），现有少送食材照常领用；
   - 记录营养差异、确认人与备注，并自动生成**家长公告草稿**（含少送货值、扣罚、不付款说明、营养对比、通知范围）。
4. `POST /api/announcements/{id}/publish`（校方）或 `POST /api/menu-adjustments/{id}/publish`：发布公告，调整单联动置为已发布，通知范围随公告留档。
5. **月底结算**：少送数量不出现在应付货值中，另出一条金额 0 的 `SHORT_DELIVERY` 备忘行留档；10% 扣罚计入合同扣罚。
6. **家长问询**：`GET /api/menu-adjustments/{id}/explanation` 或 `.../by-meal/{mealId}/explanation` 返回原菜单、替代菜、增补、营养差异、通知范围、少送原因、扣罚与少送货值，用于解释变更原因。

## 异常类型与合同扣罚规则

| 异常 | 触发时机 | 扣罚 |
|---|---|---|
| 晚送 LATE_DELIVERY | 送货登记（宽限 30 分钟） | 订单金额 5%，最低 200 |
| 冷链超标 COLD_CHAIN_BREACH | 送货登记（车厢温度超出食材冷链区间） | 冷链货值 5%，最低 300 |
| 替换品类 SUBSTITUTION | 送货登记（实送食材≠订购食材或计划外送货） | 送货金额 10% |
| 少送 SHORT_DELIVERY | 验收（按订货明细累计送货量判定） | 少送金额 10% |
| 腐烂超标 HIGH_ROT_RATIO | 验收（蔬果阈值 10%，其他 5%） | 腐烂金额 5% |
| 票证缺失 MISSING_DOCUMENTS | 验收（肉类无检疫票） | 固定 100 |
| 已备餐无法拒收 CANNOT_REJECT_PREPARED | 验收（拒收食材已被备餐餐次需要） | 不扣罚，自动生成货值 50% 折价索赔 |
| 结算差异 SETTLEMENT_MISMATCH | 结算确认（开票金额≠系统应付） | 不扣罚，仅标记 |

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

- 应用：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html （OpenAPI 3.x 文档：/v3/api-docs）
- PostgreSQL：localhost:5432（food / food123 / fooddb），数据持久化在 `pgdata` volume

首次启动自动初始化演示数据（4 供应商、14 食材（含可互为替代的**鸡腿/鸡块**，均带每单位热量与蛋白质）、库存、6 个年级用餐人数、最近一个工作周含周五「香酥鸡腿」的菜单）。设 `APP_SEED_DEMO_DATA=false` 可关闭（环境变量对应 `app.seed-demo-data`）。

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
| buyer | buyer123 | 采购负责人 | 供应商/食材/下单/送货登记/降级审批/退货/索赔/结算/异常处理/**菜单调整建议与确认** |
| canteen | canteen123 | 食堂 | 周菜单/生成采购计划/库存领用/年级人数/发起菜单调整建议 |
| admin | admin123 | 校方 | 公告发布（含菜单调整发布）、复盘统计、全部只读 |

## 验证方式

### 1. 单元测试

```bash
mvn test
```

### 2. 端到端冒烟脚本（推荐）

应用启动后执行（脚本需**全新数据库**，重置后重启应用再跑）：

```bash
python3 scripts/smoke.py http://localhost:8080
```

脚本以 4 个角色走完整流程并断言 91 项检查：权限拦截 → 菜单/计划/拆单 → 送货（晚送+冷链+替换）→ 验收（少送/腐烂/缺票证/降级）→ 库存与安全线 → 降级审批/退货 → 影响分析与家长公告 → 已备餐无法拒收（附条件接收+自动索赔）→ 索赔 → **少送鸡腿→菜单调整（建议缺口/鸡块替代+鸡蛋增补/营养差异/年级通知范围/采购确认/菜单变更留档/公告发布/家长问询）** → 结算（金额逐项断言+少送备忘行+对账差异）→ 供应商看板/异常归因/复盘统计 → 未认证拦截与 OpenAPI 文档。

### 3. 手工体验（curl 示例）

```bash
# 生成采购计划（菜单 id 见 GET /api/menus）
curl -u canteen:canteen123 -X POST http://localhost:8080/api/plans/menus/1/generate -H 'Content-Type: application/json' -d '{}'
# 下单（计划 id 见上一步）
curl -u buyer:buyer123 -X POST http://localhost:8080/api/plans/1/orders -H 'Content-Type: application/json' \
  -d '{"expectedArrivalAt":"2026-10-06T08:00:00"}'
# 供应商异常看板
curl -u buyer:buyer123 http://localhost:8080/api/anomalies/dashboard/2
# 少送异常 -> 生成菜单调整建议（anomalyId 取 SHORT_DELIVERY 异常 id）
curl -u buyer:buyer123 -X POST http://localhost:8080/api/menu-adjustments/suggest \
  -H 'Content-Type: application/json' -d '{"anomalyId":10}'
# 采购员确认（改用鸡块/增加鸡蛋，selections 来自建议返回的 items）
curl -u buyer:buyer123 -X POST http://localhost:8080/api/menu-adjustments/1/confirm \
  -H 'Content-Type: application/json' \
  -d '{"remark":"改用鸡块并增加鸡蛋","selections":[{"itemId":1,"selected":true},{"itemId":2,"selected":true}]}'
# 家长问询：按餐次解释变更原因
curl -u admin:admin123 http://localhost:8080/api/menu-adjustments/by-meal/7/explanation
# 复盘统计
curl -u admin:admin123 http://localhost:8080/api/stats/by-supplier
```

全部接口见 Swagger UI。

## 实际验证结论（本环境已执行）

- `mvn test`：5/5 通过（PenaltyRulesTest 扣罚规则）
- `mvn package`：构建成功
- 本地 PostgreSQL 15（独立实例 5433 端口、全新 fooddb）启动应用：正常，演示数据自动初始化（4 供应商 / 14 食材 / 6 年级 / 含香酥鸡腿的本周菜单）
- `python3 scripts/smoke.py`：**91/91 全部通过**（含少送鸡腿→鸡块替代+鸡蛋增补、营养差异、年级通知范围、确认/发布、家长问询、结算少送备忘与不付款断言）
- 手工接口复验：调整单列表/按批次查询/专属发布幂等（409）/建议幂等/非少送异常拒绝（400）/食堂可发起建议（200）/未认证 401
- Swagger UI `/swagger-ui/index.html` 返回 200，`/v3/api-docs` 返回 OpenAPI 3.0.1 文档（65 个路径，新增 `/api/menu-adjustments/**`、`/api/grades/**`）
- 注：本环境无 Docker，`docker compose up --build` 未实际执行；Compose 配置与本地验证使用相同的镜像构建路径（Dockerfile 多阶段构建）与连接参数

## 项目结构

```
src/main/java/com/campus/food/
├── config/        # 安全、OpenAPI、全局异常、演示数据初始化
├── domain/        # 26 个 JPA 实体 + enums/ 22 个枚举
├── repo/          # 26 个 Spring Data JPA 仓库
├── service/       # 15 个业务服务（菜单/计划/送货/验收/库存/退货/索赔/结算/异常/影响/菜单调整/统计）
└── web/           # 17 个 REST 控制器
scripts/smoke.py   # 端到端冒烟验证脚本
Dockerfile         # 多阶段构建（maven 构建 → jre 运行）
docker-compose.yml # app + postgres 一键启动
```

## 关键设计

- **一致性**：验收在一个事务内联动库存流水、退货单、异常与索赔；结算金额完全由验收明细、退货、折价、索赔、扣罚推导，可追溯到行级明细
- **少送判定**：按订货明细的累计送货量（跨批次）判定，避免分批送货误报；月底结算同样按累计口径复算，仅真正缺口出 `SHORT_DELIVERY` 金额 0 备忘行，已补送不误记
- **少送菜单调整**：已到部分入库后仍按原菜供应（净缺口才替代）；原菜单行保留留档（`ORIGINAL`），替代/增补行标记 `SUBSTITUTE`，菜单变更、营养差异、通知范围、扣罚与公告随调整单长期留档，支持家长问询追溯
- **无法拒收**：拒收数量若被"已备餐"餐次需要，自动转附条件接收（照常入库）+ 异常 + 货值 50% 折价索赔
- **降级使用**：验收登记降级数量 → 采购负责人审批 → 批准按折价率入库并参与折价结算，驳回自动转退货
- **复盘归因**：异常关闭时必须归因（天气/供应商履约/验收执行偏差），统计接口按供应商、品类、验收员三个维度输出
