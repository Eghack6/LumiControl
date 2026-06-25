const HOST = window.location.hostname;
const HTTP_PORT = 4560;
const WS_PORT = 4561;

let SENSITIVITY = 2.5;

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

function apiGet(path) {
  return fetch(`http://${HOST}:${HTTP_PORT}/api${path}`)
    .then(r => r.json()).catch(() => null);
}

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

// ---- Tab switching ----
document.getElementById('tabBar').addEventListener('click', (e) => {
  const tab = e.target.closest('[data-tab]');
  if (!tab) return;
  switchTab(tab.dataset.tab);
});

// Settings button
document.getElementById('settingsBtn').addEventListener('click', () => {
  switchTab('settings');
});

// Settings back button
document.getElementById('settingsBack').addEventListener('click', () => {
  switchTab('touchpad');
});

function switchTab(name) {
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  if (name !== 'settings') {
    document.querySelector('.tab-btn[data-tab="' + name + '"]')?.classList.add('active');
  }
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.getElementById('page-' + name)?.classList.add('active');
  if (name === 'settings') loadSettings();
}

// ---- Settings ----
let cachedSettings = null;

function loadSettings() {
  const container = document.getElementById('settingsContainer');
  container.innerHTML = '<div class="settings-loading">加载中...</div>';
  apiGet('/settings').then(s => {
    if (!s) { container.innerHTML = '<div class="settings-loading">加载失败</div>'; return; }
    cachedSettings = s;
    SENSITIVITY = s.sensitivity || 2.5;
    renderSettings(container, s);
  });
}

