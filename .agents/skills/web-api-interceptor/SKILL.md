---
name: web-api-interceptor
description: >-
  Provides guidelines, sniffing snippets, and replay patterns for intercepting
  browser Network requests (fetch/XHR), capturing API endpoints, tokens, and payloads
  for direct API automation.
---

# Web API Interceptor & Network Sniffer Skill

Use this skill when DOM clicking is unreliable, blocked by anti-bot measures, or when the user wants to trigger actions directly via background HTTP API calls rather than simulating mouse clicks.

---

## 1. Why API Interception Outperforms DOM Clicking
- **Bypasses UI Disabled States:** API requests do not depend on button `disabled` attributes or CSS styles.
- **Immediate Feedback:** Directly returns the server's JSON response (error message, cooldown time, or allocated phone number).
- **Speed & Efficiency:** Operates in milliseconds without DOM re-renders.

---

## 2. Universal Fetch & XHR Interceptor Snippet

Inject this snippet into DevTools Console to log all outgoing API calls and capture the exact endpoint triggered by a button:

```javascript
(function interceptNetwork() {
  console.log('🕵️ Network Sniffer activated. Perform your action on the web page...');

  // Hook Fetch
  const originalFetch = window.fetch;
  window.fetch = async function(...args) {
    const [url, config] = args;
    console.log('🌐 [FETCH Request]:', url, config);
    try {
      const response = await originalFetch.apply(this, args);
      const clone = response.clone();
      clone.text().then(body => {
        try {
          console.log('📥 [FETCH Response]:', url, JSON.parse(body));
        } catch {
          console.log('📥 [FETCH Response text]:', url, body);
        }
      });
      return response;
    } catch (err) {
      console.error('❌ [FETCH Error]:', url, err);
      throw err;
    }
  };

  // Hook XMLHttpRequest
  const originalXHR = window.XMLHttpRequest.prototype.open;
  const originalSend = window.XMLHttpRequest.prototype.send;

  window.XMLHttpRequest.prototype.open = function(method, url) {
    this._url = url;
    this._method = method;
    return originalXHR.apply(this, arguments);
  };

  window.XMLHttpRequest.prototype.send = function(body) {
    console.log(`📡 [XHR ${this._method}]:`, this._url, body);
    this.addEventListener('load', function() {
      try {
        console.log(`📥 [XHR Response ${this.status}]:`, this._url, JSON.parse(this.responseText));
      } catch {
        console.log(`📥 [XHR Response ${this.status}]:`, this._url, this.responseText);
      }
    });
    return originalSend.apply(this, arguments);
  };
})();
```

---

## 3. Direct API Replay Pattern

Once the target URL, headers (Authorization/Bearer), and payload are captured, replay requests directly using a headless loop:

```javascript
async function requestNumberDirectly(endpoint, authToken, payload = {}) {
  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${authToken}`
      },
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    console.log('Server response:', data);
    return data;
  } catch (err) {
    console.error('API request failed:', err);
  }
}
```
