package com.jobradar.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResumeParserTest {
    /** Set RESUME_FILE to a .docx to print what the parser reads from it. */
    @Test
    fun parsesResume() {
        val path = System.getenv("RESUME_FILE") ?: return
        val p = ResumeParser.parse(ResumeParser.docxText(File(path).inputStream()))
        println("NAME=${p.name}\nHEADLINE=${p.headline}\nEMAIL=${p.email} PHONE=${p.phone} CITY=${p.city}\nYEARS=${p.years}")
        println("TITLES=${p.targetTitles}\nKEY=${p.keySkills}\nOTHER=${p.otherSkills}\nDOMAINS=${p.domains}\nCOUNTRY=${p.preferredCountries}")
        println("NOTICE=${p.noticePeriod} RELOC=${p.relocation}")
        p.experience.forEach { println("EXP: ${it.role} | ${it.company} | ${it.period} | ${it.location} | ${it.points.lines().size} points") }
        println("EDU=${p.education}\nCERT=${p.certifications}\nLANG=${p.languages}\nSUMMARY=${p.summary.take(120)}")
        assertTrue(p.email.isNotEmpty())
    }
}
