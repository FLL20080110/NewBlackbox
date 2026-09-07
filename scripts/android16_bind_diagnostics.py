from pathlib import Path

p = Path("Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java")
s = p.read_text(encoding="utf-8")

marker = "        Application application;\n        try {\n            onBeforeCreateApplication(packageName, processName, packageContext);"
replacement = """        Slog.d(TAG, \"Binding guest package=\" + packageName
                + \", process=\" + processName
                + \", sourceDir=\" + applicationInfo.sourceDir
                + \", splits=\" + Arrays.toString(applicationInfo.splitSourceDirs)
                + \", nativeLibraryDir=\" + applicationInfo.nativeLibraryDir
                + \", appComponentFactory=\" + applicationInfo.appComponentFactory);

        Application application;
        try {
            onBeforeCreateApplication(packageName, processName, packageContext);"""
if marker not in s:
    raise SystemExit("bind marker not found")
s = s.replace(marker, replacement, 1)

old = """            if (application == null) {
                Slog.w(TAG, \"makeApplication returned null, attempting fallback creation\");
                
                
                try {
                    application = BRLoadedApk.get(loadedApk).makeApplication(true, null);
                } catch (Exception e) {
                    Slog.e(TAG, \"Fallback makeApplication also failed\", e);
                }
                
                
                if (application == null) {
                    Slog.w(TAG, \"Creating minimal application context as fallback\");
                    try {
                        
                        application = (Application) packageContext;
                        if (application == null) {
                            Slog.e(TAG, \"Even package context is null, this is critical\");
                            throw new RuntimeException(\"Unable to create application context\");
                        }
                    } catch (Exception contextException) {
                        Slog.e(TAG, \"Failed to create fallback application context\", contextException);
                        throw new RuntimeException(\"Unable to makeApplication - all fallback attempts failed\", contextException);
                    }
                }
            }
            
            if (application == null) {
                Slog.e(TAG, \"makeApplication application Error! All attempts failed\");
                throw new RuntimeException(\"Unable to create application - all creation methods failed\");
            }"""

new = """            if (application == null) {
                Slog.w(TAG, \"makeApplication returned null, retrying with forceDefaultAppClass=true\");
                try {
                    application = BRLoadedApk.get(loadedApk).makeApplication(true, null);
                } catch (Exception e) {
                    Slog.e(TAG, \"Fallback makeApplication also failed\", e);
                }
            }

            if (application == null) {
                throw new RuntimeException(\"LoadedApk.makeApplication returned null for \" + packageName
                        + \"; refusing invalid Context-to-Application fallback\");
            }

            try {
                ClassLoader guestClassLoader = BRLoadedApk.get(loadedApk).getClassLoader();
                Slog.d(TAG, \"Guest classloader=\" + guestClassLoader
                        + \", parent=\" + (guestClassLoader != null ? guestClassLoader.getParent() : null));
            } catch (Throwable classLoaderError) {
                Slog.e(TAG, \"Unable to inspect guest classloader for \" + packageName, classLoaderError);
            }"""

if old not in s:
    raise SystemExit("application fallback block not found")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")
print("Android 16 bind diagnostics patch applied")
