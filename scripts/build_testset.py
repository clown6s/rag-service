"""从审计日志半自动构建 Ragas 测试集

用法（仅标准库，任意 Python 可跑）:
    python scripts/build_testset.py [--audit-dir logs/audit] [--output data/testset.json]
                                    [--rag-url http://127.0.0.1:8080] [--max 20]

流程:
1. 读 logs/audit/audit-*.jsonl，取 endpoint=agent/chat 且 sources 非空且成功的问答
2. 对每个问题:
   - 重放 /api/rag/agent/chat 生成完整 answer（审计里存的是 500 字截断预览）
   - 调 /api/rag/search 回放检索得到 contexts（前 5 条命中片段）
3. 输出 data/testset.json（question/reference/answer/contexts）；
   reference 留空待人工标注；--snapshot-reference 可把当前 contexts 快照为参考基线
   （回归检测模式：后续改切分/检索参数后跑评测，reference 不变，掉分即回归）

完成后提交评测（ai-sidecar）:
    curl -X POST http://127.0.0.1:8081/evaluate -H "Content-Type: application/json" -d @data/testset.json
"""
import argparse
import glob
import json
import os
import urllib.request

NO_ANSWER = "根据现有资料，暂无法回答该问题"


def read_audit_events(audit_dir: str) -> list:
    """读审计 JSONL，返回 (query, sources) 去重列表（只取有效知识问答）"""
    events = {}
    for path in sorted(glob.glob(os.path.join(audit_dir, "audit-*.jsonl"))):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    e = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if (e.get("endpoint") == "agent/chat"
                        and e.get("status") == "SUCCESS"
                        and e.get("sources")):
                    events[e["query"]] = e  # 同 query 去重，保留最新
    return list(events.values())


def post_json(url: str, payload: dict, timeout: int = 180) -> dict:
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def build(args) -> list:
    events = read_audit_events(args.audit_dir)[: args.max]
    testset = []
    skipped = 0
    for e in events:
        query = e["query"]
        # 1. 重放 agent 对话拿完整答案（顺带确认当前系统仍能回答）
        chat_resp = post_json(f"{args.rag_url}/api/rag/agent/chat", {"query": query})
        data = chat_resp.get("data") or {}
        answer = data.get("answer") or ""
        if not answer or answer.startswith(NO_ANSWER):
            skipped += 1
            continue
        # 2. 重放检索拿 contexts
        search_resp = post_json(f"{args.rag_url}/api/rag/search", {"query": query})
        hits = (search_resp.get("data") or {}).get("hits") or []
        contexts = [h["text"] for h in hits[:5] if h.get("text")]
        if not contexts:
            skipped += 1
            continue
        # ragas 0.4 字段名为 reference（旧版 ground_truth 已改名，缺失则 context_recall 恒为 0）
        reference = contexts if args.snapshot_reference else ""
        testset.append({
            "question": query,
            "reference": reference,   # 参考上下文（人工标注或 --snapshot-reference 快照）
            "answer": answer,
            "contexts": contexts,
        })
        print(f"[+] {query}  ->  {len(contexts)} contexts")
    print(f"\n共 {len(testset)} 条（跳过 {skipped} 条：兜底回答或无检索结果）")
    return testset


def main():
    parser = argparse.ArgumentParser(description="从审计日志构建 Ragas 测试集")
    parser.add_argument("--audit-dir", default="logs/audit")
    parser.add_argument("--output", default="data/testset.json")
    parser.add_argument("--rag-url", default="http://127.0.0.1:8080")
    parser.add_argument("--max", type=int, default=20)
    parser.add_argument("--snapshot-reference", action="store_true",
                        help="把当前 contexts 快照为 reference（回归基线模式）")
    args = parser.parse_args()

    testset = build(args)
    if not testset:
        print("[WARN] 没有可用的问答对（先正常使用问答，审计日志积累后重跑）")
        return

    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(testset, f, ensure_ascii=False, indent=2)
    print(f"已写入: {args.output}")
    print(f"提交评测: curl -X POST http://127.0.0.1:8081/evaluate "
          f'-H "Content-Type: application/json" -d @{args.output}')


if __name__ == "__main__":
    main()
