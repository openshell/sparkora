#!/usr/bin/env python3
"""E1 阶段 A 对拍 harness(只读旧 4 表 + 只读 vector_store,绝不修改任何业务行)。

方法:
  1. 对固定 query 集,调远程 embedding 端点取唯一 query 向量(两条路径共用同一向量,消除嵌入抖动);
  2. 旧路径:逐字复刻 CarDocEmbeddingMapper.searchTopKUnified 的 CAR+KB 合并窗 + NEWS 独立窗 SQL;
  3. 新路径:逐字复刻 PgVectorStore.similaritySearch 在 domain∈{CAR,KB} / {NEWS} + active + embeddingModel
     过滤下的 SQL(COSINE:`ORDER BY embedding <=> vec LIMIT k`,score=1-distance);
  4. 比对各窗的 (source, refId) 序列 + score(容差 1e-4)+ 顺序,并统计四态/配额后 selected 一致性
     (selected 比对由 Java 单测保证,此处比对候选窗与分数)。
"""
import json
import os
import subprocess
import sys
import urllib.request

ROOT = "/dockerData/code/sparkora"
OUT = os.path.join(ROOT, ".trellis/tasks/10-03-e1-pgstore-migrate/research/parity-A.md")
TOP_K = 32
SCORE_TOL = 1e-4

# 读取 .env(不打印)
env = {}
with open(os.path.join(ROOT, ".env")) as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")

DB = dict(host=env.get("SPARKORA_DB_HOST", "localhost"), port=env.get("SPARKORA_DB_PORT", "5432"),
          user=env.get("SPARKORA_DB_USER", "sparkora"), name=env.get("SPARKORA_DB_NAME", "sparkora"),
          password=env.get("SPARKORA_DB_PASSWORD", ""))
MODEL = env.get("AI_EMBEDDING_MODEL", "Qwen3-Embedding-8B")
BASE = env.get("AI_BASE_URL", "").rstrip("/")
KEY = env.get("AI_API_KEY", "")

QUERIES = [
    ("海狮08EV 续航", [55]),
    ("大唐EV 价格", [39]),
    ("海狮06EV 动力与充电", [33]),
    ("比亚迪 新车型 发布", []),
    ("充电桩怎么选", []),
    ("比亚迪海外销量", []),
    ("深度分析海狮08定价逻辑，这个价格到底贵不贵？", []),
]


def psql(sql):
    # 精确扫描:关闭索引扫描,让 ORDER BY distance LIMIT k 走 seq scan + sort,
    # 消除 HNSW 近似性(两套独立索引图不同 → 边界近似结果天然不同)。这样只验证
    # 「向量/过滤/距离公式」等价,不掺入 ANN 近似噪声。
    cmd = ["psql", "-h", DB["host"], "-p", str(DB["port"]), "-U", DB["user"], "-d", DB["name"],
           "-tAF", "\t", "-c", "SET enable_indexscan=off; SET enable_bitmapscan=off; " + sql]
    e = dict(os.environ, PGPASSWORD=DB["password"])
    out = subprocess.run(cmd, capture_output=True, text=True, env=e)
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    rows = []
    for line in out.stdout.splitlines():
        if not line.strip() or line.strip() == "SET":
            continue
        rows.append(line.split("\t"))
    return rows


