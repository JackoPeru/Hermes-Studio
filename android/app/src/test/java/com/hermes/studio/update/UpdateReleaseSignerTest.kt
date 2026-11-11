package com.hermes.studio.update

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateReleaseSignerTest {

    private fun freshKeyPair(): Pair<String, java.security.PrivateKey> {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val kp = gen.generateKeyPair()
        // kp.public.encoded è già SPKI DER: stesso formato della chiave embedded.
        val spki = Base64.getEncoder().encodeToString(kp.public.encoded)
        return spki to kp.private
    }

    private fun signB64(canonical: String, priv: java.security.PrivateKey): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv)
        s.update(canonical.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(s.sign())
    }

    @Test
    fun `release firmata correttamente passa`() {
        val (pub, priv) = freshKeyPair()
        val unsigned = AppUpdateRelease(
            versionCode = 42L, versionName = "0.1.0-test",
            apkUrl = "https://github.com/x/y/releases/download/studio-42/a.apk",
            assetSize = 12345L, sha256 = "ab".repeat(32), signature = ""
        )
        val sig = signB64(UpdateReleaseSigner.canonicalManifest(unsigned), priv)
        assertNull(UpdateReleaseSigner.verifyRelease(unsigned.copy(signature = sig), pub))
    }

    @Test
    fun `tampering versionCode invalida la firma`() {
        val (pub, priv) = freshKeyPair()
        val base = AppUpdateRelease(
            versionCode = 42L, versionName = "0.1.0-test",
            apkUrl = "https://github.com/x/y/releases/download/studio-42/a.apk",
            assetSize = 12345L, sha256 = "ab".repeat(32), signature = ""
        )
        val sig = signB64(UpdateReleaseSigner.canonicalManifest(base), priv)
        val tampered = base.copy(versionCode = 9999, signature = sig)
        assertNotNull(UpdateReleaseSigner.verifyRelease(tampered, pub))
    }

    @Test
    fun `chiave vuota e sha mancante rifiutati fail-closed`() {
        val r = AppUpdateRelease(
            versionCode = 1L, versionName = "x",
            apkUrl = "https://x/y.apk", assetSize = 10L,
            sha256 = "ab".repeat(32), signature = "zzz"
        )
        assertNotNull(UpdateReleaseSigner.verifyRelease(r, ""))
        assertNotNull(UpdateReleaseSigner.verifyRelease(r.copy(sha256 = ""), UpdateReleaseSigner.RELEASE_SIGNING_PUBLIC_KEY_SPKI_B64))
    }
}
