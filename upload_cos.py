#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
陈律工具箱（优化版）· 腾讯云 COS 上传脚本（仅上传本轮产物）
- APK 装进 chenlv-toolbox.zip（COS 禁止直发 .apk）
- 另出 chenlv-toolbox.bin 备用
- 附带优化版源码包、使用说明
- 生成预签名直链（1 年），写入 /workspace/下载链接.md
"""
import os
import sys
import shutil
import zipfile
import importlib.util
from qcloud_cos import CosConfig, CosS3Client

ROOT = "/workspace/chenlv-toolbox-full-source"
APK = os.path.join(ROOT, "app/build/outputs/apk/release/app-release.apk")
SRC_ZIP = os.path.join(ROOT, "陈律工具箱源码.zip")
STAGE = "/tmp/cos_stage"
PREFIX = "chenlv/"
LINKS_MD = "/workspace/下载链接.md"
EXPIRED = 365 * 24 * 3600

# ---- 读配置 ----
cfg_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cos_config.py")
spec = importlib.util.spec_from_file_location("cos_config", cfg_path)
cfg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cfg)
SID = cfg.COS_SECRET_ID
SKEY = cfg.COS_SECRET_KEY
BUCKET = cfg.COS_BUCKET
REGION = cfg.COS_REGION
if not (SID and SKEY and BUCKET and REGION):
    print("✗ 缺少 COS 配置"); sys.exit(1)


def pack_source():
    if os.path.exists(SRC_ZIP):
        os.remove(SRC_ZIP)
    skip_dirs = {".gradle", "__pycache__", "build", ".git"}
    with zipfile.ZipFile(SRC_ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        for base, dirs, files in os.walk(ROOT):
            dirs[:] = [d for d in dirs if d not in skip_dirs]
            for fn in files:
                full = os.path.join(base, fn)
                rel = os.path.relpath(full, ROOT)
                if rel in ("cos_config.py", "陈律工具箱源码.zip", "陈律工具箱.apk") or fn.endswith(".tmp"):
                    continue
                z.write(full, arcname=rel)
    print(f"✓ 源码打包：{os.path.basename(SRC_ZIP)}")


def prepare_apk():
    shutil.rmtree(STAGE, ignore_errors=True)
    os.makedirs(STAGE, exist_ok=True)
    zip_path = os.path.join(STAGE, "陈律工具箱.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(APK, arcname="陈律工具箱.apk")
    bin_path = os.path.join(STAGE, "chenlv-toolbox.bin")
    shutil.copyfile(APK, bin_path)
    return [
        (zip_path, "chenlv-toolbox.zip", "安装包（解压得 陈律工具箱.apk）"),
        (bin_path, "chenlv-toolbox.bin", "安装包备用（改回 .apk）"),
    ]


def main():
    client = CosS3Client(CosConfig(Region=REGION, SecretId=SID, SecretKey=SKEY))
    try:
        client.head_bucket(Bucket=BUCKET)
    except Exception as e:
        print(f"✗ 无法访问存储桶 {BUCKET}：{e}"); sys.exit(1)
    print(f"✓ 已连接存储桶：{BUCKET}（{REGION}）")

    if not os.path.exists(APK):
        print(f"✗ 找不到 APK：{APK}"); sys.exit(1)

    pack_source()
    tasks = prepare_apk()
    tasks += [
        (SRC_ZIP, "chenlv-source.zip", "优化版完整源码"),
        (os.path.join(ROOT, "使用说明.md"), "chenlv-readme.md", "使用说明"),
        (os.path.join(ROOT, "架构优化升级说明.md"), "chenlv-arch.md", "架构优化升级说明"),
    ]

    results = []
    print("\n" + "=" * 60)
    for local, name, desc in tasks:
        if not os.path.exists(local):
            print(f"✗ 跳过（不存在）：{name}"); continue
        key = PREFIX + name
        try:
            client.upload_file(Bucket=BUCKET, Key=key, LocalFilePath=local, EnableMD5=False)
            url = client.get_presigned_url(Method="GET", Bucket=BUCKET,
                                           Key=key, Expired=EXPIRED)
            size_mb = os.path.getsize(local) / 1024 / 1024
            results.append((desc, name, size_mb, url))
            print(f"✓ {name}  ({size_mb:.1f} MB)")
        except Exception as e:
            print(f"✗ 上传失败 {name}：{e}")
    print("=" * 60)

    lines = ["# 陈律工具箱（优化版）· 下载链接", "",
             "> 腾讯云 COS 预签名直链，有效期 1 年，手机浏览器可直接下载，无需登录。", "",
             "| 文件 | 说明 | 大小 | 下载 |", "|---|---|---|---|"]
    for desc, name, size_mb, url in results:
        lines.append(f"| `{name}` | {desc} | {size_mb:.1f} MB | [点击下载]({url}) |")
    lines += ["", "## 安装说明", "",
              "1. 手机浏览器打开 `chenlv-toolbox.zip` 链接下载",
              "2. 用文件管理器解压，得到 `陈律工具箱.apk`",
              "3. 点击安装（首次需在设置允许「未知来源」应用）", "",
              "> 若解压不便，可下载 `chenlv-toolbox.bin`，把扩展名改为 `.apk` 后直接安装。", "",
              "> 注：腾讯云 COS 默认域名禁止直接分发 `.apk` 文件，故做上述封装。"]
    with open(LINKS_MD, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"\n✓ 链接清单已写入：{LINKS_MD}\n")
    for desc, name, size_mb, url in results:
        print(f"【{desc}】{name}  {size_mb:.1f} MB")
        print(f"{url}\n")


if __name__ == "__main__":
    main()
