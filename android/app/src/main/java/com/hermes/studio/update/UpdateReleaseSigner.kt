package com.hermes.studio.update

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifica firme del canale update. Puro JVM (niente android.*): testabile in unit test.
 *
 * Formato: ECDSA P-256 + SHA-256 sul manifest canonico
 * `hermes-studio-update-v1\n{versionCode}\n{versionName}\n{apkUrl}\n{assetSize}\n{sha256}`.
 * Chiave pubblica va incorporata in [RELEASE_SIGNING_PUBLIC_KEY_SPKI_B64] (SPKI DER base64).
 * Senza chiave o firma invalida la release e' rifiutata (fail-closed).
 *
 * Setup chiave (una tantum, sul server/maintainer — MAI in CI):
 * `openssl ecparam -genkey -name prime256v1 -noout -out hermes-studio-update-sign.key`
 * `openssl ec -in hermes-studio-update-sign.key -pubout -outform DER | base64 -w0` -> SPKI qui.
 * `openssl dgst -sha256 -sign hermes-studio-update-sign.key` sul manifest canonico -> signature.
 * Firma release:
 * `printf 'hermes-studio-update-v1\n%s\n%s\n%s\n%s\n%s' CODE NAME URL SIZE SHA | openssl dgst -sha256 -sign hermes-studio-update-sign.key | base64 -w0`
 */
object UpdateReleaseSigner {
    /**
     * SPKI pubblica P-256 (DER, base64) del canale update Hermes Studio.
     * Generata 2026-11-11 dal maintainer, privata SOLO in
     * `Documents/HermesStudio-update-keys/hermes-studio-update-sign.key` (mai nel repo).
     * Fingerprint SHA-256 SPKI: 91e247fd0c04e5b74cf7ea733625f2f6348ed3d1dc8fd527e0327a07a9a97fe7.
     * Smarrirla = rigenerare coppia + reinstallazione pulita (i client vecchi
     * rifiutano feed firmati con la nuova chiave).
     */
    const val RELEASE_SIGNING_PUBLIC_KEY_SPKI_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEv3ZgFmbbNAeGeo4OkEpEMA7l48vJQRf5QLF73o/W2RrX3qsc6+hvzbqqAUHMlzuBZeOJ5SplbrES4/aJogAEjA=="

    fun canonicalManifest(release: AppUpdateRelease): String = listOf(
        "hermes-studio-update-v1",
        release.versionCode.toString(),
        release.versionName,
        release.apkUrl,
        release.assetSize.toString(),
        release.sha256.lowercase()
    ).joinToString("\n")

    /** @return null se valida, altrimenti motivo del rifiuto. */
    fun verifyRelease(release: AppUpdateRelease, publicKeySpkiB64: String = RELEASE_SIGNING_PUBLIC_KEY_SPKI_B64): String? {
        if (publicKeySpkiB64.isBlank()) {
            return "Canale update senza chiave di firma configurata: release rifiutata."
        }
        if (release.assetSize <= 0L) {
            return "Release con dimensione asset non valida: impossibile verificare l'APK."
        }
        if (release.sha256.isBlank()) {
            return "Release senza SHA-256: impossibile verificare l'APK."
        }
        if (!release.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            return "SHA-256 release malformato."
        }
        if (release.signature.isBlank()) {
            return "Release senza firma: impossibile autenticare il manifest."
        }
        return try {
            val keyBytes = Base64.getDecoder().decode(publicKeySpkiB64)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            val signatureBytes = Base64.getDecoder().decode(release.signature)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(canonicalManifest(release).toByteArray(Charsets.UTF_8))
            if (verifier.verify(signatureBytes)) null else "Firma release non valida."
        } catch (throwable: Throwable) {
            "Verifica firma fallita: ${throwable.message ?: "errore"}."
        }
    }
}
