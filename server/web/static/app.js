// NasGameHub v=r11 - 2026-09-30 15:25 build (rebrand to NasGameHub)
console.log("%c[NasGameHub] 版本 r11 已加载", "color:#0f0;font-weight:bold;font-size:14px", "build: 2026-09-30 15:25");
// NASGame app.js v=20260930-modal-v2 (13:12)

// NASGame app.js v=20260930-modal (13:10)

// NASGame SPA - pure render-function Vue3
const { createApp, ref, reactive, computed, onMounted, onUnmounted,
        watch, h, defineComponent, provide, inject } = Vue;

// ---------------- API client ----------------
// Hidden dev hook (?test_token=<jwt>) for headless screenshot. Set NASGAME_DEV_HOOK=1 in docker env
// to enable; ignored otherwise. Used only for automated README screenshots — not a security risk
// because the JWT itself still requires valid credentials.
if (typeof window !== 'undefined' && window.__NASGAME_DEV_HOOK__) {
  try {
    const _qs = new URLSearchParams(location.search);
    const _tt = _qs.get('test_token');
    if (_tt) {
      localStorage.setItem('nasgame_token', _tt);
      console.log('[NasGameHub] Test token injected via URL');
    }
  } catch (e) {}
}

const api = {
  token: localStorage.getItem('nasgame_token') || '',
  setToken(t) {
    this.token = t || '';
    if (t) localStorage.setItem('nasgame_token', t);
    else localStorage.removeItem('nasgame_token');
  },
  async req(path, opts = {}) {
    const headers = { ...(opts.headers || {}) };
    if (this.token) headers['Authorization'] = 'Bearer ' + this.token;
    if (opts.body && typeof opts.body === 'object' && !(opts.body instanceof FormData)) {
      headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(opts.body);
    }
    const r = await fetch(path, { ...opts, headers });
    if (r.status === 401) { this.setToken(''); location.reload(); }
    let data = null;
    const ct = r.headers.get('content-type') || '';
    if (ct.includes('json')) data = await r.json();
    else data = await r.text();
    if (!r.ok) {
      const msg = (data && data.detail) || r.statusText || '请求失败';
      throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
    }
    return data;
  },
  get(p)    { return this.req(p); },
  post(p,b) { return this.req(p, { method: 'POST', body: b }); },
  patch(p,b){ return this.req(p, { method: 'PATCH', body: b }); },
  del(p)    { return this.req(p, { method: 'DELETE' }); },
};

function fmtSize(b) {
  if (!b) return '0 B';
  const u = ['B','KB','MB','GB','TB']; let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return b.toFixed(b >= 10 ? 0 : 1) + ' ' + u[i];
}
function fmtTime(s) {
  if (!s) return '-';
  return new Date(s).toLocaleString('zh-CN');
}

const STYLES = `
* { box-sizing: border-box; margin: 0; padding: 0; }
:root { --bg:#0f1419;--bg-card:#1a1f29;--bg-card-2:#242a36;--border:#2c3340;
  --text:#e5e9ef;--text-dim:#8b96a8;--accent:#4f9eff;--accent-hover:#6bb0ff;
  --success:#5fd68a;--warning:#ffb84d;--danger:#ff6b6b; }
body { background:var(--bg);color:var(--text);margin:0;
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
  min-height:100vh;font-size:14px; }
button { background:var(--bg-card-2);color:var(--text);
  border:1px solid var(--border);padding:8px 16px;border-radius:6px;
  cursor:pointer;font-size:14px;transition:all .2s; }
button:hover:not(:disabled){background:#2c3340;border-color:var(--accent);}
button:disabled{opacity:.5;cursor:not-allowed;}
button.primary{background:var(--accent);border-color:var(--accent);color:#fff;font-weight:600;}
button.primary:hover:not(:disabled){background:var(--accent-hover);border-color:var(--accent-hover);}
button.danger{color:var(--danger);border-color:rgba(255,107,107,.4);}
input,select,textarea{background:var(--bg);border:1px solid var(--border);color:var(--text);
  padding:8px 12px;border-radius:6px;font-size:14px;outline:none;}
input:focus,select:focus,textarea:focus{border-color:var(--accent);}
a{color:var(--accent);text-decoration:none;}
a:hover{text-decoration:underline;}
.login-bg{min-height:100vh;
  background:linear-gradient(135deg,#0f1419 0%,#1a1f29 50%,#2a1f3d 100%);
  display:flex;align-items:center;justify-content:center;}
.login-card{background:var(--bg-card);border:1px solid var(--border);
  border-radius:12px;padding:40px;width:380px;
  box-shadow:0 20px 60px rgba(0,0,0,.4);}
.logo{text-align:center;margin-bottom:32px;}
.logo-icon{font-size:48px;margin-bottom:8px;}
.logo h1{font-size:28px;background:linear-gradient(90deg,#4f9eff,#a06bff);
  -webkit-background-clip:text;-webkit-text-fill-color:transparent;}
.logo p{color:var(--text-dim);margin-top:6px;font-size:13px;}
.form-group{margin-bottom:16px;}
.form-group label{display:block;margin-bottom:6px;font-size:13px;color:var(--text-dim);}
.form-group input,.form-group select,.form-group textarea{width:100%;}
.form-actions{display:flex;align-items:center;gap:16px;margin-top:24px;}
.error{background:rgba(255,107,107,.1);border:1px solid rgba(255,107,107,.3);
  color:var(--danger);padding:8px 12px;border-radius:6px;margin-bottom:16px;font-size:13px;}
.hint{color:var(--text-dim);font-size:12px;margin-top:4px;}
.layout{display:flex;min-height:100vh;}
.sidebar{width:220px;background:var(--bg-card);border-right:1px solid var(--border);
  display:flex;flex-direction:column;flex-shrink:0;}
.brand{display:flex;align-items:center;gap:10px;padding:20px;
  border-bottom:1px solid var(--border);font-size:18px;font-weight:600;}
.brand-icon{font-size:24px;}
.sidebar nav{flex:1;padding:12px 0;}
.sidebar nav a{display:flex;align-items:center;gap:12px;padding:12px 20px;
  color:var(--text-dim);cursor:pointer;transition:all .15s;border-left:3px solid transparent;}
.sidebar nav a:hover{background:var(--bg-card-2);color:var(--text);}
.sidebar nav a.active{background:var(--bg-card-2);color:var(--accent);border-left-color:var(--accent);}
.m-icon{font-size:18px;width:24px;text-align:center;}
.user-info{display:flex;align-items:center;gap:12px;padding:16px 20px;
  border-top:1px solid var(--border);}
.avatar{width:36px;height:36px;background:var(--accent);color:#fff;border-radius:50%;
  display:flex;align-items:center;justify-content:center;font-weight:600;}
.user-name{font-size:13px;font-weight:500;}
.btn-link{background:none;border:none;color:var(--text-dim);padding:0;font-size:12px;cursor:pointer;}
.btn-link:hover{color:var(--accent);background:none;}
.content{flex:1;padding:24px 32px;overflow-x:hidden;}
.page{max-width:1400px;}
.page h2{margin-bottom:24px;font-size:22px;}
.page h3{margin:24px 0 12px;font-size:16px;color:var(--text-dim);}
.page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:24px;}
.page-header h2{margin:0;}
.actions{display:flex;gap:12px;}
.stats-grid{display:grid;gap:16px;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));margin-bottom:24px;}
.stat-card{background:var(--bg-card);border:1px solid var(--border);border-radius:8px;
  padding:20px;text-align:center;}
.stat-value{font-size:36px;font-weight:700;color:var(--accent);}
.stat-label{color:var(--text-dim);margin-top:6px;font-size:13px;}
.stat-card.success .stat-value{color:var(--success);}
.stat-card.warning .stat-value{color:var(--warning);}
.platform-bars{background:var(--bg-card);padding:16px;border-radius:8px;border:1px solid var(--border);}
.plat-bar{display:grid;grid-template-columns:80px 1fr 60px;align-items:center;gap:12px;padding:6px 0;}
.plat-bar-label{font-weight:600;font-family:monospace;}
.plat-bar-track{background:var(--bg);height:16px;border-radius:8px;overflow:hidden;}
.plat-bar-fill{background:linear-gradient(90deg,var(--accent),#a06bff);height:100%;transition:width .3s;}
.plat-bar-count{text-align:right;color:var(--text-dim);font-family:monospace;}
.quick-actions{display:flex;gap:12px;flex-wrap:wrap;}
.quick-actions button{font-size:15px;padding:12px 20px;}
.last-task{margin-top:16px;color:var(--text-dim);font-size:13px;}
.filters{display:flex;gap:12px;margin-bottom:20px;flex-wrap:wrap;align-items:center;}
.filters input,.filters select{min-width:120px;}
.filters input[type="text"]{flex:1;min-width:200px;}
.game-grid{display:grid;gap:16px;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));}
.game-card{background:var(--bg-card);border:1px solid var(--border);border-radius:8px;
  overflow:hidden;cursor:pointer;transition:transform .15s,border-color .15s;}
.game-card .game-cover{position:relative;aspect-ratio:3/4;background:var(--bg-card-2);
  display:flex;align-items:center;justify-content:center;font-size:48px;color:var(--text-dim);}
.game-card .game-cover img{width:100%;height:100%;object-fit:cover;}
.game-card .game-info{padding:10px 12px;}
.game-card .game-title{font-weight:600;font-size:13px;line-height:1.4;
  overflow:hidden;text-overflow:ellipsis;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;}
.game-card .game-meta{display:flex;gap:6px;align-items:center;margin-top:4px;
  font-size:11px;flex-wrap:wrap;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
.game-card .platform-tag{background:var(--accent);color:#fff;padding:1px 5px;border-radius:3px;font-size:10px;}
  overflow:hidden;cursor:pointer;transition:all .2s;}
.game-card:hover{transform:translateY(-4px);border-color:var(--accent);
  box-shadow:0 8px 24px rgba(79,158,255,.2);}
.game-card .cover{position:relative;aspect-ratio:3/4;
  background:linear-gradient(135deg,#1a1f29,#2a1f3d);overflow:hidden;}
.game-card .cover img{width:100%;height:100%;object-fit:cover;}
.cover-placeholder{height:100%;display:flex;flex-direction:column;
  align-items:center;justify-content:center;padding:12px;text-align:center;}
.cover-platform{font-size:24px;font-weight:700;color:var(--accent);margin-bottom:8px;}
.cover-title{font-size:12px;color:var(--text-dim);line-height:1.4;
  display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden;}
.card-badges{position:absolute;top:6px;left:6px;display:flex;gap:4px;flex-wrap:wrap;}
.badge{font-size:11px;padding:2px 6px;border-radius:4px;background:rgba(0,0,0,.6);color:#fff;}
.badge.pending{background:rgba(255,184,77,.85);color:#1a1f29;}
.badge.failed{background:rgba(255,107,107,.85);}
.badge.fav{background:rgba(255,215,0,.85);color:#1a1f29;}
.badge.cloud{background:rgba(96,165,250,.85);}
.badge.scan{background:rgba(79,158,255,.7);}
.badge.scrape{background:rgba(160,107,255,.7);}
.badge.status-done{background:rgba(95,214,138,.7);}
.badge.status-running{background:rgba(255,184,77,.85);color:#1a1f29;}
.badge.status-pending{background:rgba(139,150,168,.7);}
.badge.status-failed{background:rgba(255,107,107,.85);}
.game-info{padding:10px;}
.game-title{font-size:13px;font-weight:500;margin-bottom:4px;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
.game-sub{font-size:11px;color:var(--text-dim);display:flex;gap:4px;flex-wrap:wrap;}
.loading{text-align:center;padding:40px;color:var(--text-dim);}
.pagination{display:flex;justify-content:center;align-items:center;gap:16px;margin-top:24px;color:var(--text-dim);}
.modal-bg{position:fixed;inset:0;background:rgba(0,0,0,.7);
  display:flex;align-items:center;justify-content:center;z-index:100;padding:20px;}
.modal{background:var(--bg-card);border:1px solid var(--border);border-radius:12px;
  padding:24px;max-width:900px;width:100%;max-height:90vh;overflow-y:auto;position:relative;}
.modal-close{position:absolute;top:12px;right:12px;width:32px;height:32px;
  border-radius:50%;padding:0;font-size:18px;line-height:1;}
.modal-large{max-width:1100px;}
.detail-grid{display:grid;grid-template-columns:300px 1fr;gap:24px;}
@media(max-width:700px){.detail-grid{grid-template-columns:1fr;}}
.detail-cover{width:100%;border-radius:8px;}
.detail-cover-placeholder{aspect-ratio:3/4;background:var(--bg-card-2);border-radius:8px;
  display:flex;align-items:center;justify-content:center;font-size:48px;color:var(--accent);}
.detail-screenshot{width:100%;margin-top:12px;border-radius:6px;}
.detail-right h1{font-size:24px;margin-bottom:4px;}
.detail-right h3{color:var(--text-dim);font-weight:400;margin:0 0 16px;}
.meta-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:8px 16px;margin-bottom:16px;font-size:13px;}
.meta-grid > div{padding:4px 0;}
.meta-grid b{color:var(--text-dim);margin-right:4px;}
.description{background:var(--bg);padding:12px;border-radius:6px;margin-bottom:16px;
  line-height:1.6;white-space:pre-wrap;font-size:13px;}
.detail-actions{display:flex;gap:12px;margin-top:16px;}
.custom-search{background:var(--bg-card);border:1px solid var(--border);
  border-radius:8px;padding:12px 14px;margin:16px 0 8px;}
.custom-search h4{margin:0 0 6px;font-size:14px;color:var(--accent);}
.custom-search .hint{font-size:12px;color:var(--text-dim);margin:0 0 8px;}
.custom-search input[type=text]{padding:6px 10px;background:var(--bg);
  border:1px solid var(--border);border-radius:4px;font-size:13px;color:var(--text);}
.custom-search input[type=text]:focus{outline:none;border-color:var(--accent);}
.toast.success{background:rgba(80,200,120,.15);color:#7be299;border-color:#3f7f55;}
.section-header{display:flex;align-items:baseline;margin-bottom:10px;}
.section-header h3{margin:0;font-size:15px;color:var(--text);}
.table{width:100%;border-collapse:collapse;background:var(--bg-card);border-radius:8px;
  overflow:hidden;border:1px solid var(--border);}
.table th,.table td{text-align:left;padding:10px 14px;border-bottom:1px solid var(--border);font-size:13px;}
.table th{background:var(--bg-card-2);color:var(--text-dim);font-weight:500;
  font-size:12px;text-transform:uppercase;}
.table tr:last-child td{border-bottom:none;}
.table tr:hover td{background:var(--bg-card-2);}
code{background:var(--bg);padding:1px 6px;border-radius:4px;font-size:12px;color:var(--accent);}
fieldset{border:1px solid var(--border);border-radius:8px;
  padding:20px;margin-bottom:20px;background:var(--bg-card);}
legend{padding:0 8px;color:var(--text-dim);font-size:13px;font-weight:600;text-transform:uppercase;}
.warn-box{background:rgba(255,184,77,.15);border:1px solid rgba(255,184,77,.4);
  border-radius:8px;padding:12px 16px;margin-bottom:16px;color:var(--text);}
.success-box{background:rgba(95,214,138,.1);border:1px solid rgba(95,214,138,.3);
  border-radius:8px;padding:12px 16px;margin-bottom:16px;color:var(--text);
  font-size:13px;line-height:1.6;}
.toast{position:fixed;bottom:24px;right:24px;background:var(--bg-card);
  border:1px solid var(--border);border-radius:8px;padding:12px 18px;
  box-shadow:0 4px 12px rgba(0,0,0,.4);z-index:9999;}
.breadcrumb{font-size:13px;margin-bottom:12px;color:var(--text-dim);}
.breadcrumb a{color:var(--accent);cursor:pointer;}
.tabs{display:flex;gap:6px;border-bottom:1px solid var(--border);margin-bottom:14px;}
.tab{padding:8px 14px;background:transparent;border:none;color:var(--text-dim);
  font-size:13px;cursor:pointer;border-bottom:2px solid transparent;}
.tab.active{color:var(--text);border-bottom-color:var(--accent);font-weight:600;}
.tab:hover{color:var(--text);}
.qr-login,.manual-cookie{padding:8px 0;}
.status-ok{color:var(--success);}
.status-off{color:var(--danger);}
.progress{position:relative;background:var(--bg);border-radius:6px;height:18px;width:160px;overflow:hidden;}
.progress-bar{background:linear-gradient(90deg,var(--accent),#a06bff);height:100%;transition:width .3s;}
.progress span{position:absolute;inset:0;display:flex;align-items:center;
  justify-content:center;font-size:11px;color:#fff;text-shadow:0 1px 2px rgba(0,0,0,.5);}
.addrom h2{margin-top:0;}
.path-bar{display:flex;align-items:center;gap:6px;background:var(--bg-card);
  border:1px solid var(--border);border-radius:8px;padding:8px 12px;margin:12px 0;flex-wrap:wrap;}
.path-bar .crumb{background:transparent;border:none;color:var(--accent);
  cursor:pointer;padding:2px 6px;font-size:13px;}
.path-bar .crumb:hover{text-decoration:underline;background:transparent;}
.path-bar .sep{color:var(--text-dim);}
.path-bar .current{color:var(--text);font-weight:600;padding:2px 6px;}
.path-bar .refresh{margin-left:auto;}
.dir-list{background:var(--bg-card);border:1px solid var(--border);border-radius:8px;
  padding:8px;max-height:480px;overflow-y:auto;}
.dir-row{display:flex;align-items:center;gap:10px;padding:8px 12px;
  border-radius:6px;cursor:pointer;transition:background .15s;
  border:1px solid transparent;}
.dir-row:hover{background:var(--bg-card-2);}
.dir-row.selected{background:rgba(79,158,255,.15);border-color:var(--accent);}
.muted{color:var(--text-dim);font-size:13px;}
.section-header{display:flex;align-items:baseline;margin:16px 0 12px;}
.section-header h3{margin:0;color:var(--accent);}
.empty-hint{padding:40px 20px;background:var(--bg-card);border:1px dashed var(--border);
  border-radius:8px;text-align:center;color:var(--text-dim);}
.cloud-badge{position:absolute;top:6px;right:6px;background:var(--accent);color:#fff;
  font-size:11px;padding:2px 6px;border-radius:4px;font-weight:bold;}
.dir-row.dir{color:var(--accent);}
.dir-row.file{color:var(--text);}
.dir-icon{font-size:18px;width:24px;text-align:center;}
.dir-name{flex:1;font-family:monospace;font-size:13px;}
.dir-size{color:var(--text-dim);font-size:11px;font-family:monospace;}
.dir-platform-hint{font-size:11px;color:var(--text-dim);}
.dir-empty{text-align:center;padding:40px;color:var(--text-dim);}
.dir-actions{display:flex;gap:12px;align-items:center;margin-top:12px;
  padding-top:12px;border-top:1px solid var(--border);}
.dir-actions .selected-info{flex:1;color:var(--text-dim);font-size:13px;}
.upload-result{margin-top:12px;padding:12px;border-radius:6px;font-size:13px;}
.upload-result.success{background:rgba(95,214,138,.1);border:1px solid rgba(95,214,138,.3);color:var(--success);}
.upload-result.error{background:rgba(255,107,107,.1);border:1px solid rgba(255,107,107,.3);color:var(--danger);}
`;

