package io.legado.app.ui.widget.components.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.base.BaseDialogFragment
import io.legado.app.ui.theme.LegadoTheme
// 基础 Compose 弹窗 Fragment
abstract class BaseComposeDialogFragment : BaseDialogFragment(0) {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DialogContent()
                }
            }
        }
    }

    @Composable
    protected abstract fun DialogContent()

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
    }
}
