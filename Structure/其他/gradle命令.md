快速检测kotlin语法（只编译不资源处理（更快）：）
.\gradlew.bat :app:compileAppMaxDebugKotlin -x :app:processAppMaxDebugResources
只编译该渠道 Debug 版的 Kotlin 代码，不打包、不处理资源
.\gradlew.bat :app:compileAppMaxDebugKotlin
编译测试 : 运行 ./gradlew compileDebugKotlin 检查是否有语法错误
.\gradlew.bat compileDebugKotlin --console=plain
它用来控制 Gradle 输出的 控制台显示模式。plain 表示使用纯文本输出,不使用 Gradle 默认的彩色或进度条等美化效果。

打包Debug版本
.\gradlew.bat :app:assembleDebug
./gradlew assembleAppMaxDebug 打包MaxDebug版本

打包Release版本
.\gradlew.bat :app:assembleRelease
./gradlew assembleAppMaxRelease 打包MaxRelease版本

查看所有可用编译任务：
.\gradlew.bat tasks
.\gradlew.bat :app:tasks --all | findstr compile.*Kotlin


打包遇到这种情况下面
./gradlew assembleAppMaxDebug
Starting a Gradle Daemon, 1 incompatible and 2 stopped Daemons could not be reused, use --status for details
> Task :app:processAppMaxDebugResources FAILED
大概是gradle的进程出问题了，需要重启gradle进程。
AI的解释是这样：这条信息是正常提示，不是错误：Gradle 要启动新的守护进程（Daemon），因为有 1 个不兼容、2 个已停止的旧进程无法复用。
incompatible（不兼容）：旧 Daemon 的 Gradle 版本、Java 版本、JVM 参数与当前项目不一致Gradle。
stopped（已停止）：旧 Daemon 已退出，但进程记录残留，无法复用Gradle。
use --status for details：可执行 ./gradlew --status 查看 Daemon 状态详情Gradle。
# 停止所有 Daemon
./gradlew --stop  ，一般用这个命令就行，命令行会显示Stopping Daemon(s) 2 Daemons stopped。
然后继续用打包命令即可。
./gradlew assembleAppMaxDebug
 临时禁用 Daemon（不推荐，构建会变慢）
 ./gradlew build --no-daemon

 KSP (Kotlin Symbol Processing) 缓存锁定的问题。通常是因为之前的构建进程没有正常退出，导致缓存文件被锁定。
 清理构建缓存并重新构建：
 ./gradlew clean 
 清理成功后，现在重新构建：
 ./gradlew assembleAppMaxDebug
 这个错误是 KSP 缓存锁定问题 ，通常发生在：
- 之前的构建进程异常终止
- 多个 Gradle 进程同时运行
- Windows 文件锁定机制导致缓存文件无法访问
Could not close incremental caches in ...\kspCaches\appMaxDebug\symbolLookups: lookups.tab
解决方案： 执行 ./gradlew clean 清理构建缓存，然后重新构建。