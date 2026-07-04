# Android 线程同步入门指南

## 为什么需要锁？

想象一个银行账户，余额 100 元。两个线程同时操作：

```
线程A（取款50）：读余额=100 → 计算100-50=50 → 写余额=50
线程B（取款30）：读余额=100 → 计算100-30=70 → 写余额=70
```

如果两个线程**同时读到 100**，最终余额可能是 50 或 70，而不是正确的 20。这就是**数据竞争**（data race）。

**锁的作用**：同一时间只允许一个线程操作共享数据，像给数据加了一把门锁，一个人进去后门就锁上，其他人必须等他出来。

---

## 两种锁：synchronized vs Mutex

### 1. `synchronized` — 线程锁（传统锁）

```kotlin
private val lock = Object()  // 锁对象

fun doSomething() {
    synchronized(lock) {
        // 这段代码同一时间只有一个线程能执行
        // 其他线程到了这里会被阻塞等待
    }
}
```

**特点**：
- 阻塞线程：拿不到锁的线程会**卡住等**（blocked）
- 用法简单，Kotlin/Java 原生支持
- 只能在**普通函数**中使用
- 适合保护：变量读写、集合操作、简单计算

### 2. `Mutex` — 协程锁（挂起锁）

```kotlin
private val mutex = Mutex()

suspend fun doSomething() {    // 必须是 suspend 函数
    mutex.withLock {
        // 这段代码同一时间只有一个协程能执行
        // 其他协程到了这里会被挂起（suspended），不阻塞线程
    }
}
```

**特点**：
- 挂起协程：拿不到锁的协程会**挂起等待**（suspended），线程不会卡住，可以去做别的事
- 必须在 `suspend` 函数中使用
- 适合保护：需要在协程中进行的异步操作

---

## 两者的核心区别

```
场景：10个人排队上厕所，只有一个坑位

synchronized（线程锁）= 10个人站在门口傻等，每个人都占着一个人的位置
Mutex（协程锁）= 10个人先去干别的事，厕所空了再来，不占地方
```

| 对比 | synchronized | Mutex |
|------|-------------|-------|
| 等待方式 | 阻塞线程（线程卡住） | 挂起协程（线程空出来干别的） |
| 使用场景 | 普通函数 | suspend 函数 |
| 性能 | 更快，JVM 底层优化 | 稍慢，有协程调度开销 |
| 适用场景 | 短小的临界区（变量读写） | 需要在锁内做挂起操作 |

---

## 什么时候需要锁？

### 需要锁的情况

多个线程/协程**同时读写同一个可变变量**：

```kotlin
// ❌ 危险：没有锁
class ViewModel {
    private var logs = mutableListOf<Log>()

    // 线程A（主线程）执行：
    fun loadLogs() {
        logs = newLogs  // 写
    }

    // 线程B（后台线程）执行：
    fun onEvent(event: Log) {
        logs.add(0, event)  // 写
    }
}
```

### 不需要锁的情况

- 变量只在一个线程中读写（比如只在主线程）
- 变量是 `val` 不可变的
- 变量是线程安全的类型（如 `AtomicInteger`）

---

## 本项目中的实际案例

### 案例1：混用锁导致数据竞争（本次修复的 bug）

**修复前（有 bug）**：

```kotlin
object DebugEventCenter {
    private val _events = ArrayDeque<DebugEvent>()
    private val mutex = Mutex()   // 锁 A

    // 写操作用 Mutex
    suspend fun emit(event: DebugEvent) {
        mutex.withLock {           // 拿锁 A
            _events.addFirst(event)
        }
        _eventFlow.emit(event)
    }

    // 读操作用 synchronized
    fun getRecentLogs(): List<DebugEvent> {
        return synchronized(_events) {  // 拿锁 B（不同的锁！）
            _events.take(500)
        }
    }
}
```

问题：`mutex.withLock` 和 `synchronized(_events)` 是**两把完全不同的锁**。当 `emit()` 正在用锁 A 修改 `_events` 时，`getRecentLogs()` 用锁 B 直接读，根本不会等待。两个线程同时操作同一个 `_events`，数据就乱了。

**修复后**：

