package de.ams.expandablelazylist

import android.graphics.pdf.content.PdfPageGotoLinkContent
import android.graphics.pdf.content.PdfPageImageContent
import android.graphics.pdf.content.PdfPageLinkContent
import android.graphics.pdf.content.PdfPageTextContent
import androidx.compose.ui.graphics.ImageBitmap

data class PdfContent(
    val image: ImageBitmap,
    val links: List<PdfPageLinkContent> = emptyList(),
    val texts: List<PdfPageTextContent> = emptyList(),
    val images: List<PdfPageImageContent> = emptyList(),
    val goToLinks: List<PdfPageGotoLinkContent> = emptyList(),
)
