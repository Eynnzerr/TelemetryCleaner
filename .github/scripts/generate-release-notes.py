#!/usr/bin/env python3
"""Generate GitHub Release notes from CHANGELOG.md with git-log fallback."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

FENCE_RE = re.compile(r"^\s*(`{3,}|~{3,})")
HTML_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
CONVENTIONAL_RE = re.compile(
    r"^(?P<type>feat|fix|perf|refactor|docs|style|chore|build|ci|test)"
    r"(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:[ \t]*(?P<desc>.+)$",
    re.IGNORECASE,
)


def run_git(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        check=False,
    )


def infer_repo() -> str | None:
    env_repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if env_repo:
        return env_repo

    result = run_git("remote", "get-url", "origin")
    if result.returncode != 0:
        return None

    url = result.stdout.strip()
    match = re.search(r"github\.com[:/](?P<repo>[^/\s]+/[^/\s]+?)(?:\.git)?$", url)
    return match.group("repo") if match else None


def strip_html_comments_for_line(line: str, in_comment: bool) -> tuple[str, bool]:
    parts: list[str] = []
    pos = 0
    length = len(line)
    while pos < length:
        if in_comment:
            end = line.find("-->", pos)
            if end == -1:
                return "".join(parts), True
            pos = end + 3
            in_comment = False
        else:
            start = line.find("<!--", pos)
            if start == -1:
                parts.append(line[pos:])
                break
            parts.append(line[pos:start])
            pos = start + 4
            in_comment = True
    return "".join(parts), in_comment


def extract_changelog_section(changelog_path: Path, tag: str) -> str | None:
    if not changelog_path.is_file():
        return None

    lines = changelog_path.read_text(encoding="utf-8").splitlines()
    bare_version = tag[1:] if tag.startswith("v") else tag
    version_pattern = rf"(?:{re.escape(tag)}|{re.escape(bare_version)})"
    target_heading_re = re.compile(
        rf"^##\s+(?:\[{version_pattern}\](?:\([^)]*\))?|{version_pattern})(?:\s+.*)?$"
    )
    section_boundary_re = re.compile(r"^#{1,2}(?:\s|$)")

    collecting = False
    fence_char: str | None = None
    fence_len = 0
    in_html_comment = False
    collected: list[str] = []

    for line in lines:
        if not in_html_comment:
            fence_match = FENCE_RE.match(line)
            if fence_match:
                marker = fence_match.group(1)
                if fence_char is None:
                    fence_char = marker[0]
                    fence_len = len(marker)
                elif marker[0] == fence_char and len(marker) >= fence_len:
                    fence_char = None
                    fence_len = 0

        if fence_char is None:
            visible_line, in_html_comment = strip_html_comments_for_line(
                line, in_html_comment
            )
            if not collecting:
                if target_heading_re.match(visible_line):
                    collecting = True
                continue
            if section_boundary_re.match(visible_line):
                break

        if collecting:
            collected.append(line)

    if not collecting:
        return None

    while collected and not collected[0].strip():
        collected.pop(0)
    while collected and not collected[-1].strip():
        collected.pop()

    body = "\n".join(collected)
    meaningful = HTML_COMMENT_RE.sub("", body).strip()
    return body if meaningful else None


def resolve_target_ref(tag: str) -> str:
    check = run_git("rev-parse", "--verify", "--quiet", f"refs/tags/{tag}^{{commit}}")
    return f"refs/tags/{tag}" if check.returncode == 0 else "HEAD"


def find_previous_tag(tag: str, target_ref: str) -> str | None:
    is_prerelease = "-" in tag.split("+", 1)[0]
    cmd = [
        "describe",
        "--tags",
        "--abbrev=0",
        "--match",
        "v*",
        "--exclude",
        tag,
    ]
    if not is_prerelease:
        cmd.extend(["--exclude", "*-*"])
    cmd.append(target_ref)

    result = run_git(*cmd)
    if result.returncode != 0:
        return None
    prev = result.stdout.strip()
    return prev or None


def collect_commits(prev_tag: str | None, target_ref: str) -> list[tuple[str, str]]:
    rev_range = f"{prev_tag}..{target_ref}" if prev_tag else target_ref
    for extra in (["--no-merges"], []):
        result = run_git("log", *extra, "--format=%h%x1f%s", rev_range)
        if result.returncode != 0:
            return []
        commits: list[tuple[str, str]] = []
        for raw_line in result.stdout.splitlines():
            if "\x1f" not in raw_line:
                continue
            sha, subject = raw_line.split("\x1f", 1)
            subject = subject.strip()
            if subject:
                commits.append((sha.strip(), subject))
        if commits:
            return commits
    return []


def format_commit_fallback(commits: list[tuple[str, str]]) -> str:
    if not commits:
        return ""

    groups: dict[str, list[str]] = {
        "Features": [],
        "Bug Fixes": [],
        "Improvements": [],
        "Other Changes": [],
    }
    matched_conventional = False

    for sha, subject in commits:
        match = CONVENTIONAL_RE.match(subject)
        if match:
            matched_conventional = True
            commit_type = match.group("type").lower()
            scope = match.group("scope")
            desc = match.group("desc").strip()
            prefix = f"**{scope}**: " if scope else ""
            entry = f"- {prefix}{desc} ({sha})"
            if commit_type == "feat":
                groups["Features"].append(entry)
            elif commit_type == "fix":
                groups["Bug Fixes"].append(entry)
            elif commit_type in {"perf", "refactor"}:
                groups["Improvements"].append(entry)
            else:
                groups["Other Changes"].append(entry)
        else:
            groups["Other Changes"].append(f"- {subject} ({sha})")

    lines = ["## What's Changed"]
    if not matched_conventional:
        lines.append("")
        lines.extend(f"- {subject} ({sha})" for sha, subject in commits)
        return "\n".join(lines)

    for title, items in groups.items():
        if items:
            lines.extend(["", f"### {title}", "", *items])
    return "\n".join(lines)


def build_full_changelog_line(repo: str | None, prev_tag: str | None, tag: str) -> str | None:
    if not repo:
        return None
    if prev_tag:
        return f"**Full Changelog**: https://github.com/{repo}/compare/{prev_tag}...{tag}"
    return f"**Full Changelog**: https://github.com/{repo}/commits/{tag}"


def warn(message: str, file_path: str | None = None) -> None:
    if os.environ.get("GITHUB_ACTIONS") == "true":
        prefix = f"::warning file={file_path}::" if file_path else "::warning::"
        print(f"{prefix}{message}", file=sys.stderr)
    else:
        print(f"[release-notes] Warning: {message}", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Extract release notes from CHANGELOG.md or fall back to git commits."
    )
    parser.add_argument("--tag", required=True, help="Release tag name (e.g. v1.5)")
    parser.add_argument(
        "--repo",
        default=None,
        help="GitHub repository in owner/name format (defaults to GITHUB_REPOSITORY or origin remote)",
    )
    parser.add_argument(
        "--changelog",
        default="CHANGELOG.md",
        help="Path to changelog markdown file (default: CHANGELOG.md)",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Write release notes to this file instead of stdout",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Fail with non-zero exit code if the tag section is missing from CHANGELOG.md",
    )
    args = parser.parse_args()

    tag = args.tag.strip()
    changelog_path = Path(args.changelog)
    repo = args.repo.strip() if args.repo else infer_repo()
    target_ref = resolve_target_ref(tag)
    prev_tag = find_previous_tag(tag, target_ref)

    body = extract_changelog_section(changelog_path, tag)
    if body is not None:
        print(
            f"[release-notes] Extracted notes for {tag} from {changelog_path}.",
            file=sys.stderr,
        )
    else:
        if args.strict:
            print(
                f"Error: No release notes section found for {tag} in {changelog_path}.",
                file=sys.stderr,
            )
            return 1
        warn(
            f"No release notes section found for {tag} in {changelog_path}; "
            "falling back to git commit history.",
            file_path=str(changelog_path),
        )
        commits = collect_commits(prev_tag, target_ref)
        body = format_commit_fallback(commits)

    parts: list[str] = []
    if body:
        parts.append(body)

    full_changelog = build_full_changelog_line(repo, prev_tag, tag)
    if full_changelog and "**Full Changelog**:" not in body:
        parts.append(full_changelog)

    output_text = "\n\n".join(parts) + ("\n" if parts else "")

    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(output_text, encoding="utf-8")
    else:
        sys.stdout.write(output_text)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
