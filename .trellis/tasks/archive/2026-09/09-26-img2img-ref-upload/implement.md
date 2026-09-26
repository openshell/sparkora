# Implement：图生图参考图直传

> 父任务：`.trellis/tasks/09-26-image-gen-ux-paste`；依赖：无（本任务先行）

## 验证命令

- 编译：`mvn -q -DskipTests compile`（maven 仓库只读时加 `-Dmaven.repo.local=/tmp/m2repo`）
- 手动联调：`./dev.sh restart backend` + `./dev.sh logs backend -f`；用 curl/前端触发新接口。

## 实施清单（有序）

1. `ImageService`：提取参考图校验私有方法（大小/扩展名/读字节/魔数），`upload` 改为复用（行为不变，文案不变）。
2. `ImageService`：新增 `generateImage2ImageFromUpload(Long projectId, MultipartFile file, String prompt, String size, int n, List<String> tags, String operator)`：
   - `ensureProject`（非空）、`prompt` 非空、`file` 非空/校验、`normalizeSize`、`n` 收敛、`tagService.normalize(tags)`。
   - 循环 `aiImageClient.generateImage2Image(prompt, bytes, refFileName, normSize)`；单张失败 warn 跳过、全失败抛 `AiException`。
   - 结果 `saveGenerated(projectId, g.url(), prompt, null, "ai-img2img", operator, g.model(), normSize, normTags)`。
3. `ImageController`：新增
   ```java
   @PostMapping("/generate-from-image-upload")
   @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
   public R<List<ImageAssetEntity>> generateFromImageUpload(
       @RequestParam("file") MultipartFile file,
       @RequestParam("prompt") String prompt,
       @RequestParam(required = false) Long projectId,
       @RequestParam(required = false) String size,
       @RequestParam(defaultValue = "1") Integer n,
       @RequestParam(required = false) List<String> tags) { ... }
   ```
   - 复用 `splitTags(tags)`；异常映射：`IllegalArgumentException`→400、`MultipartException`→400、其他→500。
4. 文档（父任务收口时同步）：`docs/spec/image.md` §6 接口表新增一行；§1 数据模型注明该来源图 `ref_image_id` 为 NULL。
5. 自检：`mvn -q -DskipTests compile`。

## 验证要点（对照 AC）

- AC-2：调用后 `GET /api/images?source=ai-img2img` 与全量列表，确认参考图未出现；结果行 `ref_image_id` 为 null。
- AC-3/AC-4：非法 file（大文件/`.gif`/伪图片）、`size=800x600`、`n=9` 各测一次。
- AC-5：VIEWER token 调用 → 403。
- AC-8：回归 `generate-from-image`、`/regenerate`。

## 风险文件 / 回滚点

- `ImageService.java`（校验方法提取处，注意 `upload` 文案零改动）。
- 回滚：删接口 + 方法即可，无 schema 变更。

## Follow-ups

- 前端 `09-26-image-gen-drawer-ux` 依赖本接口；接口就绪后通知联调。
