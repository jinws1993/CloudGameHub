"""CloudGameHub Windows client - PySide6."""
import sys
import os
import subprocess
import json
from pathlib import Path
from urllib.parse import urljoin

from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLineEdit, QPushButton, QLabel, QListWidget, QListWidgetItem,
    QMessageBox, QDialog, QFormLayout, QComboBox, QFileDialog,
    QStackedWidget, QFrame, QGridLayout, QScrollArea, QProgressBar,
    QCheckBox, QGroupBox, QStatusBar,
)
from PySide6.QtCore import Qt, QThread, Signal, QSize, QUrl, QTimer
from PySide6.QtGui import QPixmap, QIcon, QDesktopServices
import requests


APP_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "CloudGameHub"
APP_DIR.mkdir(parents=True, exist_ok=True)
CONFIG_FILE = APP_DIR / "config.json"
ROM_CACHE = APP_DIR / "roms"
ROM_CACHE.mkdir(parents=True, exist_ok=True)


def load_config() -> dict:
    if CONFIG_FILE.exists():
        try:
            return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {
        "server": "",
        "token": "",
        "emulators": {},
        "username": "",
    }


def save_config(c: dict) -> None:
    CONFIG_FILE.write_text(json.dumps(c, indent=2, ensure_ascii=False), encoding="utf-8")


# ---------- API client (runs in worker thread) ----------
class APIClient:
    def __init__(self, base_url: str, token: str = ""):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.session = requests.Session()

    def _headers(self):
        h = {}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    def login(self, username, password):
        r = self.session.post(f"{self.base_url}/api/auth/login",
                              json={"username": username, "password": password},
                              timeout=10)
        r.raise_for_status()
        return r.json()

    def me(self):
        r = self.session.get(f"{self.base_url}/api/auth/me",
                             headers=self._headers(), timeout=10)
        r.raise_for_status()
        return r.json()

    def platforms(self):
        r = self.session.get(f"{self.base_url}/api/platforms", timeout=15)
        r.raise_for_status()
        return r.json()

    def games(self, **params):
        r = self.session.get(f"{self.base_url}/api/games",
                             params=params, headers=self._headers(), timeout=30)
        r.raise_for_status()
        return r.json()

    def game(self, gid):
        r = self.session.get(f"{self.base_url}/api/games/{gid}",
                             headers=self._headers(), timeout=15)
        r.raise_for_status()
        return r.json()

    def play_local(self, gid, emulator_override=""):
        r = self.session.post(
            f"{self.base_url}/api/games/{gid}/play/local",
            params={"client": "windows", "emulator_override": emulator_override},
            headers=self._headers(), timeout=15)
        r.raise_for_status()
        return r.json()

    def stream(self, gid):
        r = self.session.post(f"{self.base_url}/api/games/{gid}/play/stream",
                              headers=self._headers(), timeout=15)
        r.raise_for_status()
        return r.json()

    def download_rom(self, gid, dest, progress_cb=None):
        url = f"{self.base_url}/api/games/{gid}/rom"
        with self.session.get(url, headers=self._headers(), stream=True, timeout=600) as r:
            r.raise_for_status()
            total = int(r.headers.get("Content-Length", 0))
            done = 0
            with open(dest, "wb") as f:
                for chunk in r.iter_content(64 * 1024):
                    if not chunk:
                        continue
                    f.write(chunk)
                    done += len(chunk)
                    if progress_cb:
                        progress_cb(done, total)


# ---------- Worker ----------
class Worker(QThread):
    finished_with = Signal(object)
    error = Signal(str)
    progress = Signal(int, int)  # done, total

    def __init__(self, fn, *args, **kwargs):
        super().__init__()
        self.fn = fn
        self.args = args
        self.kwargs = kwargs

    def run(self):
        try:
            r = self.fn(*self.args, **self.kwargs)
            self.finished_with.emit(r)
        except Exception as e:
            self.error.emit(str(e))


