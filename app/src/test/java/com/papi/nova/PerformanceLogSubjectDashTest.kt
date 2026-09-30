package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The performance log's email subject used a spaced hyphen as a dash. English and German lost it
 * in round 1; Russian and both Chinese translations kept it (audit C29).
 */
class PerformanceLogSubjectDashTest {
    @Test
    fun noTranslationOfTheSubjectUsesADash() {
        val subjects = File("src/main/res").listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
            .mapNotNull { dir -> File(dir, "strings.xml").takeIf { it.isFile } }
            .mapNotNull { file ->
                Regex("<string name=\"email_subject\">(.*?)</string>").find(file.readText())?.let { file.parentFile.name to it.groupValues[1] }
            }
        assertTrue("the subject is in several languages", subjects.size >= 5)
        for ((locale, subject) in subjects) {
            assertFalse("$locale: $subject", subject.contains(" - ") || subject.contains('\u2013') || subject.contains('\u2014'))
        }
    }
}
