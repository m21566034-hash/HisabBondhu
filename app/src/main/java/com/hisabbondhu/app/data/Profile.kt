package com.hisabbondhu.app.data

/** Business profile and preferences, read from the synced settings table. */
data class Profile(
    val name: String = "", val owner: String = "", val phone: String = "", val address: String = "",
    val type: String = "", val logo: String = "", val openingCash: Long = 0,
    val methods: List<String> = DEFAULT_METHODS, val units: List<String> = DEFAULT_UNITS,
    val defMethod: String = "ক্যাশ", val accent: Int = 0xFF0B6E4F.toInt(), val footer: String = "",
    val png: Boolean = false, val allowNegative: Boolean = false
) {
    val complete get() = name.isNotBlank()
    companion object {
        val DEFAULT_METHODS = listOf("ক্যাশ", "বিকাশ", "নগদ (মোবাইল ব্যাংকিং)", "রকেট", "ব্যাংক", "অন্যান্য")
        val DEFAULT_UNITS = listOf("পিস", "কার্টন", "ডজন", "কেজি", "গ্রাম", "লিটার", "বস্তা", "প্যাকেট")
        val CATEGORIES = listOf("দোকান ভাড়া", "বিদ্যুৎ বিল", "কর্মচারীর বেতন", "পরিবহন", "প্যাকেজিং", "মেরামত", "ইন্টারনেট", "অন্যান্য")
        fun from(m: Map<String, String>) = Profile(
            m["biz.name"].orEmpty(), m["biz.owner"].orEmpty(), m["biz.phone"].orEmpty(), m["biz.address"].orEmpty(),
            m["biz.type"].orEmpty(), m["biz.logo"].orEmpty(), m["biz.openingCash"]?.toLongOrNull() ?: 0,
            m["methods"]?.split("|")?.filter { it.isNotBlank() }?.ifEmpty { null } ?: DEFAULT_METHODS,
            m["units"]?.split("|")?.filter { it.isNotBlank() }?.ifEmpty { null } ?: DEFAULT_UNITS,
            m["defMethod"] ?: "ক্যাশ", m["accent"]?.toIntOrNull() ?: 0xFF0B6E4F.toInt(), m["inv.footer"].orEmpty(),
            m["inv.png"] == "1", m["allowNegative"] == "1")
    }
}
