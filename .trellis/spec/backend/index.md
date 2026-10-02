# Backend Development Guidelines

> Best practices for backend development in this project.

---

## Overview

This directory contains guidelines for backend development. Fill in each file with your project's specific conventions.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Module organization and file layout | To fill |
| [Database Guidelines](./database-guidelines.md) | ORM patterns, queries, migrations | **已填**（幂等迁移/原子抢占/白名单） |
| [Error Handling](./error-handling.md) | Error types, handling strategies | **已填**（R<T> 语义/错误映射矩阵/状态机回退惯例） |
| [External CLI Integration](./external-cli-integration.md) | 外部 CLI 调用约定（参数优先级/资源物化/降级链） | **已填**（wenyan render 先例） |
| [Container Deployment](./container-deployment.md) | 容器化产线部署（端口单一来源/健康检查 401 口径/env_file 注入/镜像内置 CLI/数据卷） | **已填**（compose + nginx envsubst 先例） |
| [AI / RAG Guidelines](./ai-rag-guidelines.md) | AI 文本合成（单轮/多轮）+ 三域知识检索契约 + 开关契约 + 搜索工具可用性契约 + axonhub 能力边界 + ChatClient/Prompt 资产化 + 结构化输出契约 + ToolCallback 能力层 + ChatMemory 多轮装配 + EmbeddingModel 向量后端 | **已填**（C4 多轮问答先例 + 09-15 工具健康状态码 + C0 探针：json_schema 退化/tool calling 支持/reasoning 支持 + C1 Spring AI ChatClient 收敛 + C2 `structured` schema 单一来源/自纠错 + C3 ToolCallback 按需工厂/非全局 bean + C4 `chatWithMemory` ChatMemory 装配 + C5 EmbeddingModel 后端/PgVectorStore 推迟 Scope B） |
| [Quality Guidelines](./quality-guidelines.md) | Code standards, forbidden patterns | **已填**（验证禁改生产数据/不可逆副作用隔离/多步写入失败顺序） |
| [Logging Guidelines](./logging-guidelines.md) | Structured logging, log levels | To fill |

---

## How to Fill These Guidelines

For each guideline file:

1. Document your project's **actual conventions** (not ideals)
2. Include **code examples** from your codebase
3. List **forbidden patterns** and why
4. Add **common mistakes** your team has made

The goal is to help AI assistants and new team members understand how YOUR project works.

---

**Language**: All documentation should be written in **English**.
