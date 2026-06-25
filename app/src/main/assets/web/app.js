const HOST = window.location.hostname;
const HTTP_PORT = 4560;
const WS_PORT = 4561;

// Sensitivity multiplier for cursor movement
const SENSITIVITY = 2.5;

// ---- WebSocket ----
let ws = null;
let wsReconnectTimer = null;

function connectWs() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  ws = new WebSocket(`ws://${HOST}:${WS_PORT}`);
  ws.onopen = () => {};
  ws.onclose = () => {
    ws = null;
    clearTimeout(wsReconnectTimer);
    wsReconnectTimer = setTimeout(connectWs, 2000);
  };
  ws.onerror = () => { if (ws) ws.close(); };
}

function wsSend(data) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(data));
  } else if (data.type === 'click') {
    touchCmd('click', cursorX, cursorY);
  } else if (data.type === 'move') {
    touchCmd('pos', cursorX, cursorY);
  } else {
    touchCmd(data.type, data.x, data.y, data.dx, data.dy);
  }
}

connectWs();

function apiPost(path, body) {
  return fetch(`http://${HOST}:${HTTP_PORT}/api${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }).then(r => r.json()).catch(() => ({ ok: false, error: '网络错误' }));
}

/** Touch events via HTTP GET (simple, reliable) */
function touchCmd(type, x, y, dx, dy) {
  const p = new URLSearchParams({ type, x: Math.round(x), y: Math.round(y) });
  if (dx !== undefined) p.set('dx', Math.round(dx));
  if (dy !== undefined) p.set('dy', Math.round(dy));
  fetch(`http://${HOST}:${HTTP_PORT}/api/touch?${p}`).catch(() => {});
}

function sendAction(action) {
  apiPost('/key', { action }).then(r => {
    if (!r.ok) toast(r.error || '执行失败');
    else toast('✔ ' + action);
  });
}

function sendText() {
  const input = document.getElementById('textInput');
  const text = input.value.trim();
  if (!text) return;
  fetch(`http://${HOST}:${HTTP_PORT}/api/text?text=${encodeURIComponent(text)}`)
    .then(r => r.json())
    .then(r => {
      if (!r.ok) toast(r.error || '发送失败');
      else { toast('✔ 已发送'); input.value = ''; }
    })
    .catch(() => toast('网络错误'));
}

document.getElementById('sendBtn').addEventListener('click', sendText);
document.getElementById('textInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') sendText();
});

// ---- Touchpad: relative cursor control ----
const touchpad = document.getElementById('touchpad');
const clickBtn = document.getElementById('clickBtn');
const cursor = document.getElementById('cursor');

// Local cursor position (for HTTP fallback only; WS uses server-side position)
let cursorX = 960, cursorY = 540;
let lastTouchX = 0, lastTouchY = 0;

function showLocalCursor(clientX, clientY) {
  cursor.style.display = 'block';
  cursor.style.left = clientX + 'px';
  cursor.style.top = clientY + 'px';
}

touchpad.addEventListener('touchstart', (e) => {
  e.preventDefault();
  const t = e.touches[0];
  const rect = touchpad.getBoundingClientRect();
  lastTouchX = t.clientX - rect.left;
  lastTouchY = t.clientY - rect.top;
  showLocalCursor(lastTouchX, lastTouchY);
});

touchpad.addEventListener('touchmove', (e) => {
  e.preventDefault();
  const t = e.touches[0];
  const rect = touchpad.getBoundingClientRect();
  const cx = t.clientX - rect.left;
  const cy = t.clientY - rect.top;
  const dx = (cx - lastTouchX) * SENSITIVITY;
  const dy = (cy - lastTouchY) * SENSITIVITY;
  cursorX += dx;
  cursorY += dy;
  lastTouchX = cx;
  lastTouchY = cy;
  showLocalCursor(cx, cy);
  wsSend({ type: 'move', dx, dy });
});

touchpad.addEventListener('touchend', () => { cursor.style.display = 'none'; });
touchpad.addEventListener('touchcancel', () => { cursor.style.display = 'none'; });

