package tw.pentamaster.bizcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.pentamaster.bizcard.data.BusinessCard
import tw.pentamaster.bizcard.data.CardCodec
import tw.pentamaster.bizcard.data.VCardReader

class BackupFormatTest {

    // ---- CSV --------------------------------------------------------------

    @Test
    fun `csv quotes fields containing commas`() {
        assertEquals("\"台北市,中山區\"", CardCodec.csvEscape("台北市,中山區"))
    }

    @Test
    fun `csv doubles embedded quotes`() {
        assertEquals("\"他說\"\"好\"\"\"", CardCodec.csvEscape("他說\"好\""))
    }

    @Test
    fun `csv defuses leading formula characters`() {
        assertEquals("'=1+1", CardCodec.csvEscape("=1+1"))
        assertEquals("'+886912345678", CardCodec.csvEscape("+886912345678"))
    }

    @Test
    fun `csv leaves ordinary text alone`() {
        assertEquals("陳志明", CardCodec.csvEscape("陳志明"))
    }

    @Test
    fun `csv row column count matches the header`() {
        val row = CardCodec.toCsvRow(
            BusinessCard(
                name = "陳志明",
                nameEn = "David Chen",
                company = "檳傑電子",
                companyEn = "Example Electronics"
            )
        )
        assertEquals(CardCodec.CSV_HEADERS.size, row.split(",").size)
    }

    // ---- JSON -------------------------------------------------------------

    @Test
    fun `json backup preserves bilingual fields and reads old backups`() {
        val original = BusinessCard(
            name = "王大明",
            nameEn = "David Wang",
            company = "範例科技",
            companyEn = "EXAMPLE TECHNOLOGY"
        )
        val restored = CardCodec.fromJson(CardCodec.toJson(original))
        assertEquals("王大明", restored.name)
        assertEquals("David Wang", restored.nameEn)
        assertEquals("範例科技", restored.company)
        assertEquals("EXAMPLE TECHNOLOGY", restored.companyEn)

        val oldJson = org.json.JSONObject()
            .put("name", "舊資料")
            .put("company", "舊公司")
        val oldRestored = CardCodec.fromJson(oldJson)
        assertEquals("舊資料", oldRestored.name)
        assertEquals("", oldRestored.nameEn)
        assertEquals("舊公司", oldRestored.company)
        assertEquals("", oldRestored.companyEn)
    }

    // ---- vCard out --------------------------------------------------------

    @Test
    fun `vcard escapes semicolons and commas in values`() {
        val out = CardCodec.toVCard(BusinessCard(name = "陳志明", notes = "很急;要回電,週一前"))
        assertTrue(out.contains("NOTE:很急\\;要回電\\,週一前"))
        assertTrue(out.startsWith("BEGIN:VCARD"))
        assertTrue(out.trim().endsWith("END:VCARD"))
    }

    @Test
    fun `vcard round trips English alternate fields`() {
        val source = BusinessCard(
            name = "王大明",
            nameEn = "David Wang",
            company = "範例科技",
            companyEn = "EXAMPLE TECHNOLOGY"
        )
        val parsed = VCardReader.parse(CardCodec.toVCard(source)).single().card
        assertEquals("王大明", parsed.name)
        assertEquals("David Wang", parsed.nameEn)
        assertEquals("範例科技", parsed.company)
        assertEquals("EXAMPLE TECHNOLOGY", parsed.companyEn)
    }

    // ---- vCard in ---------------------------------------------------------

    @Test
    fun `reads quoted-printable Chinese from a vCard 2_1 export`() {
        val vcf = buildString {
            append("BEGIN:VCARD\r\nVERSION:2.1\r\n")
            append("N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E9=99=B3=E5=BF=97=E6=98=8E;;;;\r\n")
            append("ORG;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E6=AA=B3=E5=82=91=E9=9B=BB=E5=AD=90\r\n")
            append("TEL;CELL:0912-345-678\r\n")
            append("TEL;WORK;FAX:03-553-5678\r\n")
            append("TEL;WORK;VOICE:03-553-1234\r\n")
            append("EMAIL;INTERNET:chen@example.com\r\n")
            append("END:VCARD\r\n")
        }
        val c = VCardReader.parse(vcf).single().card
        assertEquals("陳志明", c.name)
        assertEquals("檳傑電子", c.company)
        assertEquals("0912-345-678", c.mobile)
        assertEquals("03-553-5678", c.fax)
        assertEquals("03-553-1234", c.phone)
        assertEquals("chen@example.com", c.email)
    }

    @Test
    fun `rejoins folded lines`() {
        val vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Wang Da Ming\n" +
            "ORG:Acme Corp;Sales Division\nTITLE:Manager\n" +
            "ADR;TYPE=WORK:;;302新竹縣竹北市光明六路\n 2號12樓;;;;\n" +
            "URL:www.acme.com\nEND:VCARD\n"
        val c = VCardReader.parse(vcf).single().card
        assertTrue("got: ${c.address}", c.address.contains("光明六路") && c.address.contains("12樓"))
        assertEquals("Wang Da Ming", c.nameEn)
        assertEquals("Acme Corp", c.companyEn)
        assertEquals("Sales Division", c.department)
    }

    @Test
    fun `rejoins quoted-printable soft line breaks`() {
        val vcf = "BEGIN:VCARD\nVERSION:2.1\n" +
            "NOTE;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E9=95=B7=E9=95=B7=E7=9A=84=\n" +
            "=E5=82=99=E8=A8=BB\nEND:VCARD\n"
        assertEquals("長長的備註", VCardReader.parse(vcf).single().card.notes)
    }

    @Test
    fun `reads several cards from one file`() {
        val one = "BEGIN:VCARD\nVERSION:3.0\nFN:A\nEND:VCARD\n"
        val two = "BEGIN:VCARD\nVERSION:3.0\nFN:B\nEND:VCARD\n"
        assertEquals(2, VCardReader.parse(one + two).size)
    }

    @Test
    fun `survives a file that is not a vCard`() {
        assertTrue(VCardReader.parse("這只是一段普通文字\n沒有名片").isEmpty())
    }
}