if (!document.getElementById('nasgame-styles')) {
  const s = document.createElement('style');
  s.id = 'nasgame-styles';
  s.textContent = STYLES;
  document.head.appendChild(s);
}

// ---------------- Components ----------------

// 简单 emit 注入: 让子组件 (Dashboard/Library 等) 可以 emit 'goto' 跳转菜单
const EMIT_KEY = Symbol('emit');
const PENDING_GAME_KEY = Symbol('pendingGameId');

function useEmit() { return inject(EMIT_KEY); }
function usePendingGame() { return inject(PENDING_GAME_KEY); }

// ===== Login (不预填用户名密码) =====
const Login = defineComponent({
  setup() {
    const username = ref('');
    const password = ref('');
    const error = ref('');
    const loading = ref(false);
    async function doLogin() {
      error.value = '';
      loading.value = true;
      try {
        const r = await api.post('/api/auth/login', {
          username: username.value, password: password.value,
        });
        api.setToken(r.access_token);
        location.reload();
      } catch (e) { error.value = e.message; }
      finally { loading.value = false; }
    }
    return () => h('div', { class: 'login-bg' }, [
      h('div', { class: 'login-card' }, [
        h('div', { class: 'logo' }, [
          h('div', { class: 'logo-icon' }, '🎮'),
          h('h1', null, 'NasGameHub'),
          h('p', null, '让游戏管理像电影一样简单'),
        ]),
        h('form', { onSubmit: e => { e.preventDefault(); doLogin(); } }, [
          h('div', { class: 'form-group' }, [
            h('label', null, '用户名'),
            h('input', {
              type: 'text', value: username.value, required: true,
              autocomplete: 'username', placeholder: '请输入用户名',
              onInput: e => username.value = e.target.value,
            }),
          ]),
          h('div', { class: 'form-group' }, [
            h('label', null, '密码'),
            h('input', {
              type: 'password', value: password.value, required: true,
              autocomplete: 'current-password', placeholder: '请输入密码',
              onInput: e => password.value = e.target.value,
            }),
          ]),
          error.value ? h('div', { class: 'error' }, error.value) : null,
          h('button', { type: 'submit', disabled: loading.value },
            loading.value ? '登录中...' : '登 录'),
          h('p', { class: 'hint' }, '提示: 在管理面板里可修改默认账号密码'),
        ]),
      ]),
    ]);
  },
});

// ===== Dashboard =====
const Dashboard = defineComponent({
  setup() {
    const stats = reactive({ total_games: 0, total_platforms: 0,
      by_status: {}, by_platform: [] });
    const lastTask = ref(null);
    const busy = ref(false);
    const gotoPage = useEmit();

    async function load() {
      try { Object.assign(stats, await api.get('/api/stats')); }
      catch (e) { console.error('stats:', e); }
      try {
        const tasks = await api.get('/api/library/tasks?limit=1');
        lastTask.value = tasks[0] || null;
      } catch (e) { console.error('tasks:', e); }
    }
    async function doScan() {
      busy.value = true;
      try {
        await api.post('/api/library/scan', {});
        alert('扫描任务已启动');
        await load();
      } catch (e) { alert(e.message); }
      finally { busy.value = false; }
    }
    async function doScrape() {
      busy.value = true;
      try {
        await api.post('/api/library/scrape', {});
        alert('刮削任务已启动, 请到任务队列查看进度');
        await load();
      } catch (e) { alert(e.message); }
      finally { busy.value = false; }
    }
    onMounted(load);

    return () => h('div', { class: 'page' }, [
      h('h2', null, '📊 概览'),
      h('div', { class: 'stats-grid' }, [
        statCard(stats.total_games, '游戏总数'),
        statCard(stats.total_platforms, '支持平台'),
        statCard(stats.by_status?.done || 0, '已刮削', 'success'),
        statCard(stats.by_status?.pending || 0, '待刮削', 'warning'),
      ]),
      h('h3', null, '平台分布'),
      h('div', { class: 'platform-bars' },
        (stats.by_platform || []).map(p =>
          h('div', { class: 'plat-bar', key: p.platform }, [
            h('div', { class: 'plat-bar-label' }, p.platform),
            h('div', { class: 'plat-bar-track' }, [
              h('div', {
                class: 'plat-bar-fill',
                style: { width: Math.min(100, p.count * 5) + '%' },
              }),
            ]),
            h('div', { class: 'plat-bar-count' }, String(p.count)),
          ])
        )
      ),
      h('h3', null, '快速操作'),
      h('div', { class: 'quick-actions' }, [
        h('button', { onClick: () => gotoPage('library') }, '📚 浏览游戏库'),
        h('button', { onClick: doScan, disabled: busy.value }, '🔍 扫描ROM'),
        h('button', { onClick: doScrape, disabled: busy.value }, '✨ 批量刮削'),
      ]),
      lastTask.value
        ? h('div', { class: 'last-task' },
            '最近任务: ' + lastTask.value.type + ' - ' +
            (lastTask.value.message || lastTask.value.status))
        : null,
    ]);

    function statCard(value, label, variant) {
      return h('div', { class: 'stat-card ' + (variant || '') }, [
        h('div', { class: 'stat-value' }, String(value)),
        h('div', { class: 'stat-label' }, label),
      ]);
    }
  },
});

