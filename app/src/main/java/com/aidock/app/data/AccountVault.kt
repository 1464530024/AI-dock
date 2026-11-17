package com.aidock.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 一条保存的站点账号。password 在磁盘上始终是密文，只在内存里是明文。 */
data class VaultAccount(
    val id: String,
    val assistantId: String,
    val username: String,
    val password: String,
    val note: String,
    val updatedAt: Long,
    /** 密钥失效（换机恢复备份等）导致解不开时为 true，密码留空待用户重填 */
    val broken: Boolean = false,
    /** 站点显示名，来自 AssistantCatalog；找不到对应助手时保留原始字符串 */
    val siteName: String = ""
)

/** 二次验证策略。hash 为加盐迭代摘要，不保存明文验证码。 */
data class PinPolicy(
    val enabled: Boolean = false,
    val length: Int = 4,
    val hash: String = "",
    val salt: String = "",
    val failCount: Int = 0,
    val lockedUntilMillis: Long = 0L
) {
    fun lockedSeconds(now: Long): Int =
        ((lockedUntilMillis - now) / 1000L).toInt().coerceAtLeast(0)
}

data class VaultSnapshot(
    val accounts: List<VaultAccount> = emptyList(),
    val pin: PinPolicy = PinPolicy(),
    /** 硬件级密钥可用（AndroidKeyStore 正常），否则降级为本地随机密钥 */
    val hardwareBacked: Boolean = true
)

sealed interface PinCheck {
    data object Ok : PinCheck
    data class Wrong(val remaining: Int) : PinCheck
    data class Locked(val seconds: Int) : PinCheck
}

/**
 * 账号保险箱。
 *
 * 存储策略：
 *  - 账号列表 → filesDir/vault.json，密码字段用 AES-256/GCM 单独加密；
 *  - 密钥 → AndroidKeyStore（不可导出），无法导出即无法在别的设备上解开；
 *  - 二次验证 → 只存 SaltedHash(验证码)，不存明文，天然无法反推。
 *
 * 说明：二次验证是"防旁人误看"的界面闸门，不替代系统锁屏。
 */
class AccountVault(private val context: Context) {

    private val file = File(context.filesDir, "vault.json")
    private val fallbackKeyFile = File(context.filesDir, ".vault.key")
    private val mutex = Mutex()
    private val rnd = SecureRandom()

    // ------------------------------------------------------------ 加密