function renderSettings(container, s) {
  const shapes = [
    { value: 'circle', label: '◯ 圆形' },
    { value: 'dot', label: '● 圆点' },
    { value: 'arrow', label: '➤ 箭头' },
    { value: 'crosshair', label: '✚ 十字' },
    { value: 'custom', label: '✏ 自定义' }
  ];

  container.innerHTML = `
    <div class="settings-group">
      <div class="settings-label">光标外观</div>

      <div class="setting-row">
        <span class="setting-name">颜色</span>
        <input type="color" class="setting-color" id="s-color" value="${s.cursorColor}">
      </div>

      <div class="setting-row">
        <span class="setting-name">大小</span>
        <div class="setting-with-value">
           <input type="range" class="setting-range" id="s-size" min="20" max="500" value="${s.cursorSize}">
          <span class="setting-val" id="s-size-val">${s.cursorSize}px</span>
        </div>
      </div>

      <div class="setting-row">
        <span class="setting-name">形状</span>
        <div class="shape-options" id="s-shape">
          ${shapes.map(sh => `
            <label class="shape-option${sh.value === s.cursorShape ? ' active' : ''}">
              <input type="radio" name="shape" value="${sh.value}"${sh.value === s.cursorShape ? ' checked' : ''}>
              <span>${sh.label}</span>
            </label>
          `).join('')}
        </div>
      </div>

      <div class="custom-cursor-section">
        <div class="custom-cursor-preview" id="cursor-preview">
          <img id="cursor-preview-img" src="" style="display:none">
          <span id="cursor-preview-placeholder">暂无自定义光标</span>
        </div>
        <div class="custom-cursor-upload" id="custom-cursor-area" style="${s.cursorShape === 'custom' ? '' : 'display:none'}">
          <div class="custom-cursor-label">点击上传 PNG 图片作为自定义光标</div>
          <input type="file" id="cursor-file-input" accept="image/png" style="display:none">
        </div>
      </div>

      <div class="setting-row">
        <span class="setting-name">透明度</span>
        <div class="setting-with-value">
          <input type="range" class="setting-range" id="s-opacity" min="20" max="100" value="${s.cursorOpacity}">
          <span class="setting-val" id="s-opacity-val">${s.cursorOpacity}%</span>
        </div>
      </div>

      <div class="setting-row">
        <span class="setting-name">闲置隐藏</span>
        <select class="setting-select" id="s-timeout">
          <option value="3"${s.inactivityTimeout === 3 ? ' selected' : ''}>3 秒</option>
          <option value="5"${s.inactivityTimeout === 5 ? ' selected' : ''}>5 秒</option>
          <option value="10"${s.inactivityTimeout === 10 ? ' selected' : ''}>10 秒</option>
          <option value="0"${s.inactivityTimeout === 0 ? ' selected' : ''}>永不隐藏</option>
        </select>
      </div>
    </div>

    <div class="settings-group">
      <div class="settings-label">触控板</div>

      <div class="setting-row">
        <span class="setting-name">灵敏度</span>
        <div class="setting-with-value">
          <input type="range" class="setting-range" id="s-sensitivity" min="1" max="6" step="0.5" value="${s.sensitivity}">
          <span class="setting-val" id="s-sensitivity-val">${s.sensitivity}x</span>
        </div>
      </div>

      <div class="setting-row">
        <span class="setting-name">轻触点击</span>
        <label class="toggle">
          <input type="checkbox" id="s-taptoclick"${s.tapToClick ? ' checked' : ''}>
          <span class="toggle-track"></span>
        </label>
      </div>

      <div class="setting-row">
        <span class="setting-name">滑动方向</span>
        <select class="setting-select" id="s-scroll">
          <option value="normal"${s.scrollDirection === 'normal' ? ' selected' : ''}>自然方向</option>
          <option value="inverted"${s.scrollDirection === 'inverted' ? ' selected' : ''}>反向</option>
        </select>
      </div>

      <div class="setting-row">
        <span class="setting-name">摇杆速度</span>
        <div class="setting-with-value">
          <input type="range" class="setting-range" id="s-autoscroll-speed" min="1" max="10" step="1" value="${s.autoScrollSpeed || 5}">
          <span class="setting-val" id="s-autoscroll-speed-val">${s.autoScrollSpeed || 5}</span>
        </div>
      </div>

      <div class="setting-row">
        <span class="setting-name">摇杆垂直反向</span>
        <label class="toggle">
          <input type="checkbox" id="s-invert-v"${s.invertVertical ? ' checked' : ''}>
          <span class="toggle-track"></span>
        </label>
      </div>

      <div class="setting-row">
        <span class="setting-name">摇杆水平反向</span>
        <label class="toggle">
          <input type="checkbox" id="s-invert-h"${s.invertHorizontal ? ' checked' : ''}>
          <span class="toggle-track"></span>
        </label>
      </div>
    </div>

    <div class="settings-group">
      <div class="settings-label">反馈</div>

      <div class="setting-row">
        <span class="setting-name">按键震动</span>
        <label class="toggle">
          <input type="checkbox" id="s-vibrate"${s.vibrationFeedback ? ' checked' : ''}>
          <span class="toggle-track"></span>
        </label>
      </div>

      <div class="setting-row">
        <span class="setting-name">按键音效</span>
        <label class="toggle">
          <input type="checkbox" id="s-sound"${s.keySound ? ' checked' : ''}>
          <span class="toggle-track"></span>
        </label>
      </div>

      <div class="setting-row">
        <span class="setting-name">导出日志</span>
        <a class="setting-btn" href="http://${HOST}:${HTTP_PORT}/api/log" target="_blank">查看</a>
      </div>
    </div>
  `;

  // Bind events
  bindSetting('s-color', 'cursorColor', (el) => el.value);
  bindSetting('s-size', 'cursorSize', (el) => parseInt(el.value), (el, v) => { document.getElementById('s-size-val').textContent = v + 'px'; });
  bindSetting('s-opacity', 'cursorOpacity', (el) => parseInt(el.value), (el, v) => { document.getElementById('s-opacity-val').textContent = v + '%'; });
  bindSetting('s-sensitivity', 'sensitivity', (el) => parseFloat(el.value), (el, v) => { document.getElementById('s-sensitivity-val').textContent = v + 'x'; SENSITIVITY = v; });
  bindSetting('s-timeout', 'inactivityTimeout', (el) => parseInt(el.value));
  bindSetting('s-taptoclick', 'tapToClick', (el) => el.checked);
  bindSetting('s-scroll', 'scrollDirection', (el) => el.value);
  bindSetting('s-vibrate', 'vibrationFeedback', (el) => el.checked);
  bindSetting('s-sound', 'keySound', (el) => el.checked);
  bindSetting('s-autoscroll-speed', 'autoScrollSpeed', (el) => parseInt(el.value), (el, v) => { document.getElementById('s-autoscroll-speed-val').textContent = v; });
  bindSetting('s-invert-v', 'invertVertical', (el) => el.checked);
  bindSetting('s-invert-h', 'invertHorizontal', (el) => el.checked);

  // Shape radios
  document.querySelectorAll('#s-shape input').forEach(r => {
    r.addEventListener('change', () => {
      document.querySelectorAll('.shape-option').forEach(o => o.classList.remove('active'));
      r.closest('.shape-option').classList.add('active');
      saveSetting('cursorShape', r.value);
      const area = document.getElementById('custom-cursor-area');
      if (area) area.style.display = r.value === 'custom' ? '' : 'none';
    });
  });

  // Custom cursor upload
  const fileInput = document.getElementById('cursor-file-input');
  const previewImg = document.getElementById('cursor-preview-img');
  const previewPlaceholder = document.getElementById('cursor-preview-placeholder');

  if (fileInput) {
    // Click the upload area to trigger file select
    document.getElementById('custom-cursor-area')?.addEventListener('click', () => fileInput.click());

    fileInput.addEventListener('change', () => {
      const file = fileInput.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = (e) => {
        previewImg.src = e.target.result;
        previewImg.style.display = 'block';
        if (previewPlaceholder) previewPlaceholder.style.display = 'none';
      };
      reader.readAsDataURL(file);
      const form = new FormData();
      form.append('image', file);
      fetch(`http://${HOST}:${HTTP_PORT}/api/cursor-image`, {
        method: 'POST',
        body: form
      }).then(r => r.json()).then(r => {
        if (!r.ok) toast(r.error || '上传失败');
        else toast('✔ 自定义光标已应用');
      }).catch(() => toast('网络错误'));
    });
  }

  // Load existing custom cursor preview
  if (previewImg) {
    previewImg.src = `http://${HOST}:${HTTP_PORT}/api/cursor-image?t=${Date.now()}`;
    previewImg.onload = () => {
      previewImg.style.display = 'block';
      if (previewPlaceholder) previewPlaceholder.style.display = 'none';
    };
    previewImg.onerror = () => {
      previewImg.style.display = 'none';
      if (previewPlaceholder) previewPlaceholder.style.display = '';
    };
  }
}

