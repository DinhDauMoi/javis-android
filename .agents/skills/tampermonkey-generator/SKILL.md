---
name: tampermonkey-generator
description: >-
  Provides templates, lifecycle best practices, and recipes for packaging console
  automation scripts into full-featured Tampermonkey/Violentmonkey UserScripts with
  auto-execution and UI menus.
---

# Tampermonkey & Violentmonkey UserScript Skill

Use this skill when converting one-off console snippets into durable, automated browser extensions with automatic page execution, settings storage, and native extension menus.

---

## 1. Standard UserScript Metadata Header

Always include a well-defined metadata block matching target domains and declaring permissions:

```javascript
// ==UserScript==
// @name         Auto Clicker & Number Claimer
// @namespace    https://github.com/
// @version      1.0.0
// @description  Automates button clicks and countdown handling
// @author       You
// @match        https://*.example.com/*
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_registerMenuCommand
// @grant        GM_notification
// @run-at       document-idle
// ==/UserScript==
```

---

## 2. Key Tampermonkey Features

### Persistent Settings (`GM_getValue` / `GM_setValue`)
Store state across page reloads (e.g., auto-start enabled, custom delay, total clicks count):
```javascript
const isAutoStart = GM_getValue('autoStart', true);
function toggleAutoStart() {
  const current = GM_getValue('autoStart', true);
  GM_setValue('autoStart', !current);
  alert(`Auto-start is now: ${!current ? 'ON' : 'OFF'}`);
}
```

### Tampermonkey Extension Menu Integration
Register interactive commands directly in the extension popup menu:
```javascript
GM_registerMenuCommand('▶ Bật/Tắt Auto Click', () => {
  window.toggleAutoClicker();
});
GM_registerMenuCommand('⚙️ Đổi thời gian quét', () => {
  const ms = prompt('Nhập ms giữa các lần quét:', '500');
  if (ms) GM_setValue('checkInterval', Number(ms));
});
```

### Desktop Audio & Toast Notifications
Notify the user when a number is acquired even if the tab is in the background:
```javascript
function notifySuccess(message) {
  GM_notification({
    title: '🎉 THÀNH CÔNG!',
    text: message,
    timeout: 5000
  });
}
```
