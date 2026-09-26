# 执行计划：后端多参考图契约

## Ordered Checklist

- [ ] 1. `AiImageClient.generateImage2Image` 签名改为多图（`List<byte[]>` + `List<String>`），保序重复 `image` part；空集合/长度不匹配抛 `AiException`；更新 Javadoc。
- [ ] 2. `ImageService.generateImage2ImageFromUpload` 增加 `List<Long> refImageIds`：
  - 参考图集合校验（1~4，0 与超限文案见 prd）。
  - 先解析 `files`（`readValidatedImage` + 现有 `ensureExt/safeName/sniffExt`），再解析 `refImageIds`（`imageMapper.selectById` 不存在 → 400「参考图不存在」；`imageStorage.download`）。
  - 保序合并为 `List<byte[]>` + `List<String>`，调用多图 `generateImage2Image`。
  - `ref_image_id` 落值：参考图数==1 且来自图库 → 该 id；否则 null。
- [ ] 3. `ImageController.generateFromImageUpload`：`file` → `files`（`List<MultipartFile>`）+ 新增 `refImageIds`（`List<Long>`）；保留其余参数与异常映射；更新 Javadoc。
- [ ] 4. `application.yml`：`max-request-size: ${IMAGE_MAX_REQUEST_MB:45}MB`；`.env.example` 增 `IMAGE_MAX_REQUEST_MB` 注释项。
- [ ] 5. 回归核对：`generate-from-image`（JSON）与 `/{id}/regenerate` 未改动、行为不变。
- [ ] 6. 编译 + 测试。

## Validation Commands

```bash
mvn -q -DskipTests compile
mvn test
```

手工验证（`./dev.sh start` 或已重建容器；先取 token）：
```bash
TOKEN=$(curl -s -X POST http://localhost:5661/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
# 0 张 → 400
curl -s -X POST http://localhost:5661/api/images/generate-from-image-upload -H "Authorization: Bearer $TOKEN" -F 'prompt=x'
# 多文件 → 200
curl -s -X POST http://localhost:5661/api/images/generate-from-image-upload -H "Authorization: Bearer $TOKEN" \
  -F 'prompt=combine' -F 'files=@/tmp/opencode/ref1.png' -F 'files=@/tmp/opencode/ref2.png'
```

## Risky Files / Rollback Points

- `ImageService.generateImage2ImageFromUpload`：签名变更影响调用方（仅控制器）。
- `AiImageClient.generateImage2Image`：签名变更影响 `ImageService.generateImage2Image`（单图图库路径，需同步传单元素列表，行为不变）。
- Rollback：revert + 重建容器。

## Follow-up Checks Before start

- [ ] 本子任务 `design.md` 字段契约（`files[]`/`refImageIds[]`/顺序/上限/`ref_image_id` 规则）与父 `design.md` 一致。
- [ ] `implement.jsonl` / `check.jsonl` 已填真实 spec 条目（非 `_example`）。
