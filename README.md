# clinical-message-ingest-java

面向医疗数据对接的患者消息接入与合并服务。消息采用 HL7v2 竖线分隔（pipe-delimited）格式，
状态只落在本地内存，不依赖任何外部服务或真实凭据。同一批消息在任意到达顺序和重复投
递下都会得到一致、可解释的结果。

## 消息格式支持范围

- **段分隔**：`\r`、`\n` 或 `\r\n`，空行忽略。
- **字段分隔**：仅支持竖线 `|`（由 MSH-1 声明，其他分隔符直接拒收）。
- **编码字符**：MSH-2 声明的 4 个字符（通常为 `^~\&`），不完整即拒收。
- **转义序列**：支持标准转义 `\F\`（|）、`\S\`（^）、`\R\`（~）、`\E\`（\）、`\T\`（&），
  未知转义原样保留。
- **必需结构**：首段必须是 `MSH`；必须包含 `PID` 段。
- **必需字段**：`MSH-7`（消息时间）、`MSH-10`（消息控制标识）、`PID-3`（患者标识，取第一
  组件）、`PID-5`（患者姓名）。
- **业务时间**：优先取 `EVN-2`（事件记录时间），缺省回退 `MSH-7`。时间格式为 HL7 TS：
  `YYYY[MM[DD[HH[MM[SS[.ffff]]]]]][+/-HHMM]`，缺省精度按最小值补齐，无时区按 UTC 处理，
  保证跨顺序比较结果一致。

### 拒收原因（可区分）

| RejectReason | 含义 |
|---|---|
| `MISSING_SEGMENT` | 必需段缺失（首段非 MSH、缺 PID） |
| `MALFORMED_SEGMENT` | 段结构错误（空消息、段名非法、MSH-2 不完整、非竖线分隔） |
| `MISSING_FIELD` | 必填字段缺失（MSH-7 / MSH-10 / PID-3 / PID-5） |
| `INVALID_TIMESTAMP` | 时间格式非法（MSH-7 / EVN-2 无法按 HL7 TS 解析） |

畸形消息一律拒收（`REJECTED`），不静默入库。

## 幂等规则

以 **MSH-10 消息控制标识**为幂等键，并记录已受理消息的内容指纹（患者标识 + 姓名 + 业务时间）：

- 同一标识且内容指纹一致 → `DUPLICATE`，幂等忽略，不产生重复患者记录或重复更新事件。
- 同一标识但内容不同 → `CONFLICT_REPLAY`（患者标识冲突），不应用。

## 合并规则

按**业务时间**（EVN-2，缺省 MSH-7）决定覆盖方向：

- 患者首次出现 → `ACCEPTED_NEW`，新建记录。
- 业务时间**晚于**当前记录 → `ACCEPTED_MERGED`，覆盖旧数据。
- 业务时间**早于**当前记录 → `ACCEPTED_STALE`，保留较新数据，旧数据不得反向覆盖。
- 业务时间**相同**但内容不同 → `CONFLICT_UPDATE`（更新冲突），不应用，原数据保留。

因此乱序到达、断线补传不会改变最终状态：最终状态只取决于业务时间最大的那条消息。

## 判定结果一览（IngestStatus）

`ACCEPTED_NEW` / `ACCEPTED_MERGED` / `ACCEPTED_STALE` / `DUPLICATE` /
`CONFLICT_REPLAY` / `CONFLICT_UPDATE` / `REJECTED`（附 `RejectReason`）。
每条消息的判定都会打印日志，包含消息控制标识、患者标识、业务时间与判定依据。

## 接口

```
POST /api/messages   Content-Type: text/plain   Body: 原始 HL7 消息
```

响应为 `IngestResult`：`{ "status": ..., "rejectReason": ..., "detail": ... }`。

## 本地验证

```bash
# 全部测试（解析器 6 + 接入场景 11 + 上下文加载 1）
mvn test

# 只跑接入场景（重复投递 / 乱序 / 畸形 / 冲突），日志含消息标识与判定依据
mvn test -Dtest=IngestServiceTest

# 本地启动后手工验证（application.properties 中 server.port=0 为随机端口，可改为固定端口）
mvn spring-boot:run
curl -X POST localhost:8080/api/messages -H 'Content-Type: text/plain' --data-binary $'MSH|^~\\&|HIS|HOSPITAL|RCV|HOSPITAL|20240101120000||ADT^A01|MSG001|P|2.5\rEVN|A01|20240101100000\rPID|1||P12345^^^HIS||Zhang^San'
```

关键测试用例：

- `duplicateDeliveryIsIdempotent`：同一消息重复投递三次，只产生一条患者记录。
- `sameControlIdWithDifferentContentIsConflict`：同号不同内容 → `CONFLICT_REPLAY`。
- `outOfOrderDeliveryKeepsNewestData` / `inOrderDeliveryMerges`：乱序不乱终态。
- `anyArrivalOrderYieldsSameFinalState`：枚举同批消息（含重复投递）的全部 24 种到达
  顺序，断言最终患者记录完全一致。
- `sameEventTimeWithDifferentContentIsConflict`：同业务时间不同内容 → `CONFLICT_UPDATE`。
- `missing*/invalid*/malformed*`：各类畸形消息按可区分原因拒收，且不入库。
