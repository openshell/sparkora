# Implement：父任务集成与收口

> 本任务不实现产品代码（除文档收口）；实现由两个子任务承担。

## 子任务顺序

1. `.trellis/tasks/09-26-img2img-ref-upload` — 后端新接口（先行，无依赖）。
2. `.trellis/tasks/09-26-image-gen-drawer-ux` — 前端共用组件 + 粘贴/本地参考图（依赖 1 联调）。

## 父任务直接工作

1. **文档收口**：`docs/spec/image.md`：
   - §1 数据模型：注明「参考图直传来源的生成结果 `ref_image_id` 为 NULL」。
   - §6 接口表：新增 `POST /api/images/generate-from-image-upload` 行（字段/权限/响应/错误）。
   - §8 页面职责：两入口统一为共用 `AiImageDrawer`；图生图参考图支持「粘贴 / 本地文件 / 图库」；粘贴/本地来源重生成依赖前端会话缓存，刷新后置灰。
   - §10 已知限制：补充「参考图不落库 + 重生成依赖会话缓存」。
2. **集成验收**：按父任务 Cross-Child AC 逐条走查（AC-1~7）。
3. **阶段提交**：子任务各自提交；父任务提交文档与集成修正。

## 验证命令

- 后端：`mvn -q -DskipTests compile`
- 前端：`cd frontend && npm run build`
- 联调：`./dev.sh restart all`，按 AC 手工回归三来源 + 重生成 + 刷新置灰。

## 完成判据

- 两个子任务均已归档；父任务 AC-1~7 全部勾选；`docs/spec/image.md` 同步完成。
