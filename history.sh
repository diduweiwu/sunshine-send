#!/bin/bash

# 提取 HISTORY.md 中第一个版本（最新）的变更日志
# 遇到第二个 ## 标题时停止

in_section=false
found_first=false

while IFS= read -r line; do
    # 跳过空行
    if [[ -z "$line" ]]; then
        continue
    fi

    # 遇到第二个 ## 版本标题，结束
    if [[ "$line" == "## "* ]]; then
        if $found_first; then
            break
        fi
        found_first=true
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