// ===== AddRom 弹窗 =====
const AddRom = defineComponent({
  emits: ['done'],
  setup(_, { emit }) {
    // 当前路径 (空数组 = /data/roms 根)
    const path = ref([]);
    const items = ref([]);
    const loading = ref(false);
    const errorMsg = ref('');
    // 选中: { path: 'fc/sfc', type: 'dir' } 或 { path: 'fc/mario.nes', type: 'file' }
    const selected = ref(null);
    const importing = ref(false);
    const result = ref(null);

    async function browse(p) {
      loading.value = true; errorMsg.value = '';
      try {
        const rel = p.map(s => s.name).join('/');
        const url = '/api/library/browse' + (rel ? '?path=' + encodeURIComponent(rel) : '');
        const r = await api.get(url);
        path.value = p; items.value = r.items;
      } catch (e) { errorMsg.value = e.message; }
      finally { loading.value = false; }
    }
    function goCrumb(i) {
      if (i < 0) browse([]);
      else browse(path.value.slice(0, i + 1));
      selected.value = null; result.value = null;
    }
    function onRowClick(item) {
      if (item.is_dir) {
        browse([...path.value, { name: item.name }]);
        selected.value = null; result.value = null;
      } else {
        // 选中文件, 路径=文件所在目录 + 文件名
        const rel = [...path.value.map(s => s.name), item.name].join('/');
        selected.value = { path: rel, type: 'file', name: item.name };
        result.value = null;
      }
    }
    function pickWholeDir(item) {
      // 选中整目录
      const rel = [...path.value.map(s => s.name), item.name].join('/');
      selected.value = { path: rel, type: 'dir', name: item.name };
      result.value = null;
    }
    async function doImport() {
      if (!selected.value) return;
      importing.value = true; result.value = null;
      try {
        const r = await api.post('/api/games/import-existing', selected.value);
        result.value = { ok: true, ...r };
        // 触发扫描入库
        try { await api.post('/api/library/scan', {}); }
        catch (e) { console.warn('post-scan:', e); }
      } catch (e) {
        result.value = { ok: false, error: e.message };
      } finally {
        importing.value = false;
      }
    }
    function close() { emit('done'); }

    onMounted(() => browse([]));

    return () => {
      const crumbs = [
        h('button', { class: 'crumb', onClick: () => goCrumb(-1) }, 'roms/'),
      ];
      path.value.forEach((seg, i) => {
        crumbs.push(h('span', { class: 'sep' }, '/'));
        if (i === path.value.length - 1) {
          crumbs.push(h('span', { class: 'current' }, seg.name));
        } else {
          crumbs.push(h('button', { class: 'crumb', onClick: () => goCrumb(i) }, seg.name));
        }
      });

      return h('div', { class: 'modal-bg', onClick: e => {
        if (e.target === e.currentTarget) close();
      } }, [
        h('div', { class: 'modal modal-large' }, [
          h('button', { class: 'modal-close', onClick: close }, '✕'),
          h('h2', null, '➕ 添加ROM (从本地目录选择)'),
          h('p', { class: 'hint' }, [
            '浏览 ', h('strong', null, '/data/roms'),
            ' 下的文件。点击目录名进入下一层, 点击文件选中, ',
            '点击目录右侧 ', h('strong', null, '✓ 选中整目录'),
            ' 添加整个目录的所有 ROM (含子目录)。',
          ]),
          h('div', { class: 'path-bar' }, [
            ...crumbs,
            h('button', {
              class: 'btn-link refresh',
              onClick: () => browse(path.value),
            }, '🔄 刷新'),
          ]),
          errorMsg.value ? h('div', { class: 'error' }, errorMsg.value) : null,
          loading.value
            ? h('div', { class: 'loading' }, '加载中...')
            : h('div', { class: 'dir-list' },
                items.value.length === 0
                  ? [h('div', { class: 'dir-empty' }, '📭 此目录为空')]
                  : items.value.map(it => {
                      const fullRel = [...path.value.map(p => p.name), it.name].join('/');
                      const isSel = selected.value &&
                        selected.value.path === fullRel &&
                        (it.is_dir ? selected.value.type === 'dir' : selected.value.type === 'file');
                      return h('div', {
                        key: it.name,
                        class: 'dir-row ' + (it.is_dir ? 'dir' : 'file') +
                          (isSel ? ' selected' : ''),
                        onClick: () => onRowClick(it),
                      }, [
                        h('span', { class: 'dir-icon' }, it.is_dir ? '📁' : '🎮'),
                        h('span', { class: 'dir-name' }, it.name),
                        it.is_dir
                          ? h('span', { class: 'dir-platform-hint' },
                              it.platform_code
                                ? '[' + it.platform_code + ']'
                                : (it.recognized ? '[未识别平台]' : ''))
                          : h('span', { class: 'dir-size' }, fmtSize(it.size)),
                        it.is_dir
                          ? h('button', {
                              class: 'btn-link',
                              onClick: e => { e.stopPropagation(); pickWholeDir(it); },
                            }, '✓ 选中')
                          : null,
                      ]);
                    })
              ),
          h('div', { class: 'dir-actions' }, [
            h('div', { class: 'selected-info' },
              selected.value
                ? (selected.value.type === 'dir'
                    ? '已选中目录: ' + selected.value.path + '/'
                    : '已选中文件: ' + selected.value.path)
                : '提示: 也可以什么都不选, 直接点击"导入当前目录"批量添加'),
            !selected.value && path.value.length >= 0
              ? h('button', {
                  class: 'btn-link',
                  onClick: () => {
                    const rel = path.value.map(s => s.name).join('/');
                    selected.value = {
                      path: rel, type: 'dir',
                      name: rel || '(根)',
                    };
                  },
                }, '📂 选中当前目录')
              : null,
            selected.value
              ? h('button', {
                  class: 'btn-link',
                  onClick: () => { selected.value = null; },
                }, '取消选中')
              : null,
            h('button', {
              class: 'primary',
              disabled: !selected.value || importing.value,
              onClick: doImport,
            }, importing.value ? '导入中...' : '⏬ 导入游戏库'),
          ]),
          result.value
            ? h('div', {
                class: 'upload-result ' + (result.value.ok ? 'success' : 'error'),
              },
                result.value.ok
                  ? `✅ 成功导入 ${result.value.imported || 0} 个游戏${
                      result.value.skipped ? `, 跳过 ${result.value.skipped} 个` : ''}${
                      result.value.task_id ? `, 任务 #${result.value.task_id}` : ''}`
                  : '❌ ' + result.value.error)
            : null,
        ]),
      ]);
    };
  },
});

// ===== Library =====
const Library = defineComponent({
  setup() {
    const games = ref([]);
    const platforms = ref([]);
    // 跨 detailView 渲染保持状态 (避免 detailView 不是 setup 函数导致 ref 失效)
    const detailState = reactive({
      customSearch: '',
      customSearching: false,
      customSearchError: '',
      customSearchResult: null,
      customApplying: false,
      coverUploading: false,
      coverError: '',
    });
    function resetDetailState(g) {
      detailState.customSearch = g.title_en || g.title_zh || g.title_raw || '';
      detailState.customSearching = false;
      detailState.customSearchError = '';
      detailState.customSearchResult = null;
      detailState.customApplying = false;
      detailState.coverUploading = false;
      detailState.coverError = '';
    }
    const loading = ref(false);
    const total = ref(0);
    const page = ref(1);
    const pageSize = 60;
    const selected = ref(null);
    const busy = ref(false);
    const showAdd = ref(false);
    const message = ref('');
    const filters = reactive({
      platform: '', status: '', search: '', sort: 'title', favorite: false,
    });
    // 从云盘页跳转过来时要自动打开详情
    const pendingGame = usePendingGame();
    async function _checkPending() {
      if (pendingGame && pendingGame.value) {
        const gid = pendingGame.value;
        pendingGame.value = null;
        try { selected.value = await api.get('/api/games/' + gid); }
        catch (e) { console.warn('open game fail', e); }
      }
    }
    if (pendingGame) watch(pendingGame, _checkPending);

    async function loadPlatforms() {
      try { platforms.value = await api.get('/api/platforms'); }
      catch (e) { console.error('platforms:', e); }
    }
    async function load(p) {
      if (p) page.value = p;
      loading.value = true;
      try {
        const qs = new URLSearchParams({
          page: page.value, page_size: pageSize, sort: filters.sort,
        });
        if (filters.platform) qs.set('platform', filters.platform);
        if (filters.status) qs.set('status', filters.status);
        if (filters.search) qs.set('search', filters.search);
        if (filters.favorite) qs.set('favorite', 'true');
        const r = await api.get('/api/games?' + qs.toString());
        games.value = r.items; total.value = r.total;
      } catch (e) { alert(e.message); }
      finally { loading.value = false; }
    }
    async function openDetail(g) {
      try { selected.value = await api.get('/api/games/' + g.id); }
      catch (e) { alert(e.message); }
    }
    async function toggleFav(id) {
      try {
        const r = await api.post('/api/games/' + id + '/favorite');
        if (selected.value && selected.value.id === id)
          selected.value.favorite = r.favorite;
        load();
      } catch (e) { alert(e.message); }
    }
    async function rescrape(id) {
      busy.value = true;
      try {
        await api.post('/api/games/' + id + '/rescrape', {});
        alert('重新刮削任务已加入队列');
      } catch (e) { alert(e.message); }
      finally { busy.value = false; }
    }
    async function deleteGame(id) {
      if (!confirm('确定删除这个游戏吗? ROM 文件也会被删除')) return;
      try {
        await api.del('/api/games/' + id);
        selected.value = null; load();
      } catch (e) { alert(e.message); }
    }
    async function doScan() {
      busy.value = true;
      try { await api.post('/api/library/scan', {}); alert('扫描任务已启动'); }
      catch (e) { alert(e.message); }
      finally { busy.value = false; }
    }
    async function doScrape() {
      busy.value = true;
      try {
        await api.post('/api/library/scrape', {});
        alert('刮削任务已启动, 请到任务队列查看进度');
      } catch (e) { alert(e.message); }
      finally { busy.value = false; }
    }
    function onAdded() { showAdd.value = false; load(1); }

    onMounted(async () => { await loadPlatforms(); await load(1); await _checkPending(); });

    return () => h('div', { class: 'page' }, [
      h('div', { class: 'page-header' }, [
        h('h2', null, '📚 游戏库'),
        h('div', { class: 'actions' }, [
          h('button', { class: 'primary', onClick: () => showAdd.value = true }, '➕ 添加ROM'),
          h('button', { onClick: doScan, disabled: busy.value }, '🔍 扫描ROM'),
          h('button', { onClick: doScrape, disabled: busy.value }, '✨ 批量刮削'),
        ]),
      ]),
      h('div', { class: 'filters' }, [
        h('select', {
          value: filters.platform,
          onChange: e => { filters.platform = e.target.value; load(1); },
        }, [
          h('option', { value: '' }, '全部平台'),
          ...platforms.value.map(p =>
            h('option', { value: p.code }, p.name + ' (' + p.code + ') - ' + p.game_count))
        ]),
        h('select', {
          value: filters.status,
          onChange: e => { filters.status = e.target.value; load(1); },
        }, [
          h('option', { value: '' }, '全部状态'),
          h('option', { value: 'done' }, '已刮削'),
          h('option', { value: 'pending' }, '待刮削'),
          h('option', { value: 'failed' }, '失败'),
        ]),
        h('input', {
          type: 'text', value: filters.search,
          placeholder: '搜索游戏名...',
          onInput: e => { filters.search = e.target.value; load(1); },
        }),
        h('select', {
          value: filters.sort,
          onChange: e => { filters.sort = e.target.value; load(1); },
        }, [
          h('option', { value: 'title' }, '按标题'),
          h('option', { value: 'title_zh' }, '按中文标题'),
          h('option', { value: 'title_en' }, '按英文标题'),
          h('option', { value: 'rating' }, '按评分'),
          h('option', { value: 'played' }, '按游玩时间'),
          h('option', { value: 'created' }, '按添加时间'),
        ]),
        h('label', null, [
          h('input', {
            type: 'checkbox', checked: filters.favorite,
            onChange: e => { filters.favorite = e.target.checked; load(1); },
          }),
          ' 收藏',
        ]),
      ]),
      loading.value
        ? h('div', { class: 'loading' }, '加载中...')
        : h('div', { class: 'game-grid' },
            games.value.length === 0
              ? [h('div', { class: 'loading' }, '🎮 暂无游戏, 点击右上角"添加ROM"开始')]
              : games.value.map(g =>
                  h('div', {
                    key: g.id,
                    class: 'game-card',
                    onClick: () => openDetail(g),
                  }, [
                    h('div', { class: 'cover' }, [
                      g.cover
                        ? h('img', { src: g.cover, loading: 'lazy',
                            onError: e => {
                              console.warn('封面加载失败:', g.cover);
                              e.target.style.display='none';
                              const ph = e.target.nextElementSibling;
                              if (ph) ph.style.display='flex';
                            }})
                        : null,
                      h('div', {
                        class: 'cover-placeholder',
                        style: { display: g.cover ? 'none' : 'flex' },
                      }, [
                        h('div', { class: 'cover-platform' },
                          g.platform?.code || '?'),
                        h('div', { class: 'cover-title' },
                          g.title || g.title_raw),
                      ]),
                      h('div', { class: 'card-badges' }, [
                        g.scrape_status === 'pending'
                          ? h('span', { class: 'badge pending' }, '待刮削') : null,
                        g.scrape_status === 'failed'
                          ? h('span', { class: 'badge failed',
                              title: (g.extra && g.extra.scrape_error)
                                || '刮削失败' }, '失败') : null,
                        g.favorite
                          ? h('span', { class: 'badge fav' }, '★') : null,
                        (g.cloud_source === '115' && !g.local_available)
                          ? h('span', { class: 'badge cloud',
                              title: g.cloud_path || '从 115 云盘导入' }, '☁️ 云端') : null,
                      ]),
                    ]),
                    h('div', { class: 'game-info' }, [
                      h('div', { class: 'game-title' },
                        g.title_zh || g.title_en || g.title_raw),
                      h('div', { class: 'game-sub' }, [
                        h('span', null, g.platform?.code || '?'),
                        g.release_date ? h('span', null, '· ' + g.release_date) : null,
                        g.rating ? h('span', null,
                          '· ⭐ ' + Number(g.rating).toFixed(1)) : null,
                        (g.cloud_source === '115' && !g.local_available)
                          ? h('span', null, '· ☁️ 115 云盘') : null,
                      ]),
                    ]),
                  ])
                )
          ),
      total.value > pageSize
        ? h('div', { class: 'pagination' }, [
            h('button', {
              onClick: () => load(page.value - 1),
              disabled: page.value <= 1,
            }, '上一页'),
            h('span', null, '第 ' + page.value + ' 页 / 共 ' +
              Math.ceil(total.value / pageSize) + ' 页 (共 ' + total.value + ' 个游戏)'),
            h('button', {
              onClick: () => load(page.value + 1),
              disabled: page.value * pageSize >= total.value,
            }, '下一页'),
          ])
        : null,
      selected.value
        ? h('div', {
            class: 'modal-bg',
            onClick: e => { if (e.target === e.currentTarget) selected.value = null; },
          }, [
            h('div', { class: 'modal modal-large' }, [
              h('button', {
                class: 'modal-close',
                onClick: () => { selected.value = null; },
              }, '✕'),
              detailView(selected.value, detailState, {
                onClose: () => { selected.value = null; },
                onToggleFav: () => toggleFav(selected.value.id),
                onRescrape: () => rescrape(selected.value.id),
                onDelete: () => deleteGame(selected.value.id),
                onCustomSearchDone: async () => {
                  try {
                    selected.value = await api.get('/api/games/' + selected.value.id);
                  } catch (e) { console.warn('refresh fail', e); }
                  load();
                  // 提示用户应用成功 + 列表已刷新
                  message.value = `✓ 已应用掳削结果, 库列表已刷新`;
                  setTimeout(() => { message.value = ''; }, 2500);
                },
              }),
            ]),
          ])
        : null,
      showAdd.value
        ? h(AddRom, { onDone: onAdded })
        : null,
      message.value
        ? h('div', { class: 'toast' }, message.value)
        : null,
    ]);
  },
});

