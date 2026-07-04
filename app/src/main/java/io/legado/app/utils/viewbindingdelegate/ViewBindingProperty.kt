@file:Suppress("RedundantVisibilityModifier")

package io.legado.app.utils.viewbindingdelegate

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.viewbinding.ViewBinding
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

public abstract class ViewBindingProperty<in R : Any, T : ViewBinding>(
    private val viewBinder: (R) -> T
) : ReadOnlyProperty<R, T> {

    private var viewBinding: T? = null
    private val lifecycleObserver = ClearOnDestroyLifecycleObserver()
    private var thisRef: R? = null

    protected abstract fun getLifecycleOwner(thisRef: R): LifecycleOwner

    @MainThread
    public override fun getValue(thisRef: R, property: KProperty<*>): T {
        viewBinding?.let { return it }

        this.thisRef = thisRef
        val lifecycle = try {
            getLifecycleOwner(thisRef).lifecycle
        } catch (e: IllegalStateException) {
            throw IllegalStateException(
                "Cannot access view binding: the fragment view is not available. " +
                "This typically happens when trying to access the binding from a coroutine " +
                "that outlives the fragment view lifecycle. " +
                "Use viewLifecycleOwner.lifecycleScope instead of lifecycleScope, " +
                "or check isAdded && view != null before accessing the binding.",
                e
            )
        }
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            mainHandler.post { viewBinding = null }
        } else {
            lifecycle.addObserver(lifecycleObserver)
        }
        return viewBinder(thisRef).also { viewBinding = it }
    }

    @MainThread
    public fun clear() {
        val thisRef = thisRef ?: return
        this.thisRef = null
        try {
            getLifecycleOwner(thisRef).lifecycle.removeObserver(lifecycleObserver)
        } catch (e: IllegalStateException) {
            // View 已经销毁，viewLifecycleOwner 不可访问，观察者会自动被移除
        }
        mainHandler.post { viewBinding = null }
    }

    private inner class ClearOnDestroyLifecycleObserver : DefaultLifecycleObserver {

        @MainThread
        override fun onDestroy(owner: LifecycleOwner): Unit = clear()
    }

    private companion object {

        private val mainHandler = Handler(Looper.getMainLooper())
    }
}