package com.openclaw.clawagent.provider

import com.openclaw.clawagent.agent.HttpToolLogic
import org.junit.Assert
import org.junit.Test

/**
 * 单元测试：ProviderCatalog (T-104)
 * 验证：
 * 1. Provider ID 唯一性
 * 2. Provider displayName 唯一性
 * 3. Provider 端点 URL 合法性 (复用 HttpToolLogic.validateUrl)
 * 4. 总预设数量 = 13 (原 10 家 + 新增 3 家)
 * 5. 新增的 3 家预设 (siliconflow / kimi / groq) 存在且字段合规
 */
class ProviderCatalogTest {

    @Test
    fun testProviderIdsAreUnique() {
        val ids = ProviderCatalog.PROVIDERS.map { it.id }
        val uniqueIds = ids.distinct()
        Assert.assertEquals("所有 Provider ID 应该唯一", ids.size, uniqueIds.size)
    }

    @Test
    fun testDisplayNamesAreUnique() {
        val names = ProviderCatalog.PROVIDERS.map { it.displayName }
        val uniqueNames = names.distinct()
        Assert.assertEquals("所有 Provider displayName 应该唯一", names.size, uniqueNames.size)
    }

    @Test
    fun testEndpointsAreValidUrls() {
        for (provider in ProviderCatalog.PROVIDERS) {
            val endpoint = provider.defaultEndpoint
            val isValid = HttpToolLogic.validateUrl(endpoint) != null
            Assert.assertTrue(
                "Provider ${provider.id} 的端点 '$endpoint' 应该是合法的 URL",
                isValid
            )
        }
    }

    @Test
    fun testProviderCount() {
        // 原有 10 家 + 新增 3 家 = 13 家
        val expectedCount = 13
        Assert.assertEquals("预设 Provider 总数应该是 13 家", expectedCount, ProviderCatalog.PROVIDERS.size)
    }

    @Test
    fun testNewProvidersPresentAndWellFormed() {
        val newIds = listOf("siliconflow", "kimi", "groq")
        for (id in newIds) {
            val p = ProviderCatalog.findById(id)
            Assert.assertNotNull("应该存在新增预设 '$id'", p)
            // 端点必须是合法 https URL
            Assert.assertTrue(
                "新增预设 '$id' 的端点应为合法 https URL，实际: ${p.defaultEndpoint}",
                p.defaultEndpoint.startsWith("https://") &&
                    HttpToolLogic.validateUrl(p.defaultEndpoint) != null
            )
            // 默认模型必须非空（任务卡要求：当前确实可用的 chat 模型）
            Assert.assertTrue(
                "新增预设 '$id' 的 defaultModel 不应为空",
                p.defaultModel.isNotBlank()
            )
        }
    }
}
