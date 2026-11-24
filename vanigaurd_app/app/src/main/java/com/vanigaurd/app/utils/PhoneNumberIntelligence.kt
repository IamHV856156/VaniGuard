package com.vanigaurd.app.utils

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

enum class NumberValidationStatus {
    VALID,
    POSSIBLE_BUT_INVALID,
    INVALID,
    PARSE_ERROR
}

data class PhoneNumberMetadata(
    val originalNumber: String,
    val normalizedNumber: String?,
    val nationalFormat: String?,
    val internationalFormat: String?,
    val countryCode: Int?,
    val regionCode: String?,
    val numberType: String?,
    val validationStatus: NumberValidationStatus,
    val explanation: String
)

object PhoneNumberIntelligence {

    private val phoneUtil = PhoneNumberUtil.getInstance()

    /**
     * Parses and validates a phone number using locally bundled
     * libphonenumber metadata.
     *
     * defaultRegion is used only when the number does not include
     * an international country calling code.
     */
    fun analyze(
        input: String,
        defaultRegion: String = "IN"
    ): PhoneNumberMetadata {

        val cleanedInput = input.trim()

        if (cleanedInput.isBlank()) {
            return invalidResult(
                input,
                NumberValidationStatus.PARSE_ERROR,
                "Phone number is empty."
            )
        }

        return try {
            val parsedNumber = phoneUtil.parse(
                cleanedInput,
                defaultRegion.uppercase()
            )

            val isPossible = phoneUtil.isPossibleNumber(parsedNumber)
            val isValid = phoneUtil.isValidNumber(parsedNumber)

            val status = when {
                isValid -> NumberValidationStatus.VALID
                isPossible -> NumberValidationStatus.POSSIBLE_BUT_INVALID
                else -> NumberValidationStatus.INVALID
            }

            val numberType = phoneUtil.getNumberType(parsedNumber)

            PhoneNumberMetadata(
                originalNumber = input,
                normalizedNumber = if (isValid) {
                    phoneUtil.format(
                        parsedNumber,
                        PhoneNumberUtil.PhoneNumberFormat.E164
                    )
                } else {
                    null
                },
                nationalFormat = phoneUtil.format(
                    parsedNumber,
                    PhoneNumberUtil.PhoneNumberFormat.NATIONAL
                ),
                internationalFormat = phoneUtil.format(
                    parsedNumber,
                    PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
                ),
                countryCode = parsedNumber.countryCode,
                regionCode = phoneUtil.getRegionCodeForNumber(parsedNumber),
                numberType = numberType.name,
                validationStatus = status,
                explanation = when (status) {
                    NumberValidationStatus.VALID ->
                        "Number matches the numbering plan."

                    NumberValidationStatus.POSSIBLE_BUT_INVALID ->
                        "Number length is possible, but it does not match a valid numbering pattern."

                    NumberValidationStatus.INVALID ->
                        "Number does not match a valid numbering pattern."

                    NumberValidationStatus.PARSE_ERROR ->
                        "Number could not be parsed."
                }
            )

        } catch (exception: NumberParseException) {
            invalidResult(
                input,
                NumberValidationStatus.PARSE_ERROR,
                exception.message ?: "Unable to parse phone number."
            )
        } catch (exception: IllegalArgumentException) {
            invalidResult(
                input,
                NumberValidationStatus.PARSE_ERROR,
                exception.message ?: "Invalid region code."
            )
        }
    }

    private fun invalidResult(
        input: String,
        status: NumberValidationStatus,
        message: String
    ): PhoneNumberMetadata {

        return PhoneNumberMetadata(
            originalNumber = input,
            normalizedNumber = null,
            nationalFormat = null,
            internationalFormat = null,
            countryCode = null,
            regionCode = null,
            numberType = null,
            validationStatus = status,
            explanation = message
        )
    }
}
