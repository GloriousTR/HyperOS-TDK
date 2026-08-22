package com.glorious.hyperostdk.privileged;

interface IPrivilegedThemeService {
    void destroy() = 16777114;
    int uid() = 1;
    String exec(String command) = 2;
}
