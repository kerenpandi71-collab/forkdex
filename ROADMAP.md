# Forkdex Development Roadmap

Scope: native Android desktop and per-application task presentation only. Keep the existing MagicDesk application and unrelated modules untouched.

## Current implementation
- [x] Separate Forkdex Android application under `forkdex/`
- [x] Installed-app discovery and app launching
- [x] Per-app scale profile persistence (50–200%)
- [x] Shizuku API dependency
- [x] Shizuku manager/service status checks
- [x] Shizuku permission request UI
- [ ] Verify Shizuku connection and native freeform behavior on-device
- [ ] Apply density to a live Android task using task-scoped WindowContainerTransaction
- [ ] Reapply saved profile when a matching task appears
- [ ] Restore system/inherited task density when profile is reset

## Desktop milestones
- [x] Native freeform launch through Shizuku UserService and ActivityTaskManager
- [x] Running task inventory from ActivityTaskManager
- [x] Basic native task controls: focus, minimize, maximize/fullscreen, restore freeform, close
- [x] Native task resize API exposed to Forkdex client
- [ ] Verify native freeform captions, resize, and task controls on-device
- [ ] Desktop workspace and persistent taskbar
- [ ] Start menu and searchable app drawer
- [ ] Running-task inventory and focus switching
- [ ] Window controls: minimize, maximize, restore, close
- [ ] Resizable freeform windows with interactive resize handles in Forkdex UI
- [ ] Snap layouts
- [ ] Alt+Tab task switcher
- [ ] External and virtual display support
- [ ] Preserve/reconcile task density when a window moves between displays

## Engineering constraints
- Never use global `wm density` as a substitute for per-app density.
- Detect whether the Android build exposes the task density override API before applying it.
- Report unsupported framework APIs and permission failures clearly; do not silently claim success.
- Keep Forkdex isolated in `forkdex/`; do not delete or refactor the existing MagicDesk modules as part of this project.
- Build and verify after each milestone before marking it complete.
