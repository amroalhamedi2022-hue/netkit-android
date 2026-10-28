# NetKit CI build result

- Result: success
- Workflow run: https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/39709515163
- Commit: bea40bcf0df11492990bf0e8031bb51f779c1816
- Verified release: https://github.com/amroalhamedi2022-hue/netkit-android/releases/tag/v0.6.0-alpha-build1 (only if successful)

## Build log tail
~~~
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.13/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build 
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:mergeDebugNativeDebugMetadata NO-SOURCE
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:checkDebugAarMetadata
> Task :app:processDebugNavigationResources
> Task :app:generateDebugResValues
> Task :app:compileDebugNavigationResources
> Task :app:mapDebugSourceSetPaths
> Task :app:generateDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:mergeDebugResources
> Task :app:processDebugMainManifest
> Task :app:packageDebugResources
> Task :app:parseDebugLocalResources
> Task :app:processDebugManifest
> Task :app:processDebugManifestForPackage
> Task :app:javaPreCompileDebug
> Task :app:mergeDebugShaders
> Task :app:compileDebugShaders NO-SOURCE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:mergeDebugAssets
> Task :app:compressDebugAssets
> Task :app:desugarDebugFileDependencies
> Task :app:processDebugResources
> Task :app:checkDebugDuplicateClasses
> Task :app:mergeExtDexDebug
> Task :app:mergeLibDexDebug
> Task :app:mergeDebugJniLibFolders
> Task :app:mergeDebugNativeLibs

> Task :app:stripDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so. Run with --info option to learn more.

> Task :app:validateSigningDebug
> Task :app:writeDebugAppMetadata
> Task :app:writeDebugSigningConfigVersions
> Task :app:compileDebugKotlin
w: file:///home/runner/work/netkit-android/netkit-android/app/src/main/java/com/netkit/local/connectivity/HotspotManager.kt:54:34 'val ssid: String?' is deprecated. Deprecated in Java.
w: file:///home/runner/work/netkit-android/netkit-android/app/src/main/java/com/netkit/local/connectivity/HotspotManager.kt:57:35 'field SSID: String!' is deprecated. Deprecated in Java.
w: file:///home/runner/work/netkit-android/netkit-android/app/src/main/java/com/netkit/local/connectivity/HotspotManager.kt:57:66 'field preSharedKey: String!' is deprecated. Deprecated in Java.

> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:dexBuilderDebug
> Task :app:mergeDebugGlobalSynthetics
> Task :app:processDebugJavaRes
> Task :app:mergeProjectDexDebug
> Task :app:mergeDebugJavaResource
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_9-1792634845235.json

BUILD SUCCESSFUL in 1m 6s
37 actionable tasks: 37 executed
~~~

## Lint log tail
~~~
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.13/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build 
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:processDebugNavigationResources UP-TO-DATE
> Task :app:compileDebugNavigationResources UP-TO-DATE
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:preDebugAndroidTestBuild SKIPPED
> Task :app:generateDebugAndroidTestResValues
> Task :app:extractProguardFiles
> Task :app:bundleDebugClassesToCompileJar
> Task :app:generateDebugLintReportModel
> Task :app:generateDebugAndroidTestLintModel
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:generateDebugUnitTestLintModel
> Task :app:lintAnalyzeDebugAndroidTest
> Task :app:lintAnalyzeDebugUnitTest
> Task :app:lintAnalyzeDebug

> Task :app:lintReportDebug
Wrote HTML report to file:///home/runner/work/netkit-android/netkit-android/app/build/reports/lint-results-debug.html

> Task :app:lintDebug
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_10-1792634911450.json

BUILD SUCCESSFUL in 55s
28 actionable tasks: 11 executed, 17 up-to-date
~~~


## 2026-10-28 — M88 SAF publication rollback guard

- Source/test SHA: `06064aa41ccc7bdb6d7d02e7835c47dc00ea5a51`.
- Exact-source JVM/static QA: **PASS**, run [40638530563](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40638530563).
- Targeted Android compile/Debug APK: **PASS**, run [40639270677](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40639270677), exact build head `1291171e261d7ce2d9c61d722d27cb82a2fda8b5`.
- Artifact [12795230533](https://github.com/amroalhamedi2022-hue/netkit-android/actions/runs/40639270677/artifacts/12795230533), ZIP SHA-256 `e70048aa96ca0bbc168db188033c5af0801432af66caf08a3d9f92b3b9964d44`.
- APK SHA-256 `f6199f20b12690be3f6d0b55a969cada6e5404c2fadb4c2ff1541214c4eaaba1`; independently recalculated and matched the packaged checksum.
- Real SAF provider/device acceptance: **NOT TESTED**.
