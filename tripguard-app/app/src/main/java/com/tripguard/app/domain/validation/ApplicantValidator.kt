package com.tripguard.app.domain.validation

import com.tripguard.app.domain.model.ApplicantDetails

/** Field-level validation for applicant details, shown inline in the form. */
object ApplicantValidator {

    private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val phoneRegex = Regex("^[+]?[0-9 ()-]{7,20}$")

    fun validate(details: ApplicantDetails): Map<String, String> {
        val errors = linkedMapOf<String, String>()

        val name = details.fullName.trim()
        when {
            name.isEmpty() -> errors["fullName"] = "Enter the full name shown on the passport."
            name.length < 3 -> errors["fullName"] = "Name looks too short."
            !name.all { it.isLetter() || it == ' ' || it == '.' || it == '-' } ->
                errors["fullName"] = "Use letters, spaces, hyphens or full stops only."
            name.count { it == ' ' } < 1 -> errors["fullName"] = "Enter the given name and surname."
        }

        val email = details.contactEmail.trim()
        if (email.isNotEmpty() && !emailRegex.matches(email)) {
            errors["contactEmail"] = "Enter a valid email address."
        }

        val phone = details.contactPhone.trim()
        if (phone.isNotEmpty() && !phoneRegex.matches(phone)) {
            errors["contactPhone"] = "Enter a valid phone number."
        }

        return errors
    }
}
