package tw.pentamaster.bizcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.pentamaster.bizcard.data.CardRepository
import tw.pentamaster.bizcard.ocr.CardParser
import tw.pentamaster.bizcard.ocr.OcrLine
import tw.pentamaster.bizcard.ocr.OcrResult

class CardParserTest {

    private fun ocr(vararg lines: Pair<String, Int>) = OcrResult(
        rawText = lines.joinToString("\n") { it.first },
        lines = lines.mapIndexed { i, (t, h) -> OcrLine(t, h, i * 30, 0) }
    )

    @Test
    fun `picks the tallest short CJK line as the name`() {
        val result = CardParser.parse(
            ocr(
                "檳傑電子設備股份有限公司" to 22,
                "陳志明" to 44,
                "資深工程師" to 20,
                "TEL: 03-5731234" to 18
            )
        )
        assertEquals("陳志明", result.name)
        assertEquals("檳傑電子設備股份有限公司", result.company)
        assertEquals("資深工程師", result.title)
    }

    @Test
    fun `splits merged bilingual name and company lines`() {
        val result = CardParser.parse(
            ocr(
                "王大明 David Wang" to 42,
                "業務經理" to 18,
                "範例科技EXAMPLE TECHNOLOGY CO., LTD." to 22,
                "Mobile: 0912-345-678" to 16
            )
        )
        assertEquals("王大明", result.name)
        assertEquals("David Wang", result.nameEn)
        assertEquals("範例科技", result.company)
        assertEquals("EXAMPLE TECHNOLOGY CO., LTD.", result.companyEn)
    }

    @Test
    fun `captures bilingual fields when OCR returns separate lines`() {
        val result = CardParser.parse(
            ocr(
                "王大明" to 42,
                "David Wang" to 40,
                "範例科技股份有限公司" to 22,
                "EXAMPLE TECHNOLOGY CO., LTD." to 21,
                "Sales Manager" to 18
            )
        )
        assertEquals("王大明", result.name)
        assertEquals("David Wang", result.nameEn)
        assertEquals("範例科技股份有限公司", result.company)
        assertEquals("EXAMPLE TECHNOLOGY CO., LTD.", result.companyEn)
    }

    @Test
    fun `separates mobile from landline`() {
        val result = CardParser.parse(
            ocr(
                "手機 0912-345-678" to 18,
                "電話 (03)553-1234" to 18,
                "傳真 03-553-5678" to 18
            )
        )
        assertEquals("0912-345-678", result.mobile)
        assertTrue(result.phone.contains("553"))
        assertTrue(result.fax.contains("5678"))
    }

    @Test
    fun `reads extension numbers`() {
        val result = CardParser.parse(ocr("TEL: 03-5731234 分機 208" to 18))
        assertTrue("got: ${result.phone}", result.phone.contains("208"))
    }

    @Test
    fun `reads international Taiwan landline without domestic zero`() {
        val result = CardParser.parse(ocr("TEL: +886-3-5731234" to 18))
        assertEquals("+886-3-5731234", result.phone)
    }

    @Test
    fun `extracts tel fax and mobile when OCR merges them into one line`() {
        val result = CardParser.parse(
            ocr("T: 03-5731234  F: 03-5735678  M: 0912-345-678" to 18)
        )
        assertEquals("03-5731234", result.phone)
        assertEquals("03-5735678", result.fax)
        assertEquals("0912-345-678", result.mobile)
    }

    @Test
    fun `extracts email and website without confusing them`() {
        val result = CardParser.parse(
            ocr(
                "stephen@pentamaster.com.my" to 16,
                "www.pentamaster.com.my" to 16
            )
        )
        assertEquals("stephen@pentamaster.com.my", result.email)
        assertTrue(result.website.startsWith("www."))
    }

    @Test
    fun `accepts modern website TLDs`() {
        val result = CardParser.parse(ocr("https://example.ai/team" to 16))
        assertEquals("https://example.ai/team", result.website)
    }

    @Test
    fun `recognises a Taiwanese address`() {
        val result = CardParser.parse(
            ocr("地址:新竹縣竹北市光明六路２號１２樓" to 16)
        )
        assertTrue("got: ${result.address}", result.address.contains("竹北市"))
    }

    @Test
    fun `keeps raw text so search can fall back on it`() {
        val result = CardParser.parse(ocr("看不懂的一行" to 16))
        assertTrue(result.rawTextFront.contains("看不懂的一行"))
    }

    @Test
    fun `handles a complete realistic card`() {
        val result = CardParser.parse(
            ocr(
                "陳大文" to 40,
                "業務經理" to 18,
                "台灣積體電路製造股份有限公司" to 20,
                "Mobile: 0928 123 456" to 16,
                "chen@tsmc.com" to 16,
                "新竹市力行六路8號" to 16,
                "Tel: (03)563-6688 ext.12345" to 16
            )
        )
        assertEquals("陳大文", result.name)
        assertEquals("業務經理", result.title)
        assertEquals("chen@tsmc.com", result.email)
        assertTrue("got: ${result.mobile}", result.mobile.replace("-", "").endsWith("123456"))
        assertTrue("got: ${result.phone}", result.phone.contains("12345"))
        assertTrue("got: ${result.address}", result.address.contains("力行六路"))
    }

    @Test
    fun `escapeLike neutralises SQL wildcards`() {
        assertEquals("100\\%", CardRepository.escapeLike("100%"))
        assertEquals("a\\_b", CardRepository.escapeLike("a_b"))
        assertEquals("c\\\\d", CardRepository.escapeLike("c\\d"))
    }

    @Test
    fun `mobile duplicate key normalises local and international Taiwan formats`() {
        assertEquals(
            CardRepository.mobileDuplicateKey("0912-345-678"),
            CardRepository.mobileDuplicateKey("+886 912 345 678")
        )
        assertEquals("", CardRepository.mobileDuplicateKey("12345"))
    }
}
