package tw.pentamaster.bizcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.pentamaster.bizcard.data.CsvCardImporter
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

class CsvCardImporterTest {

    @Test
    fun parsesCamCardHeadersAndPreservesCreatedDate() {
        val csv = "\uFEFF創建日期,姓名,名字,名字拚音或音標,姓,姓氏拚音或音標,公司1,部門1,職位1,公司2\r\n" +
            "2024/05/24 13:28,王小明,小明,xiaoming,王,wang,Example Corp,研發部,經理,Example Taiwan\r\n"

        val card = CsvCardImporter.parse(csv.toByteArray(Charsets.UTF_8)).single()
        assertEquals("王小明", card.name)
        assertEquals("Example Corp", card.company)
        assertEquals("研發部", card.department)
        assertEquals("經理", card.title)
        assertTrue(card.notes.contains("Example Taiwan"))
        assertTrue(card.rawTextFront.contains("名字拚音或音標: xiaoming"))
        assertEquals(
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).parse("2024/05/24 13:28")!!.time,
            card.createdAt
        )
    }

    @Test
    fun handlesQuotedCommaAndRicherContactColumns() {
        val csv = "姓名,公司,手機,電話,Email,地址,備註\r\n" +
            "陳大華,\"ACME, Inc.\",0912345678,02-12345678,user@example.com,台北市,客戶\r\n"

        val card = CsvCardImporter.parse(csv.toByteArray()).single()
        assertEquals("ACME, Inc.", card.company)
        assertEquals("0912345678", card.mobile)
        assertEquals("02-12345678", card.phone)
        assertEquals("user@example.com", card.email)
    }

    @Test
    fun fallsBackToBig5ForLegacyChineseCsv() {
        val csv = "姓名,公司,職稱\r\n林測試,範例科技,經理\r\n"
        val card = CsvCardImporter.parse(csv.toByteArray(Charset.forName("Big5"))).single()
        assertEquals("林測試", card.name)
        assertEquals("範例科技", card.company)
        assertEquals("經理", card.title)
    }
}