function detailView(g, state, { onClose, onToggleFav, onRescrape, onDelete, onCustomSearchDone }) {
  const userJson = localStorage.getItem('nasgame_user');
  let isAdmin = false;
  try { isAdmin = userJson ? JSON.parse(userJson).is_admin : false; } catch (e) {}
  const ds = state;  // reactive state owned by parent setup (跨渲染持久)
  // 如果上次 modal 未关 + 打开了新 detail, 重置 result (避免混淆)
  // (但保留输入框内容以免用户输入丢失)
  if (g.id !== ds._currentGameId) {
    ds._currentGameId = g.id;
    // 首次打开或换了游戏 → 重新初始化输入框, 清空 result
    ds.customSearch = g.title_en || g.title_zh || g.title_raw || '';
    ds.customSearching = false;
    ds.customSearchError = '';
    ds.customSearchResult = null;
    ds.customApplying = false;
  }

  async function submitCustomSearch() {
    const name = (ds.customSearch || '').trim();
    if (!name) return;
    ds.customSearching = true;
    ds.customSearchError = '';
    ds.customSearchResult = null;
    console.log('[NASGame] submitCustomSearch name=', name);
    try {
      const r = await api.post('/api/games/' + g.id + '/scrape-search',
        { custom_name: name });
      console.log('[NASGame] scrape-search response:', r);
      ds.customSearchResult = r;
      // 重置上传状态 (新搜次结果后, 旧的覆盖预览被覆盖)
      ds.coverUploading = false;
      ds.coverError = '';
    } catch (e) {
      console.error('[NASGame] scrape-search error:', e);
      ds.customSearchError = '✗ ' + (e.message || '搜索失败');
    } finally {
      ds.customSearching = false;
    }
  }
  async function uploadCover(file) {
    if (!file) return;
    ds.coverUploading = true;
    ds.coverError = '';
    try {
      // 把文件读为 data URL 预览, 同时上传
      const reader = new FileReader();
      const dataUrlPromise = new Promise((resolve, reject) => {
        reader.onload = () => resolve(reader.result);
        reader.onerror = reject;
        reader.readAsDataURL(file);
      });
      const [dataUrl] = await Promise.all([dataUrlPromise]);
      const r = await fetch('/api/games/' + g.id + '/cover', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + api.token },
        body: (() => {
          const fd = new FormData();
          fd.append('file', file, file.name || 'cover.jpg');
          return fd;
        })(),
      });
      if (!r.ok) {
        const err = await r.json().catch(() => ({ detail: '上传失败' }));
        throw new Error(err.detail || `HTTP ${r.status}`);
      }
      const j = await r.json();
      // 更新 modal 预览
      if (ds.customSearchResult && ds.customSearchResult.metadata) {
        ds.customSearchResult.metadata._cover_b64 = dataUrl.split(',')[1];
        const ext = (dataUrl.split(';')[0] || 'image/jpeg').split('/')[1] || 'jpg';
        ds.customSearchResult.metadata._cover_ext = ext;
        ds.customSearchResult.metadata.cover_url = j.url;
      }
      // 成功后回调刷新游戏详情 (封面立即可见)
      if (onCustomSearchDone) onCustomSearchDone();
    } catch (e) {
      console.error('[NASGame] cover upload:', e);
      ds.coverError = e.message || '上传失败';
    } finally {
      ds.coverUploading = false;
    }
  }
  function closeCustomSearchResult() {
    ds.customSearchResult = null;
    ds.customSearchError = '';
  }
  async function applyCustomSearchResult() {
    if (!ds.customSearchResult || !ds.customSearchResult.metadata) return;
    ds.customApplying = true;
    ds.customSearchError = '';
    try {
      await api.post('/api/games/' + g.id + '/scrape-apply', {
        metadata: ds.customSearchResult.metadata,
        source: ds.customSearchResult.source || 'custom',
      });
      ds.customSearchResult = null;
      if (onCustomSearchDone) onCustomSearchDone();
    } catch (e) {
      ds.customSearchError = '✗ ' + (e.message || '应用失败');
    } finally {
      ds.customApplying = false;
    }
  }
  return h('div', { class: 'detail-grid' }, [
    h('div', { class: 'detail-left' }, [
      g.cover
        ? h('img', { src: g.cover, class: 'detail-cover' })
        : h('div', { class: 'detail-cover-placeholder' }, g.platform?.code || '?'),
      ...((g.screenshots || []).map(s =>
        h('img', { src: s, class: 'detail-screenshot' }))),
    ]),
    h('div', { class: 'detail-right' }, [
      h('h1', null, g.title_zh || g.title_en || g.title_raw),
      (g.title_en && g.title_zh)
        ? h('h3', null, g.title_en) : null,
      h('div', { class: 'meta-grid' }, [
        h('div', null, [h('b', null, '平台:'), g.platform?.name]),
        h('div', null, [h('b', null, '年份:'), g.release_date || '未知']),
        h('div', null, [h('b', null, '类型:'), g.genre || '未知']),
        h('div', null, [h('b', null, '开发商:'), g.developer || '未知']),
        h('div', null, [h('b', null, '发行商:'), g.publisher || '未知']),
        h('div', null, [h('b', null, '玩家数:'), g.players || '1']),
        g.rating ? h('div', null, [h('b', null, '评分:'),
          '⭐ ' + Number(g.rating).toFixed(1)]) : null,
        h('div', null, [h('b', null, '文件:'), g.rom_filename]),
        h('div', null, [h('b', null, '大小:'), fmtSize(g.rom_size)]),
        h('div', null, [h('b', null, '游玩:'), (g.play_count || 0) + ' 次']),
      ]),
      h('p', { class: 'description' }, g.description || '(无简介)'),
      isAdmin ? h('div', { class: 'custom-search' }, [
        h('h4', null, '🔍 自定义名字刮削 (v2026-09-30-r11)'),
        h('p', { class: 'hint' },
          '文件名刮不准? 填一个准确的名字, 按 Enter 或点搜索; ' +
          '会按这个名字去掳源 (ScreenScraper / AI) 检索, 跳出子画面显示结果, ' +
          '点应用后覆盖当前元数据。'),
        h('div', { style: { display: 'flex', gap: '6px', alignItems: 'center' } }, [
          h('input', {
            type: 'text',
            value: ds.customSearch,
            placeholder: '例: Super Mario Bros / 马里奥 / 最终幻想 7',
            style: { flex: 1 },
            onInput: e => { ds.customSearch = e.target.value; },
            onKeydown: e => { if (e.key === 'Enter') submitCustomSearch(); },
          }),
          h('button', {
            class: 'primary',
            disabled: ds.customSearching || !(ds.customSearch || '').trim(),
            onClick: submitCustomSearch,
          }, ds.customSearching ? '搜索中...' : '🔍 搜索'),
          ds.customSearch ? h('button', {
            class: 'btn-link',
            onClick: () => { ds.customSearch = ''; ds.customSearchResult = null; },
          }, '清空') : null,
        ]),
        ds.customSearchError && !ds.customSearchResult
          ? h('div', { class: 'error', style: { marginTop: '6px' } }, ds.customSearchError)
          : null,
      ]) : null,
      h('div', { class: 'detail-actions' }, [
        isAdmin
          ? h('button', { onClick: onRescrape }, '🔄 重新刮削 (用文件名)') : null,
        isAdmin
          ? h('button', { class: 'danger', onClick: onDelete }, '🗑 删除') : null,
        h('button', { onClick: onToggleFav },
          g.favorite ? '★ 已收藏' : '☆ 收藏'),
      ]),
    ]),
    // === 子画面: 掳源检索结果 ===
    ds.customSearchResult ? renderCustomSearchModal({
      query: (ds.customSearch || '').trim(),
      result: ds.customSearchResult,
      applying: ds.customApplying,
      applyError: ds.customSearchError,
      onClose: closeCustomSearchResult,
      onApply: applyCustomSearchResult,
      onUploadCover: uploadCover,
      coverUploading: ds.coverUploading,
      coverError: ds.coverError,
    }) : null,
  ]);
}

