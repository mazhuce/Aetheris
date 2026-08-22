# Git 开发工作流

## 首次配置身份

```bash
git config user.name  "你的名字"
git config user.email "你的邮箱"
```

## 分支模型（简化 Git Flow）

| 分支 | 用途 |
| --- | --- |
| `main` | 稳定发布分支 |
| `develop` | 日常集成分支 |
| `feature/xxx` | 新功能开发 |
| `fix/xxx` | 缺陷修复 |

## 日常开发节奏

```bash
# 1. 拉取最新代码
git pull --rebase origin develop

# 2. 开分支
git switch -c feature/metadata-service

# 3. 边写边提交（小步提交）
git status
git diff
git add services/metadata
git commit -m "feat(metadata): 初始化元数据服务"

# 4. 推送分支
git push -u origin feature/metadata-service

# 5. 发起合并请求 / PR，评审后合并到 develop
```

## 提交信息规范（Conventional Commits）

格式：`type(scope): subject`

- `feat`：新功能
- `fix`：缺陷修复
- `docs`：文档
- `refactor`：重构
- `test`：测试
- `chore`：构建/工具/杂项

示例：

```text
feat(ingestion): 增加 MySQL 数据源接入
fix(auth): 修复 token 刷新失败
```

## 回退与查看

```bash
git log --oneline --graph --all
git diff HEAD~1
git restore --staged <file>   # 取消暂存
git restore <file>            # 丢弃工作区改动
```
