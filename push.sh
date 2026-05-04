#!/bin/bash
set -e  # 遇到错误立即退出

# 进入脚本所在目录（即 EosMesh 文件夹）
cd "$(dirname "$0")"

echo "=== 当前目录: $(pwd) ==="

# 如果已经存在 .git，询问是否删除
if [ -d ".git" ]; then
    echo "警告：已存在 .git 文件夹，将删除它重新开始！"
    rm -rf .git
fi

# 配置 Git 用户信息（如果还没配置全局，请在这里填写你的信息）
git config --global user.name "NTdebug145"
git config --global user.email "915891335@qq.com"   # 请改成你的邮箱

# 初始化仓库
git init

# 添加远程仓库（你自己的）
git remote add origin https://github.com/NTdebug145/EosMesh.git

# 获取远程分支信息
git fetch origin

# 检测远程默认分支（main 或 master）
REMOTE_BRANCH=$(git remote show origin | grep "HEAD branch" | cut -d ":" -f 2 | xargs)
if [ -z "$REMOTE_BRANCH" ]; then
    REMOTE_BRANCH="main"
fi
echo "远程默认分支: $REMOTE_BRANCH"

# 创建本地 main 分支，并基于远程分支
git checkout -b main "origin/$REMOTE_BRANCH" 2>/dev/null || git checkout -b main

# 添加所有文件（包括你修改的和压缩后的）
git add .

# 提交
git commit -m "最终提交：所有重要修改（包含压缩后的文件）"

# 推送到远程 main 分支（如果远程已有内容且不匹配，会失败，此时尝试合并）
if ! git push -u origin main; then
    echo "远程有更新，尝试合并..."
    git pull origin main --no-rebase
    git push -u origin main
fi

echo "=== 推送完成！ ==="