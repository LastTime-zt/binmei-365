# -*- coding: utf-8 -*-
"""
行藏有度 注册码管理器 GUI (与 APP License.java 共享密钥)
生成: 输入设备码 + 有效期 -> 生成注册码, 支持复制
解密: 粘贴注册码 -> 显示绑定设备/到期时间/状态
"""
import datetime
import os
import re
import threading
import tkinter as tk
from tkinter import ttk, messagebox
from Crypto.Cipher import AES  # pip install pycryptodome

KEY = b"bM@2026!Lx#Yd$Kz"   # 与 License.java 的 KEY 一致
B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


# ---------------- 核心算法(与 keygen_license.py 相同) ----------------

def base32_encode(data: bytes) -> str:
    bitbuf, bits, out = 0, 0, []
    for b in data:
        bitbuf = (bitbuf << 8) | b
        bits += 8
        while bits >= 5:
            out.append(B32[(bitbuf >> (bits - 5)) & 31])
            bits -= 5
    if bits:
        out.append(B32[(bitbuf << (5 - bits)) & 31])
    return "".join(out)


def base32_decode(s: str) -> bytes:
    bitbuf, bits, out = 0, 0, []
    for ch in s.upper():
        v = B32.find(ch)
        if v < 0:
            raise ValueError("非法字符: " + ch)
        bitbuf = (bitbuf << 5) | v
        bits += 5
        if bits >= 8:
            out.append((bitbuf >> (bits - 8)) & 0xFF)
            bits -= 8
    return bytes(out)


def pad(b: bytes) -> bytes:
    n = 16 - len(b) % 16
    return b + bytes([n]) * n


def unpad(b: bytes) -> bytes:
    return b[:-b[-1]]


def encrypt(plain: str) -> str:
    return base32_encode(AES.new(KEY, AES.MODE_ECB).encrypt(pad(plain.encode())))


def decrypt(code: str) -> str:
    code = code.replace("-", "").replace(" ", "").upper()
    return unpad(AES.new(KEY, AES.MODE_ECB).decrypt(base32_decode(code))).decode()


def make_code(device_id: str, expire: str) -> str:
    raw = encrypt(f"1{device_id.upper()}{expire}")
    return "-".join(raw[i:i + 4] for i in range(0, len(raw), 4))


def fmt_expire(exp: str) -> str:
    return datetime.datetime.strptime(exp, "%y%m%d").strftime("%Y-%m-%d") + " 23:59:59"


# ---------------- 云端 (GitHub license-db 仓库) ----------------

CLOUD_REPO = "LastTime-zt/license-db"
CLOUD_PATH = "licenses.json"
CLOUD_RAW = [
    f"https://raw.githubusercontent.com/{CLOUD_REPO}/main/{CLOUD_PATH}",
    f"https://ghfast.top/https://raw.githubusercontent.com/{CLOUD_REPO}/main/{CLOUD_PATH}",
    f"https://gh-proxy.com/https://raw.githubusercontent.com/{CLOUD_REPO}/main/{CLOUD_PATH}",
]
# 直连失败时依次尝试的代理
PROXIES = ["http://192.168.1.7:7890",
           "http://12349876:12349876@a.rr2.kdns.fr:10800"]


def _open_url(url: str, data=None, headers=None, method="GET", timeout=15):
    import urllib.request, urllib.error
    attempts = [None] + PROXIES   # 先直连, 再走代理
    last = None
    for p in attempts:
        try:
            handlers = [urllib.request.ProxyHandler(
                {"http": p, "https": p})] if p else []
            opener = urllib.request.build_opener(*handlers)
            req = urllib.request.Request(url, data=data, method=method)
            for k, v in (headers or {}).items():
                req.add_header(k, v)
            return opener.open(req, timeout=timeout)
        except Exception as e:   # noqa: BLE001
            last = e
    raise last


def cloud_get(token: str) -> dict:
    """拉取云端 licenses.json, 返回 (dict, sha)"""
    import base64, json
    if token:
        r = _open_url(f"https://api.github.com/repos/{CLOUD_REPO}/contents/{CLOUD_PATH}",
                      headers={"Authorization": f"Bearer {token}",
                               "Accept": "application/vnd.github+json"})
        info = json.loads(r.read())
        return json.loads(base64.b64decode(info["content"])), info.get("sha")
    last = None
    for u in CLOUD_RAW:
        try:
            return json.loads(_open_url(u, timeout=10).read()), None
        except Exception as e:   # noqa: BLE001
            last = e
    raise last


