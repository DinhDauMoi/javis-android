---
name: web-auto-clicker
description: >-
  Provides specialized guidelines, code patterns, and workflows for developing,
  debugging, and optimizing browser auto-clicker scripts, countdown timers,
  React synthetic event triggers, and DOM automation tools.
---

# Web Auto Clicker & DOM Automation Skill

Use this skill when designing, improving, or debugging browser auto-clicker scripts, bookmarklets, Tampermonkey userscripts, or DevTools snippets for interacting with dynamic web applications.

---

## 1. Core Architecture Patterns

### Timer Cleanup & Multi-Instance Prevention
When users re-run scripts in the DevTools console, previous `setInterval` or `setTimeout` loops remain running unless explicitly stored on `window` and cleared:
```javascript
if (window.__autoClickerTimer) {
  clearInterval(window.__autoClickerTimer);
  window.__autoClickerTimer = null;
}
```

### Multi-Modal Stopping Mechanisms
Always provide multiple convenient ways for users to terminate automation without needing to rush to the console:
1. **Floating UI Button:** Fixed position, high `z-index`, accessible click-to-stop.
2. **Keyboard Shortcut:** Event listener for `Escape` key.
3. **Console Command:** Exported function on `window` (e.g., `window.stopAutoClicker()`).

---

## 2. Bypassing Modern Frontend Framework Limitations (React / Vue / Angular)

### The Disabled Button Trap
In HTML5 and modern frameworks (especially React), if `<button disabled>` is present:
- Native `element.click()` is suppressed by the browser engine.
- Synthetic React `onClick` listeners will not trigger.

### Solution: React Internal Fiber / Props Invocation
Directly access and execute React's attached event handlers via component fibers:
```javascript
function invokeReactOnClick(element) {
  const propKey = Object.keys(element).find(k => 
    k.startsWith('__reactProps$') || 
    k.startsWith('__reactEventHandlers$') || 
    k.startsWith('__reactFiber$')
  );

  if (propKey && element[propKey]) {
    const props = element[propKey];
    const handler = props.onClick || (props.memoizedProps && props.memoizedProps.onClick);
    if (typeof handler === 'function') {
      handler({
        preventDefault: () => {},
        stopPropagation: () => {},
        target: element,
        currentTarget: element
      });
      return true;
    }
  }
  return false;
}
```

### Full Event Dispatch Chain
Always dispatch full mouse events alongside `.click()`:
```javascript
element.disabled = false;
element.removeAttribute('disabled');
element.removeAttribute('aria-disabled');
element.classList.remove('cursor-not-allowed', 'disabled');
element.style.pointerEvents = 'auto';

element.focus();
element.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true, view: window }));
element.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, view: window }));
element.click();
```

---

## 3. Countdown & Cooldown Detection

### Flexible Time Parsing
Buttons often format countdowns in various patterns:
- `(mm:ss)` or `mm:ss` -> `Number(match[1]) * 60 + Number(match[2])`
- `(ss)` or `(ss s)` -> `Number(match[1])`

### Boolean Logic Safeguards
**Critical Rule:** Never combine countdown conditions with `|| !disabled`.
- Incorrect: `if (seconds === 0 || !disabled)` -> Clicks during active countdowns if `disabled` attribute is absent!
- Correct:
```javascript
const isCountingDown = (seconds !== null && seconds > 0) || (seconds === null && hasParenthesis);
if (!disabled && !isCountingDown) {
  triggerClick(btn);
}
```

---

## 4. Distinguishing Client vs. Server Rate Limits

1. **Client-side Storage:**
   If countdown timestamps are stored in `localStorage` or `sessionStorage`, clearing storage or opening an Incognito tab resets the cooldown immediately.
2. **Server-side Cooldowns:**
   If requests return HTTP `429 Too Many Requests` or JSON errors like `"Cooldown active"`, the restriction is enforced by backend database timestamps and cannot be bypassed via DOM clicks alone.
