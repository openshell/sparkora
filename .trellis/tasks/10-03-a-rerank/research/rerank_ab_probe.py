#!/usr/bin/env python3
"""A rerank A/B 评估 harness（10-03-a-rerank）。

- baseline = 向量召回原序（curated 候选集，模拟纯余弦 top-K；真实链路原序即按分数降序）
- rerank   = LlmReranker 同款 prompt（rag/rerank-system.st 文案）+ axonhub 真实模型，输出 order
- 指标：相关候选排名 / MRR / top-1 命中；order 完整性（应用 applyOrder 同款后验校验）

不打印任何密钥。用法：python3 rerank_ab_probe.py [out.json]
"""
import json, os, sys, urllib.request

BASE = "https://axo.caiqz.cn"
# .env 路径：优先环境变量，其次从脚本位置回溯到仓库根（<repo>/.trellis/tasks/10-03-a-rerank/research/ → 上溯 4 层）
ENV_PATH = os.environ.get("SPARKORA_ENV",
                          os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                       "..", "..", "..", "..", ".env"))
ENV_PATH = os.path.abspath(ENV_PATH)
ENV = {}
for line in open(ENV_PATH):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    ENV[k] = v
BASE = ENV["AI_BASE_URL"].rstrip("/")
KEY = ENV["AI_API_KEY"]
MODEL = ENV["AI_MODEL"]

# 代表 query + 候选集（来源真实三域；rel=标注的相关候选下标，基于库内已知事实）
CASES = [
    {
        "key": "NEWS_charge",
        "query": "比亚迪闪充电池的充电倍率是多少",
        "cands": [
            ("CAR", "车型数据 海狮08 参数分组：动力\n前电机最大功率（kW）：200"),
            ("KB", "通用知识 充电功率常识：7kW 家充为交流慢充。"),
            ("NEWS", "官方新闻 比亚迪闪充电池：充电倍率达到10C，是全球量产最大充电倍率。"),
            ("NEWS", "官方新闻 比亚迪海外销量再创历史新高。"),
            ("CAR", "车型数据 大唐EV 价格区间：239,900 - 279,900"),
        ],
        "rel": {2},
    },
    {
        "key": "CAR_param_range",
        "query": "大唐EV 纯电续航里程是多少",
        "cands": [
            ("NEWS", "官方新闻 比亚迪海外销量再创历史新高。"),
            ("KB", "通用知识 家用充电桩选择要点：看车型最大充电功率。"),
            ("CAR", "车型数据 大唐EV 参数分组：续航\nCLTC纯电续航里程（km）：700"),
            ("CAR", "车型数据 汉EV 购车权益：首任车主终身质保。"),
            ("NEWS", "官方新闻 比亚迪闪充站建设目标。"),
        ],
        "rel": {2},
    },
    {
        "key": "KB_text",
        "query": "家用充电桩怎么选择",
        "cands": [
            ("CAR", "车型数据 海狮08 前电机最大功率（kW）：200"),
            ("NEWS", "官方新闻 比亚迪海外销量再创历史新高。"),
            ("KB", "通用知识 家用充电桩选择要点：按车型最大充电功率选 7kW/11kW；固定车位优先。"),
            ("CAR", "车型数据 大唐EV 价格区间：239,900 - 279,900"),
            ("NEWS", "官方新闻 比亚迪闪充电池充电倍率10C。"),
        ],
        "rel": {2},
    },
    {
        "key": "MIX_platform",
        "query": "超级e平台兆瓦闪充的技术参数",
        "cands": [
            ("KB", "通用知识 家用充电桩选择要点。"),
            ("CAR", "车型数据 大唐EV 价格区间：239,900 - 279,900"),
            ("NEWS", "官方新闻 超级e平台：全球首个量产乘用车全域千伏高压架构，闪充功率1兆瓦（1000kW），充电倍率10C。"),
            ("NEWS", "官方新闻 比亚迪海外销量再创历史新高。"),
            ("CAR", "车型数据 海狮08 续航（km）：700"),
        ],
        "rel": {2},
    },
    {
        "key": "CAR_price",
        "query": "海豹07EV 价格区间",
        "cands": [
            ("NEWS", "官方新闻 比亚迪闪充站建设目标。"),
            ("CAR", "车型数据 海豹07EV 价格区间：18.98 - 23.98 万元"),
            ("KB", "通用知识 购车权益常识。"),
            ("CAR", "车型数据 汉EV 购车权益。"),
            ("NEWS", "官方新闻 比亚迪海外销量再创历史新高。"),
        ],
        "rel": {1},
    },
]


