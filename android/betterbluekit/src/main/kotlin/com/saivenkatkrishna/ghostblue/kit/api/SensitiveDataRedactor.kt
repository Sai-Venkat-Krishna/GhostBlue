package com.saivenkatkrishna.ghostblue.kit.api

/** Removes passwords, tokens, locations, emails and similar from logged requests and responses. */
object SensitiveDataRedactor {
    private val tokenKeys = listOf(
        "access_token", "refresh_token", "accessToken", "refreshToken",
        "serializedAuthToken", "rememberMeToken", "Accesstoken", "Pauth",
        "TransactionId", "Cookie", "__cf_bm", "otpKey",
    ).joinToString("|")

    private val emailKeys = listOf(
        "username", "email", "userId", "loginId", "notificationEmail",
    ).joinToString("|")

    private val phoneKeys = listOf(
        "phone", "phoneNumber", "mobileNumber", "cellPhone",
        "telematicsPhoneNumber", "number",
    ).joinToString("|")

    private val idKeys = listOf(
        "accountId", "account_id", "userId", "user_id",
        "memberId", "member_id", "idmId", "nadid",
        "billingAccountNumber", "enrollmentId", "enrollmentCode",
        "deviceKey", "deviceid", "clientuuid",
    ).joinToString("|")

    // Same patterns and replacements, in the same order, as the Swift redactor.
    private val rules: List<Pair<Regex, String>> = listOf(
        // Passwords and PINs
        """"(password|pin|PIN)"\s*:\s*"[^"]*"""" to "\"\$1\":\"[REDACTED]\"",
        // Bearer tokens
        """Bearer\s+[A-Za-z0-9._-]+""" to "Bearer [REDACTED]",
        // Token/secret fields (handles escaped quotes)
        """"($tokenKeys)"\s*:\s*"(?:[^"\\]|\\.)*"""" to "\"\$1\":\"[REDACTED]\"",
        // Latitude/longitude coordinates
        """"(latitude|longitude|lat|lng|lon)"\s*:\s*[-+]?\d+\.?\d*""" to "\"\$1\":\"[REDACTED]\"",
        // Coordinate pairs in arrays or objects
        """[-+]?\d{1,3}\.\d{3,10}\s*,\s*[-+]?\d{1,3}\.\d{3,10}""" to "[LOCATION_REDACTED]",
        // Names
        """"(firstName|lastName)"\s*:\s*"[^"]*"""" to "\"\$1\":\"[REDACTED]\"",
        // Email addresses in JSON fields
        """"($emailKeys)"\s*:\s*"([^"@])[^"@]*@[^".]*(\.[^"]+)"""" to "\"\$1\":\"\$3***@***\$4\"",
        // Emails embedded in URL paths
        """(/)[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""" to "\$1[EMAIL_REDACTED]",
        // Phone numbers
        """"($phoneKeys)"\s*:\s*"[^"]*"""" to "\"\$1\":\"[REDACTED]\"",
        // Account/user/device IDs
        """"($idKeys)"\s*:\s*"[^"]*"""" to "\"\$1\":\"[REDACTED]\"",
        // Physical address fields
        """"(street|postalCode)"\s*:\s*"[^"]*"""" to "\"\$1\":\"[REDACTED]\"",
        // VIN numbers (keep first 3 and last 4)
        """"(vin|VIN)"\s*:\s*"([A-HJ-NPR-Z0-9]{3})[A-HJ-NPR-Z0-9]{10}([A-HJ-NPR-Z0-9]{4})"""" to
            "\"\$1\":\"\$2**********\$3\"",
        // Registration IDs
        """"(regId|regID|regid)"\s*:\s*"[^"]*"""" to "\"\$1\":\"[REDACTED]\"",
    ).map { (pattern, replacement) -> Regex(pattern) to replacement }

    fun redact(text: String?): String? {
        if (text == null) return null
        return rules.fold(text) { redacted, (regex, replacement) ->
            regex.replace(redacted) { match -> expandTemplate(replacement, match) }
        }
    }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> {
        val sensitiveKeys = setOf(
            "cookie", "set-cookie", "__cf_bm", "transactionid",
            "password", "pin", "bluelinkservicepin",
            "clientsecret", "client_secret", "secretkey",
        )
        return headers.mapValues { (key, value) ->
            val lower = key.lowercase()
            when {
                lower == "authorization" -> "Bearer [REDACTED]"
                lower in sensitiveKeys -> "[REDACTED]"
                "auth" in lower || "token" in lower -> "[REDACTED]"
                else -> value
            }
        }
    }

    /**
     * Expands `$n` like NSRegularExpression templates: a reference to a group
     * the pattern doesn't have becomes empty text instead of an error.
     */
    private fun expandTemplate(template: String, match: MatchResult): String = buildString {
        var index = 0
        while (index < template.length) {
            val char = template[index]
            if (char == '$' && index + 1 < template.length && template[index + 1].isDigit()) {
                val group = template[index + 1].digitToInt()
                append(match.groups.getOrNull(group)?.value ?: "")
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }

    private fun MatchGroupCollection.getOrNull(index: Int): MatchGroup? =
        if (index < size) get(index) else null
}
