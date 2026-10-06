# clinical-message-ingest-java

面向医疗数据对接的患者消息接入与合并服务。接收 HL7v2 竖线分隔（pipe-delimited）格式的患者消息，
在本地完成解析、幂等判定与按业务时间的合并，**全部状态只落在本地内存，不依赖任何外部服务或真实凭据**。
同一批消息在任意到达顺序、重复投放与并发调度下，最终患者记录一致、且每条消息的处理结论可解释、可查询。

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

| IngestStatus | 含义 |
|---|---|
| `ACCEPTED_NEW` | 新消息，已按业务时间应用 |
| `DUPLICATE_RESENT` | 编号相同且**内容完全一致**的重发，不产生重复记录/更新 |
| `IGNORED_STALE` | 乱序到达的更旧数据，已忽略，不反盖新数据 |
| `REJECTED_PARSE` | 解析/校验失败（原因见 `parseFailureReason`） |
| `REJECTED_MISSING_KEY_FIELD` | 关键患者字段（PID-5 姓名）为空，独立原因拒收 |
| `REJECTED_ID_REUSED` | 编号被复用但内容对不上（上游出错），不动已有数据 |
| `REJECTED_CONFLICT` | 患者标识冲突（本地病案号相同、分配机构不同） |
| `CONFLICT_UPDATE` | 同一业务时间、内容对不上的更新冲突，已有数据保持不动 |

## 幂等规则：重发 vs 编号复用

- 以 **MSH-10 消息控制标识** 为幂等键，同时对消息内容计算 SHA-256 指纹，记录在本地 `MessageStore`。
- 编号首次到达：进入正常处理流程。
- 编号已见、**内容指纹一致**：`DUPLICATE_RESENT`（一字不差的重发），不产生重复患者记录、不重复计数；
  结论详情中会附上该编号的原始处理结论。
- 编号已见、**内容指纹不一致**：`REJECTED_ID_REUSED`（编号复用，上游出错），**绝不改动已入册患者数据**，
  与真正的重发明确区分；再次投递同一条篡改内容，结论稳定仍是 `REJECTED_ID_REUSED`。
- 判定是原子的：并发下同编号消息只放行一条进入后续处理，同一条消息并发投递恰好只有一条判为
  `ACCEPTED_NEW`，其余全部判 `DUPLICATE_RESENT`。

## 合并规则

- 合并以 **业务时间（MSH-7）** 为准，而非到达顺序：
  - 业务时间更晚 → 覆盖（`ACCEPTED_NEW`）；乱序/断线补传的较新数据仍会正确应用。
  - 业务时间更早 → 忽略（`IGNORED_STALE`），**旧数据不会反盖新数据**。
  - 业务时间相同、内容一致 → 正常应用，不算冲突。
  - 业务时间相同、**内容对不上** → `CONFLICT_UPDATE` 更新冲突。
- 患者标识冲突：本地病案号（PID-3 第一个 `^` 之前）相同但分配机构不同 → `REJECTED_CONFLICT`，
  不覆盖任何现有记录。

### 更新冲突（同一业务时间、内容对不上）

- 判定为 `CONFLICT_UPDATE`，**已有数据保持不动**，不会卡死患者：后续业务时间更靠后的正常更新照常生效。
- 每次冲突都在本地 `ConflictStore` 留痕，供人工核对，字段包括：
  - `existingControlId` / `incomingControlId`：哪条消息跟哪条冲突；
  - `differingFields`：对不上的字段（`PID-5` 姓名 / `PID-8` 性别）；
  - `businessTime`、`detail`：冲突发生的业务时间与判断依据。
- 留痕可按患者查询（见下文查询接口）。

### 关键患者字段为空

- 上游补传中 `PID-5`（姓名）为空的消息，按 `REJECTED_MISSING_KEY_FIELD` 拒收：
  - **不会**被悄悄写进患者档案形成空记录，也不覆盖已有档案，避免污染下游对账；
  - 与 `REJECTED_PARSE`（解析失败）、`CONFLICT_UPDATE`（更新冲突）是三种可明确区分的结论；
  - 拒收结论同样按消息编号/患者可查（该编号视为已处理：内容一致的再投判 `DUPLICATE_RESENT`，
    内容不同的再投判 `REJECTED_ID_REUSED`——上游修正内容后请使用新的消息编号重新发送）。

