package com.zcode.watchrec

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TingwuStorageGrantTest {
    private fun grant() = JSONObject().put("endpoint", "oss-cn-shanghai.aliyuncs.com")
        .put("bucket", "synthetic-bucket").put("fileKey", "synthetic/object.m4a")
        .put("accessKeyId", "synthetic-id").put("accessKeySecret", "synthetic-secret").put("securityToken", "synthetic-token")

    @Test fun temporaryWebsiteGrantUsesHttpsOssEndpoint() {
        val auth = TingwuStorageGrant(grant())
        assertEquals("https://oss-cn-shanghai.aliyuncs.com", auth.endpoint)
        assertEquals("synthetic-bucket", auth.bucket)
        assertEquals("synthetic/object.m4a", auth.fileKey)
    }

    @Test fun rejectsNonOssOrCredentialBearingEndpointsWithoutEchoingSecrets() {
        for (endpoint in listOf("http://oss-cn-shanghai.aliyuncs.com", "https://evil.test", "https://oss-cn-shanghai.aliyuncs.com.evil.test", "https://user:secret@oss-cn-shanghai.aliyuncs.com", "https://oss-cn-shanghai-internal.aliyuncs.com", "https://oss-cn-shanghai.aliyuncs.com:444", "https://oss-cn-shanghai.aliyuncs.com/?token=secret")) {
            val error = assertThrows(TingwuFailure::class.java) { TingwuStorageGrant(grant().put("endpoint", endpoint)) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun incompleteGrantsNeverBeginStorageRequests() {
        for (key in listOf("bucket", "fileKey", "accessKeyId", "accessKeySecret", "securityToken")) {
            assertThrows(TingwuFailure::class.java) { TingwuStorageGrant(grant().apply { remove(key) }) }
        }
    }
}
