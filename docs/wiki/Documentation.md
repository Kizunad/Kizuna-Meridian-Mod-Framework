# 文档维护与 Wiki 发布

唯一编辑来源是主仓库 `docs/wiki/`，GitHub Wiki 为发布副本。不要长期维护两套互相不同的正文。

修改身体数据、寿命、扩展 API、协议、存储或兼容范围时，同一提交更新对应页面。候选接口、已实现、已验证、已发布四种状态分别标注。

## 检查

需要 Python 3.10 或更新版本，无额外依赖：

```bash
python3 scripts/wiki.py check
```

检查必需页面、代码块闭合、相对链接、文件末尾与行尾空白。CI 执行相同检查；它不验证运行时玩法。

## 发布

先提交主仓库文档，再在旁边克隆 Wiki。GitHub 首次需在网页建立 Home 页面，启用 Wiki 开关不一定自动创建 Wiki Git 仓库。

```bash
git clone "https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework.wiki.git" "../Kizuna-Meridian-Mod-Framework.wiki"
python3 scripts/wiki.py export "../Kizuna-Meridian-Mod-Framework.wiki"
git -C "../Kizuna-Meridian-Mod-Framework.wiki" diff --check
git -C "../Kizuna-Meridian-Mod-Framework.wiki" diff
```

已有克隆时先确认干净并更新远端。导出器将页面间 `.md` 链接转换为 Wiki 链接，只写受管页面，保留其他页面；拒绝向非 Git 检出或脏 Wiki 检出导出。检查差异后，在获得发布授权的情况下正常提交并推送 Wiki 当前分支，不强推。

导出不会自动提交、推送、下载依赖或管理认证。若有人在网页修改了受管页面，应先审阅并合回文档源，再发布，不能用“源文档权威”掩盖未读的他人修改。

主仓库与 Wiki 是两个 Git 仓库，发布后分别核验远端提交与页面内容。GitHub 页面缓存可能稍晚刷新；不要把仅启用 Wiki 当成已经发布正文。
