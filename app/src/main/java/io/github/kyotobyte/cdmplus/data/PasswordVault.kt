package io.github.kyotobyte.cdmplus.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class SavedLogin(val site: String, val username: String, val password: String, val updatedAt: Long = System.currentTimeMillis())

/**
 * Password manager for the in-app browser. Logins are stored in one file encrypted with AES-256-GCM;
 * on the phone the key lives in the Android Keystore (it never leaves the secure hardware), see
 * [io.github.kyotobyte.cdmplus.service.KeystoreKey]. The UI asks for fingerprint / face / screen lock
 * before showing or filling a password.
 */
class PasswordVault(private val file: File, private val key: () -> SecretKey) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: List<SavedLogin>? = null

    companion object {
        /** "accounts.google.com" -> "google.com"; keeps 3 labels for things like "example.co.uk". */
        fun siteOf(hostOrUrl: String): String {
            val host = hostOrUrl.substringAfter("://").substringBefore('/').substringBefore(':').lowercase().removePrefix("www.")
            val p = host.split('.')
            if (p.size <= 2) return host
            val twoLevel = p[p.size - 2] in setOf("co", "com", "net", "org", "gov", "ac", "edu") && p.last().length == 2
            return p.takeLast(if (twoLevel) 3 else 2).joinToString(".")
        }

        fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            val iv = c.iv
            return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
        }

        fun decrypt(data: ByteArray, key: SecretKey): ByteArray {
            val n = data[0].toInt()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 1, n))
            return c.doFinal(data, 1 + n, data.size - 1 - n)
        }

        /** JavaScript that fills the first login form on the page. Values are passed JSON-escaped. */
        fun fillScript(user: String, pass: String): String {
            val u = Json.encodeToString(user); val p = Json.encodeToString(pass)
            return """(function(){var pw=document.querySelector('input[type=password]');if(!pw)return 'nofield';
var f=pw.form||document;var us=f.querySelector('input[type=email],input[autocomplete=username],input[name*=user i],input[name*=login i],input[name*=email i],input[type=text],input[type=tel]');
function set(el,v){if(!el)return;var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
set(us,$u);set(pw,$p);return 'ok';})()"""
        }

        /** JavaScript that reports a submitted login to the app (window.CdmVault.offer). */
        const val CAPTURE_SCRIPT = """(function(){if(window.__cdmv)return;window.__cdmv=1;
function grab(f){try{var pw=(f||document).querySelector('input[type=password]');if(!pw||!pw.value)return;var us=(f||document).querySelector('input[type=email],input[autocomplete=username],input[name*=user i],input[name*=login i],input[name*=email i],input[type=text],input[type=tel]');
window.CdmVault&&CdmVault.offer(location.host,us?us.value:'',pw.value);}catch(e){}}
document.addEventListener('submit',function(e){grab(e.target)},true);
document.addEventListener('click',function(e){var b=e.target.closest&&e.target.closest('button,input[type=submit]');if(b)grab(b.form)},true);})()"""
    }

    fun all(): List<SavedLogin> {
        cache?.let { return it }
        val list = if (!file.exists()) emptyList() else runCatching { json.decodeFromString<List<SavedLogin>>(String(decrypt(file.readBytes(), key()), Charsets.UTF_8)) }.getOrDefault(emptyList())
        cache = list
        return list
    }

    fun forSite(url: String): List<SavedLogin> { val s = siteOf(url); return all().filter { it.site == s } }

    @Synchronized fun save(url: String, user: String, pass: String) {
        val s = siteOf(url)
        val list = all().filterNot { it.site == s && it.username == user } + SavedLogin(s, user, pass)
        write(list)
    }

    @Synchronized fun delete(login: SavedLogin) = write(all().filterNot { it.site == login.site && it.username == login.username })

    fun isSame(url: String, user: String, pass: String) = forSite(url).any { it.username == user && it.password == pass }

    private fun write(list: List<SavedLogin>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(encrypt(json.encodeToString(list).toByteArray(), key()))
        tmp.renameTo(file)
        cache = list
    }
}