## 批量接入

- `POST /api/messages/batch`，请求体为原始 HL7 消息字符串的 JSON 数组。
- 批次内部允许乱序、夹带重发与冲突消息：**按到达顺序逐条处理，最终患者状态与一条条接入完全一致**
  （有排列组合与并发调度的自动化用例验证）。
- **容量上限**：`ingest.max-batch-size`（默认 100，`application.properties` 可配）。
  超过上限时**整批确定性拒收**（`accepted=false`，原因中包含实际大小与上限），一条都不处理；
  同样输入重复提交，拒收结论完全一致。等于上限的批次正常受理。
- **并发安全**：服务可被多线程同时调用。合并判定与应用是原子临界区，幂等判定基于并发原子操作：
  同一患者并发更新不会出现旧数据反盖新数据，同一条消息不会被重复计数，也不会丢失。

## 本地查询（对接排查）

全部来自本地内存状态，不依赖外部服务：

| 接口 | 说明 |
|---|---|
| `GET /api/messages/{controlId}` | 按消息编号查处理结论（状态、判定依据、内容指纹、涉及患者、处理时间） |
| `GET /api/messages?patientId=...` | 按患者查相关消息的全部处理结论 |
| `GET /api/conflicts?patientId=...` | 查更新冲突留痕（`patientId` 可省略，查全部） |

## 与上一版的兼容范围

- **老结论语义不变**：`ACCEPTED_NEW` / `DUPLICATE_RESENT` / `IGNORED_STALE` / `REJECTED_PARSE` /
  `REJECTED_CONFLICT` 的判定条件与上一版一致，老消息重放结论相同。
- **一处行为收紧**：上一版中"业务时间相同、内容不同"的消息会静默覆盖现有记录；本版将其判为
  `CONFLICT_UPDATE` 并保持已有数据不动。业务时间相同且内容一致的消息行为不变（正常应用）。
- **幂等键不变**：仍以 MSH-10 为幂等键；新增内容指纹仅用于把"编号复用"从重发中区分出来。
- **存储结构变化**：`MessageStore` 由"已见编号集合"升级为"编号 → 内容指纹 + 处理结论"的映射，
  仅内存、进程重启即清空，与上一版一致。

## 本地验证方法

运行全部单元测试（35 个：重复投递、乱序到达、畸形消息、标识冲突、转义字符、更新冲突、
编号复用、批量乱序等价、容量上限、并发调度）：

```bash
mvn test
```

测试日志会打印每条消息的 **消息控制标识（controlId）与判定依据（status / parseFailure / detail）**，
冲突场景还会打印留痕内容（双方编号、差异字段、依据）。

本地启动并通过 REST 接入消息：

```bash
mvn spring-boot:run

# 单条
curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: text/plain' \
  --data-binary $'MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20261002090000||ADT^A01|MSG001|P|2.5\rPID|1||P001^^^HIS||Zhang^San||19800101|M'

# 批量（JSON 数组）
curl -X POST http://localhost:8080/api/messages/batch \
  -H 'Content-Type: application/json' \
  -d '["MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20261002090000||ADT^A01|MSG001|P|2.5\rPID|1||P001^^^HIS||Zhang^San||19800101|M"]'

# 查询处理结论与冲突留痕
curl http://localhost:8080/api/messages/MSG001
curl 'http://localhost:8080/api/messages?patientId=P001^^^HIS'
curl 'http://localhost:8080/api/conflicts?patientId=P001^^^HIS'
```

单条响应为 `IngestResult` JSON：`status`（处理结论）、`messageControlId`、`detail`（判定依据）、
`parseFailureReason`（解析失败原因，可区分）。批量响应为 `BatchIngestResult` JSON：
`accepted`、`rejectionReason`（整批拒收原因）、`maxBatchSize`、`results`（逐条结论，顺序与到达一致）。