def embed(text):
    body = json.dumps({"model": MODEL, "input": text}).encode()
    req = urllib.request.Request(BASE + "/v1/embeddings", data=body,
                                 headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.load(r)
    return "[" + ",".join(repr(x) for x in data["data"][0]["embedding"]) + "]"


def old_car_kb(vec, k):
    # 逐字复刻 searchTopKUnified 的 ① 段:整个 (CAR UNION ALL KB) 组共用一个 top-k 窗。
    return psql(f"""
        SELECT * FROM (
          (SELECT 'CAR' AS source, e.doc_id::text AS refid, 1 - (e.embedding <=> '{vec}'::vector) AS score
           FROM sparkora_car_doc_embedding e
           JOIN sparkora_car_doc d ON d.id = e.doc_id AND d.deleted = 0
           JOIN sparkora_car_model m ON m.id = d.model_id
           WHERE e.embedding_model = '{MODEL}')
          UNION ALL
          (SELECT 'KB' AS source, e.chunk_id::text AS refid, 1 - (e.embedding <=> '{vec}'::vector) AS score
           FROM sparkora_kb_chunk_embedding e
           JOIN sparkora_kb_chunk c ON c.id = e.chunk_id
           JOIN sparkora_kb_doc d2 ON d2.id = c.doc_id AND d2.deleted = 0 AND d2.enabled = TRUE
           WHERE e.embedding_model = '{MODEL}')
        ) u ORDER BY "score" DESC LIMIT {k}""")


def old_news(vec, k):
    return psql(f"""
        SELECT 'NEWS' AS source, e.doc_id::text AS refid, 1 - (e.embedding <=> '{vec}'::vector) AS score
        FROM sparkora_news_doc_embedding e
        JOIN sparkora_news_doc d ON d.id = e.doc_id AND d.deleted = 0
        JOIN sparkora_news n ON n.id = d.news_id AND n.deleted = 0
        WHERE e.embedding_model = '{MODEL}'
        ORDER BY e.embedding <=> '{vec}'::vector LIMIT {k}""")


def _jp(domains):
    dom = " || ".join(f'$.domain == "{d}"' for d in domains)
    return f'({dom}) && $.active == true && $.embeddingModel == "{MODEL}"'


def new_car_kb(vec, k):
    return psql(f"""
        SELECT (metadata->>'domain') AS source, (metadata->>'refId') AS refid,
               1 - (embedding <=> '{vec}'::vector) AS score
        FROM vector_store
        WHERE "metadata"::jsonb @@ '{_jp(["CAR", "KB"])}'::jsonpath
        ORDER BY embedding <=> '{vec}'::vector LIMIT {k}""")


def new_news(vec, k):
    return psql(f"""
        SELECT (metadata->>'domain') AS source, (metadata->>'refId') AS refid,
               1 - (embedding <=> '{vec}'::vector) AS score
        FROM vector_store
        WHERE "metadata"::jsonb @@ '{_jp(["NEWS"])}'::jsonpath
        ORDER BY embedding <=> '{vec}'::vector LIMIT {k}""")


IMG_MIN = 0.3


def old_image(vec, k):
    return psql(f"""
        SELECT 'IMAGE' AS source, e.image_id::text AS refid,
               1 - (e.embedding <=> '{vec}'::vector) AS score
        FROM sparkora_image_embedding e
        WHERE e.embedding_model = '{MODEL}' AND 1 - (e.embedding <=> '{vec}'::vector) >= {IMG_MIN}
        ORDER BY e.embedding <=> '{vec}'::vector LIMIT {k}""")


def new_image(vec, k):
    # PgVectorStore COSINE:相似度门槛转 distance < 1-minScore
    return psql(f"""
        SELECT (metadata->>'domain') AS source, (metadata->>'refId') AS refid,
               1 - (embedding <=> '{vec}'::vector) AS score
        FROM vector_store
        WHERE "metadata"::jsonb @@ '{_jp(["IMAGE"])}'::jsonpath
          AND (embedding <=> '{vec}'::vector) < {1.0 - IMG_MIN}
        ORDER BY embedding <=> '{vec}'::vector LIMIT {k}""")


def cmp_rows(old, new, label):
    old_seq = [(r[0], r[1]) for r in old]
    new_seq = [(r[0], r[1]) for r in new]
    set_diffs = set(old_seq) ^ set(new_seq)
    order_ok = old_seq == new_seq
    score_diffs = []
    om = {(r[0], r[1]): float(r[2]) for r in old}
    nm = {(r[0], r[1]): float(r[2]) for r in new}
    for key in set(om) & set(nm):
        d = abs(om[key] - nm[key])
        if d > SCORE_TOL:
            score_diffs.append((key, om[key], nm[key], d))
    return dict(label=label, old_n=len(old), new_n=len(new), order_ok=order_ok,
                set_diff=sorted(set_diffs), score_diffs=score_diffs)


def main():
    lines = ["# E1 阶段 A 对拍报告(parity-A)", "",
             f"- 固定 query 集:{len(QUERIES)} 条;候选窗 topK={TOP_K}",
             f"- 模型:{MODEL};score 容差 {SCORE_TOL};比较 (source,refId) 序列 + score",
             "- 方法:同一 query 向量分别跑旧 SQL(逐字复刻 searchTopKUnified)与新 store SQL(复刻 PgVectorStore COSINE 查询)",
             ""]
    all_ok = True
    for q, anchors in QUERIES:
        vec = embed(q)
        rows = []
        rows.append(cmp_rows(old_car_kb(vec, TOP_K), new_car_kb(vec, TOP_K), "CAR+KB"))
        rows.append(cmp_rows(old_news(vec, TOP_K), new_news(vec, TOP_K), "NEWS"))
        rows.append(cmp_rows(old_image(vec, 10), new_image(vec, 10), "IMAGE"))
        lines.append(f"## query: {q}  (anchors={anchors})")
        for r in rows:
            status = "OK" if r["order_ok"] and not r["set_diff"] and not r["score_diffs"] else "DIFF"
            if status != "OK":
                all_ok = False
            lines.append(f"- **{r['label']}**: {status}  old={r['old_n']} new={r['new_n']} "
                         f"order_equal={r['order_ok']}")
            if r["set_diff"]:
                lines.append(f"  - 集合差异: {r['set_diff'][:10]}")
            if r["score_diffs"]:
                lines.append(f"  - 分数差异(>{SCORE_TOL}): {r['score_diffs'][:5]}")
        lines.append("")
    lines.append(f"## 结论: {'全部一致' if all_ok else '存在差异(见上)'}")
    with open(OUT, "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
