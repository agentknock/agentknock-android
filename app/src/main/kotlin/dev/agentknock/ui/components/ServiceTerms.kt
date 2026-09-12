package dev.agentknock.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.core.net.toUri

private const val TERMS_URL = "https://agentknock.dev/terms/"
private const val PRIVACY_URL = "https://agentknock.dev/privacy/"

@Composable
internal fun ClaimServiceTermsNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val linkStyles =
        TextLinkStyles(
            style =
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                )
        )
    fun link(url: String) = LinkAnnotation.Url(url, linkStyles) { context.openServiceDocument(url) }

    Text(
        text =
            buildAnnotatedString {
                append("By claiming this address, you agree to our ")
                withLink(link(TERMS_URL)) { append("terms\u00a0of\u00a0service") }
                append(". See our ")
                withLink(link(PRIVACY_URL)) { append("privacy\u00a0notice") }
                append(".")
            },
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ServiceDocumentLinks(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { context.openServiceDocument(TERMS_URL) }) {
            Text("Terms of service")
        }
        TextButton(onClick = { context.openServiceDocument(PRIVACY_URL) }) {
            Text("Privacy notice")
        }
    }
}

private fun Context.openServiceDocument(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app available to open this link", Toast.LENGTH_SHORT).show()
    }
}
