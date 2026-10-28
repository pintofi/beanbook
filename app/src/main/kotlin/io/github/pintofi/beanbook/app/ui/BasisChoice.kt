package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.domain.Valuation

/**
 * The choice of basis -- Units, At cost, Market value -- as every screen that shows figures offers it.
 *
 * One preference for the whole app, so switching it here changes Home, Journal, Balances and the
 * widgets together, and written once so the three screens cannot drift apart.
 *
 * A connected row, not chips: these are one basis at a time, the same figures valued three ways, and
 * a connected row is what Material uses to say "pick one of these". Chips read as filters that
 * combine, which is the opposite.
 */
@Composable
internal fun BasisChoice(
    modifier: Modifier = Modifier,
    describe: (Valuation) -> String = { "Show figures ${it.label}" },
) {
    val context = LocalContext.current
    val store = remember(context) { ValuationPreference(context) }
    val basis by store.flow.collectAsState()
    ConnectedChoiceRow(
        options = Valuation.entries,
        selected = basis,
        onSelect = { store.write(it) },
        label = { it.label },
        describe = describe,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Measure.gutter)
            .padding(bottom = Measure.snug),
    )
}