// 子画面: 显示掳源返回的候选项 metadata
function renderCustomSearchModal({ query, result, applying, applyError, onClose, onApply,
                                   onUploadCover, coverUploading, coverError }) {
  const m = result.metadata || {};
  const src = result.source || '?';
  const srcLabel = src === 'screenscraper' ? 'ScreenScraper'
                 : src === 'ai' ? 'AI 识别'
                 : src;
  const coverSrc = m._cover_b64
    ? `data:image/${m._cover_ext || 'jpg'};base64,${m._cover_b64}`
    : (m.cover_url || '');
  const regions = m.regions || [];
  return h('div', {
    class: 'modal-bg',
    onClick: (e) => { if (e.target.classList.contains('modal-bg')) onClose(); },
  }, [
    h('div', {
      class: 'modal',
      style: { maxWidth: '720px', maxHeight: '90vh', overflow: 'auto' },
    }, [
      h('div', { class: 'modal-header' }, [
        h('h3', null, '🔍 掳源搜索结果'),
        h('button', { class: 'btn-link', onClick: onClose }, '✕'),
      ]),
      h('div', { style: { padding: '12px 16px' } }, [
        h('div', { style: { fontSize: '12px', color: 'var(--text-dim)', marginBottom: '10px' } }, [
          '搜索名: ', h('b', null, `"${query}"`), '  |  来源: ',
          h('b', { style: { color: 'var(--accent)' } }, srcLabel),
        ]),
        h('div', { style: { display: 'flex', gap: '16px', alignItems: 'flex-start' } }, [
          h('div', { style: { position: 'relative' } }, [
            coverSrc
              ? h('img', {
                  src: coverSrc,
                  style: {
                    width: '140px', height: '180px',
                    objectFit: 'cover', borderRadius: '6px',
                    background: 'var(--bg-card)',
                  },
                  onError: (e) => { e.target.style.display = 'none'; },
                })
              : h('div', {
                  style: {
                    width: '140px', height: '180px',
                    background: 'var(--bg-card)', borderRadius: '6px',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    color: 'var(--text-dim)', fontSize: '12px',
                  },
                }, '无封面'),
            // 上传封面按钮: 小覆盖在右下角
            h('label', {
              style: {
                position: 'absolute', bottom: '4px', right: '4px',
                background: 'rgba(0,0,0,0.75)', color: 'white',
                padding: '3px 8px', borderRadius: '4px',
                fontSize: '11px', cursor: coverUploading ? 'wait' : 'pointer',
                opacity: coverUploading ? 0.5 : 1,
              },
            }, [
              coverUploading ? '⏳' : '📷',
              coverUploading ? ' 上传中...' : ' 上传封面',
              h('input', {
                type: 'file',
                accept: 'image/jpeg,image/png,image/webp',
                style: { display: 'none' },
                disabled: coverUploading,
                onChange: (e) => {
                  const f = e.target.files?.[0];
                  if (f && onUploadCover) onUploadCover(f);
                  e.target.value = '';
                },
              }),
            ]),
            coverError ? h('div', {
              style: {
                position: 'absolute', top: '-22px', left: 0, right: 0,
                background: 'rgba(220,40,40,0.9)', color: 'white',
                padding: '2px 6px', borderRadius: '3px',
                fontSize: '10px', textAlign: 'center',
              },
            }, coverError) : null,
          ]),
          h('div', { style: { flex: 1 } }, [
            h('div', { style: { fontSize: '18px', fontWeight: '600', marginBottom: '4px' } },
              m.title_en || m.title_zh || m.title_jp || '(无标题)'),
            m.title_zh && m.title_en
              ? h('div', { style: { fontSize: '13px', color: 'var(--text-dim)', marginBottom: '6px' } },
                  `中文: ${m.title_zh}`)
              : null,
            h('div', { class: 'meta-grid', style: { fontSize: '13px' } }, [
              m.year ? h('div', null, [h('b', null, '年份:'), m.year]) : null,
              m.developer ? h('div', null, [h('b', null, '开发商:'), m.developer]) : null,
              m.publisher ? h('div', null, [h('b', null, '发行商:'), m.publisher]) : null,
              m.genre ? h('div', null, [h('b', null, '类型:'), m.genre]) : null,
              m.players ? h('div', null, [h('b', null, '玩家数:'), m.players]) : null,
              m.rating ? h('div', null, [h('b', null, '评分:'),
                '⭐ ' + Number(m.rating).toFixed(1)]) : null,
              regions.length ? h('div', null, [
                h('b', null, '地区:'), regions.join(', '),
              ]) : null,
            ]),
          ]),
        ]),
        (m.description_zh || m.description_en) ? h('details', { style: { marginTop: '12px' } }, [
          h('summary', { style: { cursor: 'pointer', fontSize: '13px' } }, '简介'),
          m.description_zh ? h('p', {
            style: {
              fontSize: '14px', color: 'var(--text)',
              marginTop: '6px', whiteSpace: 'pre-wrap', lineHeight: '1.5',
            },
          }, m.description_zh) : null,
          m.description_en ? h('p', {
            style: {
              fontSize: '12px', color: 'var(--text-dim)',
              marginTop: m.description_zh ? '8px' : '6px',
              whiteSpace: 'pre-wrap', lineHeight: '1.4',
              borderTop: m.description_zh ? '1px dashed var(--border)' : 'none',
              paddingTop: m.description_zh ? '6px' : 0,
            },
          }, m.description_en) : null,
        ]) : null,
        applyError ? h('div', {
          class: 'error',
          style: { marginTop: '10px', fontSize: '13px' },
        }, applyError) : null,
        h('div', {
          style: {
            display: 'flex', gap: '10px', justifyContent: 'flex-end',
            marginTop: '16px', paddingTop: '12px',
            borderTop: '1px solid var(--border)',
          },
        }, [
          h('button', { onClick: onClose, disabled: applying }, '取消'),
          h('button', {
            class: 'primary',
            disabled: applying,
            onClick: onApply,
          }, applying ? '应用中...' : '✅ 应用此结果到游戏'),
        ]),
      ]),
    ]),
  ]);
}

// ===== Platforms =====
const Platforms = defineComponent({
  setup() {
    const platforms = ref([]);
    onMounted(async () => {
      try { platforms.value = await api.get('/api/platforms'); }
      catch (e) { console.error('platforms:', e); }
    });
    return () => h('div', { class: 'page' }, [
      h('div', { class: 'page-header' }, [h('h2', null, '🕹️ 平台管理')]),
      h('p', { class: 'hint' },
        '提示: 把ROM文件按平台文件夹分类放置 (例如 fc/, sfc/, ps1/), ' +
        '系统会自动识别; 也可只放文件,系统按扩展名自动归类。'),
      h('table', { class: 'table' }, [
        h('thead', null, [h('tr', null, [
          h('th', null, '代号'), h('th', null, '名称'),
          h('th', null, '英文名'), h('th', null, '目录'),
          h('th', null, '扩展名'), h('th', null, '游戏数'),
          h('th', null, '启用'),
        ])]),
        h('tbody', null, platforms.value.map(p =>
          h('tr', { key: p.id }, [
            h('td', null, h('b', null, p.code)),
            h('td', null, p.name),
            h('td', null, p.name_en),
            h('td', null, h('code', null, p.folder)),
            h('td', null, h('code', null, p.extensions)),
            h('td', null, String(p.game_count)),
            h('td', null, p.enabled
              ? h('span', { class: 'status-ok' }, '✓')
              : h('span', { class: 'status-off' }, '✗')),
          ])
        )),
      ]),
    ]);
  },
});

