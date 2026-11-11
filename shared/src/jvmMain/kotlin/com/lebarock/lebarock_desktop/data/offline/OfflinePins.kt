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
 *  - a Caisse's offline PIN signs in as its « Hors ligne » account; the copy holds every Caisse of the branch and the
 *    PIN typed decides which (O4a; the caisse never lets two Caisses share one);
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
            "Connexion hors ligne indisponible : définissez le code PIN hors ligne d'une caisse (Paramètres → Mode hors ligne sur la caisse) et enregistrez ce poste avec la clé de synchronisation (Réglages → Mode hors ligne)."
        const val NO_DISCOUNT_CODE = "Aucun code remise n'est défini : l'Admin le définit dans Paramètres → Remises."
        const val DISCOUNT_WRONG = "Code remise incorrect."
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

    /** Offline sign-in is possible on this PC (a copy with at least one Caisse's offline PIN). */
    suspend fun signInAvailable(): Boolean = store.offlineAccesses().any { secrets.open(it.accountVerifierSealed) != null }

    /** A Caisse's offline PIN: on success, that Caisse's « Hors ligne » account to sign in with. */
    suspend fun signIn(pin: String): Result<OfflineAccess> {
        val candidates = store.offlineAccesses().mapNotNull { access -> secrets.open(access.accountVerifierSealed)?.let { access to it } }
        if (candidates.isEmpty()) return Result.failure(IllegalStateException(NONE))
        var found: OfflineAccess? = null
        return check(pin) { typed ->
            found = candidates.firstOrNull { (_, verifier) -> matches(typed, verifier) }?.first
            found != null
        }.map { found!! }
    }

    /** « Code remise » (client, 2026-11-11): the restaurant's discount code, for a discount added offline. */
    suspend fun checkDiscountCode(code: String): Result<Unit> {
        val verifier = secrets.open(store.offlineAccess()?.discountVerifierSealed) ?: return Result.failure(IllegalStateException(NO_DISCOUNT_CODE))
        return check(code) { typed -> matches(typed, verifier) }.recoverCatching { e ->
            // The same words as the caisse's.
            throw IllegalStateException(e.message?.replace(WRONG, DISCOUNT_WRONG)?.replace("Le code PIN compte", "Le code remise compte"))
        }
    }

    /** The shared payment PIN (Admin / Gérant), for offline payments on a check. */
    suspend fun checkPaymentPin(pin: String): Result<Unit> {
        val verifier = secrets.open(store.offlineAccess()?.paymentVerifierSealed) ?: return Result.failure(IllegalStateException(NO_PAYMENT_PIN))
        return check(pin) { typed -> matches(typed, verifier) }
    }

    private suspend fun check(pin: String, test: (String) -> Boolean): Result<Unit> {
        val at = now()
        if (at < lockedUntil) {
            val seconds = ((lockedUntil - at + 999) / 1000).coerceAtLeast(1)
            return Result.failure(IllegalStateException("Trop d'essais : réessayez dans $seconds s."))
        }
        if (pin.length !in 4..6 || !pin.all { it.isDigit() }) {
            return Result.failure(IllegalStateException("Le code PIN compte de 4 à 6 chiffres."))
        }

        // About a tenth of a second per Caisse on a counter PC (100 000 rounds): off the UI thread.
        val ok = withContext(Dispatchers.Default) { test(pin) }
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