// Click button — no coordinates, server reads cursor overlay position
clickBtn.addEventListener('touchend', (e) => {
  e.preventDefault();
  wsSend({ type: 'click' });
});
clickBtn.addEventListener('click', () => {
  wsSend({ type: 'click' });
});

// Mouse support (for desktop testing)
touchpad.addEventListener('mousemove', (e) => {
  const rect = touchpad.getBoundingClientRect();
  const cx = e.clientX - rect.left;
  const cy = e.clientY - rect.top;
  if (e.buttons === 1) {
    const dx = (cx - lastTouchX) * SENSITIVITY;
    const dy = (cy - lastTouchY) * SENSITIVITY;
    cursorX += dx;
    cursorY += dy;
    wsSend({ type: 'move', dx, dy });
  }
  lastTouchX = cx;
  lastTouchY = cy;
});

// Keyboard shortcuts
document.addEventListener('keydown', (e) => {
  const map = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
    Enter: 'center', Escape: 'back', Home: 'home'
  };
  const a = map[e.key];
  if (a) { e.preventDefault(); sendAction(a); }
});

// Delegate key buttons
document.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-key]');
  if (btn) sendAction(btn.dataset.key);
});

// ---- Status polling ----
function pollStatus() {
  fetch(`http://${HOST}:${HTTP_PORT}/api/status`)
    .then(r => r.json())
    .then(s => {
      const dot = document.getElementById('conn-status');
      const label = document.getElementById('conn-label');
      if (!dot || !label) return;
      dot.className = 'dot';
      if (s.accessibility) {
        dot.classList.add('ok');
        label.textContent = '已连接';
      } else {
        dot.classList.add('warn');
        label.textContent = '无障碍未开启';
      }
    })
    .catch(() => {
      const dot = document.getElementById('conn-status');
      const label = document.getElementById('conn-label');
      if (dot && label) {
        dot.className = 'dot err';
        label.textContent = '未连接';
      }
    });
}

setInterval(pollStatus, 3000);
pollStatus();

// ---- Toast ----
function toast(msg) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(el._timer);
  el._timer = setTimeout(() => el.classList.remove('show'), 2000);
}

// ---- APK Upload ----
const uploadZone = document.getElementById('uploadZone');
const fileInput = document.getElementById('fileInput');

uploadZone.addEventListener('click', () => fileInput.click());

uploadZone.addEventListener('dragover', (e) => {
  e.preventDefault();
  uploadZone.classList.add('dragover');
});
uploadZone.addEventListener('dragleave', () => {
  uploadZone.classList.remove('dragover');
});
uploadZone.addEventListener('drop', (e) => {
  e.preventDefault();
  uploadZone.classList.remove('dragover');
  const file = e.dataTransfer.files[0];
  if (file) uploadApk(file);
});

fileInput.addEventListener('change', () => {
  if (fileInput.files[0]) uploadApk(fileInput.files[0]);
});

function uploadApk(file) {
  if (!file.name.endsWith('.apk')) { toast('请选择 .apk 文件'); return; }
  const form = new FormData();
  form.append('apk', file);
  const progress = document.getElementById('uploadProgress');
  const fill = document.getElementById('progressFill');
  const status = document.getElementById('uploadStatus');
  progress.hidden = false;
  fill.style.width = '0%';
  status.textContent = '上传中...';
  const xhr = new XMLHttpRequest();
  xhr.open('POST', `http://${HOST}:${HTTP_PORT}/api/install`, true);
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) {
      fill.style.width = (e.loaded / e.total * 100) + '%';
    }
  };
  xhr.onload = () => {
    if (xhr.status === 200) {
      status.textContent = '上传完成，正在安装...';
      toast('APK 上传成功，正在安装');
    } else {
      try {
        const err = JSON.parse(xhr.responseText);
        status.textContent = err.error || '上传失败';
        toast(err.error || '上传失败');
      } catch (_) {
        status.textContent = '上传失败';
        toast('上传失败');
      }
    }
  };
  xhr.onerror = () => { status.textContent = '网络错误'; toast('网络错误'); };
  xhr.send(form);
}
