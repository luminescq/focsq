package com.csqtt.client.ui.legacy.dialogs

import androidx.compose.runtime.Composable
import com.csqtt.client.ui.v2.dialogs.V2TurnSubnetsDialog

@Composable
internal fun TurnSubnetsDialog(
    selectedSubnetId: String,
    turnSubnetIpLimit: String = "ALL",
    onSelectSubnet: (String) -> Unit,
    onSelectIpLimit: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    V2TurnSubnetsDialog(
        selectedSubnetId = selectedSubnetId,
        turnSubnetIpLimit = turnSubnetIpLimit,
        onSelectSubnet = onSelectSubnet,
        onSelectIpLimit = onSelectIpLimit,
        onDismiss = onDismiss,
    )
}
