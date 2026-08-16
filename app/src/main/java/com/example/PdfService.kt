package com.example

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object PdfService {

    /**
     * Parses and returns the raw text content from a PDF file.
     */
    fun getPdfRawText(file: File, password: String = ""): String {
        val document = if (password.isNotEmpty()) {
            PDDocument.load(file, password)
        } else {
            PDDocument.load(file)
        }

        val stripper = PDFTextStripper()
        val text = stripper.getText(document)
        document.close()
        return text
    }
}