def render_system(query, cands):
    lines = "\n".join(
        f"[{i}] 来源={s} 文本={t}" for i, (s, t) in enumerate(cands))
    return ("你是知识库检索重排助手。给定一个查询和一组候选知识块(已用序号 0..N-1 标注),\n"
            "请按「与查询的相关性」从高到低对候选排序,只返回 JSON 对象,不要任何额外文字。\n"
            "规则:\n- order 必须是候选序号的完整排列:包含 0..N-1 每个序号恰好一次,不得缺失、不得重复、不得越界。\n"
            "- 相关性判断依据:候选文本是否直接回答查询、是否含查询涉及的关键参数/实体/数值;仅措辞相似但主题无关的排后。\n"
            "- 数值不得改写、不得脑补;只做排序。\n\n"
            f"查询:{query}\n\n候选:\n{lines}\n\n只输出 JSON: {{\"order\":[整数数组]}}")


def call_model(query, cands):
    body = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": render_system(query, cands)},
            {"role": "user", "content": "请输出重排后的 JSON 对象。"},
        ],
        "max_tokens": 2048,
        "response_format": {"type": "json_object"},
    }
    req = urllib.request.Request(BASE + "/v1/chat/completions",
                                data=json.dumps(body).encode(),
                                headers={"Authorization": "Bearer " + KEY,
                                         "Content-Type": "application/json"})
    d = json.loads(urllib.request.urlopen(req, timeout=120).read().decode())
    content = d["choices"][0]["message"].get("content") or "{}"
    return json.loads(content).get("order") or []


def apply_order(n, order):
    """与 LlmReranker.applyOrder 同款后验校验：越界/重复丢弃；缺项按原序补尾。"""
    used, out = set(), []
    for i in order:
        if isinstance(i, int) and 0 <= i < n and i not in used:
            used.add(i); out.append(i)
    if not out:
        return None
    out += [i for i in range(n) if i not in used]
    return out


def rank_of(order, rel):
    for pos, idx in enumerate(order):
        if idx in rel:
            return pos + 1
    return None


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/opencode/rerank-ab-result.json"
    rows, base_rr, re_rr, base_top1, re_top1 = [], 0.0, 0.0, 0, 0
    valid_orders = 0
    for c in CASES:
        n = len(c["cands"])
        baseline = list(range(n))
        raw = call_model(c["query"], c["cands"])
        order = apply_order(n, raw)
        complete = order is not None and sorted(order) == list(range(n)) and len(raw) == n
        if complete:
            valid_orders += 1
        if order is None:
            order = baseline
        br, rr = rank_of(baseline, c["rel"]), rank_of(order, c["rel"])
        base_rr += 1.0 / br if br else 0.0
        re_rr += 1.0 / rr if rr else 0.0
        base_top1 += 1 if br == 1 else 0
        re_top1 += 1 if rr == 1 else 0
        rows.append({"key": c["key"], "query": c["query"], "n": n,
                     "rawOrder": raw, "appliedOrder": order, "complete": complete,
                     "baselineRank": br, "rerankRank": rr})
        print(f"[{c['key']}] raw={raw} applied={order} baselineRank={br} rerankRank={rr} complete={complete}",
              flush=True)
    k = len(CASES)
    summary = {"cases": k, "validOrderRate": valid_orders / k,
               "baselineTop1": base_top1 / k, "rerankTop1": re_top1 / k,
               "baselineMRR": base_rr / k, "rerankMRR": re_rr / k}
    json.dump({"summary": summary, "rows": rows}, open(out_path, "w"),
              ensure_ascii=False, indent=2)
    print("SUMMARY", json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
