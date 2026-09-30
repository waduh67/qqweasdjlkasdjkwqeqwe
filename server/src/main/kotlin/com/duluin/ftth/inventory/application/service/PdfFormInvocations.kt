package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDResources
import java.util.Collections
import java.util.IdentityHashMap

internal data class PdfContentContext(val resources: PDResources?, val initial: PdfGraphicsSyntax, val kind: PdfContentKind,
    val forms: PdfFormInvocations, val restrictColour: Boolean = false)

internal class PdfFormInvocations(private val budget: PdfSyntaxBudget) {
    private val active = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())
    private val invoked = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())

    fun invoke(name: COSName, caller: PdfContentContext) {
        val stream = requireNotNull(caller.resources).cosObject.getCOSDictionary(COSName.XOBJECT)?.getDictionaryObject(name) as? COSStream
            ?: error("XObject stream required")
        if (stream.getNameAsString(COSName.SUBTYPE) == "Image") return
        require(stream.getNameAsString(COSName.SUBTYPE) == "Form")
        val resources = stream.getCOSDictionary(COSName.RESOURCES)?.let(::PDResources) ?: caller.resources
        validate(stream, caller.copy(resources = resources, initial = caller.initial.enterForm(stream), kind = PdfContentKind.FORM))
        invoked.add(stream)
    }

    fun wasInvoked(stream: COSStream): Boolean = stream in invoked

    fun detached(stream: COSStream, resources: PDResources?, kind: PdfContentKind) {
        val initial = PdfGraphicsSyntax.detached().let { if (stream.getNameAsString(COSName.SUBTYPE) == "Form") it.enterForm(stream) else it }
        validate(stream, PdfContentContext(resources, initial, kind, this))
    }

    private fun validate(stream: COSStream, context: PdfContentContext) {
        require(active.size < 64 && active.add(stream)) { "Recursive Form invocation" }
        try {
            val content = stream.createInputStream().use { it.readNBytes(16777217) }
            budget.decoded(content.size)
            PdfContentSyntax.validate(content, context, budget)
        } finally { active.remove(stream) }
    }
}
