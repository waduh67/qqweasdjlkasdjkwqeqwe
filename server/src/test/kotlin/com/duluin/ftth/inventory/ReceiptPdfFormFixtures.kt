package com.duluin.ftth.inventory

internal object ReceiptPdfFormFixtures {
    data class Form(val name: String = "Fm", val content: String, val resources: String? = "<< >>", val matrix: String? = null)

    fun document(page: String, forms: List<Form>, extras: String = ""): ByteArray {
        val references = forms.mapIndexed { index, form -> "/${form.name} ${index + 5} 0 R" }.joinToString(" ")
        val resources = "<< /XObject << $references >>${if (extras.isEmpty()) "" else " $extras"} >>"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources $resources /Contents 4 0 R >>",
            "<< /Length ${page.length} >>\nstream\n$page\nendstream") + forms.map { form ->
            "<< /Length ${form.content.length} /Type /XObject /Subtype /Form /FormType 1 /BBox [0 0 20 20]" +
                (form.resources?.let { " /Resources $it" } ?: "") + (form.matrix?.let { " /Matrix $it" } ?: "") +
                " >>\nstream\n${form.content}\nendstream"
        }
        return ReceiptPdfSyntaxFixtures.serialize(objects)
    }

    fun exact(reset: Boolean = false) = document("/DeviceRGB cs /Fm Do", listOf(Form(content =
        (if (reset) "/DeviceRGB cs " else "") + "1 0 0 sc 0 0 10 10 re f")))

    fun positive(kind: String): ByteArray = when (kind) {
        "EXACT" -> exact()
        "RESET" -> exact(true)
        "GRAY" -> document("/DeviceGray cs /Fm Do", listOf(Form(content = ".5 sc 0 0 10 10 re f")))
        "CMYK" -> document("/DeviceCMYK cs /Fm Do", listOf(Form(content = "0 1 1 0 sc 0 0 10 10 re f")))
        "NAMED" -> document("/CS1 cs /Fm Do", listOf(Form(content = "1 0 0 sc 0 0 10 10 re f")), "/ColorSpace << /CS1 /DeviceRGB >>")
        "STROKE" -> document("/DeviceRGB CS /Fm Do", listOf(Form(content = "1 0 0 SC 0 0 10 10 re S")))
        "ISOLATED" -> document("/DeviceRGB cs /Fm Do 1 0 0 sc", listOf(Form(content = "/DeviceGray cs .5 sc 0 0 10 10 re f")))
        "SHARED" -> document("/DeviceGray cs /Fm Do .5 sc /DeviceRGB cs /Fm Do 1 0 0 sc /DeviceCMYK cs /Fm Do 0 1 1 0 sc /CS1 cs /Fm Do 0 1 0 sc",
            listOf(Form(content = "/DeviceGray cs .5 sc 0 0 10 10 re f")), "/ColorSpace << /CS1 /DeviceRGB >>")
        "NESTED" -> document("/DeviceRGB cs /Fm Do 1 0 0 sc", listOf(
            Form(content = "1 0 0 sc /Child Do 0 1 0 sc", resources = "<< /XObject << /Child 6 0 R >> >>"),
            Form("Child", "0 0 1 sc /DeviceCMYK cs 0 1 1 0 sc 0 0 10 10 re f")))
        "SIBLINGS" -> document("/DeviceRGB cs /Fm Do /Child Do", listOf(Form(content = "/DeviceGray cs .5 sc"), Form("Child", "1 0 0 sc")))
        "DETACHED" -> document("q Q", listOf(Form(content = "1 0 0 sc 0 0 10 10 re f")))
        "Q_RESTORE" -> document("q /DeviceRGB cs /Fm Do 1 0 0 sc Q .5 sc", listOf(Form(content = "q /DeviceCMYK cs 0 1 1 0 sc Q 0 0 1 sc")))
        "MATRIX" -> document("2 0 0 2 10 20 cm /DeviceRGB cs /Fm Do 1 0 0 sc", listOf(Form(content = "3 0 0 3 0 0 cm 1 0 0 sc 0 0 10 10 re W n", matrix = "[1 0 0 1 3 4]")))
        else -> error("Unknown form fixture")
    }

    fun negative(kind: String): ByteArray = when (kind) {
        "GRAY_MISMATCH" -> document("/DeviceGray cs /Fm Do", listOf(Form(content = "1 0 0 sc")))
        "RGB_MISMATCH" -> document("/DeviceRGB cs /Fm Do", listOf(Form(content = ".5 sc")))
        "CMYK_MISMATCH" -> document("/DeviceCMYK cs /Fm Do", listOf(Form(content = "1 0 0 sc")))
        "NAMED_MISMATCH" -> document("/CS1 cs /Fm Do", listOf(Form(content = ".5 sc")), "/ColorSpace << /CS1 /DeviceRGB >>")
        "SECOND_INVOCATION" -> document("/DeviceGray cs /Fm Do /DeviceRGB cs /Fm Do", listOf(Form(content = ".5 sc")))
        "CYCLE" -> document("/Fm Do", listOf(Form(content = "/Fm Do", resources = "<< /XObject << /Fm 5 0 R >> >>")))
        "NESTED_CYCLE" -> document("/Fm Do", listOf(Form(content = "/Child Do", resources = "<< /XObject << /Child 6 0 R >> >>"),
            Form("Child", "/Fm Do", "<< /XObject << /Fm 5 0 R >> >>")))
        "UNDERFLOW" -> document("q /Fm Do Q", listOf(Form(content = "Q")))
        "UNCLOSED" -> document("/Fm Do", listOf(Form(content = "q")))
        "BUDGET" -> document("/Fm Do\n".repeat(30000), listOf(Form(content = "q Q")))
        "DEPTH" -> document("/F0 Do", (0..64).map { index -> Form("F$index", if (index == 64) "q Q" else "/F${index + 1} Do",
            if (index == 64) "<< >>" else "<< /XObject << /F${index + 1} ${index + 6} 0 R >> >>") })
        "MATRIX" -> document("/Fm Do", listOf(Form(content = "q Q", matrix = "[1 0 0]")))
        "DETACHED_CONFLICT" -> document("q Q", listOf(Form(content = ".5 sc 1 0 0 sc")))
        "DETACHED_CHILD_CONFLICT" -> document("q Q", listOf(Form(content = ".5 sc /Child Do", resources = "<< /XObject << /Child 6 0 R >> >>"), Form("Child", "1 0 0 sc")))
        else -> error("Unknown invalid form fixture")
    }
}
