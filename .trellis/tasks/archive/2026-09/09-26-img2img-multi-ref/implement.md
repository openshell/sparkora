# 执行计划：图生图多参考图（父任务集成收口）

## 子任务与顺序

1. `09-26-img2img-multi-ref-backend` — 后端多图契约（R1/R2/R5）。
2. `09-26-img2img-multi-ref-frontend` — 前端多图交互与统一提交（R3/R4/R6，依赖 1 的字段契约）。
3. 父任务集成收口 — 文档同步（R8）+ 部署重建验证（R7）+ 跨子 AC 终验。

## Checklist（父任务侧）

- [ ] 确认子任务 1、2 的 `check` 通过，跨子 AC-1~AC-6 有证据。
- [ ] `docs/spec/image.md` 同步：
  - 图生图接口字段级表格改为多图契约（`files[]`/`refImageIds[]`/上限 4/`ref_image_id` NULL 规则）。
  - UI 职责章节更新为多参考图交互。
  - 已知限制补「多参考图结果不可后端重生成 / 会话缓存」条目。
- [ ] `.env.example` 增加 `IMAGE_MAX_REQUEST_MB`（若后端子任务未加）。
- [ ] 部署重建验证（AC-7）：
  ```bash
  docker compose up -d --build
  docker compose ps            # backend healthy
  TOKEN=$(curl -s -X POST http://localhost:5661/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:5661/api/images/generate-from-image-upload \
    -H "Authorization: Bearer $TOKEN" -F 'prompt=test'    # 期望 400（缺失参考图），非 405
  ```
- [ ] 真实多图端到端：2~4 张（含混合来源）生成成功、参考图不入图库、结果 `ref_image_id` 为 null。

## Validation Commands

```bash
mvn -q -DskipTests compile
mvn test
cd frontend && npm run build
docker compose up -d --build && docker compose ps
```

## Review Gates / Rollback

- Gate：子任务 design.md 的字段契约（`files[]`/`refImageIds[]`/顺序/上限）确认后才启动前端子任务。
- Rollback：revert 提交 + `docker compose up -d --build`；无 DB 迁移。