function bindSetting(id, key, getValue, onUpdate) {
  const el = document.getElementById(id);
  if (!el) return;
  el.addEventListener('input', () => {
    const v = getValue(el);
    if (onUpdate) onUpdate(el, v);
    saveSetting(key, v);
  });
  el.addEventListener('change', () => {
    const v = getValue(el);
    saveSetting(key, v);
  });
}

let saveTimer = null;
function saveSetting(key, value) {
  if (!cachedSettings) return;
  cachedSettings[key] = value;
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    apiPost('/settings', cachedSettings).then(r => {
      if (r && r.ok) toast('✔ 已应用');
    });
  }, 300);
}

// ---- Touchpad ----
const touchpad = document.getElementById('touchpad');
const cursor = document.getElementById('cursor');

let cursorX = 0, cursorY = 0;
let lastTouchX = 0, lastTouchY = 0;
let touchStartX = 0, touchStartY = 0;
let isButtonTouch = false;
let touchMoved = false;
let cursorVisible = false;

function showLocalCursor(clientX, clientY) {
  if (!cursorVisible) {
    cursor.style.display = 'block';
    cursorVisible = true;
  }
  cursor.style.transform = `translate(${clientX - 11}px, ${clientY - 11}px)`;
}

touchpad.addEventListener('touchstart', (e) => {
  if (e.target.closest('button')) {
    isButtonTouch = true;
    return;
  }
  isButtonTouch = false;
  touchMoved = false;
  const t = e.touches[0];
  const rect = touchpad.getBoundingClientRect();
  lastTouchX = t.clientX - rect.left;
  lastTouchY = t.clientY - rect.top;
  touchStartX = lastTouchX;
  touchStartY = lastTouchY;
  showLocalCursor(lastTouchX, lastTouchY);
});

