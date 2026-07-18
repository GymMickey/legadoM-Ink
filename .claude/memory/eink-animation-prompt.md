---
name: eink-animation-optimization-prompt
description: E-Ink 墨水屏跳过核心路径动画的完整代码窗口提示词（3文件合并版）
metadata:
  type: reference
---

## 为 E-Ink 墨水屏跳过阅读核心路径的界面动画

### 背景

当前 E-Ink 模式的适配集中在颜色（黑白配）和部分动画（菜单滑入、对话框、自动翻页、底部导航），但翻页动画、快速滚动条、列表项入场动画这三处在墨水屏上仍会产生残影和闪烁。这次批量修复。

---

### 1. PageDelegate.kt — 翻页动画（最高优先级）

**文件：** `app/src/main/java/io/legado/app/ui/book/read/page/delegate/PageDelegate.kt`

**位置：** `startScroll` 方法（约第 73-83 行）

改前：
```kotlin
protected fun startScroll(startX: Int, startY: Int, dx: Int, dy: Int, animationSpeed: Int) {
    val duration = if (dx != 0) {
        (animationSpeed * abs(dx)) / viewWidth
    } else {
        (animationSpeed * abs(dy)) / viewHeight
    }
    scroller.startScroll(startX, startY, dx, dy, duration)
    isRunning = true
    isStarted = true
    readView.invalidate()
}
```

改后：
```kotlin
protected fun startScroll(startX: Int, startY: Int, dx: Int, dy: Int, animationSpeed: Int) {
    val duration = if (AppConfig.isEInkMode) {
        0
    } else if (dx != 0) {
        (animationSpeed * abs(dx)) / viewWidth
    } else {
        (animationSpeed * abs(dy)) / viewHeight
    }
    scroller.startScroll(startX, startY, dx, dy, duration)
    isRunning = true
    isStarted = true
    readView.invalidate()
}
```

**原理：** `Scroller.startScroll` 的 duration 为 0 时，下一次 `computeScrollOffset()` 直接跳到最终位置，无中间帧动画。覆盖点击翻页和触摸滑动翻页两种场景。

---

### 2. FastScroller.kt — 快速滚动条动画

**文件：** `app/src/main/java/io/legado/app/ui/widget/recycler/scroller/FastScroller.kt`

#### 2a. showBubble（约第 445-454 行）

改前：
```kotlin
private fun showBubble() {
    if (!isViewVisible(mBubbleView)) {
        mBubbleView.visibility = View.VISIBLE
        mBubbleAnimator = mBubbleView.animate().alpha(1f)
            .setDuration(sBubbleAnimDuration.toLong())
            .setListener(object : AnimatorListenerAdapter() {

                // adapter required for new alpha value to stick
            })
    }
```

改后：
```kotlin
private fun showBubble() {
    if (!isViewVisible(mBubbleView)) {
        mBubbleView.visibility = View.VISIBLE
        if (AppConfig.isEInkMode) {
            mBubbleAnimator?.cancel()
            mBubbleAnimator = null
            mBubbleView.alpha = 1f
        } else {
            mBubbleAnimator = mBubbleView.animate().alpha(1f)
                .setDuration(sBubbleAnimDuration.toLong())
                .setListener(object : AnimatorListenerAdapter() {

                    // adapter required for new alpha value to stick
                })
        }
    }
```

#### 2b. hideBubble（约第 457-473 行）

改前：
```kotlin
private fun hideBubble() {
    if (isViewVisible(mBubbleView)) {
        mBubbleAnimator = mBubbleView.animate().alpha(0f)
            .setDuration(sBubbleAnimDuration.toLong())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super.onAnimationEnd(animation)
                    mBubbleView.visibility = View.INVISIBLE
                    mBubbleAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    super.onAnimationCancel(animation)
                    mBubbleView.visibility = View.INVISIBLE
                    mBubbleAnimator = null
                }
            })
    }
```

改后：
```kotlin
private fun hideBubble() {
    if (isViewVisible(mBubbleView)) {
        if (AppConfig.isEInkMode) {
            mBubbleAnimator?.cancel()
            mBubbleAnimator = null
            mBubbleView.alpha = 0f
            mBubbleView.visibility = View.INVISIBLE
        } else {
            mBubbleAnimator = mBubbleView.animate().alpha(0f)
                .setDuration(sBubbleAnimDuration.toLong())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        mBubbleView.visibility = View.INVISIBLE
                        mBubbleAnimator = null
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        super.onAnimationCancel(animation)
                        mBubbleView.visibility = View.INVISIBLE
                        mBubbleAnimator = null
                    }
                })
        }
    }
```

#### 2c. showScrollbar（约第 480-488 行）

改后的 E-Ink 分支：
```kotlin
            if (AppConfig.isEInkMode) {
                mScrollbarAnimator?.cancel()
                mScrollbarAnimator = null
                mScrollbar.translationX = 0f
                mScrollbar.alpha = 1f
            } else {
                mScrollbarAnimator = mScrollbar.animate().translationX(0f).alpha(1f)
                    .setDuration(sScrollbarAnimDuration.toLong())
                    .setListener(object : AnimatorListenerAdapter() {

                        // adapter required for new alpha value to stick
                    })
            }
```

#### 2d. hideScrollbar（约第 493-495 行）

改后的 E-Ink 分支：
```kotlin
    if (AppConfig.isEInkMode) {
        mScrollbarAnimator?.cancel()
        mScrollbarAnimator = null
        mScrollbar.translationX = transX
        mScrollbar.alpha = 0f
    } else {
        mScrollbarAnimator = mScrollbar.animate().translationX(transX).alpha(0f)
            .setDuration(sScrollbarAnimDuration.toLong())
            .setListener(object : AnimatorListenerAdapter() {
```

---

### 3. RecyclerAdapter.kt — 列表项入场动画

**文件：** `app/src/main/java/io/legado/app/base/adapter/RecyclerAdapter.kt`

**位置：** `addAnimation` 方法（约第 470-478 行）

改前：
```kotlin
private fun addAnimation(holder: ItemViewHolder) {
    itemAnimation?.let {
        if (it.itemAnimEnabled) {
            if (!it.itemAnimFirstOnly || holder.layoutPosition > it.itemAnimStartPosition) {
                startAnimation(holder, it)
                it.itemAnimStartPosition = holder.layoutPosition
            }
        }
    }
}
```

改后：
```kotlin
private fun addAnimation(holder: ItemViewHolder) {
    if (AppConfig.isEInkMode) return
    itemAnimation?.let {
        if (it.itemAnimEnabled) {
            if (!it.itemAnimFirstOnly || holder.layoutPosition > it.itemAnimStartPosition) {
                startAnimation(holder, it)
                it.itemAnimStartPosition = holder.layoutPosition
            }
        }
    }
}
```

---

### 全局注意事项

- 三个文件各自需要确认已导入 `import io.legado.app.help.config.AppConfig`
- E-Ink 判断只作为条件分支，不影响非 E-Ink 路径
- 不修改 Scroller、RecyclerView、Animator 的全局行为

### 修改顺序

PageDelegate.kt → 编译通过 → RecyclerAdapter.kt → 编译通过 → FastScroller.kt → 编译通过 → 停止

### 约束

- 不 commit，不 push