    private fun androidKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                gen.generateKey()
            }
    } catch (_: Throwable) {
        null
    }

    /** 降级密钥：Keystore 不可用时的本地随机密钥（仍比明文强，但不是硬件保护） */
    private fun fallbackKey(): SecretKey {
        val bytes = if (fallbackKeyFile.exists()) {
            fallbackKeyFile.readBytes()
        } else {
            ByteArray(32).also { rnd.nextBytes(it) }.also {
                fallbackKeyFile.writeBytes(it)
                fallbackKeyFile.setReadable(false, false)
            }
        }
        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
    }

    private fun key(): Pair<SecretKey, Boolean> =
        androidKey()?.let { it to true } ?: (fallbackKey() to false)

    /**
     * 加密。
     *
     * Keystore 生成的 AES 密钥默认 `setRandomizedEncryptionRequired(true)`，
     * 也就是说 **不允许调用方自带 IV**（自己传 GCMParameterSpec 会抛
     * InvalidAlgorithmParameterException: Caller-provided IV not permitted）。
     * 正确做法是 init 时不传 IV，由 Keystore 生成后从 cipher.iv 取出来存进密文头；
     * 解密时再把 IV 传回去（解密方向不受此限制）。
     *
     * 降级密钥是普通 SecretKeySpec，没有这个限制，需要自己生成 IV。
     */
    private fun encrypt(plain: String): String {
        val (k, hardware) = key()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val iv: ByteArray = if (hardware) {
            c.init(Cipher.ENCRYPT_MODE, k)
            c.iv
        } else {
            ByteArray(12).also { rnd.nextBytes(it) }.also {
                c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, it))
            }
        }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "v1:" + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(token: String): String? {
        return try {
            val parts = token.split(":")
            if (parts.size != 3) return null
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ct = Base64.decode(parts[2], Base64.NO_WRAP)
            val (k, _) = key()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 验证码

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val saltBytes = salt.toByteArray(Charsets.UTF_8)
        var cur = md.digest((salt + ":" + pin).toByteArray(Charsets.UTF_8))
        repeat(PIN_ROUNDS - 1) {
            md.reset()
            md.update(saltBytes)
            md.update(cur)
            cur = md.digest()
        }
        return Base64.encodeToString(cur, Base64.NO_WRAP)
    }

    private fun newSalt(): String =
        Base64.encodeToString(ByteArray(16).also { rnd.nextBytes(it) }, Base64.NO_WRAP)

    // ------------------------------------------------------------ 读写

    private fun readRoot(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun writeRoot(root: JSONObject) {
        val tmp = File(file.parentFile, "vault.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    private fun parsePin(root: JSONObject): PinPolicy {
        val o = root.optJSONObject("pin") ?: return PinPolicy()
        return PinPolicy(
            enabled = o.optBoolean("enabled", false),
            length = if (o.optInt("length", 4) == 6) 6 else 4,
            hash = o.optString("hash", ""),
            salt = o.optString("salt", ""),
            failCount = o.optInt("fail", 0),
            lockedUntilMillis = o.optLong("lockedUntil", 0L)
        )
    }

    private fun writePin(root: JSONObject, p: PinPolicy) {
        root.put(
            "pin", JSONObject()
                .put("enabled", p.enabled)
                .put("length", p.length)
                .put("hash", p.hash)
                .put("salt", p.salt)
                .put("fail", p.failCount)
                .put("lockedUntil", p.lockedUntilMillis)
        )
    }

    private fun parseAccounts(root: JSONObject): List<VaultAccount> {
        val arr = root.optJSONArray("accounts") ?: JSONArray()
        val out = ArrayList<VaultAccount>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val raw = o.optString("pwd", "")
            val plain = if (raw.isEmpty()) "" else decrypt(raw)
            val siteId = o.optString("assistantId", "")
            out += VaultAccount(
                id = id,
                assistantId = siteId,
                username = o.optString("user", ""),
                password = plain ?: "",
                note = o.optString("note", ""),
                updatedAt = o.optLong("at", 0L),
                broken = raw.isNotEmpty() && plain == null,
                siteName = o.optString("site", "")
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }

    private fun writeAccounts(root: JSONObject, list: List<VaultAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("assistantId", a.assistantId)
                    .put("site", a.siteName)
                    .put("user", a.username)
                    .put("pwd", if (a.password.isEmpty()) "" else encrypt(a.password))
                    .put("note", a.note)
                    .put("at", a.updatedAt)
            )
        }
        root.put("accounts", arr)
    }

    // ------------------------------------------------------------ 对外 API

    suspend fun snapshot(): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRoot()
            VaultSnapshot(
                accounts = parseAccounts(root),
                pin = parsePin(root),
                hardwareBacked = androidKey() != null
            )
        }
    }

    suspend fun upsert(account: VaultAccount): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRoot()
            val list = parseAccounts(root).toMutableList()
            val idx = list.indexOfFirst { it.id == account.id }
            if (idx >= 0) list[idx] = account else list.add(0, account)
            writeAccounts(root, list)
            writeRoot(root)
            VaultSnapshot(parseAccounts(root), parsePin(root), androidKey() != null)
        }
    }

    suspend fun delete(id: String): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRoot()
            val list = parseAccounts(root).filterNot { it.id == id }
            writeAccounts(root, list)
            writeRoot(root)
            VaultSnapshot(parseAccounts(root), parsePin(root), androidKey() != null)
        }
    }

    suspend fun setPinEnabled(enabled: Boolean, pin: String): VaultSnapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val root = readRoot()
                val old = parsePin(root)
                val next = if (enabled) {
                    val salt = if (old.salt.isBlank()) newSalt() else old.salt
                    // 已有验证码且未改位数时沿用旧摘要，避免"开关一下就得重设"
                    if (pin.isBlank() && old.hash.isNotBlank()) {
                        old.copy(enabled = true)
                    } else {
                        old.copy(
                            enabled = true,
                            length = if (pin.length == 6) 6 else 4,
                            salt = salt,
                            hash = hashPin(pin, salt),
                            failCount = 0,
                            lockedUntilMillis = 0L
                        )
                    }
                } else {
                    old.copy(enabled = false, failCount = 0, lockedUntilMillis = 0L)
                }
                writePin(root, next)
                writeRoot(root)
                VaultSnapshot(parseAccounts(root), next, androidKey() != null)
            }
        }

    suspend fun verifyPin(input: String): Pair<PinCheck, PinPolicy> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRoot()
            val p = parsePin(root)
            val now = System.currentTimeMillis()

            if (!p.enabled) return@withLock PinCheck.Ok to p

            val left = p.lockedSeconds(now)
            if (left > 0) return@withLock PinCheck.Locked(left) to p

            val ok = p.hash.isNotBlank() && p.hash == hashPin(input, p.salt)
            if (ok) {
                val next = p.copy(failCount = 0, lockedUntilMillis = 0L)
                writePin(root, next)
                writeRoot(root)
                PinCheck.Ok to next
            } else {
                val fails = p.failCount + 1
                val next = if (fails >= PIN_MAX_FAILS) {
                    p.copy(failCount = 0, lockedUntilMillis = now + PIN_LOCK_MILLIS)
                } else {
                    p.copy(failCount = fails)
                }
                writePin(root, next)
                writeRoot(root)
                if (fails >= PIN_MAX_FAILS) {
                    PinCheck.Locked((PIN_LOCK_MILLIS / 1000).toInt()) to next
                } else {
                    PinCheck.Wrong(PIN_MAX_FAILS - fails) to next
                }
            }
        }
    }

    /** 只改位数或改验证码；不改变开关状态 */
    suspend fun updatePin(pin: String): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRoot()
            val old = parsePin(root)
            val salt = if (old.salt.isBlank()) newSalt() else old.salt
            val next = old.copy(
                length = if (pin.length == 6) 6 else 4,
                salt = salt,
                hash = hashPin(pin, salt),
                failCount = 0,
                lockedUntilMillis = 0L
            )
            writePin(root, next)
            writeRoot(root)
            VaultSnapshot(parseAccounts(root), next, androidKey() != null)
        }
    }

    suspend fun deleteAllAccounts(): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = readRoot()
            root.put("accounts", JSONArray())
            writeRoot(root)
            VaultSnapshot(emptyList(), parsePin(root), androidKey() != null)
        }
    }

    fun newId(): String = "acc_" + System.currentTimeMillis().toString(36) +
        "_" + Integer.toHexString(rnd.nextInt())

    companion object {
        private const val ALIAS = "aidock_vault_v1"
        private const val PIN_ROUNDS = 4096
        private const val PIN_MAX_FAILS = 5
        private const val PIN_LOCK_MILLIS = 30_000L

        val hardwareInfo: String
            get() = "AndroidKeyStore · AES-256-GCM · ${Build.VERSION.RELEASE}"
    }
}
