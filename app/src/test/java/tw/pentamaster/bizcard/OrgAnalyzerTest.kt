package tw.pentamaster.bizcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.pentamaster.bizcard.data.BusinessCard
import tw.pentamaster.bizcard.util.OrgAnalyzer
import tw.pentamaster.bizcard.util.OrgLevel

class OrgAnalyzerTest {

    @Test
    fun groupsCommonCompanyLegalSuffixVariants() {
        val cards = listOf(
            BusinessCard(name = "A", company = "Acme Co., Ltd."),
            BusinessCard(name = "B", company = "Acme Ltd"),
            BusinessCard(name = "C", company = "Other Inc.")
        )

        val accounts = OrgAnalyzer.accounts(cards)
        assertEquals(2, accounts.size)
        assertEquals(2, accounts.first { it.key == "acme" }.cards.size)
    }

    @Test
    fun groupsChineseCompanySuffixVariants() {
        val a = OrgAnalyzer.companyKey("測試科技股份有限公司")
        val b = OrgAnalyzer.companyKey("測試科技有限公司")
        assertEquals(a, b)
        assertEquals("測試科技", a)
    }

    @Test
    fun ranksCommonChineseAndEnglishTitles() {
        assertEquals(OrgLevel.EXECUTIVE, OrgAnalyzer.levelFor("總經理"))
        assertEquals(OrgLevel.SENIOR_LEADER, OrgAnalyzer.levelFor("Vice President"))
        assertEquals(OrgLevel.MANAGER, OrgAnalyzer.levelFor("設備經理"))
        assertEquals(OrgLevel.TEAM_LEAD, OrgAnalyzer.levelFor("課務主任"))
        assertEquals(OrgLevel.PROFESSIONAL, OrgAnalyzer.levelFor("Senior Engineer"))
    }

    @Test
    fun keepsUnknownTitlesExplicitlyUncertain() {
        assertEquals(OrgLevel.OTHER, OrgAnalyzer.levelFor("專案窗口"))
    }

    @Test
    fun departmentGroupingKeepsUnspecifiedPeopleVisible() {
        val departments = OrgAnalyzer.departments(
            listOf(
                BusinessCard(name = "A", department = "設備處"),
                BusinessCard(name = "B", department = "設備處"),
                BusinessCard(name = "C", department = "")
            )
        )
        assertTrue(departments.any { it.name == "設備處" && it.cards.size == 2 })
        assertTrue(departments.any { it.name == "未標示部門" && it.cards.size == 1 })
    }
}
