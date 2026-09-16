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
| [AI / RAG Guidelines](./ai-rag-guidelines.md) | AI 文本合成（单轮/多轮）+ 三域知识检索契约 + 开关契约 + 搜索工具可用性契约 | **已填**（C4 多轮问答先例 + 09-15 工具健康状态码） |
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