// ===== Settings =====
const Settings = defineComponent({
  setup() {
    const cfg = reactive({});
    const pwd = reactive({ old: '', new: '' });
    const saved = ref(false);
    const testStatus = ref('');
    const proxyStatus = ref('');
    const test115Status = ref('');
    const qrTab = ref('scan');
    const qrSession = ref(null);
    const qrStatus = ref(null);
    let qrPoll = null;

    onMounted(async () => {
      try { Object.assign(cfg, await api.get('/api/config')); }
      catch (e) { console.error('config:', e); }
    });

    async function save() {
      try {
        await api.post('/api/config', cfg);
        saved.value = true;
        setTimeout(() => saved.value = false, 2000);
      } catch (e) { alert(e.message); }
    }
    async function testScraper() {
      testStatus.value = '测试中...';
      try {
        const r = await api.post('/api/config/test-scraper', {
          user: cfg.screenscraper_user,
          pass: cfg.screenscraper_pass,
          devid: cfg.screenscraper_devid,
          devpass: cfg.screenscraper_devpass,
        });
        testStatus.value = r.ok ? '✓ 连接成功' : '✗ ' + (r.error || '失败');
        setTimeout(() => testStatus.value = '', 4000);
      } catch (e) {
        testStatus.value = '✗ ' + e.message;
        setTimeout(() => testStatus.value = '', 4000);
      }
    }
    async function testProxy() {
      proxyStatus.value = '测试中...';
      try {
        const r = await api.post('/api/config/test-proxy', {
          proxy_url: cfg.proxy_url,
        });
        proxyStatus.value = r.ok ? '✓ ' + (r.msg || '代理可用') : '✗ ' + (r.error || '失败');
        setTimeout(() => proxyStatus.value = '', 5000);
      } catch (e) {
        proxyStatus.value = '✗ ' + e.message;
        setTimeout(() => proxyStatus.value = '', 5000);
      }
    }
    async function test115() {
      test115Status.value = '测试中...';
      try {
        const r = await api.post('/api/cloud/115/test', {
          cookie: cfg.cloud_115_cookie,
        });
        test115Status.value = r.ok ? '✓ ' + (r.msg || '连接成功') : '✗ ' + (r.error || '失败');
        setTimeout(() => test115Status.value = '', 5000);
      } catch (e) {
        test115Status.value = '✗ ' + e.message;
        setTimeout(() => test115Status.value = '', 5000);
      }
    }

    async function startQrLogin() {
      qrSession.value = null;
      qrStatus.value = null;
      try {
        const r = await api.post('/api/cloud/115/qr/start', {});
        if (!r.ok) {
          alert('创建扫码会话失败: ' + (r.error || '未知错误'));
          return;
        }
        qrSession.value = r;
        // 开始轮询 (每 2 秒一次)
        if (qrPoll) clearInterval(qrPoll);
        qrPoll = setInterval(() => pollQrStatus(), 2000);
      } catch (e) {
        alert('启动扫码登录失败: ' + e.message);
      }
    }
    async function pollQrStatus() {
      if (!qrSession.value) return;
      try {
        const r = await api.get('/api/cloud/115/qr/status/' + qrSession.value.uid);
        if (!r.ok) {
          qrStatus.value = { status: -1, message: r.error };
          return;
        }
        qrStatus.value = r;
        if (r.status === 2) {
          // 确认成功, 停止轮询, 但等用户点 "使用该 Cookie" 才存
          if (qrPoll) { clearInterval(qrPoll); qrPoll = null; }
        }
      } catch (e) {
        // ignore
      }
    }
    async function confirmQrLogin() {
      console.log('confirmQrLogin called, qrStatus=', JSON.stringify(qrStatus.value));
      if (!qrStatus.value || qrStatus.value.status !== 2) {
        console.warn('qrStatus invalid:', qrStatus.value);
        return;
      }
      // 检查 cookie 是否完整
      const missing = qrStatus.value.missing_fields || [];
      if (missing.length) {
        const proceed = confirm(
          '扫码返回的 Cookie 缺字段: ' + missing.join(', ') +
          '\n\n点 "确定" 仍保存 (适用于 Cookie 格式不同的情况), 点 "取消" 返回。'
        );
        if (!proceed) return;
      }
      cfg.cloud_115_cookie = qrStatus.value.cookie_string;
      test115Status.value = '已扫码, 保存中...';
      // 自动保存配置
      try {
        await api.post('/api/config', {
          cloud_115_enabled: true,
          cloud_115_cookie: qrStatus.value.cookie_string,
        });
        cfg.cloud_115_cookie_set = true;
        test115Status.value = '✓ 扫码登录成功并保存';
        setTimeout(() => test115Status.value = '', 4000);
        // 刷新 cfg
        const newCfg = await api.get('/api/config');
        Object.assign(cfg, newCfg);
        // 提示已保存
        alert('✓ Cookie 已保存!\n\n现在可以进入 “云盘” 页选择目录导入 ROM。');
      } catch (e) {
        test115Status.value = '✗ 保存失败: ' + e.message;
        alert('保存失败: ' + e.message);
      }
    }
    async function changePwd() {
      if (!pwd.old || !pwd.new) return alert('请填写旧密码和新密码');
      try {
        const fd = new FormData();
        fd.append('old', pwd.old); fd.append('new', pwd.new);
        await api.post('/api/auth/change-password', fd);
        alert('密码已修改');
        pwd.old = ''; pwd.new = '';
      } catch (e) { alert(e.message); }
    }

    const userJson = localStorage.getItem('nasgame_user');
    let isAdmin = false;
    try { isAdmin = userJson ? JSON.parse(userJson).is_admin : false; } catch (e) {}

    return () => h('div', { class: 'page' }, [
      h('h2', null, '⚙️ 系统设置'),
      h('form', { onSubmit: e => { e.preventDefault(); save(); } }, [
        h('fieldset', null, [
          h('legend', null, '🌐 网络代理'),
          h('p', { class: 'hint', style: { marginBottom: '12px' } }, [
            '如果需要通过代理访问外部服务 (例如 ScreenScraper、LibRetro 缩略图) , 在这里设置。 ',
            '115 网盘走直连, 不走这个代理。',
            '如不需要, 关闭即可。',
          ]),
          h('div', { class: 'form-group' }, [
            h('label', null, [
              h('input', {
                type: 'checkbox',
                checked: !!cfg.proxy_enabled,
                onChange: e => cfg.proxy_enabled = e.target.checked,
              }),
              ' 启用代理',
            ]),
          ]),
          h('div', { class: 'form-group' }, [
            h('label', null, '代理地址'),
            h('input', { value: cfg.proxy_url || '',
              onInput: e => cfg.proxy_url = e.target.value,
              placeholder: 'http://192.168.31.144:1081',
              disabled: !cfg.proxy_enabled,
            }),
            h('p', { class: 'hint' }, '支持 http:// 和 socks5:// 代理。'),
            h('button', { type: 'button', style: { marginTop: '6px' },
                onClick: testProxy, disabled: !cfg.proxy_enabled },
              proxyStatus.value || '测试代理连接'),
          ]),
        ]),
        h('fieldset', null, [
          h('legend', null, '刮削源'),
          h('p', { class: 'hint', style: { marginBottom: '12px' } }, [
            '本系统自动按顺序尝试多个源: ',
            h('b', null, '内置库 → LibRetro 元数据 → ScreenScraper → LibRetro(封面)'),
            '。',
            h('br'),
            '默认情况下 ',
            h('b', null, '无需任何账号'),
            ' 即可获得标题/年份/地区/发行商等元数据 (来自 LibRetro 缩略图目录) 。',
          ]),
          h('div', { class: 'success-box', style: { marginBottom: '12px' } }, [
            '✅ ',
            h('b', null, '默认刮削源'),
            ': ', h('br'),
            ' 1️⃣ ', h('b', null, '内置库'),
            ' - 部分经典游戏 (马里奥、魂斗罗、塞尔达等) ', h('br'),
            ' 2️⃣ ', h('b', null, 'LibRetro 元数据'),
            ' - 无需 API, 自动从 thumbnails.libretro.com 拉取文件名 (包含官方标题/年份/地区/发行商), 缓存到本地 ', h('br'),
            ' 3️⃣ ', h('b', null, 'LibRetro 封面'),
            ' - 无需 API, 直接下载官匹的缩略图 ', h('br'),
          ]),
          h('details', { style: { marginTop: '12px' } }, [
            h('summary', { style: { cursor: 'pointer', color: 'var(--text-dim)',
                fontSize: '13px', marginBottom: '8px' } },
              '⚙️ 可选: 添加 ScreenScraper (需注册论坛账号, 能获得更全的描述和截图)'),
            h('div', { class: 'form-group' }, [
              h('label', null, 'ScreenScraper 用户名'),
              h('input', { value: cfg.screenscraper_user || '',
                onInput: e => cfg.screenscraper_user = e.target.value,
                placeholder: '在 screenscraper.fr 注册' }),
            ]),
            h('div', { class: 'form-group' }, [
              h('label', null, 'ScreenScraper 密码 ' +
                (cfg.screenscraper_pass_set ? '✓ 已设置' : '(未设置)')),
              h('input', {
                type: 'password',
                value: cfg.screenscraper_pass || '',
                onInput: e => cfg.screenscraper_pass = e.target.value,
                placeholder: cfg.screenscraper_pass_set
                  ? '已设置 - 输入新值覆盖'
                  : '在 screenscraper.fr 注册后填入',
              }),
            ]),
            h('details', null, [
              h('summary', { style: { cursor: 'pointer', color: 'var(--text-dim)',
                  fontSize: '13px', marginBottom: '8px' } },
                '⚙️ 高级: 开发者账号 (需在 SS 论坛申请)'),
              h('p', { class: 'hint', style: { marginBottom: '8px' } }, [
                '⚠️ ',
                h('b', null, '重要'),
                '：ScreenScraper 的 ',
                h('code', null, 'devid/devpassword'),
                ' 不是你网站的登录账号。须在 ',
                h('a', { href: 'https://www.screenscraper.fr/forumsujets.php?frub=12&numpage=0',
                    target: '_blank' }, 'SS 论坛开发者区'),
                ' 发帖申请你软件的专属 API 凭据。 ',
                h('br'),
                '不填也可用 —— 系统内置了经典游戏库 (超级马里奥、魂斗罗、塞尔达、最终幻想7、时空之轮、仙剑等), 存有部分元数据。',
              ]),
              h('div', { class: 'form-group' }, [
                h('label', null, '开发者 ID'),
                h('input', { value: cfg.screenscraper_devid || '',
                  onInput: e => cfg.screenscraper_devid = e.target.value,
                  placeholder: '留空使用默认值' }),
              ]),
              h('div', { class: 'form-group' }, [
                h('label', null, '开发者密码 ' +
                  (cfg.screenscraper_devpass_set ? '✓ 已设置' : '(未设置)')),
                h('input', {
                  type: 'password',
                  value: cfg.screenscraper_devpass || '',
                  onInput: e => cfg.screenscraper_devpass = e.target.value,
                  placeholder: cfg.screenscraper_devpass_set
                    ? '已设置 - 输入新值覆盖'
                    : '留空使用默认值',
                }),
              ]),
            ]),
            h('p', { class: 'hint' }, [
              'ScreenScraper 是最大的ROM元数据库,需要注册免费账号。 ',
              h('a', { href: 'https://www.screenscraper.fr', target: '_blank' }, '→ 注册'),
            ]),
          ]),
        ]),
        h('fieldset', null, [
          h('legend', null, 'AI 智能识别 (可选)'),
          h('div', { class: 'form-group' }, [
            h('label', null, [
              h('input', {
                type: 'checkbox',
                checked: !!cfg.ai_enabled,
                onChange: e => cfg.ai_enabled = e.target.checked,
              }),
              ' 启用 AI (需要 OpenAI 兼容 API)',
            ]),
          ]),
          h('div', { class: 'form-group' }, [
            h('label', null, 'API 地址'),
            h('input', { value: cfg.ai_base_url || '',
              onInput: e => cfg.ai_base_url = e.target.value,
              placeholder: 'https://api.openai.com/v1' }),
          ]),
          h('div', { class: 'form-group' }, [
            h('label', null, 'API Key ' +
              (cfg.ai_api_key_set ? '✓ 已设置' : '(未设置)')),
            h('input', {
              type: 'password',
              value: cfg.ai_api_key || '',
              onInput: e => cfg.ai_api_key = e.target.value,
              placeholder: cfg.ai_api_key_set
                ? '已设置 - 输入新值覆盖'
                : 'sk-...',
            }),
          ]),
          h('div', { class: 'form-group' }, [
            h('label', null, '模型'),
            h('input', { value: cfg.ai_model || '',
              onInput: e => cfg.ai_model = e.target.value,
              placeholder: 'gpt-4o-mini' }),
          ]),
          h('p', { class: 'hint' },
            'AI 用于识别不规范的游戏名, 以及优化游戏简介翻译。'),
        ]),
        h('fieldset', null, [
          h('legend', null, '串流'),
          h('div', { class: 'form-group' }, [
            h('label', null, [
              h('input', {
                type: 'checkbox',
                checked: !!cfg.stream_enabled,
                onChange: e => cfg.stream_enabled = e.target.checked,
              }),
              ' 启用 NAS 服务器端串流游玩',
            ]),
          ]),
        ]),

        h('fieldset', null, [
          h('legend', null, '☁️ 115 云盘'),
          h('p', { class: 'hint', style: { marginBottom: '12px' } }, [
            '把 115 网盘上的 ROM 导入到游戏库，',
            '本地没有时可以从网盘 302 直连下载玩。',
          ]),
          h('div', { class: 'form-group' }, [
            h('label', null, [
              h('input', {
                type: 'checkbox',
                checked: !!cfg.cloud_115_enabled,
                onChange: e => cfg.cloud_115_enabled = e.target.checked,
              }),
              ' 启用 115 网盘',
            ]),
          ]),

          // Tab 切换: 扫码登录 / 手动输入
          h('div', { class: 'tabs' }, [
            h('button', {
              class: 'tab' + (qrTab.value === 'scan' ? ' active' : ''),
              onClick: () => qrTab.value = 'scan',
            }, '📱 扫码登录 (推荐)'),
            h('button', {
              class: 'tab' + (qrTab.value === 'manual' ? ' active' : ''),
              onClick: () => qrTab.value = 'manual',
            }, '⌨️ 手动输入'),
          ]),

          qrTab.value === 'scan'
            ? h('div', { class: 'qr-login' }, [
                h('p', { class: 'hint' }, [
                  '用 ',
                  h('b', null, '手机 115 App'),
                  ' 扫下面的二维码，或 ',
                  h('b', null, '已登录 115 的浏览器'),
                  ' 点击二维码跳转确认。',
                ]),
                qrSession.value
                  ? h('div', { style: { textAlign: 'center' } }, [
                      h('img', {
                        src: qrSession.value.qr_png_data_uri,
                        style: { width: '240px', height: '240px', border: '1px solid var(--border)' },
                      }),
                      h('p', { class: 'hint' }, [
                        '状态：',
                        qrStatus.value
                          ? (qrStatus.value.status === 2
                              ? h('span', { style: { color: 'var(--success)' } }, '✓ 已确认')
                              : h('span', { style: { color: 'var(--accent)' } },
                                  qrStatus.value.message))
                          : '⏳ 等待扫码',
                      ]),
                      h('p', { class: 'hint', style: { fontSize: '11px' } },
                        'uid: ' + qrSession.value.uid),
                      h('p', { class: 'hint', style: { fontSize: '11px' } }, [
                        '手机 115 App 扫码, ',
                        h('br'),
                        '或在已登录 115 的浏览器打开: ',
                        h('a', {
                          href: qrSession.value.qr_url,
                          target: '_blank',
                          rel: 'noopener',
                          style: { color: 'var(--accent)' },
                        }, qrSession.value.qr_url.replace('https://115.com','')),
                      ]),
                      qrStatus.value && qrStatus.value.status === 2
                        ? h('button', {
                            type: 'button',
                            class: 'primary',
                            style: { marginTop: '10px' },
                            onClick: confirmQrLogin,
                          }, '✓ 使用该 Cookie')
                        : null,
                    ])
                  : h('button', {
                      type: 'button',
                      class: 'primary',
                      onClick: startQrLogin,
                      disabled: !cfg.cloud_115_enabled,
                    }, '🪄 生成登录二维码'),
                qrPoll ? null : null, // (轮询定时器引用)
              ])
            : h('div', { class: 'manual-cookie' }, [
                h('div', { class: 'form-group' }, [
                  h('label', null, [
                    'Cookie',
                    cfg.cloud_115_cookie_set
                      ? h('span', { style: { color: 'var(--success)', marginLeft: '8px' } }, '✓ 已设置')
                      : null,
                  ]),
                  h('textarea', {
                    value: cfg.cloud_115_cookie || '',
                    onInput: e => cfg.cloud_115_cookie = e.target.value,
                    placeholder: 'UID=xxx; CID=xxx; SEID=xxx',
                    rows: 3,
                    style: { fontFamily: 'monospace', fontSize: '12px' },
                    disabled: !cfg.cloud_115_enabled,
                  }),
                  h('p', { class: 'hint' }, [
                    '从浏览器 115.com 已登录的页面 DevTools 里复制：',
                    h('br'), 'Application → Cookies → UID / CID / SEID',
                  ]),
                  h('button', { type: 'button', style: { marginTop: '6px' },
                      onClick: test115,
                      disabled: !cfg.cloud_115_enabled || !cfg.cloud_115_cookie },
                    test115Status.value || '测试 115 连接'),
                ]),
              ]),
        ]),
        isAdmin ? h('fieldset', null, [
          h('legend', null, '修改密码'),
          h('div', { class: 'form-group' }, [
            h('label', null, '旧密码'),
            h('input', { type: 'password', value: pwd.old,
              onInput: e => pwd.old = e.target.value }),
          ]),
          h('div', { class: 'form-group' }, [
            h('label', null, '新密码'),
            h('input', { type: 'password', value: pwd.new,
              onInput: e => pwd.new = e.target.value }),
          ]),
          h('button', { type: 'button', onClick: changePwd }, '修改密码'),
        ]) : null,
        h('div', { class: 'form-actions' }, [
          h('button', { type: 'submit', class: 'primary' }, '保存设置'),
          saved.value ? h('span', { class: 'status-ok' }, '✓ 已保存') : null,
          h('button', { type: 'button', style: { marginLeft: '8px' },
              onClick: testScraper },
            testStatus.value || '测试刮削连接'),
        ]),
      ]),
    ]);
  },
});

