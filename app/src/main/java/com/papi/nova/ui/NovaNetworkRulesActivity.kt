package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.papi.nova.NovaActivity
import com.papi.nova.R
import com.papi.nova.network.HostConnectionCondition
import com.papi.nova.network.HostNetworkRules
import com.papi.nova.network.NetworkProfileRule
import com.papi.nova.network.NetworkProfileRuleStore
import com.papi.nova.network.TransportCondition
import com.papi.nova.network.YesNoCondition
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import com.papi.nova.ui.compose.NovaComposeTheme

/**
 * One host's network rules: an ordered list of conditions, each naming an existing saved settings
 * profile. Off until turned on. Only profile UUIDs and condition choices are stored, per host.
 */
class NovaNetworkRulesActivity : NovaActivity() {
    private lateinit var store: NetworkProfileRuleStore
    private lateinit var hostUuid: String
    private var rules by mutableStateOf(HostNetworkRules.NONE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostUuid = intent.getStringExtra(EXTRA_HOST_UUID)?.takeIf { it.isNotBlank() } ?: run {
            finish()
            return
        }
        val hostName = intent.getStringExtra(EXTRA_HOST_NAME).orEmpty()
        store = NetworkProfileRuleStore(this)
        rules = store.load(hostUuid)
        setContent {
            NovaComposeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NetworkRulesScreen(
                        hostName = hostName,
                        rules = rules,
                        profiles = ProfilesManager.getInstance().getProfiles().sortedBy { it.getName().lowercase() },
                        onChange = ::update,
                    )
                }
            }
        }
    }

    private fun update(next: HostNetworkRules) {
        rules = next
        store.save(hostUuid, next)
    }

    companion object {
        private const val EXTRA_HOST_UUID = "nova.networkRules.hostUuid"
        private const val EXTRA_HOST_NAME = "nova.networkRules.hostName"

        fun newIntent(context: Context, hostUuid: String, hostName: String): Intent =
            Intent(context, NovaNetworkRulesActivity::class.java)
                .putExtra(EXTRA_HOST_UUID, hostUuid)
                .putExtra(EXTRA_HOST_NAME, hostName)

        /** Test tags, so UI tests address controls without reading their words. */
        const val TAG_ENABLED = "networkRules.enabled"
        const val TAG_ADD = "networkRules.add"
        fun ruleTag(index: Int, part: String) = "networkRules.rule$index.$part"
    }
}

private fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1).mod(size)]

@Composable
private fun NetworkRulesScreen(
    hostName: String,
    rules: HostNetworkRules,
    profiles: List<SettingsProfile>,
    onChange: (HostNetworkRules) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.nova_network_rules_title), style = MaterialTheme.typography.headlineSmall)
        if (hostName.isNotBlank()) {
            Text(stringResource(R.string.nova_network_rules_subtitle, hostName), style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val label = stringResource(R.string.nova_network_rules_enabled)
            Text(label, modifier = Modifier.weight(1f))
            Switch(
                checked = rules.enabled,
                onCheckedChange = { onChange(rules.copy(enabled = it)) },
                modifier = Modifier.testTag(NovaNetworkRulesActivity.TAG_ENABLED).semantics { contentDescription = label },
            )
        }
        Text(stringResource(R.string.nova_network_rules_explainer), style = MaterialTheme.typography.bodySmall)

        if (profiles.isEmpty()) {
            Text(stringResource(R.string.nova_network_rules_no_profiles))
        } else {
            RuleList(rules, profiles, onChange)
        }
    }
}

