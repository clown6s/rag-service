"""
RAGAS 评估脚本

用法:
    python scripts/ragas_evaluate.py --testset data/test_qa.json --output report.json

test_qa.json 格式:
[
    {
        "question": "退款需要多久？",
        "ground_truth": "退款在7个工作日内完成",
        "answer": "根据退款政策，退款需在7个工作日内完成",
        "contexts": ["退款政策规定退款需在7个工作日内处理完毕"]
    }
]

评估指标:
    - faithfulness:        答案忠实度（是否幻觉）
    - answer_relevancy:    答案相关性
    - context_precision:   检索精度（无关内容占比）
    - context_recall:      检索召回（是否漏了关键信息）

安装:
    pip install ragas datasets
"""
import json
import argparse
import sys
from datasets import Dataset
from ragas import evaluate
from ragas.metrics import (
    faithfulness,
    answer_relevancy,
    context_precision,
    context_recall,
)


def main():
    parser = argparse.ArgumentParser(description="RAGAS 评估")
    parser.add_argument("--testset", required=True, help="测试集 JSON 文件")
    parser.add_argument("--output", default="ragas_report.json", help="输出文件")
    parser.add_argument("--threshold-faithfulness", type=float, default=0.80,
                        help="faithfulness 门禁阈值")
    parser.add_argument("--threshold-relevancy", type=float, default=0.75,
                        help="answer_relevancy 门禁阈值")
    args = parser.parse_args()

    # 加载测试集
    with open(args.testset, encoding="utf-8") as f:
        raw = json.load(f)

    if not raw:
        print("[ERROR] 测试集为空")
        sys.exit(1)

    ds = Dataset.from_list(raw)
    print(f"[评估] 测试集 {len(raw)} 条")

    # 执行评估
    results = evaluate(ds, metrics=[
        faithfulness,
        answer_relevancy,
        context_precision,
        context_recall,
    ])

    # 输出结果
    print("\n" + "=" * 50)
    print("RAGAS 评估结果")
    print("=" * 50)
    report = {}
    for metric_name, score in results.items():
        val = round(float(score), 4)
        report[metric_name] = val
        bar = "█" * int(val * 20) + "░" * (20 - int(val * 20))
        print(f"  {metric_name:25s}: {val:.4f}  {bar}")

    # 写报告
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n报告已写入: {args.output}")

    # 门禁检查
    print("\n" + "=" * 50)
    print("门禁检查")
    print("=" * 50)

    thresholds = {
        "faithfulness": args.threshold_faithfulness,
        "answer_relevancy": args.threshold_relevancy,
    }

    fails = []
    for metric, threshold in thresholds.items():
        if metric in results:
            val = results[metric]
            status = "PASS" if val >= threshold else "FAIL"
            print(f"  {metric}: {val:.4f} >= {threshold} → {status}")
            if status == "FAIL":
                fails.append(f"{metric} ({val:.4f} < {threshold})")

    if fails:
        print(f"\n[FAIL] 门禁不通过: {', '.join(fails)}")
        sys.exit(1)
    else:
        print("\n[PASS] 所有指标达标")


if __name__ == "__main__":
    main()