def cloud_put(token: str, data: dict, sha=None):
    """写入云端 licenses.json (需要 PAT)"""
    import base64 as b64, json
    body = {"message": "update licenses.json",
            "content": b64.b64encode(
                json.dumps(data, ensure_ascii=False, indent=2).encode()).decode()}
    if sha:
        body["sha"] = sha
    r = _open_url(f"https://api.github.com/repos/{CLOUD_REPO}/contents/{CLOUD_PATH}",
                  data=json.dumps(body).encode(),
                  headers={"Authorization": f"Bearer {token}",
                           "Accept": "application/vnd.github+json"},
                  method="PUT")
    return r.status


# ---------------- GUI ----------------

COLOR_BG = "#F2F5F9"
COLOR_CARD = "#FFFFFF"
COLOR_PRIMARY = "#1A66C2"
COLOR_PRIMARY_HOVER = "#1554A0"
COLOR_BORDER = "#E3E8F0"
COLOR_OK = "#0A7D32"
COLOR_ERR = "#CC3333"


def setup_style():
    style = ttk.Style()
    try:
        style.theme_use("clam")
    except tk.TclError:
        pass
    style.configure(".", background=COLOR_BG, font=("Microsoft YaHei UI", 10))
    style.configure("TNotebook", background=COLOR_BG, borderwidth=0)
    style.configure("TNotebook.Tab", padding=(18, 8),
                    background="#E8ECF2", foreground="#555555")
    style.map("TNotebook.Tab",
              background=[("selected", COLOR_PRIMARY)],
              foreground=[("selected", "#FFFFFF")])
    style.configure("Card.TFrame", background=COLOR_CARD, relief="flat")
    style.configure("CardTitle.TLabel", background=COLOR_CARD,
                    foreground=COLOR_PRIMARY, font=("Microsoft YaHei UI", 11, "bold"))
    style.configure("Card.TLabel", background=COLOR_CARD, foreground="#444444")
    style.configure("Info.TLabel", background=COLOR_CARD, foreground="#8A94A0",
                    font=("Microsoft YaHei UI", 9))
    style.configure("Primary.TButton", background=COLOR_PRIMARY, foreground="#FFFFFF",
                    borderwidth=0, focusthickness=0, padding=(16, 8))
    style.map("Primary.TButton",
              background=[("active", COLOR_PRIMARY_HOVER), ("disabled", "#9DBCE4")])
    style.configure("TButton", padding=(12, 6), borderwidth=1)
    style.configure("TEntry", fieldbackground="#FFFFFF", bordercolor=COLOR_BORDER,
                    lightcolor=COLOR_BORDER, padding=4)
    style.configure("TRadiobutton", background=COLOR_CARD)
    style.configure("Treeview", background="#FFFFFF", fieldbackground="#FFFFFF",
                    rowheight=26, borderwidth=0)
    style.configure("Treeview.Heading", background="#EDF3FC",
                    font=("Microsoft YaHei UI", 9, "bold"))
    style.configure("Status.Treeview", background="#FFFFFF")


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("行藏有度 · 注册码管理器")
        self.resizable(False, False)
        self.configure(bg=COLOR_BG, padx=14, pady=12)
        setup_style()
        self._build()
        self.eval(f"tk::PlaceWindow {self.winfo_toplevel()} center")

    # ---- 界面 ----
    def _build(self):
        nb = ttk.Notebook(self)
        nb.add(self._tab_gen(), text=" 生成注册码 ")
        nb.add(self._tab_dec(), text=" 解密注册码 ")
        nb.add(self._tab_cloud(), text=" 云端激活管理 ")
        nb.pack(fill="both", expand=True)

    def _card(self, parent, title=None):
        outer = tk.Frame(parent, bg=COLOR_BORDER)
        inner = ttk.Frame(outer, style="Card.TFrame", padding=14)
        inner.pack(fill="both", expand=True, padx=1, pady=1)
        outer.pack(fill="x", pady=(0, 10))
        if title:
            ttk.Label(inner, text=title, style="CardTitle.TLabel").pack(anchor="w", pady=(0, 6))
        return inner

    def _tab_gen(self):
        f = ttk.Frame(padding=4, style="Card.TFrame")
        self._wrap_bg(f)

        card1 = self._card(f, "生成")
        row = ttk.Frame(card1, style="Card.TFrame")
        row.pack(fill="x", pady=3)
        ttk.Label(row, text="设备码:", style="Card.TLabel").pack(side="left")
        self.e_dev = ttk.Entry(row, width=26, font=("Consolas", 11))
        self.e_dev.pack(side="left", padx=(8, 16))

        ttk.Label(row, text="有效期:", style="Card.TLabel").pack(side="left")
        self.v_mode = tk.StringVar(value="days")
        self.e_days = ttk.Entry(row, width=7)
        self.e_days.insert(0, "365")
        ttk.Radiobutton(row, text="天数", variable=self.v_mode, value="days",
                        style="Card.TRadiobutton").pack(side="left", padx=(8, 2))
        self.e_days.pack(side="left", padx=(0, 12))
        self.e_date = ttk.Entry(row, width=12)
        ttk.Radiobutton(row, text="到期日期(YYYY-MM-DD)", variable=self.v_mode,
                        value="date", style="Card.TRadiobutton").pack(side="left", padx=(8, 2))
        self.e_date.pack(side="left")

        b = ttk.Button(card1, text="生成注册码", command=self.on_gen, style="Primary.TButton")
        b.pack(fill="x", pady=(10, 2))

        card2 = self._card(f, "结果")
        self.t_code = tk.Text(card2, height=3, font=("Consolas", 12), wrap="char",
                              relief="flat", background="#F7F9FC",
                              highlightthickness=1, highlightbackground=COLOR_BORDER)
        self.t_code.pack(fill="x")
        self.t_code.bind("<Key>", lambda e: "break")   # 只读

        bf = ttk.Frame(card2, style="Card.TFrame")
        bf.pack(fill="x", pady=(4, 0))
        ttk.Button(bf, text="复制", command=self.on_copy).pack(side="right")
        self.l_gen_info = ttk.Label(card2, text="", foreground=COLOR_OK,
                                    style="Card.TLabel")
        self.l_gen_info.pack(anchor="w", pady=(2, 0))

        # 历史记录
        card3 = self._card(f, "历史记录 (双击复制注册码)")
        cols = ("id", "time", "dev", "expire", "days", "code", "status")
        self.tv = ttk.Treeview(card3, columns=cols, show="headings", height=8)
        for c, w, t in zip(cols, (40, 130, 85, 100, 62, 290, 64),
                           ("#", "授权时间", "设备码", "到期日", "时长", "注册码", "状态")):
            self.tv.heading(c, text=t)
            self.tv.column(c, width=w, anchor="w")
        self.tv.pack(fill="x")
        self.tv.bind("<Double-1>", self.on_hist_copy)
        self.tv.tag_configure("expired", foreground="#B0B6BD")
        self.tv.tag_configure("active", foreground=COLOR_OK)
        bf2 = ttk.Frame(card3, style="Card.TFrame")
        bf2.pack(fill="x", pady=(6, 0))
        ttk.Button(bf2, text="标记已续期", command=self.on_hist_renew).pack(side="left", padx=2)
        ttk.Button(bf2, text="备注", command=self.on_hist_note).pack(side="left", padx=2)
        ttk.Button(bf2, text="详情", command=self.on_hist_detail).pack(side="left", padx=2)
        ttk.Button(bf2, text="删除选中记录", command=self.on_hist_del).pack(side="right")
        self.load_hist()
        return f

    def _wrap_bg(self, _f):
        pass  # tab 底色已由 Card.TFrame 统一

    def _tab_dec(self):
        f = ttk.Frame(padding=4, style="Card.TFrame")

        card = self._card(f, "解密注册码")
        self.e_code = ttk.Entry(card, width=44, font=("Consolas", 11))
        self.e_code.pack(fill="x", pady=3)

        ttk.Button(card, text="解密", command=self.on_dec,
                   style="Primary.TButton").pack(fill="x", pady=(8, 2))

        self.l_dec = tk.Text(card, height=5, font=("Microsoft YaHei UI", 11), wrap="word",
                             relief="flat", background="#F7F9FC",
                             highlightthickness=1, highlightbackground=COLOR_BORDER)
        self.l_dec.pack(fill="x", pady=(6, 0))
        self.l_dec.bind("<Key>", lambda e: "break")
        return f

    # ---- 云端激活管理 ----
    def _token(self):
        with self._db() as conn:
            row = conn.execute(
                "SELECT value FROM kv WHERE k='admin_pat'").fetchone()
        tok = (row[0] if row else "").strip()
        # 本机无 PAT 时, 从 api key.txt 自动导入
        if not tok:
            try:
                txt = open(os.path.join(
                    os.path.dirname(os.path.abspath(__file__)),
                    "api key.txt"), encoding="utf-8").read()
                m = re.search(r"(github_pat_[A-Za-z0-9_]+)", txt)
                if m:
                    tok = m.group(1)
                    self._save_token(tok)
            except OSError:
                pass
        return tok

    def _save_token(self, tok):
        with self._db() as conn:
            conn.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, value TEXT)")
            conn.execute("INSERT OR REPLACE INTO kv(k, value) VALUES('admin_pat',?)",
                         (tok,))

    def _tab_cloud(self):
        f = ttk.Frame(padding=4, style="Card.TFrame")

        card0 = self._card(f, "管理员设置 (PAT 仅存本机)")
        row0 = ttk.Frame(card0, style="Card.TFrame")
        row0.pack(fill="x")
        ttk.Label(row0, text="GitHub PAT:", style="Card.TLabel").pack(side="left")
        self.e_pat = ttk.Entry(row0, width=52, show="*")
        self.e_pat.pack(side="left", padx=8, fill="x", expand=True)
        self.e_pat.insert(0, self._token())
        ttk.Button(card0, text="保存PAT", command=self.on_save_pat).pack(
            anchor="e", pady=(4, 0))

        card = self._card(f, "云端激活记录 (license-db)")
        cols = ("code", "dev", "expire", "status", "account", "pwd", "note", "updated")
        self.tv_cloud = ttk.Treeview(card, columns=cols, show="headings", height=12)
        for c, w, t in zip(cols, (250, 80, 90, 62, 120, 90, 110, 140),
                           ("注册码", "设备码", "到期日", "状态", "绑定账号", "密码", "备注", "更新时间")):
            self.tv_cloud.heading(c, text=t)
            self.tv_cloud.column(c, width=w, anchor="w")
        self.tv_cloud.pack(fill="x")
        self.tv_cloud.tag_configure("blocked", foreground=COLOR_ERR)
        self.tv_cloud.tag_configure("active", foreground=COLOR_OK)

        bf = ttk.Frame(card, style="Card.TFrame")
        bf.pack(fill="x", pady=(6, 0))
        ttk.Button(bf, text="刷新云端", command=self.on_cloud_refresh,
                   style="Primary.TButton").pack(side="left", padx=2)
        ttk.Button(bf, text="登记选中历史记录", command=self.on_cloud_push).pack(
            side="left", padx=2)
        ttk.Button(bf, text="一键同步(云端为主)", command=self.on_cloud_sync,
                   style="Primary.TButton").pack(side="left", padx=2)
        ttk.Button(bf, text="停用(吊销)", command=lambda: self.on_cloud_toggle("blocked")).pack(
            side="left", padx=2)
        ttk.Button(bf, text="重新启用", command=lambda: self.on_cloud_toggle("active")).pack(
            side="left", padx=2)
        ttk.Button(bf, text="改到期(续期)", command=self.on_cloud_edit_expire).pack(
            side="left", padx=2)
        ttk.Button(bf, text="解绑账号", command=self.on_cloud_unbind).pack(side="left", padx=2)
        ttk.Button(bf, text="云端删除", command=self.on_cloud_del).pack(side="left", padx=2)

        self.l_cloud = ttk.Label(card, text="", style="Card.TLabel")
        self.l_cloud.pack(anchor="w", pady=(4, 0))
        return f

    def on_save_pat(self):
        self._save_token(self.e_pat.get().strip())
        self.l_cloud.config(text="PAT 已保存到本机", foreground=COLOR_OK)

    def _cloud_records(self):
        """返回 (records, sha); records 为 list"""
        data, sha = cloud_get(self._token())
        return data.get("licenses", []), sha

    def on_cloud_refresh(self):
        try:
            recs, _ = self._cloud_records()
        except Exception as e:   # noqa: BLE001
            self.l_cloud.config(text=f"刷新失败: {e}", foreground=COLOR_ERR)
            return
        self.tv_cloud.delete(*self.tv_cloud.get_children())
        now = datetime.datetime.now().strftime("%y%m%d")
        bound = 0
        for r in recs:
            st = r.get("status", "active")
            exp = r.get("expire", "")
            acc = r.get("account", "")
            if acc:
                bound += 1
            tag = "blocked" if st == "blocked" else (
                "active" if exp >= now else "")
            self.tv_cloud.insert("", "end", values=(
                r.get("code", ""), r.get("device", ""),
                fmt_expire(exp) if exp else "-",
                st + ("(已过期)" if exp and exp < now and st == "active" else ""),
                acc, r.get("pwd", ""),
                r.get("note", ""), r.get("updated_at", "")))
        self.l_cloud.config(
            text=f"共 {len(recs)} 条记录, 已绑定账号 {bound} 条", foreground=COLOR_OK)

    def on_cloud_unbind(self):
        """解绑: 清除选中记录的 account/pwd, 该码可重新绑定任意账号"""
        item = self.tv_cloud.focus()
        if not item:
            messagebox.showinfo("提示", "先选中一条云端记录", parent=self)
            return
        vals = self.tv_cloud.item(item, "values")
        code, acc = vals[0], vals[4]
        if not acc:
            messagebox.showinfo("提示", "该记录未绑定账号", parent=self)
            return
        if not messagebox.askyesno(
                "确认解绑", f"清除该注册码的账号绑定?\n{code}\n账号: {acc}",
                parent=self):
            return
        try:
            recs, sha = self._cloud_records()
            for rec in recs:
                if rec.get("code", "") == code:
                    rec.pop("account", None)
                    rec.pop("pwd", None)
                    rec["updated_at"] = datetime.datetime.now().strftime(
                        "%Y-%m-%d %H:%M:%S")
            cloud_put(self._token(), {"licenses": recs}, sha)
            self.on_cloud_refresh()
            self.l_cloud.config(text="已解绑, 该码可重新绑定账号", foreground=COLOR_OK)
        except Exception as e:   # noqa: BLE001
            messagebox.showerror("失败", f"解绑失败:\n{e}", parent=self)

    def on_cloud_push(self, rec=None, quiet=False):
        """登记注册码到云端(status=active); rec 为历史记录 dict; quiet 静默自动同步"""
        r = rec or self._sel()
        if not r:
            return
        try:
            recs, sha = self._cloud_records()
        except Exception as e:   # noqa: BLE001
            if not quiet:
                messagebox.showerror("失败", f"拉取云端失败:\n{e}", parent=self)
            return
        norm = r["code"].replace("-", "").upper()
        if any(x.get("code", "").replace("-", "").upper() == norm for x in recs):
            if not quiet:
                messagebox.showinfo("提示", "该注册码已在云端", parent=self)
            return
        recs.append({"code": r["code"], "device": r["dev"],
                     "expire": r["exp_ts"][:6], "status": "active",
                     "note": r["note"] or "",
                     "updated_at": datetime.datetime.now().strftime(
                         "%Y-%m-%d %H:%M:%S")})
        try:
            cloud_put(self._token(), {"licenses": recs}, sha)
            self.on_cloud_refresh()
            self.l_cloud.config(text=f"已登记记录 #{r['id']}", foreground=COLOR_OK)
        except Exception as e:   # noqa: BLE001
            if not quiet:
                messagebox.showerror("失败", f"写入云端失败:\n{e}", parent=self)

    def on_cloud_sync(self):
        """一键同步(云端为主): 本地缺的补到云端; 云端 blocked 状态回写本地"""
        try:
            recs, sha = self._cloud_records()
        except Exception as e:   # noqa: BLE001
            messagebox.showerror("失败", f"拉取云端失败:\n{e}", parent=self)
            return
        cloud_norm = {x.get("code", "").replace("-", "").upper(): x for x in recs}
        pushed, updated = 0, 0
        with self._db() as conn:
            rows = conn.execute(
                "SELECT id, code, dev, exp_ts, note, status FROM hist").fetchall()
            for rid, code, dev, exp_ts, note, _st in rows:
                norm = (code or "").replace("-", "").upper()
                if not norm:
                    continue
                if norm not in cloud_norm:
                    recs.append({"code": code, "device": dev or "",
                                 "expire": (exp_ts or "")[:6], "status": "active",
                                 "note": note or "",
                                 "updated_at": datetime.datetime.now().strftime(
                                     "%Y-%m-%d %H:%M:%S")})
                    pushed += 1
                elif cloud_norm[norm].get("status") == "blocked":
                    if _st != "已作废":
                        conn.execute("UPDATE hist SET status='已作废' WHERE id=?",
                                     (rid,))
                        updated += 1
        try:
            if pushed:
                cloud_put(self._token(), {"licenses": recs}, sha)
        except Exception as e:   # noqa: BLE001
            messagebox.showerror("失败", f"写入云端失败:\n{e}", parent=self)
            return
        self.on_cloud_refresh()
        self.load_hist()
        self.l_cloud.config(
            text=f"同步完成: 新上传 {pushed} 条, 本地标记已作废 {updated} 条 (云端为主)",
            foreground=COLOR_OK)

    def on_cloud_toggle(self, status):
        item = self.tv_cloud.focus()
        if not item:
            messagebox.showinfo("提示", "先选中一条云端记录", parent=self)
            return
        code = self.tv_cloud.item(item, "values")[0]
        try:
            recs, sha = self._cloud_records()
            for rec in recs:
                if rec.get("code", "") == code:
                    rec["status"] = status
                    rec["updated_at"] = datetime.datetime.now().strftime(
                        "%Y-%m-%d %H:%M:%S")
            cloud_put(self._token(), {"licenses": recs}, sha)
            self.on_cloud_refresh()
            self.l_cloud.config(
                text=f"{'已停用' if status == 'blocked' else '已启用'}: {code[:12]}...",
                foreground=COLOR_OK)
        except Exception as e:   # noqa: BLE001
            messagebox.showerror("失败", f"操作失败:\n{e}", parent=self)

    def on_cloud_edit_expire(self):
        """改云端到期日(续期): APP 下次启动复查自动取新到期日, 免重输码"""
        item = self.tv_cloud.focus()
        if not item:
            messagebox.showinfo("提示", "先选中一条云端记录", parent=self)
            return
        code = self.tv_cloud.item(item, "values")[0]
        import tkinter.simpledialog as sd
        v = sd.askstring("改到期(续期)",
                         f"注册码 {code[:12]}...\n输入天数 或 到期日 YYYY-MM-DD:",
                         parent=self)
        if not v or not v.strip():
            return
        v = v.strip()
        try:
            if v.isdigit():
                exp = (datetime.datetime.now()
                       + datetime.timedelta(days=int(v))).strftime("%y%m%d")
            else:
                exp = datetime.datetime.strptime(v, "%Y-%m-%d").strftime("%y%m%d")
        except ValueError:
            messagebox.showwarning("格式错误", "请输入天数或 YYYY-MM-DD", parent=self)
            return
        try:
            recs, sha = self._cloud_records()
            for rec in recs:
                if rec.get("code", "") == code:
                    rec["expire"] = exp
                    rec["status"] = "active"
                    rec["updated_at"] = datetime.datetime.now().strftime(
                        "%Y-%m-%d %H:%M:%S")
            cloud_put(self._token(), {"licenses": recs}, sha)
            self.on_cloud_refresh()
            self.l_cloud.config(text=f"已改为到期 {fmt_expire(exp)}",
                                foreground=COLOR_OK)
        except Exception as e:   # noqa: BLE001
            messagebox.showerror("失败", f"续期失败:\n{e}", parent=self)

    def on_cloud_del(self):
        item = self.tv_cloud.focus()
        if not item:
            messagebox.showinfo("提示", "先选中一条云端记录", parent=self)
            return
        code = self.tv_cloud.item(item, "values")[0]
        if not messagebox.askyesno("确认", f"从云端删除该注册码?\n{code}", parent=self):
            return
        try:
            recs, sha = self._cloud_records()
            recs = [x for x in recs if x.get("code", "") != code]
            cloud_put(self._token(), {"licenses": recs}, sha)
            self.on_cloud_refresh()
            self.l_cloud.config(text="已删除", foreground=COLOR_OK)
        except Exception as e:   # noqa: BLE001
            messagebox.showerror("失败", f"删除失败:\n{e}", parent=self)

    # ---- 逻辑 ----
    def on_gen(self):
        dev = self.e_dev.get().strip().upper()
        if len(dev) != 8 or not all(c in "0123456789ABCDEF" for c in dev):
            messagebox.showwarning("设备码错误", "设备码应为 8 位十六进制(大写)\n"
                                             "例: 449F7909", parent=self)
            return
        try:
            if self.v_mode.get() == "days":
                n = int(self.e_days.get().strip())
                if n <= 0:
                    raise ValueError
                exp_dt = datetime.datetime.now() + datetime.timedelta(days=n)
                exp = exp_dt.strftime("%y%m%d")
                desc, mode = f"{n} 天", "按天数"
            else:
                d = self.e_date.get().strip()
                exp_dt = datetime.datetime.strptime(d, "%Y-%m-%d") \
                    .replace(hour=23, minute=59, second=59)
                exp = exp_dt.strftime("%y%m%d")
                desc, mode = d, "指定日期"
            code = make_code(dev, exp)
        except ValueError:
            messagebox.showwarning("输入错误", "有效期格式不正确", parent=self)
            return
        self.t_code.config(state="normal")
        self.t_code.delete("1.0", "end")
        self.t_code.insert("1.0", code)
        self.t_code.config(state="disabled")
        self.l_gen_info.config(
            text=f"绑定设备: {dev} | 到期: {fmt_expire(exp)}", foreground="#0A7D32")
        self.save_hist(dev, fmt_expire(exp), code, mode, desc,
                       exp_dt.strftime("%y%m%d%H%M%S"))
        # 生成后自动登记到云端(静默, 失败不打扰)
        self.after(100, lambda: self.on_cloud_push(
            {"id": 0, "code": code, "dev": dev, "exp_ts": exp_dt.strftime(
                "%y%m%d%H%M%S"), "note": ""}, quiet=True))

    def on_copy(self):
        code = self.t_code.get("1.0", "end").strip()
        if code:
            self.clipboard_clear()
            self.clipboard_append(code)
            self.l_gen_info.config(text="已复制到剪贴板", foreground="#0A7D32")

    # ---- 历史记录 ----
    DB_COLS = ("id", "ts", "dev", "expire", "code", "mode", "days",
               "exp_ts", "status", "note", "activated_at")

    def _db(self):
        import sqlite3, os
        db = os.path.join(os.path.dirname(os.path.abspath(__file__)), "keygen_history.db")
        conn = sqlite3.connect(db)
        conn.execute("CREATE TABLE IF NOT EXISTS hist("
                     "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                     "ts TEXT, dev TEXT, expire TEXT, code TEXT)")
        # 新增详情列(老库自动补列)
        cols = [r[1] for r in conn.execute("PRAGMA table_info(hist)")]
        for name, typ in [("mode", "TEXT"), ("days", "TEXT"), ("exp_ts", "TEXT"),
                          ("status", "TEXT"), ("note", "TEXT"), ("activated_at", "TEXT")]:
            if name not in cols:
                conn.execute(f"ALTER TABLE hist ADD COLUMN {name} {typ}")
        # PAT 等配置存 kv 表(老库自动建表)
        conn.execute("CREATE TABLE IF NOT EXISTS kv("
                     "k TEXT PRIMARY KEY, value TEXT)")
        return conn

    def load_hist(self):
        now = datetime.datetime.now().strftime("%y%m%d%H%M%S")
        self.tv.delete(*self.tv.get_children())
        with self._db() as conn:
            # 刷新状态列: 过期但未标"已过期/已作废"的自动显示过期
            for rid, exp_ts, st in conn.execute(
                    "SELECT id, exp_ts, status FROM hist").fetchall():
                if exp_ts and exp_ts < now and st not in ("已过期", "已作废"):
                    conn.execute("UPDATE hist SET status='已过期' WHERE id=?", (rid,))
            for row in conn.execute(
                    "SELECT id, ts, dev, expire, days, code, status FROM hist "
                    "ORDER BY id DESC"):
                st = row[6] or "未启用"
                tag = "expired" if st in ("已过期", "已作废", "已续期") else (
                    "active" if st == "已激活" else "")
                self.tv.insert("", "end", iid=row[0],
                               values=(row[0], row[1], row[2], row[3], row[4],
                                       row[5], st), tags=(tag,))

    def save_hist(self, dev, expire, code, mode, days, exp_ts):
        ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        with self._db() as conn:
            conn.execute("INSERT INTO hist(ts, dev, expire, code, mode, days, exp_ts,"
                         "status) VALUES(?,?,?,?,?,?,?,?)",
                         (ts, dev, expire, code, mode, days, exp_ts, "未启用"))
        self.load_hist()

    def _sel(self):
        item = self.tv.focus()
        if not item:
            messagebox.showinfo("提示", "先选中一条记录", parent=self)
            return None
        with self._db() as conn:
            row = conn.execute(
                "SELECT id, ts, dev, expire, code, mode, days, exp_ts, status,"
                " note, activated_at FROM hist WHERE id=?", (item,)).fetchone()
        return dict(zip(self.DB_COLS, row)) if row else None

    def on_hist_copy(self, _e):
        item = self.tv.focus()
        if item:
            code = self.tv.item(item, "values")[5]
            self.clipboard_clear()
            self.clipboard_append(code)
            self.l_gen_info.config(text="已从历史复制", foreground="#0A7D32")

    def on_hist_detail(self):
        r = self._sel()
        if not r:
            return
        now = datetime.datetime.now()
        exp_dt = datetime.datetime.strptime(r["expire"], "%Y-%m-%d 23:59:59") \
            if r["expire"] else None
        remain = ""
        if exp_dt:
            d = exp_dt - now
            remain = f"剩余 {d.days} 天" if d.total_seconds() > 0 else "已到期"
        activated = r["activated_at"] or "未知(未收到APP激活反馈)"
        messagebox.showinfo(
            f"记录 #{r['id']} 详情",
            f"授权时间(生成时间): {r['ts']}\n"
            f"绑定设备码: {r['dev']}\n"
            f"注册码: {r['code']}\n"
            f"有效期模式: {r['mode'] or '-'}  时长: {r['days'] or '-'}\n"
            f"到期时间: {r['expire']}\n"
            f"当前状态: {r['status'] or '未启用'}  {remain}\n"
            f"APP激活时间: {activated}\n"
            f"备注: {r['note'] or '(无)'}",
            parent=self)

    def on_hist_renew(self):
        """把选中记录标记为续期来源: 生成新码时统计续期次数"""
        r = self._sel()
        if not r:
            return
        with self._db() as conn:
            conn.execute("UPDATE hist SET status='已续期' WHERE id=?", (r["id"],))
        self.load_hist()
        self.l_gen_info.config(
            text=f"记录 #{r['id']} 已标记续期", foreground="#0A7D32")

    def on_hist_note(self):
        r = self._sel()
        if not r:
            return
        import tkinter.simpledialog as sd
        note = sd.askstring("备注", f"记录 #{r['id']} 备注(客户名/微信号等):",
                            initialvalue=r["note"] or "", parent=self)
        if note is None:
            return
        with self._db() as conn:
            conn.execute("UPDATE hist SET note=? WHERE id=?", (note, r["id"]))
        self.load_hist()

    def on_hist_del(self):
        item = self.tv.focus()
        if not item:
            messagebox.showinfo("提示", "先选中一条记录", parent=self)
            return
        row = self._sel()
        with self._db() as conn:
            conn.execute("DELETE FROM hist WHERE id=?", (item,))
        self.load_hist()
        # 联动删除云端记录
        if row and row.get("code"):
            try:
                recs, sha = self._cloud_records()
                norm = row["code"].replace("-", "").upper()
                remain = [x for x in recs
                          if x.get("code", "").replace("-", "").upper() != norm]
                if len(remain) != len(recs):
                    cloud_put(self._token(), {"licenses": remain}, sha)
                    self.on_cloud_refresh()
                    messagebox.showinfo("已删除", "本地及云端记录均已删除", parent=self)
            except Exception as e:   # noqa: BLE001
                messagebox.showwarning(
                    "部分失败", "本地已删除, 但云端删除失败:\n"
                    f"{e}\n请到云端激活管理手动删除", parent=self)

    def on_dec(self):
        code = self.e_code.get().strip()
        if not code:
            return
        try:
            info = decrypt(code)
            ver, dev, exp = info[0], info[1:9], info[9:]
            now = datetime.datetime.now().strftime("%y%m%d")
            if exp < now:
                status, color = "已过期", "#CC3333"
            else:
                status, color = "有效", "#0A7D32"
            text = (f"版本: {ver}\n绑定设备: {dev}\n"
                    f"到期: {fmt_expire(exp)}\n状态: {status}")
        except Exception:
            text, color = "注册码无效(解密失败)", "#CC3333"
        self.l_dec.config(state="normal")
        self.l_dec.delete("1.0", "end")
        self.l_dec.insert("1.0", text)
        self.l_dec.config(state="disabled")
        self.l_dec.tag_add("all", "1.0", "end")
        self.l_dec.tag_config("all", foreground=color)


if __name__ == "__main__":
    App().mainloop()
