package com.nuvio.app.features.boomio

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.add_tv_request_failed
import org.jetbrains.compose.resources.getString

internal sealed interface TvLoginApprovalResult {
    data object Approved : TvLoginApprovalResult

    /** [message] is already user-facing: it is either the server's own text or a local fallback. */
    data class Refused(val message: String) : TvLoginApprovalResult
}

/**
 * Signs in a TV by approving the short code the television is displaying.
 *
 * There is deliberately no server endpoint here. `approve_tv_login_session` is
 * SECURITY DEFINER and keys off `auth.uid()`, and it is granted to
 * `authenticated` but **not** to `anon`, so a signed-in client can call it over
 * the ordinary PostgREST passthrough. That also means the caller must hold a
 * real Supabase session — see [com.nuvio.app.core.auth.AuthRepository.signInAnonymously],
 * which mints a local UUID and would be refused.
 */
internal object TvLoginApprovalRepository {
    private val log = Logger.withTag("TvLoginApproval")

    /** Length of the code the TV displays, before the client inserts its dash. */
    const val CODE_LENGTH: Int = 6

    suspend fun approve(code: String): TvLoginApprovalResult {
        val params = buildJsonObject { put("p_code", code) }
        return try {
            val row = SupabaseProvider.client.postgrest
                .rpc("approve_tv_login_session", params)
                .decodeList<TvLoginApprovalRow>()
                .firstOrNull()

            val fallback = getString(Res.string.add_tv_request_failed)
            when {
                row == null -> TvLoginApprovalResult.Refused(fallback)
                row.success -> TvLoginApprovalResult.Approved
                // The server's refusals ("Invalid or expired TV login code", the
                // throttle notice) are written for the person reading them, so
                // they are shown verbatim rather than reworded here.
                else -> TvLoginApprovalResult.Refused(
                    row.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "Approving a TV login code failed" }
            TvLoginApprovalResult.Refused(getString(Res.string.add_tv_request_failed))
        }
    }
}

@Serializable
private data class TvLoginApprovalRow(
    val success: Boolean = false,
    val message: String? = null,
)