// ===== Tasks =====
const Tasks = defineComponent({
  setup() {
    const tasks = ref([]);
    const selected = reactive(new Set());
    const message = ref('');
    async function load() {
      try {
        const data = await api.get('/api/library/tasks?limit=100');
        tasks.value = data;
        // 丢掉已不在任务列表中的选中项
        const validIds = new Set(data.map(t => t.id));
        for (const id of [...selected]) {
          if (!validIds.has(id)) selected.delete(id);
        }
      } catch (e) { console.error('tasks:', e); }
    }
    onMounted(load);
    const timer = setInterval(load, 5000);
    onUnmounted(() => clearInterval(timer));

    function toggleOne(id) {
      if (selected.has(id)) selected.delete(id);
      else selected.add(id);
    }
    function toggleAll() {
      const finished = tasks.value.filter(t => t.status !== 'running');
      if (selected.size === finished.length && finished.length > 0) {
        selected.clear();
      } else {
        finished.forEach(t => selected.add(t.id));
      }
    }
    function isAllSelected() {
      const finished = tasks.value.filter(t => t.status !== 'running');
      return finished.length > 0 && selected.size === finished.length;
    }
    async function deleteOne(t) {
      if (!confirm(`删除任务 #${t.id} (${t.type})?`)) return;
      try {
        await api.delete(`/api/library/tasks/${t.id}`);
        selected.delete(t.id);
        message.value = `✓ 已删除任务 #${t.id}`;
        await load();
      } catch (e) {
        message.value = '✗ 删除失败: ' + e.message;
      }
      setTimeout(() => message.value = '', 3000);
    }
    async function deleteBatch() {
      if (selected.size === 0) return;
      if (!confirm(`批量删除 ${selected.size} 个任务?`)) return;
      try {
        const r = await api.post('/api/library/tasks/delete-batch',
                                  { ids: [...selected] });
        selected.clear();
        const skipped = r.skipped && r.skipped.length
          ? ` (跳过 ${r.skipped.length} 个运行中的)` : '';
        message.value = `✓ 已删除 ${r.deleted.length} 个任务${skipped}`;
        await load();
      } catch (e) {
        message.value = '✗ 删除失败: ' + e.message;
      }
      setTimeout(() => message.value = '', 3000);
    }
    async function clearFinished() {
      const n = tasks.value.filter(t => t.status !== 'running').length;
      if (n === 0) { message.value = '没有已完成的任务'; setTimeout(() => message.value = '', 2000); return; }
      if (!confirm(`清理全部 ${n} 个已完成/已取消的任务?`)) return;
      try {
        const r = await api.post('/api/library/tasks/clear-finished');
        selected.clear();
        message.value = `✓ 已清理 ${r.deleted} 个任务`;
        await load();
      } catch (e) {
        message.value = '✗ 清理失败: ' + e.message;
      }
      setTimeout(() => message.value = '', 3000);
    }

    return () => h('div', { class: 'page' }, [
      h('div', { class: 'page-header' }, [
        h('h2', null, '📋 任务队列'),
        h('div', { style: { display: 'flex', gap: '8px' } }, [
          h('button', { onClick: load }, '🔄 刷新'),
          h('button', {
            class: 'danger',
            disabled: selected.size === 0,
            onClick: deleteBatch,
          }, `🗑 删除选中 (${selected.size})`),
          h('button', { onClick: clearFinished }, '🧹 清理已完成'),
        ]),
      ]),
      message.value
        ? h('div', { class: 'toast' }, message.value)
        : null,
      h('table', { class: 'table' }, [
        h('thead', null, [h('tr', null, [
          h('th', { style: { width: '40px' } }, [
            h('input', {
              type: 'checkbox',
              checked: isAllSelected(),
              onChange: toggleAll,
              title: '全选已完成的任务',
            }),
          ]),
          h('th', null, 'ID'), h('th', null, '类型'),
          h('th', null, '目标'), h('th', null, '进度'),
          h('th', null, '状态'), h('th', null, '消息'),
          h('th', null, '创建'), h('th', null, '完成'),
          h('th', { style: { width: '60px' } }, '操作'),
        ])]),
        h('tbody', null, tasks.value.length === 0
          ? [h('tr', null, [h('td', { colspan: '10', class: 'loading' }, '暂无任务')])]
          : tasks.value.map(t =>
              h('tr', { key: t.id, class: selected.has(t.id) ? 'selected' : '' }, [
                h('td', null, [
                  h('input', {
                    type: 'checkbox',
                    checked: selected.has(t.id),
                    onChange: () => toggleOne(t.id),
                    disabled: t.status === 'running',
                    title: t.status === 'running' ? '运行中的任务不能删' : '选中',
                  }),
                ]),
                h('td', null, String(t.id)),
                h('td', null, h('span', { class: 'badge ' + t.type }, t.type)),
                h('td', null, t.target),
                h('td', null, [
                  h('div', { class: 'progress' }, [
                    h('div', {
                      class: 'progress-bar',
                      style: { width: (t.total ? (t.processed / t.total * 100) : 0) + '%' },
                    }),
                    h('span', null, t.processed + '/' + t.total),
                  ]),
                  (t.success || t.failed)
                    ? h('small', null, ' ✓' + (t.success || 0) + ' ✗' + (t.failed || 0))
                    : null,
                ]),
                h('td', null, h('span', { class: 'badge status-' + t.status }, t.status)),
                h('td', null, t.message),
                h('td', null, fmtTime(t.created_at)),
                h('td', null, fmtTime(t.finished_at)),
                h('td', null, [
                  t.status !== 'running'
                    ? h('button', {
                        class: 'icon-btn danger',
                        title: '删除',
                        onClick: () => deleteOne(t),
                      }, '✕')
                    : h('span', { class: 'muted' }, '…'),
                ]),
              ])
            )
        ),
      ]),
    ]);
  },
});

