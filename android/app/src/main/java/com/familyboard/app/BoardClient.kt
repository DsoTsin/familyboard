package com.familyboard.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.familyboard.protocol.*
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun serverOrigin(text: String): URI? = runCatching {
    URI(text.trim()).also { uri ->
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        require(uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
    }
}.getOrNull()

internal class BoardClient(private val context: Context) : AutoCloseable {
    private var channel: ManagedChannel? = null
    private var currentOrigin: String? = null

    @Synchronized
    private fun stub(origin: URI): FamilyBoardGrpc.FamilyBoardBlockingStub {
        val port = if (origin.port == -1) 443 else origin.port
        val key = "${origin.host.lowercase()}:$port"
        if (key != currentOrigin) {
            close()
            channel = OkHttpChannelBuilder.forAddress(origin.host.removeSurrounding("[", "]"), port)
                .useTransportSecurity()
                .maxInboundMessageSize(1024 * 1024)
                .build()
            currentOrigin = key
        }

        val metadata = Metadata()
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer ${credential(key)}")
        return FamilyBoardGrpc.newBlockingStub(checkNotNull(channel))
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    suspend fun connect(origin: URI): FamilyBoardGrpc.FamilyBoardBlockingStub =
        withContext(Dispatchers.IO) { stub(origin) }

    @Synchronized
    override fun close() {
        channel?.shutdownNow()
        channel = null
        currentOrigin = null
    }

    private fun credential(origin: String): String {
        val store = context.getSharedPreferences("device_credentials", Context.MODE_PRIVATE)
        val alias = "familyboard.session"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        store.getString(origin, null)?.let { encoded ->
            val (iv, ciphertext) = encoded.split(':', limit = 2)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.updateAAD(origin.toByteArray())
            return String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }

        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val credential = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(origin.toByteArray())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(credential.toByteArray()), Base64.NO_WRAP)
        check(store.edit().putString(origin, "$iv:$ciphertext").commit())
        return credential
    }
}
