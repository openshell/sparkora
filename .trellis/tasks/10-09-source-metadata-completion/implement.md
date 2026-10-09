# implement.md — M: 信源 metadata 补全（URL + 权威档透传）

> 依赖：`10-05-source-domain-retrieval`（E，`Citation` 带 sourceType/category）+ `10-05-source-web-fusion`（F，消费侧已读 url/authorityTier）。
> 契约以 `design.md` 与父 `../10-05-self-hosted-sources/design.md` §2.x 为准。

## 实施顺序

1. **`CarRagService` 记录扩字段**：`UnifiedHit`/`Citation` 增可空 `url`/`authorityTier` + 兼容构造器（旧参数组合委托）；`toUnified` 读 metadata `url`/`authorityTier`；组 `cites` 与锚点加权重建同步透传。
2. **写路径**：`NewsDocEntity` 增非持久化 `url`/`authorityTier`；`NewsDocService`（BYD）metadata 写 `url`（若有）+ `authorityTier=official`；`SourceDocService.sourceMeta` 写 `url`/`authorityTier`（空值不写）；`SourceCollectService`/rebuild 调用处把 `SourceEntity.authorityTier` 与内容 URL 填进 `NewsDocEntity`。
3. **取数**：`SearchTool.SearchHit.source(...)` 增 `url` 承载（重载/新参数，兼容旧 7/8 参）；`KnowledgeSearchTool` 传 `c.url()`/`c.authorityTier()`。
4. **迁移** `V16__news_source_metadata_url_tier.sql`：幂等回填 NEWS metadata `url`（`news.url` 派生，可 JOIN）与 byd-news 行 `authorityTier='official'`；不动 id/embedding；V1–V15 不改。
5. **文档**：`docs/spec/brief-generation.md` §4/§5、`retrieval.md` §3、`.trellis/spec/backend/ai-rag-guidelines.md` 把"已知接线缺口"改为"已补齐"，补 `Citation.url/authorityTier` 字段契约。
6. **测试**（见下）→ 全绿后交 check。

## 实现期第一件核对事项

- **[核对] 零回归**：`Citation`/`UnifiedHit` 旧构造器逐位等价；无自建信源时无 SOURCE 命中；BYD 路径 type 仍 KB。
- **[核对] 贯通**：`url`/`authorityTier` 从 metadata → `toUnified` → `cites` → `Citation` → `SearchHit.source` 全程非空（构造输入）。
- **[核对] 迁移**：`V16` 紧随 `V15`；幂等；`id`/`embedding` 不动。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                    # 基线 1062 全绿
npm run build
```

## 测试清单

- `CarRagServiceTest`（增）：`toUnified` 读 `url`/`authorityTier`；`Citation` 兼容构造器新字段 null；贯通。
- `SourceDocServiceTest`（增）：`sourceMeta` 有则写 url/authorityTier、无则不写。
- `NewsDocTransactionTest`/`NewsDocService`（增）：BYD metadata `authorityTier=official`+`url`。
- `KnowledgeSearchToolTest`（增）：SOURCE 命中 `SearchHit.url`/`authorityTier` 非空。
- `FactSheetServiceTest`（增）：真实链路字段触发跨 type 同 URL 去重（AC-M1）；`authorityTier=official`→0.9（AC-M2）。
- 迁移：`V16` 临时 schema `BEGIN…ROLLBACK` 幂等复现（AC-M3）。

## 风险检查点（实现期）

| 检查点 | 何时 | 判定 |
|---|---|---|
| URL 贯通 | record/toUnified/KnowledgeSearchTool 改后 | SOURCE.url 非空且进 fact |
| 权威档贯通 | 写 metadata + 传字段后 | 开启分档取到 0.9/0.7/0.5 |
| 不虚高 | 去重改后 | 跨 type 同 URL sourceCount==1 |
| 零回归 | 默认关+无采集 | 逐位等价 F 收口 |
| 迁移幂等 | V16 | 二次跑 0 变更、id 不动 |

## 回滚点

- 运行时：`DEEP_SOURCE_AUTHORITY_ENABLED=false` + 无自建信源 → 等价现状。
- 代码：revert record/写路径/取数改动；`git revert` V16（无重嵌）。

## 交付物

- `Citation`/`UnifiedHit` 带 `url`/`authorityTier`；写路径 + 迁移 + 取数贯通；文档同步；单测 + build 全绿。
- 不包含：融合算法、采集侧 URL 真伪、domain 改名/重嵌。