// ===== Cloud (115 网盘) =====
const Cloud115 = defineComponent({
  setup() {
    const enabled = ref(false);
    const items = ref([]);
    const path = ref([{ cid: "0", name: '根目录' }]);
    const current = ref(0);  // cid
    const loading = ref(false);
    const message = ref('');
    const platform = ref('');
    const platforms = ref([]);
    const showImported = ref(false);

    // ===== 云盘整理后的游戏列表 =====
    const cloudGames = ref([]);
    const cloudGamesLoading = ref(false);
    const cloudGamesTotal = ref(0);

    async function loadCloudGames() {
      cloudGamesLoading.value = true;
      try {
        const r = await api.get('/api/games?cloud_source=115&sort=created&page=1&page_size=20');
        cloudGames.value = r.items || [];
        cloudGamesTotal.value = r.total || 0;
      } catch (e) {
        console.warn('loadCloudGames fail', e);
      } finally {
        cloudGamesLoading.value = false;
      }
    }

    // ===== 添加云盘目录: 弹窗选择器 =====
    const pickerOpen = ref(false);
    const pickerItems = ref([]);
    const pickerPath = ref([{ cid: "0", name: '根目录' }]);
    const pickerCurrent = ref(0);
    const pickerLoading = ref(false);
    const pickerPlatform = ref('');
    const pickerAutoScrape = ref(true);
    const pickerSelected = ref(null);  // {cid, name, type, path}
    const pickerImporting = ref(false);
    const pickerError = ref('');
    const pickerResult = ref(null);

    async function openPicker() {
      pickerOpen.value = true;
      pickerPath.value = [{ cid: "0", name: '根目录' }];
      pickerCurrent.value = "0";
      pickerSelected.value = null;
      pickerError.value = '';
      pickerResult.value = null;
      pickerImporting.value = false;
      await pickerNavigate("0");
    }
    function closePicker() {
      pickerOpen.value = false;
    }
    async function pickerNavigate(cid, nameHint) {
      console.log('[NASGame] pickerNavigate cid=', cid, 'nameHint=', nameHint);
      pickerLoading.value = true;
      pickerError.value = '';
      try {
        const r = await api.get('/api/cloud/115/list?cid=' + cid);
        console.log('[NASGame] pickerNavigate r.items count=', r.items?.length, 'path=', r.path);
        if (!r.ok) {
          pickerError.value = r.error || '加载失败';
          pickerItems.value = [];
          return;
        }
        pickerItems.value = r.items;
        // 115 API get_path 在子目录返回 [] 会丢面包屑；前端自己拼接路径
        if (cid === "0") {
          pickerPath.value = [{ cid: "0", name: '根目录' }];
        } else if (Array.isArray(r.path) && r.path.length > 0) {
          pickerPath.value = r.path;
        } else {
          // 本地拼接: 先查现有路径里有没有这个 cid (goUp/面包屑点击场景)
          const idx = pickerPath.value.findIndex(p => p.cid === cid);
          if (idx >= 0) {
            pickerPath.value = pickerPath.value.slice(0, idx + 1);
          } else {
            // 推一项: 优先用传入的 nameHint (从点击的目录项拿), 没用就给 cid:XXX
            pickerPath.value = pickerPath.value.concat([{
              cid: cid,
              name: nameHint || `cid:${cid}`,
            }]);
          }
        }
        pickerCurrent.value = cid;
        pickerSelected.value = null;
      } catch (e) {
        pickerError.value = e.message || '网络错误';
      } finally {
        pickerLoading.value = false;
      }
    }
    async function pickerGoUp() {
      if (pickerPath.value.length <= 1) return;
      pickerPath.value = pickerPath.value.slice(0, -1);
      const parent = pickerPath.value[pickerPath.value.length - 1];
      await pickerNavigate(parent.cid);
    }
    async function pickerConfirm() {
      if (!pickerSelected.value) {
        pickerError.value = '请先选中一个目录 (点 ✓ 选中 或双击行进入子目录)';
        return;
      }
      const sel = pickerSelected.value;
      if (!confirm(`导入目录 "${sel.path}/" 下所有 ROM 到游戏库?`)) return;
      pickerImporting.value = true;
      pickerError.value = '';
      pickerResult.value = null;
      try {
        const r = await api.post('/api/cloud/115/scan', {
          cid: sel.cid,
          platform_code: pickerPlatform.value,
          auto_scrape: pickerAutoScrape.value,
        });
        pickerResult.value = { ok: true, msg: `✓ 任务已启动 (#${r.task_id}), 入库完成后会自动利削 (如勾选)。` };
        message.value = `✓ 云盘导入任务 #${r.task_id} 已启动`;
        setTimeout(() => message.value = '', 6000);
        // 刷新主页 cloud games 列表
        loadCloudGames();
        setTimeout(() => closePicker(), 2500);
      } catch (e) {
        pickerResult.value = { ok: false, msg: '✗ ' + (e.message || '导入失败') };
      } finally {
        pickerImporting.value = false;
      }
    }

    function pickerCrumbItems() {
      return pickerPath.value.map((p, i) => h('span', { key: i }, [
        i > 0 ? ' / ' : '',
        h('a', {
          onClick: (e) => { e.preventDefault(); pickerNavigate(p.cid, p.name); },
          href: '#',
        }, p.name),
      ]));
    }

    function renderPickerList() {
      if (pickerLoading.value) return h('div', { class: 'loading' }, '加载中...');
      if (pickerItems.value.length === 0) {
        return h('p', { class: 'hint' }, '空目录');
      }
      return h('div', { class: 'dir-list' },
        pickerItems.value.map((it, idx) => {
          // 115 返回的 items 可能有重复 cid, 用 idx 作 key 避免 Vue 错位复用 DOM
          const rowKey = (it.cid || 0) + ':' + idx + ':' + it.name;
          const isSel = pickerSelected.value &&
            pickerSelected.value.cid === it.cid &&
            pickerSelected.value.name === it.name;
          return h('div', {
            key: rowKey,
            class: 'dir-row ' + (it.is_dir ? 'dir' : 'file') + (isSel ? ' selected' : ''),
            onClick: () => {
              if (it.is_dir) {
                // 单击选中, 双击进入
                pickerSelected.value = {
                  cid: it.cid,
                  name: it.name,
                  type: 'dir',
                  path: pickerPath.value.map(p => p.name).concat(it.name).join('/'),
                };
              }
            },
            onDblclick: () => { if (it.is_dir) pickerNavigate(it.cid, it.name); },
          }, [
            h('span', { class: 'dir-icon' }, it.is_dir ? '📁' : '🎮'),
            h('span', { class: 'dir-name', style: { flex: 1 } }, it.name),
            h('span', { class: 'dir-size', style: { width: '100px', textAlign: 'right' } },
              it.is_dir ? '' : fmtSize(it.size)),
            it.is_dir
              ? h('div', { style: { display: 'flex', gap: '6px' } }, [
                  h('button', {
                    class: 'btn-link',
                    style: { padding: '2px 8px' },
                    onClick: (e) => {
                      e.stopPropagation();
                      pickerNavigate(it.cid, it.name);
                    },
                  }, '进入'),
                  h('button', {
                    class: 'btn-link primary',
                    style: { padding: '2px 8px' },
                    onClick: (e) => {
                      e.stopPropagation();
                      pickerSelected.value = {
                        cid: it.cid,
                        name: it.name,
                        type: 'dir',
                        path: pickerPath.value.map(p => p.name).concat(it.name).join('/'),
                      };
                    },
                  }, '✓ 选中'),
                ])
              : null,
          ]);
        })
      );
    }

    function renderPicker() {
      return h('div', {
        class: 'modal-bg',
        onClick: (e) => { if (e.target.classList.contains('modal-bg')) closePicker(); },
      }, h('div', { class: 'modal modal-large' }, [
        h('button', { class: 'modal-close', onClick: closePicker }, '✕'),
        h('h2', null, '➕ 添加云盘目录 (从 115 选择) (v2026-09-30-r11)'),
        h('div', {
          style: {
            fontSize: '11px', color: '#888', padding: '4px 8px',
            background: '#f8f8f8', marginBottom: '8px', borderRadius: '4px',
          },
        }, [
          h('span', null, '当前 cid: ' + pickerCurrent.value + ' | '),
          h('span', null, 'items: ' + pickerItems.value.length + ' | '),
          h('span', null, 'path: ' + pickerPath.value.map(p => p.name).join(' / ')),
        ]),
        h('p', { class: 'hint' }, [
          '点击目录右侧 ',
          h('strong', null, '✓ 选中'),
          ' 或列表行选中目录, 也可 ',
          h('strong', null, '双击行 / 点 进入 '),
          ' 下钻。选中后点下方 ',
          h('strong', null, '⏬ 导入游戏库'),
          ' 启动刮削任务。',
        ]),
        h('div', { class: 'path-bar' }, [
          ...pickerCrumbItems(),
          h('button', {
            class: 'btn-link refresh',
            style: { marginLeft: '8px' },
            onClick: () => pickerNavigate(pickerCurrent.value),
          }, '🔄 刷新'),
        ]),
        pickerError.value
          ? h('div', { class: 'error' }, pickerError.value)
          : null,
        renderPickerList(),
        h('div', { class: 'dir-actions' }, [
          h('div', { class: 'selected-info' },
            pickerSelected.value
              ? '已选中目录: ' + pickerSelected.value.path + '/'
              : (pickerCurrent.value === "0"
                  ? '提示: 请双击/点 进入 进入要导入的子目录, 或选中当前根目录导入'
                  : '提示: 可点下方 "📂 选中当前目录" 导入当前目录, 或选子目录')
          ),
          h('div', { style: { display: 'flex', gap: '12px', alignItems: 'center' } }, [
            h('label', null, '平台: '),
            h('select', {
              value: pickerPlatform.value,
              onChange: e => pickerPlatform.value = e.target.value,
            }, [
              h('option', { value: '' }, '全部'),
              ...platforms.value.map(p => h('option', { value: p.code }, p.code)),
            ]),
            h('label', { style: { marginLeft: '8px' } }, [
              h('input', {
                type: 'checkbox',
                checked: pickerAutoScrape.value,
                onChange: e => pickerAutoScrape.value = e.target.checked,
              }),
              ' 自动刮削',
            ]),
          ]),
          h('div', { style: { display: 'flex', gap: '8px' } }, [
            !pickerSelected.value
              ? h('button', {
                  class: 'btn-link',
                  onClick: () => {
                    const rel = pickerPath.value.map(p => p.name).join('/') || '(根)';
                    pickerSelected.value = {
                      cid: pickerCurrent.value,
                      name: pickerPath.value[pickerPath.value.length - 1].name,
                      type: 'dir',
                      path: rel,
                    };
                  },
                }, '📂 选中当前目录')
              : null,
            pickerSelected.value
              ? h('button', {
                  class: 'btn-link',
                  onClick: () => { pickerSelected.value = null; },
                }, '取消选中')
              : null,
            h('button', {
              class: 'primary',
              disabled: !pickerSelected.value || pickerImporting.value,
              onClick: pickerConfirm,
            }, pickerImporting.value ? '导入中...' : '⏬ 导入游戏库'),
          ]),
        ]),
        pickerResult.value
          ? h('div', {
              class: 'upload-result ' + (pickerResult.value.ok ? 'success' : 'error'),
            }, pickerResult.value.msg)
          : null,
      ]));
    }

    async function loadConfig() {
      try {
        const cfg = await api.get('/api/config');
        enabled.value = cfg.cloud_115_enabled;
        if (!enabled.value) {
          message.value = '提示: 请先在 设置 → 115 云盘 里启用并填入 Cookie';
        }
        // 无论 enabled 与否都加载平台 (用于导入时筛选)
        const r = await api.get('/api/platforms');
        platforms.value = r;
        if (!enabled.value) return;
        // 并行加载: 根目录 115 列表 + 云盘已整理游戏
        await Promise.all([navigate("0"), loadCloudGames()]);
      } catch (e) { message.value = '✗ ' + e.message; }
    }
    onMounted(loadConfig);

    async function navigate(cid) {
      loading.value = true;
      try {
        const r = await api.get('/api/cloud/115/list?cid=' + cid);
        if (!r.ok) {
          message.value = '✗ ' + r.error;
          items.value = [];
          return;
        }
        items.value = r.items;
        // 更新面包屑
        if (cid === "0") path.value = [{ cid: "0", name: '根目录' }];
        else path.value = r.path;
        current.value = cid;
      } catch (e) {
        message.value = '✗ ' + e.message;
      } finally {
        loading.value = false;
      }
    }

    async function goUp() {
      if (path.value.length <= 1) return;
      path.value.pop();
      const parent = path.value[path.value.length - 1];
      await navigate(parent.cid);
    }

    async function importDir(item) {
      if (!confirm(`导入目录 "${item.name}" 下所有 ROM 到游戏库?`)) return;
      try {
        const r = await api.post('/api/cloud/115/scan', {
          cid: item.cid,
          platform_code: platform.value,
          auto_scrape: true,
        });
        message.value = `✓ 任务已启动 (#${r.task_id}), 完成后会自动刮削`;
        setTimeout(() => message.value = '', 6000);
      } catch (e) {
        message.value = '✗ ' + e.message;
      }
    }

    function fmtSize(b) {
      if (!b) return '';
      const u = ['B', 'KB', 'MB', 'GB'];
      let n = b; let i = 0;
      while (n >= 1024 && i < 3) { n /= 1024; i++; }
      return n.toFixed(1) + ' ' + u[i];
    }
    function fmtTime(t) {
      if (!t) return '';
      return new Date(t * 1000).toLocaleString('zh-CN', { hour12: false });
    }

    return () => h('div', { class: 'page' }, [
      h('div', { class: 'page-header' }, [
        h('h2', null, '☁️ 115 云盘'),
        h('button', {
          onClick: () => loadCloudGames(),
        }, '🔄 刷新'),
        h('button', {
          onClick: openPicker,
          style: { marginLeft: '8px' },
          class: 'primary',
        }, '📂 添加云盘目录'),
      ]),
      enabled.value
        ? null
        : h('div', { class: 'warn-box' }, [
            '⚠️ 请先在 ', h('b', null, '设置 → 115 云盘'), ' 里启用并填入 Cookie。',
          ]),
      message.value
        ? h('div', { class: 'toast' }, message.value)
        : null,
      // ===== 云盘整理后的游戏列表 (首页主区) =====
      h('div', { class: 'section-header' }, [
        h('h3', null, '🎮 云盘整理的游戏'),
        h('span', { class: 'muted', style: { marginLeft: '8px' } },
          cloudGamesLoading.value ? '加载中...' : `共 ${cloudGamesTotal.value} 个`),
      ]),
      cloudGamesLoading.value && cloudGames.value.length === 0
        ? h('div', { class: 'loading' }, '加载中...')
        : cloudGames.value.length === 0
          ? h('div', { class: 'empty-hint' }, [
              '还没有从云盘导入的游戏。',
              h('br'),
              h('br'),
              '点击右上角 ', h('strong', null, '📂 添加云盘目录'),
              ' 选中一个 115 目录开始导入。',
            ])
          : h('div', { class: 'game-grid' },
              cloudGames.value.map(g => h('div', {
                key: g.id,
                class: 'game-card',
                onClick: () => {
                  const pg = usePendingGame();
                  if (pg) pg.value = g.id;
                  const emit = useEmit();
                  if (emit) emit('library');
                },
              }, [
                h('div', { class: 'game-cover' }, [
                  g.cover
                    ? h('img', { src: g.cover, alt: g.title, loading: 'lazy' })
                    : h('div', { class: 'cover-placeholder' }, '🎮'),
                  !g.local_available && g.cloud_source === '115'
                    ? h('span', { class: 'cloud-badge' }, '☁️ 云盘')
                    : null,
                ]),
                h('div', { class: 'game-info' }, [
                  h('div', { class: 'game-title' }, g.title || g.rom_filename || '(未命名)'),
                  h('div', { class: 'game-meta' }, [
                    g.platform ? h('span', { class: 'platform-tag' }, g.platform.code) : null,
                    h('span', { class: 'muted' }, g.rom_filename || ''),
                  ]),
                ]),
              ]))
            ),
      // ===== Picker Modal =====
      pickerOpen.value ? renderPicker() : null,
    ]);
  },
});

// ===== Root App =====
const AppRoot = defineComponent({
  setup() {
    const user = reactive({ username: '', is_admin: false });
    const page = ref('dashboard');
    const menu = [
      { id: 'dashboard', name: '概览', icon: '📊' },
      { id: 'library', name: '库浏览', icon: '📚' },
      { id: 'cloud', name: '云盘', icon: '☁️' },
      { id: 'platforms', name: '平台', icon: '🕹️' },
      { id: 'tasks', name: '任务', icon: '📋' },
      { id: 'settings', name: '设置', icon: '⚙️' },
    ];
    const currentPage = computed(() => ({
      dashboard: Dashboard, library: Library, cloud: Cloud115,
      platforms: Platforms, tasks: Tasks, settings: Settings,
    })[page.value] || Dashboard);

    function goto(p) { page.value = p; }
    async function logout() { api.setToken(''); location.reload(); }

    onMounted(async () => {
      try {
        const r = await api.get('/api/auth/me');
        user.username = r.username;
        user.is_admin = r.is_admin;
        localStorage.setItem('nasgame_user', JSON.stringify(r));
      } catch (e) {
        api.setToken(''); location.reload();
      }
    });

    // 提供 emit('goto', ...) 给子组件
    provide(EMIT_KEY, goto);
    // 待打开的游戏 ID (用于跨页跳转后自动打开详情)
    const pendingGameId = ref(null);
    provide(PENDING_GAME_KEY, pendingGameId);

    return () => h('div', { class: 'layout' }, [
      h('aside', { class: 'sidebar' }, [
        h('div', { class: 'brand' }, [
          h('span', { class: 'brand-icon' }, '🎮'),
          h('span', null, 'NasGameHub'),
        ]),
        h('nav', null, menu.map(m =>
          h('a', {
            key: m.id,
            class: page.value === m.id ? 'active' : '',
            onClick: () => goto(m.id),
          }, [
            h('span', { class: 'm-icon' }, m.icon),
            h('span', null, m.name),
          ])
        )),
        h('div', { class: 'user-info' }, [
          h('div', { class: 'avatar' },
            (user.username || '?')[0].toUpperCase()),
          h('div', null, [
            h('div', { class: 'user-name' }, user.username || '加载中...'),
            h('button', { class: 'btn-link', onClick: logout }, '退出登录'),
          ]),
        ]),
      ]),
      h('main', { class: 'content' }, [
        h(currentPage.value),
      ]),
    ]);
  },
});

// ===== Bootstrap =====
const Root = defineComponent({
  setup() {
    const loggedIn = ref(!!api.token);
    return () => loggedIn.value ? h(AppRoot) : h(Login);
  },
});

createApp(Root).mount('#app');
