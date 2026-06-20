"""
福建烟草订货平台 — 自动爬取本期货源策略表

流程:
  1. GET 登录页 → 提取验证码图片 URL
  2. GET 验证码 → ddddocr 识别
  3. POST 登录（账号 + 密码 + 验证码）
  4. 访问快讯列表 API → 找"货源策略"条目
  5. 进入详情 → 下载 .xlsx
  6. 上传到 GitHub Release

环境变量 (GitHub Secrets):
  TOBACCO_USERNAME   - 登录账号
  TOBACCO_PASSWORD   - 登录密码
  GH_TOKEN           - GitHub Personal Access Token (repo 权限)
  GITHUB_REPOSITORY  - GitHub Actions 自动注入
"""

import os
import re
import sys
import json
import datetime
import io
from pathlib import Path

import requests
import ddddocr


# ========== 配置 ==========

BASE_URL = "https://yxmall.fjycgs.com"
session = requests.Session()
session.headers.update({
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        " (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    ),
})

USERNAME = os.environ["TOBACCO_USERNAME"]
PASSWORD = os.environ["TOBACCO_PASSWORD"]
GH_TOKEN = os.environ["GH_TOKEN"]
GITHUB_REPO = os.environ.get("GITHUB_REPOSITORY", "")

ocr = ddddocr.DdddOcr(show_ad=False)
LOCAL_FILE = "supply_strategy.xlsx"


def log(msg):
    print(f"[{datetime.datetime.now():%H:%M:%S}] {msg}")


# ========== 步骤1: 登录（含验证码识别） ==========

def get_captcha():
    """
    获取验证码图片并识别
    尝试多种常见验证码路径
    """
    captcha_paths = [
        f"{BASE_URL}/captcha.jpg",
        f"{BASE_URL}/captcha",
        f"{BASE_URL}/mobile/captcha.jpg",
        f"{BASE_URL}/api/captcha",
        f"{BASE_URL}/mobile/api/captcha",
        f"{BASE_URL}/common/captcha",
        f"{BASE_URL}/kaptcha.jpg",
    ]

    for url in captcha_paths:
        # 加随机参数避免缓存
        ts = int(datetime.datetime.now().timestamp() * 1000)
        resp = session.get(f"{url}?t={ts}", timeout=10)
        if resp.status_code == 200 and len(resp.content) > 100:
            content_type = resp.headers.get("Content-Type", "")
            if "image" in content_type or resp.content[:4] in (b'\xff\xd8', b'\x89PNG', b'GIF8'):
                try:
                    result = ocr.classification(resp.content)
                    code = ''.join(c for c in result if c.isdigit())
                    if len(code) >= 3:
                        log(f"验证码识别: {result} → {code}  (from {url})")
                        return code
                except Exception as e:
                    log(f"OCR 失败: {e}")

    log("未找到验证码图片，尝试从登录页 HTML 提取...")
    return None


def find_captcha_from_page():
    """从登录页 HTML 中提取验证码图片 URL"""
    try:
        resp = session.get(f"{BASE_URL}/mobile/#/pages/index/login", timeout=15)
        # 也尝试 API 版本
        resp2 = session.get(f"{BASE_URL}/mobile/pages/index/login", timeout=15)
    except Exception:
        pass

    # 搜索 HTML 中的 img 标签和常见 captcha URL 模式
    for r in [resp, resp2] if 'resp2' in dir() else [resp]:
        text = r.text
        # 查找 img src 中含 captcha/kaptcha/verify 的
        patterns = [
            r'<img[^>]+src=["\']([^"\']*(?:captcha|kaptcha|verify|code|yzm)[^"\']*)["\']',
            r'src=["\']([^"\']*(?:captcha|kaptcha|verify|code)[^"\']*\.(?:jpg|png|jpeg|gif))["\']',
        ]
        for pat in patterns:
            matches = re.findall(pat, text, re.IGNORECASE)
            for m in matches:
                if not m.startswith("http"):
                    m = BASE_URL + (m if m.startswith("/") else "/" + m)
                log(f"找到验证码 URL: {m}")
                try:
                    ts = int(datetime.datetime.now().timestamp() * 1000)
                    img_resp = session.get(f"{m}?t={ts}", timeout=10)
                    if img_resp.status_code == 200 and len(img_resp.content) > 100:
                        result = ocr.classification(img_resp.content)
                        code = ''.join(c for c in result if c.isdigit())
                        if len(code) >= 3:
                            log(f"验证码识别: {result} → {code}")
                            return code
                except Exception as e:
                    log(f"下载/识别失败: {e}")

    return None


