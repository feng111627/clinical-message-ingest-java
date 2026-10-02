# clinical-message-ingest-java

面向医疗数据对接的患者消息接入与合并服务。接收 HL7v2 竖线分隔（pipe-delimited）格式的患者消息，
在本地完成解析、幂等判定与按业务时间的合并，**全部状态只落在本地内存，不依赖任何外部服务或真实凭据**。
同一批消息在任意到达顺序和重复投放下，最终患者记录一致、且每条消息的处理结论可解释。

## 消息格式支持范围

- 段分隔：`\r`（兼容 `\n`）；字段分隔符由 MSH 段第 4 个字符声明（通常为 `|`）。
- 编码字符：读取 MSH-2（组件 `^`、重复 `~`、转义 `\`、子组件 `&`），要求 4 个字符齐全。
- 转义序列：支持标准反转义 `\F\`→`|`、`\S\`→`^`、`\R\`→`~`、`\E\`→`\`、`\T\`→`&`。
- 必填字段：`MSH-7`（消息时间）、`MSH-9`（消息类型）、`MSH-10`（消息控制标识）、`PID-3`（患者标识）。
- 业务时间：`MSH-7`，支持 `yyyyMMdd` / `yyyyMMddHHmm` / `yyyyMMddHHmmss`。
- 患者字段：取自 `PID-3`（标识，CX 格式如 `P001^^^HIS`）、`PID-5`（姓名）、`PID-8`（性别）。

### 拒收原因（可区分，不静默入库）

| ParseFailureReason | 含义 |
|---|---|
| `EMPTY_MESSAGE` | 消息为空 |
| `MISSING_MSH_SEGMENT` | 消息未以 MSH 段开头 |
| `BAD_SEGMENT_STRUCTURE` | 段结构错误（缺分隔符、段名非法、MSH-2 不完整等） |
| `MISSING_REQUIRED_FIELD` | 必填段/字段缺失 |
| `INVALID_TIMESTAMP` | MSH-7 时间格式非法 |

解析失败的消息一律以 `REJECTED_PARSE` + 上述原因拒收，不写入任何本地存储。

## 幂等规则

- 以 **MSH-10 消息控制标识** 为幂等键，记录在本地 `MessageStore`。
- 首次到达：`ACCEPTED_NEW`（或按合并规则的其他结论）。
- 相同控制标识再次投递：`DUPLICATE_RESENT`，**不产生重复患者记录、不产生重复更新事件**，
  与真正的新消息明确区分。

## 合并规则

- 合并以 **业务时间（MSH-7）** 为准，而非到达顺序：
  - 业务时间不早于现有记录 → 覆盖（`ACCEPTED_NEW`）；乱序/断线补传的较新数据仍会正确应用。
  - 业务时间早于现有记录 → 忽略（`IGNORED_STALE`），**旧数据不会反盖新数据**。
- 患者标识冲突：本地病案号（PID-3 第一个 `^` 之前）相同但分配机构不同 → `REJECTED_CONFLICT`，
  不覆盖任何现有记录。
- 因此同一批消息无论正序、反序还是重复投递，最终患者记录一致（有单测验证）。

## 本地验证方法

运行全部单元测试（覆盖重复投递、乱序到达、畸形消息、标识冲突、转义字符）：

```bash
mvn test
```

测试日志会打印每条消息的 **消息控制标识（controlId）与判定依据（status / parseFailure / detail）**。

本地启动并通过 REST 接入消息：

```bash
mvn spring-boot:run

curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: text/plain' \
  --data-binary $'MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20261002090000||ADT^A01|MSG001|P|2.5\rPID|1||P001^^^HIS||Zhang^San||19800101|M'
```

响应为 `IngestResult` JSON：`status`（处理结论）、`messageControlId`、`detail`（判定依据）、
`parseFailureReason`（解析失败原因，可区分）。