```kotlin
object DebugEventCenter {
    private val _events = ArrayDeque<DebugEvent>()

    // 写操作改成 synchronized
    suspend fun emit(event: DebugEvent) {
        synchronized(_events) {    // 和读操作用同一把锁
            _events.addFirst(event)
        }
        _eventFlow.emit(event)     // suspend 操作放在锁外面
    }

    // 读操作不变
    fun getRecentLogs(): List<DebugEvent> {
        return synchronized(_events) {
            _events.take(500)
        }
    }
}
```

关键改动：读写都用 `synchronized(_events)`，同一把锁，互相知道对方的存在。

### 案例2：ViewModel 变量跨线程读写

**修复前**：

```kotlin
class DebugLogViewModel {
    private var _allLogs = listOf<DebugEvent>()  // 没有任何保护

    // Dispatchers.Main（主线程）执行：
    fun loadHistoryLogs() {
        _allLogs = loadedLogs   // 写
    }

    // Dispatchers.Default（后台线程）执行：
    private fun subscribeToEventFlow() {
        eventFlow.onEach { event ->
            _allLogs = updatedLogs  // 写 — 数据竞争！
        }.launchIn(viewModelScope)
    }
}
```

**修复后**：

```kotlin
class DebugLogViewModel {
    @Volatile                    // 保证可见性
    private var _allLogs = listOf<DebugEvent>()

    // 主线程写：
    fun loadHistoryLogs() {
        val loaded = ...
        synchronized(this) {
            _allLogs = loaded    // 加锁写
        }
    }

    // 后台线程写：
    private fun subscribeToEventFlow() {
        eventFlow.onEach { event ->
            val newLogs = synchronized(this) {  // 加锁读写
                val updated = mutableListOf(event) + _allLogs
                _allLogs = updated
                updated
            }
            _uiState.value = ...  // 用局部变量更新 UI，不读 _allLogs
        }.launchIn(viewModelScope)
    }
}
```

---

## `@Volatile` 是什么？

`@Volatile` 保证变量的**可见性**：一个线程修改了值，其他线程能**立刻看到**新值。

没有 `@Volatile` 时，JVM 可能做优化，把变量缓存在 CPU 寄存器里。线程A改了值，线程B读到的还是旧值。

```kotlin
@Volatile
private var _allLogs = listOf<DebugEvent>()
```

**注意**：`@Volatile` 只保证可见性，不保证原子性。如果读-改-写是三步操作（读出旧值 → 计算新值 → 写入），`@Volatile` 不够，还需要 `synchronized`。

```
@Volatile  = 保证别人能看到我改了什么
synchronized = 保证我改的过程中别人不能同时改
```

---

## Flow 和线程的关系

Kotlin Flow 的操作符（`onEach`、`map`、`filter`）运行在**上游发射的线程**上，不是主线程。

```kotlin
// SharedFlow 从 Dispatchers.Default 发射
GlobalScope.launch(Dispatchers.Default) {
    eventFlow.emit(event)   // 在后台线程发射
}

// ViewModel 中订阅
eventFlow
    .onEach { event ->
        // 这里的代码运行在 Dispatchers.Default（后台线程）！
        // 不是主线程！
        _uiState.value = ...  // 虽然 StateFlow 是线程安全的，
                              // 但如果同时读写 _allLogs 就有问题
    }
    .launchIn(viewModelScope)  // viewModelScope 是主线程
                               // 但 launchIn 只管协程生命周期，不改变流的线程
```

**解决方案**：在 `onEach` 内部用 `synchronized` 保护共享变量，或者用 `flowOn(Dispatchers.Main)` 把流切换到主线程。

---

## 常见错误速查

| 错误 | 后果 | 修复 |
|------|------|------|
| 同一数据用不同锁保护 | 数据竞争，崩溃 | 统一用同一把锁 |
| `var` 在多线程读写无保护 | 数据竞争 | 加 `@Volatile` + `synchronized` |
| `@Synchronized` 函数内启动协程 | 锁持有时间过长，其他线程等待 | 先捕获变量，再释放锁，再启动协程 |
| Flow `onEach` 修改共享变量无锁 | 数据竞争 | `synchronized(this) { }` 包裹 |
| 混用 `Mutex` 和 `synchronized` | 两把锁互不感知，等于没锁 | 选一种统一使用 |

---

## 一句话总结

> **对同一份数据，所有读写操作必须用同一把锁保护。** 不管用 `synchronized` 还是 `Mutex`，统一就好。混用等于没锁。
