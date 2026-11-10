package com.lebarock.lebarock_desktop.data.offline

import com.lebarock.lebarock_desktop.data.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The offline PINs on this PC (offline step 3, the handheld's OfflinePins), checked here while the caisse does not
 * answer:
 *  - the Caisse's offline PIN signs in as its « Hors ligne » account;
 *  - the shared payment PIN (Admin / Gérant) allows offline payments on a check.
 * The caisse ships only their checks — `pbkdf2-sha256$<iterations>$<salt>$<hash>` (base64), the same as
 * App\Support\Sync\OfflinePin — kept sealed for this Windows account. Five wrong PINs in a row lock the check for 30 s.
 */
class OfflinePins(
    private val store: OfflineStore,
    private val secrets: SecretBox,
    private val now: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val MAX_TRIES = 5
        const val LOCK_MS = 30_000L
        const val WRONG = "Code PIN incorrect."
        const val NONE =
            "Connexion hors ligne indisponible : définissez le code PIN hors ligne de cette caisse (Paramètres → Mode hors ligne sur la caisse), puis connectez une fois un compte sur ce poste quand la caisse répond."
        const val NO_PAYMENT_PIN =
            "Paiement hors ligne indisponible : aucun code de paiement n'est défini sur la caisse (Paramètres → Mode hors ligne)."

        /** PBKDF2-HMAC-SHA256 against a caisse verifier. */
        fun matches(pin: String, verifier: String?): Boolean {
            val bits = verifier?.split('$') ?: return false
            if (bits.size != 4 || bits[0] != "pbkdf2-sha256") return false
            val iterations = bits[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
            return runCatching {
                val salt = Base64.getDecoder().decode(bits[2])
                val hash = Base64.getDecoder().decode(bits[3])
                val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, hash.size * 8)
                val computed = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                spec.clearPassword()
                MessageDigest.isEqual(computed, hash)
            }.getOrDefault(false)
        }
    }

    @Volatile private var failures = 0
    @Volatile private var lockedUntil = 0L

    /** Offline sign-in is possible on this PC (a copy with the Caisse's offline PIN). */
    suspend fun signInAvailable(): Boolean = store.offlineAccess()?.accountVerifierSealed?.let { secrets.open(it) } != null

    /** The Caisse's offline PIN: on success, the « Hors ligne » account and its Caisse to sign in with. */
    suspend fun signIn(pin: String): Result<OfflineAccess> {
        val access = store.offlineAccess() ?: return Result.failure(IllegalStateException(NONE))
        val verifier = secrets.open(access.accountVerifierSealed) ?: return Result.failure(IllegalStateException(NONE))
        return check(pin, verifier).map { access }
    }

    /** The shared payment PIN (Admin / Gérant), for offline payments on a check. */
    suspend fun checkPaymentPin(pin: String): Result<Unit> {
        val verifier = secrets.open(store.offlineAccess()?.paymentVerifierSealed) ?: return Result.failure(IllegalStateException(NO_PAYMENT_PIN))
        return check(pin, verifier)
    }

    private suspend fun check(pin: String, verifier: String): Result<Unit> {
        val at = now()
        if (at < lockedUntil) {
            val seconds = ((lockedUntil - at + 999) / 1000).coerceAtLeast(1)
            return Result.failure(IllegalStateException("Trop d'essais : réessayez dans $seconds s."))
        }
        if (pin.length !in 4..6 || !pin.all { it.isDigit() }) {
            return Result.failure(IllegalStateException("Le code PIN compte de 4 à 6 chiffres."))
        }

        // About a tenth of a second on a counter PC (100 000 rounds): off the UI thread.
        val ok = withContext(Dispatchers.Default) { matches(pin, verifier) }
        if (ok) {
            failures = 0
            return Result.success(Unit)
        }

        failures++
        if (failures >= MAX_TRIES) {
            failures = 0
            lockedUntil = now() + LOCK_MS
            return Result.failure(IllegalStateException("$WRONG Trop d'essais : réessayez dans ${LOCK_MS / 1000} s."))
        }
        return Result.failure(IllegalStateException(WRONG))
    }
}
