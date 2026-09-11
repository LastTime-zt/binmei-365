# -*- coding: utf-8 -*-
"""
行藏有度 注册码管理器 GUI (与 APP License.java 共享密钥)
生成: 输入设备码 + 有效期 -> 生成注册码, 支持复制
解密: 粘贴注册码 -> 显示绑定设备/到期时间/状态
"""
import datetime
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
        with self._db() as conn:
            conn.execute("DELETE FROM hist WHERE id=?", (item,))
        self.load_hist()

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