def login():
    """
    登录平台（自动识别验证码）

    ⚠️ 登录 API 需抓包确认。以下为常见模式，如果失败请根据实际接口调整。
    """
    log("正在登录...")

    # --- 先识别验证码 ---
    captcha = get_captcha()
    if not captcha:
        captcha = find_captcha_from_page()

    if not captcha:
        log("无法获取验证码，尝试无验证码登录...")
        captcha = ""

    log(f"验证码: {captcha}")

    # --- 尝试登录 ---
    login_payloads = [
        # 方式1: JSON
        lambda: session.post(f"{BASE_URL}/mobile/api/login", json={
            "username": USERNAME,
            "password": PASSWORD,
            "captcha": captcha,
            "code": captcha,
        }),
        # 方式2: form
        lambda: session.post(f"{BASE_URL}/mobile/api/login", data={
            "username": USERNAME,
            "password": PASSWORD,
            "captcha": captcha,
        }),
        # 方式3: app login
        lambda: session.post(f"{BASE_URL}/api/login", json={
            "username": USERNAME, "password": PASSWORD, "captcha": captcha,
        }),
        # 方式4: 其他路径
        lambda: session.post(f"{BASE_URL}/mobile/api/account/login", json={
            "username": USERNAME, "password": PASSWORD, "captcha": captcha,
        }),
    ]

    for i, fn in enumerate(login_payloads):
        try:
            resp = fn()
            if _check_login(resp):
                log(f"登录成功 ✓ (方式{i+1})")
                return True
        except Exception as e:
            log(f"方式{i+1} 失败: {e}")

    log("所有登录方式均失败")
    return False


def _check_login(resp):
    if resp.status_code != 200:
        return False
    try:
        data = resp.json()
        if data.get("code") == 0 or data.get("success"):
            return True
        # 不返回 token 但返回用户信息也算成功
        if data.get("data", {}).get("userInfo") or data.get("data", {}).get("token"):
            return True
    except Exception:
        pass
    return "token" in resp.text and len(resp.text) > 100


# ========== 步骤2: 快讯 API + 详情 + 下载 ==========

def find_notice_api():
    """
    获取快讯列表 API 地址

    优先尝试 /mobile/api/notice/list（常见 Vue SPA 后端）
    """
    column_uuid = "C9D1BD5D9F9000019EF6113B668014D4"

    urls = [
        f"{BASE_URL}/mobile/api/notice/list?column_uuid={column_uuid}&page=1&pageSize=30",
        f"{BASE_URL}/api/notice/list?column_uuid={column_uuid}&page=1&pageSize=30",
        f"{BASE_URL}/mobile/api/notice/page?columnUuid={column_uuid}&page=1&pageSize=30",
    ]

    for url in urls:
        try:
            resp = session.get(url, timeout=15)
            if resp.status_code == 200:
                data = resp.json()
                items = _extract_items(data)
                if items:
                    log(f"快讯 API: {url} ({len(items)} 条)")
                    return url, items
        except Exception:
            continue

    return None, []


def _extract_items(data):
    """从 API 响应中提取条目列表"""
    for key in ["data", "rows", "list", "result"]:
        items = data.get(key, None)
        if isinstance(items, list) and items:
            return items
    # data.rows / data.list
    inner = data.get("data", {})
    if isinstance(inner, list):
        return inner
    for key in ["rows", "list", "records", "items"]:
        items = inner.get(key, [])
        if isinstance(items, list) and items:
            return items
    return []


