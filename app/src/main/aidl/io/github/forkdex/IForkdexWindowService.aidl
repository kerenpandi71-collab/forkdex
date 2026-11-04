package io.github.forkdex;

import android.graphics.Rect;

interface IForkdexWindowService {
    String launchFreeform(String packageName, String className, int displayId, in Rect bounds);
    String getWindowingCapabilities();
    String enableDesktopMode();
    String listTasks(int displayId);
    String resizeTask(int displayId, int taskId, in Rect bounds);
    String setTaskMode(int displayId, int taskId, int windowingMode, in Rect bounds);
    String focusTask(int displayId, int taskId);
    String minimizeTask(int displayId, int taskId);
    String closeTask(int taskId);
    String setPackageDensity(int displayId, String packageName, int densityDpi);
}