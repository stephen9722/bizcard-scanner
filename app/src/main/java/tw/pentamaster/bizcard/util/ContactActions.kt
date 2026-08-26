package tw.pentamaster.bizcard.util

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import tw.pentamaster.bizcard.data.BusinessCard

object ContactActions {
    fun insert(context: Context, card: BusinessCard): Boolean {
        val primaryPhone = card.mobile.ifBlank { card.phone }
        val secondaryPhone = if (card.mobile.isNotBlank() && card.phone.isNotBlank()) card.phone else ""
        val primaryName = card.name.ifBlank { card.nameEn }
        val primaryCompany = card.company.ifBlank { card.companyEn }

        val notes = buildList {
            if (card.nameEn.isNotBlank() && card.nameEn != primaryName) add("英文姓名：${card.nameEn}")
            if (card.companyEn.isNotBlank() && card.companyEn != primaryCompany) add("英文公司：${card.companyEn}")
            if (card.department.isNotBlank()) add("部門：${card.department}")
            if (card.website.isNotBlank()) add("網站：${card.website}")
            if (card.fax.isNotBlank()) add("傳真：${card.fax}")
            if (card.notes.isNotBlank()) add(card.notes)
        }.joinToString("\n")

        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            if (primaryName.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NAME, primaryName)
            if (primaryCompany.isNotBlank()) putExtra(ContactsContract.Intents.Insert.COMPANY, primaryCompany)
            if (card.title.isNotBlank()) putExtra(ContactsContract.Intents.Insert.JOB_TITLE, card.title)
            if (primaryPhone.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.PHONE, primaryPhone)
                putExtra(
                    ContactsContract.Intents.Insert.PHONE_TYPE,
                    if (card.mobile.isNotBlank()) ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    else ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                )
            }
            if (secondaryPhone.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, secondaryPhone)
                putExtra(
                    ContactsContract.Intents.Insert.SECONDARY_PHONE_TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                )
            }
            if (card.email.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.EMAIL, card.email)
                putExtra(
                    ContactsContract.Intents.Insert.EMAIL_TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_WORK
                )
            }
            if (card.address.isNotBlank()) putExtra(ContactsContract.Intents.Insert.POSTAL, card.address)
            if (notes.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NOTES, notes)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