def find_latest_strategy(items):
    """从快讯列表中匹配本期货源策略条目"""
    today = datetime.date.today()
    month_cn = f"{today.year}年{today.month}月"

    # 1. 精确匹配: 货源 + 当月
    for item in items:
        title = item.get("title", item.get("title_name", ""))
        if not title:
            continue
        if ("货源" in title or "投放策略" in title or "策略表" in title) \
                and month_cn in title:
            item_id = item.get("id", item.get("item_id", item.get("news_id"))
            log(f"✓ 本期条目: {title}")
            return item_id

    # 2. 宽松匹配: 货源
    for item in items:
        title = item.get("title", item.get("title_name", ""))
        if "货源" in title or "投放策略" in title or "策略表" in title:
            item_id = item.get("id", item.get("item_id", item.get("news_id"))
            log(f"✓ 货源条目: {title}")
            return item_id

    return None


def get_detail_and_xlsx(item_id):
    """通过详情 API 获取 .xlsx 下载链接"""
    urls = [
        f"{BASE_URL}/mobile/api/notice/detail?item_id={item_id}",
        f"{BASE_URL}/api/notice/detail?item_id={item_id}",
        f"{BASE_URL}/mobile/api/notice/{item_id}",
    ]

    for url in urls:
        try:
            resp = session.get(url, timeout=15)
            if resp.status_code != 200:
                continue
            text = resp.text

            # 正则搜索 .xlsx/.xls 链接
            matches = re.findall(r'https?://[^\s"\'<>]+\.xlsx?', text, re.IGNORECASE)
            if matches:
                log(f"✓ xlsx: {matches[0][:120]}...")
                return matches[0]

            # 尝试 JSON
            try:
                data = resp.json()
                content = json.dumps(data, ensure_ascii=False)
                matches = re.findall(r'https?://[^\s"\'<>]+\.xlsx?', content)
                if matches:
                    return matches[0]
            except Exception:
                pass
        except Exception:
            continue

    return None


def download_xlsx(url):
    if not url:
        return False
    log(f"下载: {url[:100]}...")
    resp = session.get(url, stream=True, timeout=60)
    if resp.status_code != 200:
        log(f"下载失败: HTTP {resp.status_code}")
        return False
    with open(LOCAL_FILE, "wb") as f:
        for chunk in resp.iter_content(8192):
            f.write(chunk)
    size = Path(LOCAL_FILE).stat().st_size
    log(f"下载完成: {size:,} bytes")
    return size > 1000


# ========== 步骤3: 上传到 GitHub Release ==========

def upload_to_release():
    if not GITHUB_REPO:
        log("跳过上传 (无 GITHUB_REPOSITORY)")
        return True

    log("上传到 GitHub Release...")
    api = f"https://api.github.com/repos/{GITHUB_REPO}"
    headers = {
        "Authorization": f"Bearer {GH_TOKEN}",
        "Accept": "application/vnd.github+json",
    }

    # 获取 release 列表
    resp = requests.get(f"{api}/releases", headers=headers)
    releases = resp.json() if resp.status_code == 200 else []

    if not releases:
        today = datetime.date.today().isoformat()
        resp = requests.post(f"{api}/releases", headers=headers, json={
            "tag_name": f"v{today}",
            "name": f"策略表 {today}",
            "body": "每日自动更新卷烟货源投放策略表",
        })
        if resp.status_code not in (200, 201):
            log(f"创建 Release 失败: {resp.status_code}")
            return False
        release = resp.json()
    else:
        release = releases[0]

    # 删旧资产
    for asset in release.get("assets", []):
        requests.delete(asset["url"], headers=headers)

    # 上传新文件
    upload_url = release.get("upload_url", "").replace("{?name,label}", "")
    with open(LOCAL_FILE, "rb") as f:
        resp = requests.post(
            f"{upload_url}?name=supply_strategy.xlsx",
            headers={**headers, "Content-Type":
                      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
            data=f,
        )
        if resp.status_code in (200, 201):
            log("上传成功 ✓")
            return True
        log(f"上传失败: {resp.status_code}")
        return False


# ========== 主流程 ==========

def main():
    log("===== 烟草策略爬虫 =====")

    if not login():
        log("登录失败，退出")
        sys.exit(1)

    notice_url, items = find_notice_api()
    if not items:
        log("快讯列表为空，退出")
        sys.exit(1)

    item_id = find_latest_strategy(items)
    if not item_id:
        log("未找到货源策略条目")
        sys.exit(1)

    xlsx_url = get_detail_and_xlsx(item_id)
    if not download_xlsx(xlsx_url):
        log("下载失败")
        sys.exit(1)

    if not upload_to_release():
        log("上传失败")
        sys.exit(1)

    log("===== 完成 =====")


if __name__ == "__main__":
    main()
