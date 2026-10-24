package com.mmbesar.boardme.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmbesar.boardme.R
import com.mmbesar.boardme.network.ServerAddress

/**
 * First start: connect to Kanboard (http:// and self-signed https:// are fine)
 * or skip the server and use BoardMe on this phone only.
 */
@Composable
fun SetupScreen(viewModel: BoardViewModel) {
    val state by viewModel.connectState.collectAsStateWithLifecycle()

    var address by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var showHttpWarning by remember { mutableStateOf(false) }

    val working = state is ConnectState.Working
    val isHttp = ServerAddress.parse(address)?.isCleartext == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.setup_intro))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.field_address)) },
            placeholder = { Text(stringResource(R.string.field_address_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (isHttp) {
            Text(
                stringResource(R.string.setup_http_notice),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.field_username)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.field_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                if (isHttp) showHttpWarning = true else viewModel.connect(address, username, token)
            },
            enabled = !working && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_connect)) }

        if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        (state as? ConnectState.Error)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(stringResource(R.string.setup_local_intro))
        OutlinedButton(
            onClick = { viewModel.startLocal() },
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_use_locally)) }
    }

    if (showHttpWarning) {
        HttpWarningDialog(
            onContinue = {
                showHttpWarning = false
                viewModel.connect(address, username, token)
            },
            onDismiss = { showHttpWarning = false },
        )
    }

    (state as? ConnectState.ConfirmCertificate)?.let { confirm ->
        CertificateDialog(
            state = confirm,
            onTrust = { viewModel.confirmCertificate(confirm) },
            onDismiss = { viewModel.dismissConnectMessage() },
        )
    }
}
