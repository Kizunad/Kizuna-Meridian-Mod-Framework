#!/usr/bin/env python3
"""验证文档源，并生成可审阅的 GitHub Wiki 发布文件。"""

import argparse
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
WIKI = ROOT / "docs" / "wiki"
REQUIRED_PAGES = {
    "Home",
    "Architecture",
    "Body-and-Persistence",
    "Meridian-and-Qi",
    "Technique-Addons",
    "Inner-World",
    "Host-Integration",
    "Protocol-and-Security",
    "Compatibility",
    "Roadmap",
    "Licensing",
    "Documentation",
    "_Sidebar",
    "_Footer",
}
MARKDOWN_LINK = re.compile(r"\]\(([^\s)]+)\)")


def check() -> list[Path]:
    """验证源页面和仓库入口，不读取 GitHub 登录信息。"""
    pages = sorted(WIKI.glob("*.md"))
    missing = REQUIRED_PAGES - {page.stem for page in pages}
    if missing:
        raise ValueError(f"缺少页面：{', '.join(sorted(missing))}")

    documents = [ROOT / "README.md", ROOT / "AGENTS.md", *pages]
    link_count = 0
    for document in documents:
        if document.is_symlink():
            raise ValueError(f"文档不能是符号链接：{document}")
        content = document.read_text(encoding="utf-8")
        if not content.strip() or not content.endswith("\n"):
            raise ValueError(f"文档为空或缺少末尾换行：{document.name}")
        if len(re.findall(r"^```", content, re.MULTILINE)) % 2:
            raise ValueError(f"代码块未闭合：{document.name}")
        for number, line in enumerate(content.splitlines(), start=1):
            if line.rstrip() != line:
                raise ValueError(f"行尾空白：{document.name}:{number}")

        for target in MARKDOWN_LINK.findall(content):
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            destination = (document.parent / unquote(parsed.path)).resolve()
            if not destination.is_relative_to(ROOT) or not destination.is_file():
                raise ValueError(f"无效链接：{document.name} -> {target}")
            if document.parent == WIKI and destination not in pages:
                raise ValueError(f"Wiki 外部链接请用完整仓库 URL：{target}")
            if parsed.query:
                raise ValueError(f"本地页面链接不支持 query：{target}")
            link_count += 1

    print(f"检查通过：{len(pages)} 个 Wiki 页面，{link_count} 个本地链接。")
    return pages


def render_wiki(content: str) -> str:
    """GitHub Wiki 使用页名路由，发布副本去掉相对链接的 .md 后缀。"""
    def convert(match: re.Match[str]) -> str:
        target = match.group(1)
        parsed = urlsplit(target)
        if parsed.scheme or parsed.netloc or not parsed.path.endswith(".md"):
            return match.group(0)
        result = parsed.path[:-3]
        if parsed.fragment:
            result += "#" + parsed.fragment
        return f"]({result})"

    return MARKDOWN_LINK.sub(convert, content)


def export(pages: list[Path], target: Path) -> None:
    """仅更新已存在的干净 Wiki 检出，供人审阅后自行提交与推送。"""
    target = target.resolve()
    if target == ROOT or target.is_relative_to(ROOT):
        raise ValueError("Wiki 检出应独立于主仓库，不能覆盖源目录。")
    if not (target / ".git").exists():
        raise ValueError("目标不是独立 Git 检出，请先克隆 Wiki。")
    status = subprocess.run(
        ["git", "-C", str(target), "status", "--porcelain"],
        check=True,
        text=True,
        capture_output=True,
    )
    if status.stdout.strip():
        raise ValueError("Wiki 检出有未提交修改，先审阅处理后再导出。")

    # 全部目标先校验，再生成发布副本；不删除远端独有页面。
    for page in pages:
        destination = target / page.name
        if destination.is_symlink() or destination.is_dir():
            raise ValueError(f"目标页面类型异常：{page.name}")
    for page in pages:
        destination = target / page.name
        destination.write_text(
            render_wiki(page.read_text(encoding="utf-8")), encoding="utf-8"
        )
    print(f"已导出 {len(pages)} 页至 {target}；请审阅差异后提交与推送。")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check", help="验证文档源")
    publish = commands.add_parser("export", help="导出 Wiki 页面，不提交或推送")
    publish.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        pages = check()
        if args.command == "export":
            export(pages, args.directory)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"错误：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
