---
name: csv-analysis
description: 分析 CSV 数据文件时使用，提供行数统计与表头识别工具
---
# CSV 分析技能

读取本技能后，可使用 csv_summary 工具快速统计 CSV 文件：

- 参数 path：相对工作区根的 CSV 文件路径
- 返回：总行数（不含表头）、列数、表头字段列表

典型流程：list_dir 定位文件 → csv_summary 统计 → 用结论回答用户。