@Composable
private fun RuleList(
    rules: HostNetworkRules,
    profiles: List<SettingsProfile>,
    onChange: (HostNetworkRules) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (rules.rules.isEmpty()) Text(stringResource(R.string.nova_network_rules_empty))

        rules.rules.forEachIndexed { index, rule ->
            fun replace(next: NetworkProfileRule) =
                onChange(rules.copy(rules = rules.rules.toMutableList().also { it[index] = next }))
            fun move(to: Int) = onChange(rules.copy(rules = rules.rules.toMutableList().also {
                val moved = it.removeAt(index)
                it.add(to, moved)
            }))
            val number = index + 1
            Card(modifier = Modifier.fillMaxWidth().testTag(NovaNetworkRulesActivity.ruleTag(index, "card"))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nova_network_rules_rule_title, number), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val profileIds = profiles.map { it.getUuid().toString() }
                        val profileName = profiles.firstOrNull { it.getUuid().toString() == rule.profileUuid }?.getName()
                            ?: stringResource(R.string.nova_network_rules_missing_profile)
                        CycleButton(stringResource(R.string.nova_network_rules_profile), profileName,
                            NovaNetworkRulesActivity.ruleTag(index, "profile")) {
                            val next = if (rule.profileUuid in profileIds) profileIds.after(rule.profileUuid) else profileIds.first()
                            replace(rule.copy(profileUuid = next))
                        }
                        CycleButton(stringResource(R.string.nova_network_rules_transport),
                            NovaNetworkProfileCopy.transportLabel(context, rule.transport),
                            NovaNetworkRulesActivity.ruleTag(index, "transport")) {
                            replace(rule.copy(transport = TransportCondition.entries.after(rule.transport)))
                        }
                        CycleButton(stringResource(R.string.nova_network_rules_metered),
                            NovaNetworkProfileCopy.yesNoLabel(context, rule.metered),
                            NovaNetworkRulesActivity.ruleTag(index, "metered")) {
                            replace(rule.copy(metered = YesNoCondition.entries.after(rule.metered)))
                        }
                        CycleButton(stringResource(R.string.nova_network_rules_vpn),
                            NovaNetworkProfileCopy.yesNoLabel(context, rule.novaVpn),
                            NovaNetworkRulesActivity.ruleTag(index, "vpn")) {
                            replace(rule.copy(novaVpn = YesNoCondition.entries.after(rule.novaVpn)))
                        }
                        CycleButton(stringResource(R.string.nova_network_rules_host_connection),
                            NovaNetworkProfileCopy.hostConnectionLabel(context, rule.hostConnection),
                            NovaNetworkRulesActivity.ruleTag(index, "hostConnection")) {
                            replace(rule.copy(hostConnection = HostConnectionCondition.entries.after(rule.hostConnection)))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (index > 0) TextButton(
                            onClick = { move(index - 1) },
                            modifier = Modifier.testTag(NovaNetworkRulesActivity.ruleTag(index, "up")),
                        ) { Text(stringResource(R.string.nova_network_rules_move_up, number)) }
                        if (index < rules.rules.lastIndex) TextButton(
                            onClick = { move(index + 1) },
                            modifier = Modifier.testTag(NovaNetworkRulesActivity.ruleTag(index, "down")),
                        ) { Text(stringResource(R.string.nova_network_rules_move_down, number)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { onChange(rules.copy(rules = rules.rules.filterIndexed { i, _ -> i != index })) },
                            modifier = Modifier.testTag(NovaNetworkRulesActivity.ruleTag(index, "delete")),
                        ) { Text(stringResource(R.string.nova_network_rules_delete, number)) }
                    }
                }
            }
        }

        if (rules.rules.size < NetworkProfileRuleStore.MAX_RULES) {
            OutlinedButton(
                onClick = {
                    onChange(rules.copy(rules = rules.rules + NetworkProfileRule(profileUuid = profiles.first().getUuid().toString())))
                },
                modifier = Modifier.testTag(NovaNetworkRulesActivity.TAG_ADD),
            ) { Text(stringResource(R.string.nova_network_rules_add)) }
        }
    }
}

/** One condition: its name and current choice; pressing it steps to the next choice. */
@Composable
private fun CycleButton(label: String, value: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.testTag(tag).semantics { contentDescription = "$label: $value" },
    ) { Text("$label: $value") }
}
