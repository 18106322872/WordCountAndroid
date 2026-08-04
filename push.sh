#!/usr/bin/env bash
# ============================================================
#  一键把项目推送到 GitHub
#  用法：在本目录右键 → Git Bash Here，然后运行：
#        bash 推送到GitHub.sh  你的仓库地址
#  例如：bash 推送到GitHub.sh  https://github.com/你的用户名/WordCountAndroid.git
# ============================================================
set -e

REPO_URL="$1"

if [ -z "$REPO_URL" ]; then
  echo "----------------------------------------------------------"
  echo "  没填仓库地址。请这样运行："
  echo "  bash 推送到GitHub.sh  https://github.com/你的用户名/仓库名.git"
  echo "----------------------------------------------------------"
  read -p "  或者现在直接粘贴你的仓库地址回车： " REPO_URL
fi

if [ -z "$REPO_URL" ]; then
  echo "仍未提供仓库地址，退出。"
  exit 1
fi

cd "$(dirname "$0")"
echo "=== 工作目录：$(pwd) ==="

# 1. 初始化（若已初始化则跳过）
if [ ! -d .git ]; then
  git init
  echo "√ 已初始化 git 仓库"
else
  echo "√ git 仓库已存在，跳过初始化"
fi

# 2. 主分支命名为 main
git branch -M main 2>/dev/null || true

# 3. 添加全部文件并提交
git add -A
if git diff --cached --quiet; then
  echo "√ 没有新的改动需要提交"
else
  git commit -m "字数统计安卓离线版：完整可构建源码"
  echo "√ 已提交"
fi

# 4. 绑定远程仓库（若已存在则更新地址）
if git remote | grep -q "^origin$"; then
  git remote set-url origin "$REPO_URL"
else
  git remote add origin "$REPO_URL"
fi
echo "√ 远程仓库已绑定：$REPO_URL"

# 5. 推送
echo "=== 开始推送到 GitHub（可能会弹出登录窗口，用浏览器授权即可） ==="
git push -u origin main

echo ""
echo "=========================================================="
echo "  推送完成！接下来："
echo "  1. 打开你的仓库网页 → 点上方 Actions 标签"
echo "  2. 能看到编译任务在自动运行（转圈 → 变绿钩）"
echo "  3. 编译好后点进任务 → 底部 Artifacts 下载 APK"
echo "=========================================================="
