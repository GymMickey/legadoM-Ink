package io.legado.app.ui.config

import android.os.Bundle
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.utils.startActivity

class RuleManageFragment : PreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_rule_manage)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.rule_manage)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
            "replaceManage" -> startActivity<ReplaceRuleActivity>()
            "dictRuleManage" -> startActivity<DictRuleActivity>()
        }
        return super.onPreferenceTreeClick(preference)
    }
}
