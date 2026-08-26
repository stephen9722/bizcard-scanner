package tw.pentamaster.bizcard.util

import tw.pentamaster.bizcard.data.BusinessCard

data class AccountGroup(
    val key: String,
    val displayName: String,
    val cards: List<BusinessCard>
)

data class OrgDepartment(
    val name: String,
    val cards: List<BusinessCard>
)

enum class OrgLevel(val order: Int, val label: String) {
    EXECUTIVE(0, "決策層"),
    SENIOR_LEADER(1, "高階主管"),
    MANAGER(2, "中階主管"),
    TEAM_LEAD(3, "基層主管"),
    PROFESSIONAL(4, "專業人員"),
    OTHER(5, "其他 / 未判定")
}

/** Offline organization inference for Key Account views. */
object OrgAnalyzer {

    fun accounts(cards: List<BusinessCard>): List<AccountGroup> = cards
        .filter { it.displayCompany.isNotBlank() }
        .groupBy { companyKey(it.displayCompany) }
        .filterKeys { it.isNotBlank() }
        .map { (key, group) ->
            AccountGroup(
                key = key,
                displayName = preferredLabel(group.map { it.displayCompany }),
                cards = group.sortedWith(
                    compareBy<BusinessCard> { levelFor(it.title).order }
                        .thenBy { departmentKey(it.department) }
                        .thenBy { it.displayName }
                )
            )
        }
        .sortedWith(compareByDescending<AccountGroup> { it.cards.size }.thenBy { it.displayName })

    fun departments(cards: List<BusinessCard>): List<OrgDepartment> = cards
        .groupBy { departmentKey(it.department) }
        .map { (key, group) ->
            OrgDepartment(
                name = if (key == NO_DEPARTMENT) "未標示部門" else preferredLabel(group.map { it.department }),
                cards = group.sortedWith(compareBy<BusinessCard> { levelFor(it.title).order }.thenBy { it.displayName })
            )
        }
        .sortedWith(
            compareBy<OrgDepartment> { it.name == "未標示部門" }
                .thenBy { it.name }
        )

    fun companyKey(input: String): String {
        var s = input.trim().lowercase()
        s = s.replace(Regex("(股份有限公司|有限公司|公司)\\s*$"), "")
        s = s.replace(
            Regex("(?i)\\b(company|co\\.?|incorporated|inc\\.?|corporation|corp\\.?|limited|ltd\\.?)\\b"),
            ""
        )
        return s.filter { it.isLetterOrDigit() }
    }

    fun departmentKey(input: String): String {
        if (input.isBlank()) return NO_DEPARTMENT
        return input.trim().lowercase().filter { it.isLetterOrDigit() }
    }

    fun levelFor(title: String): OrgLevel {
        if (title.isBlank()) return OrgLevel.OTHER
        val t = title.trim().lowercase()

        return when {
            t.containsAny(
                "副總", "協理", "處長", "廠長", "事業部主管", "營運長", "技術長", "財務長",
                "vice president", "svp", "evp", "avp", "director", "head of", "chief operating",
                "chief technology", "chief financial", "plant manager"
            ) -> OrgLevel.SENIOR_LEADER

            t.containsAny(
                "董事長", "執行長", "總經理", "總裁", "chairman", "chief executive",
                "ceo", "president", "general manager", "managing director"
            ) -> OrgLevel.EXECUTIVE

            t.containsAny(
                "經理", "副理", "課長", "部長", "manager", "assistant manager", "section manager"
            ) -> OrgLevel.MANAGER

            t.containsAny(
                "主任", "組長", "副課長", "領班", "supervisor", "team lead", "leader", "foreman"
            ) -> OrgLevel.TEAM_LEAD

            t.containsAny(
                "工程師", "專員", "技師", "技術員", "業務", "助理", "顧問",
                "engineer", "specialist", "technician", "sales", "account executive", "consultant",
                "assistant", "coordinator"
            ) -> OrgLevel.PROFESSIONAL

            else -> OrgLevel.OTHER
        }
    }

    private fun preferredLabel(values: List<String>): String = values
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
        .firstOrNull()
        ?.key
        .orEmpty()

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

    private const val NO_DEPARTMENT = "__none__"
}
