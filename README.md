# clinical-message-ingest-java

面向医疗数据对接的患者消息接入与合并服务。接收 HL7v2 竖线分隔（pipe-delimited）格式的患者消息，
在本地完成解析、幂等判定、关键字段校验与按业务时间的合并，**全部状态只落在本地内存，不依赖任何外部服务或真实凭据**。
同一批消息在任意到达顺序、重复投放与多线程并发接入下，最终患者记录一致、且每条消息的处理结论可解释、可查询。

## 消息格式支持范围

- 段分隔：`\r`（兼容 `\n`）；字段分隔符由 MSH 段第 4 个字符声明（通常为 `|`）。
- 编码字符：读取 MSH-2（组件 `^`、重复 `~`、转义 `\`、子组件 `&`），要求 4 个字符齐全。
- 转义序列：支持标准反转义 `\F\`→`|`、`\S\`→`^`、`\R\`→`~`、`\E\`→`\`、`\T\`→`&`。
- 必填字段：`MSH-7`（消息时间）、`MSH-9`（消息类型）、`MSH-10`（消息控制标识）、`PID-3`（患者标识）。
- 业务时间：`MSH-7`，支持 `yyyyMMdd` / `yyyyMMddHHmm` / `yyyyMMddHHmmss`。
- 患者字段：取自 `PID-3`（标识，CX 格式如 `P001^^^HIS`）、`PID-5`（姓名）、`PID-8`（性别）。

### 拒收原因（可区分，不静默入库）

解析/校验失败一律以 `REJECTED_PARSE` + 下列原因之一拒收，不写入任何本地存储：

| ParseFailureReason | 含义 |
|---|---|
| `EMPTY_MESSAGE` | 消息为空 |
| `MISSING_MSH_SEGMENT` | 消息未以 MSH 段开头 |
| `BAD_SEGMENT_STRUCTURE` | 段结构错误（缺分隔符、段名非法、MSH-2 不完整等） |
| `MISSING_REQUIRED_FIELD` | 必填段/字段缺失 |
| `INVALID_TIMESTAMP` | MSH-7 时间格式非法 |

## 处理结论一览（IngestStatus）

| 状态 | 含义 |
|---|---|
| `ACCEPTED_NEW` | 新消息，已按业务时间应用 |
| `DUPLICATE_RESENT` | 编号与内容指纹均一致的一字不差重发，不产生重复记录 |
| `IGNORED_STALE` | 乱序到达的更旧数据，已忽略，未反盖新数据 |
| `REJECTED_PARSE` | 解析/校验失败（原因见 `parseFailureReason`） |
| `REJECTED_CONFLICT` | 患者标识冲突（本地病案号相同、分配机构不同） |
| `CONFLICT_UPDATE` | 同一业务时间、内容对不上的更新冲突：已有数据不动，已留痕 |
| `REJECTED_ID_REUSE` | 消息编号被复用但内容对不上（上游出错），已入册数据不动 |
| `REJECTED_VALIDATION` | 关键患者字段（PID-5 姓名）为空，按数据校验拒收，不写入患者档案 |

## 幂等与重发细分

- 以 **MSH-10 消息控制标识** 为幂等键，并对消息原文（去首尾空白）计算 **SHA-256 内容指纹**，
  一同记录在本地 `MessageStore`。
- 编号未见过 → 正常处理（`ACCEPTED_NEW` / `IGNORED_STALE` / 各拒收结论）。
- 编号见过、**指纹一致** → `DUPLICATE_RESENT`：一字不差的重发，不产生重复记录、不重复计数。
- 编号见过、**指纹对不上** → `REJECTED_ID_REUSE`：上游把同一编号发了不同内容，
  **已入册患者数据分毫不动**，原编号的指纹不被覆盖（之后原始消息的真重发仍判 `DUPLICATE_RESENT`）。
- 解析失败的消息不进入幂等存储；其余结论（含 `REJECTED_VALIDATION`）都会归档，供事后查询。

## 合并规则

- 合并以 **业务时间（MSH-7）** 为准，而非到达顺序：
  - 业务时间更晚 → 覆盖（`ACCEPTED_NEW`）；乱序/断线补传的较新数据仍会正确应用。
  - 业务时间更早 → 忽略（`IGNORED_STALE`），**旧数据不会反盖新数据**。
  - 业务时间相同、内容一致 → 幂等应用（`ACCEPTED_NEW`），不算冲突。
  - 业务时间相同、**内容对不上** → `CONFLICT_UPDATE`：**已有数据保持不动**，并留痕待人工核对。
- 患者标识冲突：本地病案号（PID-3 第一个 `^` 之前）相同但分配机构不同 → `REJECTED_CONFLICT`，不覆盖任何现有记录。
- 关键字段校验：PID-5 姓名为空的上游补传消息 → `REJECTED_VALIDATION`，**不写入患者档案、不产生空记录**，
  与解析失败、更新冲突明确区分。

### 更新冲突留痕（ConflictRecord）

每次 `CONFLICT_UPDATE` 都在本地 `ConflictStore` 留痕，字段包括：

- `incomingControlId` / `existingControlId`：哪条消息跟哪条冲突；
- `mismatches`：逐字段差异（字段名、本地值、来报值），即判断依据；
- `patientId`、`businessTime`、`sequence`（冲突序号）。

冲突**不会卡死患者**：之后业务时间更靠后的正常更新仍照常生效（有单测验证）。

## 批量接入

- 入口：`POST /api/messages/batch`（JSON 字符串数组，一个元素一条 HL7 原文）。
- 批次内部允许乱序、重发、编号复用与冲突消息；逐条按批次内到达顺序处理，
  **最终患者状态与逐条接入完全一致**（有单测对照验证）。
- 容量上限：`ingest.max-batch-size`（默认 **500**，见 `application.properties`）。
  超过上限或空批次 → **整批拒收**（HTTP 413，`accepted=false`，`results` 为空），
  一条都不处理，拒绝原因在 `detail` 中明确给出。
- 线程安全：服务可被多线程同时调用。幂等登记是原子操作（同一编号并发到达只有一个线程判 NEW），
  患者记录的状态迁移在记录级锁内完成——不会旧数据反盖新数据、不会同一条消息重复计数、不会丢消息
  （有并发单测覆盖：同消息并发、不同更新并发、同时间冲突并发、编号复用并发、并发批次）。

## 本地排查查询

全部只查本地内存状态：

| 请求 | 说明 |
|---|---|
| `GET /api/messages/{controlId}` | 按消息编号查处理结论（含被复用编号的多次结论） |
| `GET /api/messages?patientId=...` | 按患者标识查处理结论 |
| `GET /api/messages/conflicts?patientId=...` | 按患者查更新冲突留痕 |
| `GET /api/messages/conflicts?controlId=...` | 按消息编号查更新冲突留痕 |
| `GET /api/messages/conflicts` | 全部冲突留痕 |

## 与上一版的兼容范围

- 原有结论（`ACCEPTED_NEW` / `DUPLICATE_RESENT` / `IGNORED_STALE` / `REJECTED_PARSE` / `REJECTED_CONFLICT`）
  的判定逻辑不变，老消息按老规则得到老结论；原有单测全部保留并通过。
- **唯一的行为收紧**：上一版对“同一业务时间、内容不同”的更新是后到覆盖先到；
  本版改为判 `CONFLICT_UPDATE`、已有数据不动并留痕。同一业务时间、内容一致的消息仍判应用，与旧行为一致。
- 上一版只按编号判重；本版对“编号相同、内容不同”的消息结论由 `DUPLICATE_RESENT` 细分为
  `REJECTED_ID_REUSE`（一字不差的重发结论不变）。

## 本地验证方法

运行全部单元测试（覆盖重复投递、乱序到达、畸形消息、标识冲突、转义字符、更新冲突、
编号复用、关键字段为空、批量乱序、容量上限、多线程并发调度）：

```bash
mvn test
```

测试日志会打印每条消息的 **消息控制标识（controlId）与判定依据（status / parseFailure / detail）**，
冲突场景另打印冲突留痕（冲突双方编号、逐字段差异）。

本地启动并通过 REST 接入消息：

```bash
mvn spring-boot:run

# 单条接入
curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: text/plain' \
  --data-binary $'MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20261002090000||ADT^A01|MSG001|P|2.5\rPID|1||P001^^^HIS||Zhang^San||19800101|M'

# 批量接入
curl -X POST http://localhost:8080/api/messages/batch \
  -H 'Content-Type: application/json' \
  -d '["MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20261002090000||ADT^A01|MSG001|P|2.5\rPID|1||P001^^^HIS||Zhang^San||19800101|M"]'

# 排查查询
curl 'http://localhost:8080/api/messages/MSG001'
curl 'http://localhost:8080/api/messages?patientId=P001^^^HIS'
curl 'http://localhost:8080/api/messages/conflicts?patientId=P001^^^HIS'
```

单条响应为 `IngestResult` JSON：`status`（处理结论）、`messageControlId`、`detail`（判定依据）、
`parseFailureReason`（解析失败原因，可区分）。批量响应为 `BatchIngestResult` JSON：
`accepted`（是否整批受理）、`detail`、`maxBatchSize`、`results`（逐条结论，与批次内顺序一致）。
