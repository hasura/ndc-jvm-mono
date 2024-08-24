package io.hasura.services.dataSourceService.authHandlers

import io.agroal.api.configuration.supplier.AgroalConnectionFactoryConfigurationSupplier
import io.agroal.api.security.AgroalSecurityProvider
import io.agroal.api.security.NamePrincipal
import net.snowflake.client.jdbc.internal.org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import net.snowflake.client.jdbc.internal.org.bouncycastle.jce.provider.BouncyCastleProvider
import net.snowflake.client.jdbc.internal.org.bouncycastle.openssl.PEMParser
import net.snowflake.client.jdbc.internal.org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import net.snowflake.client.jdbc.internal.org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import net.snowflake.client.jdbc.internal.org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.io.File
import java.net.URLDecoder
import java.security.PrivateKey
import java.security.Security
import java.util.*

data class PrivateKeyParts(val pathToKey: String, val passphrase: String?)

object PrivateKeySecurityProvider : AgroalSecurityProvider {
    override fun getSecurityProperties(securityObject: Any?): Properties {
        val props = Properties()
        val pk = getKey(securityObject as PrivateKeyParts)
        props["privateKey"] = pk
        return props
    }

    private fun getKey(privateKeyParts: PrivateKeyParts): PrivateKey {
        Security.addProvider(BouncyCastleProvider())
        val pemParser = PEMParser(File(privateKeyParts.pathToKey).bufferedReader())
        val pemObject = pemParser.readObject()
        val privateKeyInfo = when (pemObject) {
            is PKCS8EncryptedPrivateKeyInfo -> {
                val pkcs8Prov = JceOpenSSLPKCS8DecryptorProviderBuilder().build(privateKeyParts.passphrase!!.toCharArray())
                pemObject.decryptPrivateKeyInfo(pkcs8Prov)
            }
            is PrivateKeyInfo -> pemObject
            else -> null
        }
        pemParser.close()
        val converter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
        return converter.getPrivateKey(privateKeyInfo)
    }
}

class PrivateKeyHandler(
    override val jdbcUrl: String,
    override val properties: Map<String, Any>,
    override val connFactory: AgroalConnectionFactoryConfigurationSupplier
) : BaseAuthHandler(jdbcUrl, properties, connFactory) {

    private fun getKeyParts(): PrivateKeyParts {
        return if (properties.containsKey("private_key_file")) {
            PrivateKeyParts(
                properties["private_key_file"] as String,
                properties.getOrDefault("private_key_file_pwd", null) as String?
            )
        } else if (jdbcUrl.contains("private_key_file")) {
            val (filteredJdbcUrl, queryString) = jdbcUrl.split('?')
            val params = queryString
                .split('&')
                .map {
                    val parts = it.split('=')
                    val name = parts.firstOrNull() ?: ""
                    val value = parts.lastOrNull() ?: ""
                    Pair(name, value)
                }.toMap()

            connFactory.principal(NamePrincipal(params["user"]))
            setProps(params.filterKeys { it != "private_key_file" && it != "private_key_file_pwd" })
            connFactory.jdbcUrl(filteredJdbcUrl)

            val passphrase = params.getOrDefault("private_key_file_pwd", null)?.let {
                URLDecoder.decode(it, "UTF-8")
            }
            PrivateKeyParts(params["private_key_file"]!!, passphrase)
        } else throw MissingParameterException("private_key_file")
    }

    override fun configFactory() {
        requireUser(true)
        connFactory.addSecurityProvider(PrivateKeySecurityProvider)
        connFactory.credential(getKeyParts())
        setProps(properties.filterKeys { it != "private_key_file" && it != "private_key_file_pwd" })
    }
}