# ---------- Login dialog ----------
class LoginDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("连接 CloudGameHub 服务端")
        self.resize(380, 220)

        layout = QFormLayout(self)
        self.ed_server = QLineEdit()
        self.ed_server.setPlaceholderText("http://192.168.1.100:14322")
        self.ed_user = QLineEdit()
        self.ed_user.setPlaceholderText("admin")
        self.ed_pass = QLineEdit()
        self.ed_pass.setEchoMode(QLineEdit.Password)

        layout.addRow("服务器地址:", self.ed_server)
        layout.addRow("用户名:", self.ed_user)
        layout.addRow("密码:", self.ed_pass)

        btn_box = QHBoxLayout()
        self.btn_login = QPushButton("登录")
        self.btn_login.clicked.connect(self.accept)
        btn_box.addWidget(self.btn_login)

        layout.addRow(btn_box)

        cfg = load_config()
        if cfg.get("server"): self.ed_server.setText(cfg["server"])
        if cfg.get("username"): self.ed_user.setText(cfg["username"])

    def get_data(self):
        return {
            "server": self.ed_server.text().strip(),
            "username": self.ed_user.text().strip(),
            "password": self.ed_pass.text(),
        }


# ---------- Emulator config dialog ----------
class EmulatorDialog(QDialog):
    def __init__(self, platforms, current_map, parent=None):
        super().__init__(parent)
        self.setWindowTitle("配置模拟器")
        self.resize(520, 480)
        self.result_map = dict(current_map)

        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("为每个平台选择模拟器可执行文件 (.exe)"))

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        inner = QWidget()
        form = QFormLayout(inner)

        self.editors = {}
        for p in platforms:
            row = QHBoxLayout()
            ed = QLineEdit(current_map.get(p["code"], ""))
            btn = QPushButton("浏览...")
            plat_code = p["code"]
            btn.clicked.connect(lambda _, e=ed: self._pick_file(e))
            row.addWidget(ed, 1)
            row.addWidget(btn)
            wrap = QWidget()
            wrap.setLayout(row)
            form.addRow(f"{p['code']} - {p['name']}:", wrap)
            self.editors[plat_code] = ed

        scroll.setWidget(inner)
        layout.addWidget(scroll, 1)

        btns = QHBoxLayout()
        b_save = QPushButton("保存")
        b_save.clicked.connect(self._save)
        b_cancel = QPushButton("取消")
        b_cancel.clicked.connect(self.reject)
        btns.addWidget(b_save)
        btns.addWidget(b_cancel)
        layout.addLayout(btns)

    def _pick_file(self, editor: QLineEdit):
        f, _ = QFileDialog.getOpenFileName(self, "选择模拟器", "", "可执行文件 (*.exe);;所有文件 (*)")
        if f:
            editor.setText(f)

    def _save(self):
        for code, ed in self.editors.items():
            self.result_map[code] = ed.text().strip()
        self.accept()