touchpad.addEventListener('touchmove', (e) => {
  if (isButtonTouch) return;
  e.preventDefault();
  touchMoved = true;
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

touchpad.addEventListener('touchend', () => {
  cursor.style.display = 'none';
  cursorVisible = false;
  if (isButtonTouch || touchMoved) return;
  const tapEnabled = cachedSettings ? cachedSettings.tapToClick : true;
  if (tapEnabled) {
    wsSend({ type: 'click' });
    toast('点击');
  }
});

touchpad.addEventListener('touchcancel', () => { cursor.style.display = 'none'; cursorVisible = false; });

// Mouse tap-to-click on touchpad
let mouseDown = false;
touchpad.addEventListener('mousedown', (e) => {
  if (e.target.closest('button')) return;
  mouseDown = true;
  touchMoved = false;
  const rect = touchpad.getBoundingClientRect();
  lastTouchX = e.clientX - rect.left;
  lastTouchY = e.clientY - rect.top;
  touchStartX = lastTouchX;
  touchStartY = lastTouchY;
});

touchpad.addEventListener('mousemove', (e) => {
  const rect = touchpad.getBoundingClientRect();
  const cx = e.clientX - rect.left;
  const cy = e.clientY - rect.top;
  if (e.buttons === 1) {
    touchMoved = true;
    const dx = (cx - lastTouchX) * SENSITIVITY;
    const dy = (cy - lastTouchY) * SENSITIVITY;
    cursorX += dx;
    cursorY += dy;
    wsSend({ type: 'move', dx, dy });
  }
  lastTouchX = cx;
  lastTouchY = cy;
});

touchpad.addEventListener('mouseup', (e) => {
  if (e.target.closest('button')) return;
  if (mouseDown && !touchMoved) {
    const tapEnabled = cachedSettings ? cachedSettings.tapToClick : true;
    if (tapEnabled) {
      wsSend({ type: 'click' });
      toast('点击');
    }
  }
  mouseDown = false;
});

touchpad.addEventListener('mouseleave', () => { mouseDown = false; });

// ---- Joystick ----
const joystick = document.getElementById('joystick');
const joystickKnob = document.getElementById('joystickKnob');
let joystickScrollTimer = null;
let joystickDir = { dx: 0, dy: 0 };

if (joystick && joystickKnob) {
  const R = 28;
  const DEAD = 10;

  function handleJoystick(clientX, clientY) {
    const rect = joystick.getBoundingClientRect();
    const cx = (clientX - rect.left) - R;
    const cy = (clientY - rect.top) - R;
    const dist = Math.sqrt(cx * cx + cy * cy);
    const clampDist = Math.min(dist, R);
    const angle = Math.atan2(cy, cx);
    const knobX = Math.cos(angle) * clampDist;
    const knobY = Math.sin(angle) * clampDist;
    joystickKnob.style.transform = `translate(${knobX}px, ${knobY}px)`;

    if (dist < DEAD) {
      joystickDir.dx = 0;
      joystickDir.dy = 0;
      joystick.classList.remove('active');
      return;
    }
    joystick.classList.add('active');
    const speed = (cachedSettings && cachedSettings.autoScrollSpeed) || 5;
    const amount = 2 * speed;
    if (Math.abs(cx) > Math.abs(cy)) {
      joystickDir.dx = cx > 0 ? amount : -amount;
      joystickDir.dy = 0;
      joystickKnob.style.transform = `translate(${cx > 0 ? clampDist : -clampDist}px, 0)`;
    } else {
      joystickDir.dx = 0;
      joystickDir.dy = cy > 0 ? amount : -amount;
      joystickKnob.style.transform = `translate(0, ${cy > 0 ? clampDist : -clampDist}px)`;
    }
  }

  function startJoystickScroll() {
    if (joystickScrollTimer) return;
    joystickScrollTimer = setInterval(() => {
      if (joystickDir.dx !== 0 || joystickDir.dy !== 0) {
        const iv = cachedSettings ? cachedSettings.invertVertical : false;
        const ih = cachedSettings ? cachedSettings.invertHorizontal : false;
        const fdx = ih ? -joystickDir.dx : joystickDir.dx;
        const fdy = iv ? -joystickDir.dy : joystickDir.dy;
        wsSend({ type: 'scroll', x: cursorX, y: cursorY, dx: fdx, dy: fdy });
      }
    }, 100);
  }

  function stopJoystick() {
    if (joystickScrollTimer) { clearInterval(joystickScrollTimer); joystickScrollTimer = null; }
    joystickDir.dx = 0;
    joystickDir.dy = 0;
    joystick.classList.remove('active');
    joystickKnob.style.transform = 'translate(0,0)';
  }

  joystick.addEventListener('touchstart', (e) => {
    e.preventDefault();
    e.stopPropagation();
    startJoystickScroll();
    handleJoystick(e.touches[0].clientX, e.touches[0].clientY);
  });

  joystick.addEventListener('touchmove', (e) => {
    e.preventDefault();
    e.stopPropagation();
    handleJoystick(e.touches[0].clientX, e.touches[0].clientY);
  });

  joystick.addEventListener('touchend', (e) => { e.stopPropagation(); stopJoystick(); });
  joystick.addEventListener('touchcancel', (e) => { e.stopPropagation(); stopJoystick(); });

  joystick.addEventListener('mousedown', (e) => {
    e.preventDefault();
    e.stopPropagation();
    startJoystickScroll();
    handleJoystick(e.clientX, e.clientY);
    const onMove = (ev) => handleJoystick(ev.clientX, ev.clientY);
    const onUp = () => { stopJoystick(); document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  });
}

// Keyboard shortcuts
document.addEventListener('keydown', (e) => {
  const map = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
    Enter: 'center', Escape: 'back', Home: 'home'
  };
  const a = map[e.key];
  if (a) { e.preventDefault(); sendAction(a); }
});

// Delegate key buttons and action buttons
document.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-key]');
  if (btn) sendAction(btn.dataset.key);
  const act = e.target.closest('[data-action]');
  if (act) {
    const action = act.dataset.action;
    if (action === 'reset') {
      wsSend({ type: 'reset' });
      toast('✔ 光标已复位');
    }
  }
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
        if (s.screenW && s.screenH) {
          cursorX = s.screenW / 2;
          cursorY = s.screenH / 2;
        }
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

if (uploadZone) {
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
}

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
