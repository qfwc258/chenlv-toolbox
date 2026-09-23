#!/usr/bin/env python3
"""将构建产物 APK 发布到 GitHub 双仓（源码仓 chenlv-toolbox + 分发仓 xccapk）。

环境变量：
  GITHUB_PAT     对两仓有 contents 写权限的令牌（缺失则跳过）
  APK            待发布的 APK 本地路径
  VERSION_NAME   版本号（如 4.2.3，缺省 local）
  SHORT_SHA      短提交哈希（仅用于 release 说明）

容错：任何仓库发布失败仅打印告警，脚本始终以 0 退出，不阻断云编译。
"""
import os
import json
import sys
import urllib.request
import urllib.error


def api(method, url, data=None, pat=""):
    req = urllib.request.Request(
        url, data=json.dumps(data).encode() if data else None, method=method
    )
    req.add_header("Authorization", "Bearer " + pat)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/json")
    return urllib.request.urlopen(req, timeout=120)


def publish_one(repo, tag, apk, ver, sha, pat):
    body = (
        "## 陈律工具箱 自动构建（云编译）\n\n"
        "- Word / PPTX / PDF / 公众号排版\n"
        "- 法律查询（OFD 阅读器）\n"
        "- 工伤赔偿计算（湖南标准）\n"
        "- 生成文书、FTP、应用内更新\n"
        f"\ncommit: {sha}"
    )
    # 创建 release；若标签已存在（422）则查找并更新
    try:
        relj = json.load(
            api(
                "POST",
                f"https://api.github.com/repos/{repo}/releases",
                {"tag_name": tag, "name": f"陈律工具箱 v{ver}", "body": body, "make_latest": "true"},
                pat,
            )
        )
    except urllib.error.HTTPError as e:
        if e.code != 422:
            raise
        lst = json.load(
            api("GET", f"https://api.github.com/repos/{repo}/releases?per_page=100", pat=pat)
        )
        relj = next((x for x in lst if x.get("tag_name") == tag), None)
        if not relj:
            print(f"  ! {repo}: 无法定位 release {tag}")
            return
    # 删除同名旧资产后上传新 APK
    upload = relj.get("upload_url", "").split("{")[0]
    for a in relj.get("assets", []):
        if a.get("name") == os.path.basename(apk):
            try:
                api("DELETE", a["url"], pat=pat)
            except Exception:
                pass
    with open(apk, "rb") as f:
        req = urllib.request.Request(
            upload + "?name=" + os.path.basename(apk), data=f.read(), method="POST"
        )
        req.add_header("Authorization", "Bearer " + pat)
        req.add_header("Content-Type", "application/vnd.android.package-archive")
        urllib.request.urlopen(req, timeout=120)
    print(f"  ✓ {repo}: 已发布 {tag}")


def main():
    pat = os.environ.get("GITHUB_PAT", "")
    apk = os.environ.get("APK", "")
    ver = os.environ.get("VERSION_NAME", "") or "local"
    sha = os.environ.get("SHORT_SHA", "nogit")
    if not pat:
        print("⚠ 未配置 GITHUB_PAT，跳过 GitHub 发布（APK 已作为本平台产物保留）")
        return
    if not apk or not os.path.isfile(apk):
        print("⚠ 未找到 APK，跳过 GitHub 发布")
        return
    for r in ["qfwc258/chenlv-toolbox", "qfwc258/xccapk"]:
        tag = ("v" + ver) if r.endswith("chenlv-toolbox") else ("chenlv-toolbox-v" + ver)
        try:
            publish_one(r, tag, apk, ver, sha, pat)
        except Exception as ex:
            print(f"  ! {r}: 发布失败 - {ex}")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"发布脚本异常（已忽略）: {e}")
    sys.exit(0)