# ---------- Main window ----------
class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("CloudGameHub")
        self.resize(1280, 800)

        self.config = load_config()
        self.api: APIClient | None = None
        self.platforms: list = []
        self.games: list = []
        self.current_filter = {"platform": "", "search": "", "page": 1, "page_size": 60}
        self.total = 0

        self._build_ui()
        if self.config.get("server") and self.config.get("token"):
            self.api = APIClient(self.config["server"], self.config["token"])
            self.statusBar().showMessage("正在加载...")
            QTimer.singleShot(100, self._load_initial)

    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)

        # Top bar
        top = QHBoxLayout()
        self.btn_server = QPushButton("⚙ 服务器")
        self.btn_server.clicked.connect(self._on_server)
        self.btn_emus = QPushButton("🎮 模拟器")
        self.btn_emus.clicked.connect(self._on_emus)
        self.cmb_platform = QComboBox()
        self.cmb_platform.setMinimumWidth(160)
        self.cmb_platform.currentIndexChanged.connect(self._on_filter)
        self.ed_search = QLineEdit()
        self.ed_search.setPlaceholderText("搜索游戏...")
        self.ed_search.returnPressed.connect(self._on_filter)
        self.btn_refresh = QPushButton("🔄")
        self.btn_refresh.clicked.connect(self._on_filter)
        top.addWidget(self.btn_server)
        top.addWidget(self.btn_emus)
        top.addSpacing(20)
        top.addWidget(QLabel("平台:"))
        top.addWidget(self.cmb_platform)
        top.addWidget(QLabel("搜索:"))
        top.addWidget(self.ed_search)
        top.addWidget(self.btn_refresh)
        root.addLayout(top)

        # Game grid in scroll area
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.grid_widget = QWidget()
        self.grid = QGridLayout(self.grid_widget)
        self.grid.setAlignment(Qt.AlignTop)
        self.scroll.setWidget(self.grid_widget)
        root.addWidget(self.scroll, 1)

        # Status bar
        self.sb = QStatusBar()
        self.setStatusBar(self.sb)
        self.progress = QProgressBar()
        self.progress.setMaximumWidth(200)
        self.progress.hide()
        self.sb.addPermanentWidget(self.progress)

        # Pagination
        bottom = QHBoxLayout()
        self.btn_prev = QPushButton("上一页")
        self.btn_prev.clicked.connect(self._prev)
        self.btn_next = QPushButton("下一页")
        self.btn_next.clicked.connect(self._next)
        self.lbl_pg = QLabel("")
        bottom.addWidget(self.btn_prev)
        bottom.addWidget(self.lbl_pg, 1, alignment=Qt.AlignCenter)
        bottom.addWidget(self.btn_next)
        root.addLayout(bottom)

    def _load_initial(self):
        self._load_platforms()
        self._load_games()

    def _on_server(self):
        dlg = LoginDialog(self)
        if dlg.exec() == QDialog.Accepted:
            data = dlg.get_data()
            try:
                api = APIClient(data["server"])
                r = api.login(data["username"], data["password"])
                self.config["server"] = data["server"]
                self.config["username"] = data["username"]
                self.config["token"] = r["access_token"]
                save_config(self.config)
                self.api = APIClient(data["server"], r["access_token"])
                self.sb.showMessage(f"已登录: {r['username']}", 3000)
                self._load_initial()
            except Exception as e:
                QMessageBox.critical(self, "登录失败", str(e))

    def _on_emus(self):
        if not self.api:
            QMessageBox.warning(self, "未连接", "请先连接服务器")
            return
        dlg = EmulatorDialog(self.platforms, self.config.get("emulators", {}), self)
        if dlg.exec() == QDialog.Accepted:
            self.config["emulators"] = dlg.result_map
            save_config(self.config)
            QMessageBox.information(self, "已保存", "模拟器配置已保存")

    def _load_platforms(self):
        try:
            self.platforms = self.api.platforms()
            self.cmb_platform.blockSignals(True)
            self.cmb_platform.clear()
            self.cmb_platform.addItem("全部平台", "")
            for p in self.platforms:
                self.cmb_platform.addItem(f"{p['name']} ({p['code']}) - {p['game_count']}", p["code"])
            self.cmb_platform.blockSignals(False)
        except Exception as e:
            self.sb.showMessage(f"加载平台失败: {e}", 5000)

    def _on_filter(self):
        self.current_filter["platform"] = self.cmb_platform.currentData() or ""
        self.current_filter["search"] = self.ed_search.text().strip()
        self.current_filter["page"] = 1
        self._load_games()

    def _prev(self):
        if self.current_filter["page"] > 1:
            self.current_filter["page"] -= 1
            self._load_games()

    def _next(self):
        if self.current_filter["page"] * self.current_filter["page_size"] < self.total:
            self.current_filter["page"] += 1
            self._load_games()

    def _load_games(self):
        if not self.api:
            return
        try:
            params = {k: v for k, v in self.current_filter.items() if v not in ("", 0)}
            r = self.api.games(**params)
            self.games = r["items"]
            self.total = r["total"]
            self._render_games()
            self.lbl_pg.setText(f"第 {self.current_filter['page']} 页, 共 {self.total} 个游戏")
        except Exception as e:
            self.sb.showMessage(f"加载失败: {e}", 5000)

    def _render_games(self):
        # clear grid
        while self.grid.count():
            it = self.grid.takeAt(0)
            w = it.widget()
            if w: w.deleteLater()

        cols = 6
        for i, g in enumerate(self.games):
            row, col = divmod(i, cols)
            card = self._make_card(g)
            self.grid.addWidget(card, row, col)
        self.sb.showMessage(f"已显示 {len(self.games)} / {self.total} 个游戏", 3000)

    def _make_card(self, g: dict):
        w = QFrame()
        w.setFrameShape(QFrame.StyledPanel)
        w.setFixedSize(200, 280)
        w.setStyleSheet("""
            QFrame { background: #1a1f29; border: 1px solid #2c3340; border-radius: 8px; }
            QFrame:hover { border-color: #4f9eff; }
        """)

        layout = QVBoxLayout(w)
        layout.setContentsMargins(8, 8, 8, 8)

        # cover
        cover = QLabel()
        cover.setFixedHeight(180)
        cover.setAlignment(Qt.AlignCenter)
        cover.setStyleSheet("background: #2a1f3d; border-radius: 4px;")
        if g.get("cover"):
            self._set_cover(cover, self.config["server"] + g["cover"])
        else:
            cover.setText(f"{g.get('platform', {}).get('code', '?')}\n{g.get('title_raw', '')[:40]}")
        layout.addWidget(cover)

        # title
        title = QLabel(g.get("title_zh") or g.get("title_en") or g.get("title_raw", ""))
        title.setWordWrap(True)
        title.setMaximumHeight(40)
        layout.addWidget(title)

        # platform/year
        sub = QLabel(f"{g.get('platform', {}).get('code', '')} · {g.get('release_date', '')[:4] if g.get('release_date') else ''}")
        sub.setStyleSheet("color: #8b96a8; font-size: 11px;")
        layout.addWidget(sub)

        # run button
        btn = QPushButton("▶ 运行")
        btn.clicked.connect(lambda _, gid=g["id"]: self._run_game(gid))
        layout.addWidget(btn)

        return w

    def _set_cover(self, label: QLabel, url: str):
        def do():
            try:
                r = requests.get(url, timeout=5)
                if r.status_code == 200:
                    pix = QPixmap()
                    pix.loadFromData(r.content)
                    if not pix.isNull():
                        label.setPixmap(pix.scaled(180, 180, Qt.KeepAspectRatio, Qt.SmoothTransformation))
            except Exception:
                pass
        Worker(do).start()

    def _run_game(self, gid: int):
        # first get game detail (filename)
        try:
            g = self.api.game(gid)
            plat_code = g.get("platform", {}).get("code", "")
            emu = self.config.get("emulators", {}).get(plat_code, "")
            if not emu:
                QMessageBox.warning(self, "未配置", f"请先在「模拟器」设置中配置 {plat_code} 的模拟器")
                return

            rom_local = ROM_CACHE / plat_code / g["rom_filename"]
            rom_local.parent.mkdir(parents=True, exist_ok=True)

            if not rom_local.exists() or rom_local.stat().st_size != g.get("rom_size", 0):
                self.sb.showMessage(f"下载 {g['rom_filename']}...")
                self.progress.show()
                self.progress.setValue(0)

                def progress_cb(done, total):
                    if total > 0:
                        self.progress.setValue(int(done * 100 / total))

                self.api.download_rom(gid, rom_local, progress_cb)
                self.progress.hide()

            # launch
            self.api.play_local(gid)
            subprocess.Popen([emu, str(rom_local)],
                             creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0)
            self.sb.showMessage(f"已启动: {g.get('title_zh') or g.get('title_en')}", 5000)
        except Exception as e:
            QMessageBox.critical(self, "运行失败", str(e))
            self.progress.hide()


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("CloudGameHub")
    app.setStyle("Fusion")

    # dark palette
    from PySide6.QtGui import QPalette, QColor
    palette = QPalette()
    palette.setColor(QPalette.Window, QColor(15, 20, 25))
    palette.setColor(QPalette.WindowText, QColor(229, 233, 239))
    palette.setColor(QPalette.Base, QColor(26, 31, 41))
    palette.setColor(QPalette.AlternateBase, QColor(36, 42, 54))
    palette.setColor(QPalette.Text, QColor(229, 233, 239))
    palette.setColor(QPalette.Button, QColor(36, 42, 54))
    palette.setColor(QPalette.ButtonText, QColor(229, 233, 239))
    palette.setColor(QPalette.Link, QColor(79, 158, 255))
    palette.setColor(QPalette.Highlight, QColor(79, 158, 255))
    palette.setColor(QPalette.HighlightedText, QColor(255, 255, 255))
    app.setPalette(palette)

    w = MainWindow()
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
