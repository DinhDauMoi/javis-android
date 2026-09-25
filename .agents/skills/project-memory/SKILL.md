---
name: project-memory
description: >-
  Provides procedures and guidelines for persistent memory management, capturing
  architectural decisions, coding preferences, bug fixes, and project context across
  coding sessions.
---

# Project Memory & Continuous Context Skill

Use this skill whenever working on complex coding tasks, discovering project-specific behaviors, learning user preferences, or when the user asks to record/remember decisions so they persist across future sessions.

---

## 1. Core Objectives
1. **Never Lose Context:** Maintain an up-to-date record of project tools, architecture patterns, and resolved issues in `.agents/memory.md`.
2. **Honor User Preferences:** Adapt to specific user workflows (e.g., unlimited runs, Vietnamese comments, clean console outputs, floating UI buttons).
3. **Prevent Regression:** Track known bugs and their definitive fixes so past mistakes are never reintroduced.

---

## 2. Memory Structure: `.agents/memory.md`

Whenever a significant decision or pattern is identified, ensure it is recorded in `.agents/memory.md` following this structure:

```markdown
# Project Memory & Knowledge Base

## Project Overview
- **Goal:** Automation tools for web interaction, countdown bypass, and SMS/OTP claiming.
- **Tech Stack:** Vanilla JavaScript, Modern DOM APIs, React Fiber internals, Tailwind CSS bypasses.

## User Preferences & Conventions
- **No arbitrary click limits:** Do not restrict scripts to a hardcoded count unless explicitly requested. Run indefinitely with manual stop controls.
- **Stop Controls:** Always provide multi-channel stopping (Floating UI button, ESC key, console function).
- **Clean Console:** Avoid polling spam; log only state changes or when countdown seconds decrement.
- **Language:** Provide user communications and code comments in Vietnamese.

## Solved Bugs & Known Gotchas
- **Bug: Premature click during countdown.**
  - *Cause:* `seconds === 0 || !disabled`. When `disabled` attribute was absent, `!disabled` evaluated to true.
  - *Rule:* Always require `!disabled && !isCountingDown`.
- **Bug: Zombie timers on re-run.**
  - *Cause:* Re-pasting scripts created concurrent `setInterval` instances.
  - *Rule:* Always clear `window.__autoClickerTimer` and remove old DOM elements before initialization.
- **React `<button disabled>` Trap:**
  - *Cause:* Browser suppresses native `.click()` when `<button disabled>` is in DOM.
  - *Rule:* Call React internal `__reactProps$` `onClick` directly and strip HTML `disabled` attributes.
```

---

## 3. Workflow for Updating Memory
1. **Identify New Knowledge:** If a new website behavior, selector pattern, or user requirement is discovered, formulate it as a concise rule.
2. **Append or Update:** Update `.agents/memory.md` using `replace_file_content` or `write_to_file`.
3. **Reference Memory:** Before starting any new feature or refactoring, check `.agents/memory.md` to ensure full compliance with established patterns.
