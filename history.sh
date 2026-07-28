#!/bin/bash

# 提取 HISTORY.md 中所有未发布的变更日志
# 格式: ## 版本号 -> ### 章节 -> 内容列表
# 从文件开头读取，直到遇到空行分隔的下一个版本或文件结束

in_section=false

while IFS= read -r line; do
    # 跳过空行
    if [[ -z "$line" ]]; then
        continue
    fi

    # 遇到 ## 版本标题，继续（跳过标题行本身）
    if [[ "$line" == "## "* ]]; then
        in_section=false
        continue
    fi

    # 遇到 ### 章节标题，打印并继续
    if [[ "$line" == "### "* ]]; then
        in_section=true
        echo "$line"
        continue
    fi

    # 打印列表内容
    if $in_section; then
        echo "$line"
    fi
done < HISTORY.md
