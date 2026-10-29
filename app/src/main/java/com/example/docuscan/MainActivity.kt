package com.example.docuscan

import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private data class PdfFile(
        val name: String,
        val uri: Uri,
        val size: Long,
        val date: Long
    )

    private data class EditablePage(
        val uri: Uri,
        var rotation: Int = 0,
        var enhancement: Int = 0
    )

    private enum class PageSize(val width: Int, val height: Int) {
        A4(595, 842),
        LETTER(612, 792)
    }

    private val prefs by lazy {
        getSharedPreferences("docuscan_settings", MODE_PRIVATE)
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            editPages(uris, "Gallery_Document")
        } else {
            message("No images selected.")
        }
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scan = GmsDocumentScanningResult
                .fromActivityResultIntent(result.data)

            val pageUris = scan?.pages?.map { it.imageUri } ?: emptyList()

            if (pageUris.isNotEmpty()) {
                editPages(pageUris, "Scanned_Document")
            } else {
                val pdfUri = scan?.pdf?.uri
                if (pdfUri != null) {
                    askPdfName("Scanned_Document") { name ->
                        saveScannedPdf(pdfUri, name)
                    }
                } else {
                    message("No scanned pages were returned.")
                }
            }
        } else {
            message("Scanning cancelled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.getBoolean("dark_mode", false)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            startScanner()
        }

        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        findViewById<Button>(R.id.btnDocuments).setOnClickListener {
            showDocuments()
        }

        updateDarkButton()

        findViewById<Button>(R.id.btnDarkMode).setOnClickListener {
            val enabled = !prefs.getBoolean("dark_mode", false)
            prefs.edit().putBoolean("dark_mode", enabled).apply()

            AppCompatDelegate.setDefaultNightMode(
                if (enabled) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            )
        }
    }

    private fun updateDarkButton() {
        findViewById<Button>(R.id.btnDarkMode).text =
            if (prefs.getBoolean("dark_mode", false)) {
                "☀ Light mode"
            } else {
                "🌙 Dark mode"
            }
    }

    // ---------------------------------------------------------
    // DOCUMENT SCANNER
    // ---------------------------------------------------------

    private fun startScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(
                GmsDocumentScannerOptions.SCANNER_MODE_FULL
            )
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(
                    IntentSenderRequest.Builder(sender).build()
                )
            }
            .addOnFailureListener {
                message("Scanner error: ${it.message}")
            }
    }

    // ---------------------------------------------------------
    // GALLERY / PAGE EDITOR
    // ---------------------------------------------------------

    private fun editPages(
        originalUris: List<Uri>,
        defaultName: String
    ) {
        val pages = originalUris.map { EditablePage(it) }.toMutableList()

        if (pages.isEmpty()) {
            message("No pages selected.")
            return
        }

        val pageSizeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("A4", "Letter")
            )
        }

        val qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("High quality", "Balanced", "Small file")
            )
        }

        val enhancementSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Original colour",
                    "Grayscale",
                    "Black & white",
                    "Enhanced contrast"
                )
            )
        }

        val imagePreview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Selected page preview"
        }

        val previewContainer = FrameLayout(this).apply {
            addView(
                imagePreview,
                FrameLayout.LayoutParams(-1, -1)
            )
        }

        val pageLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(4, 8, 4, 8)
        }

        fun makeButton(text: String) = Button(this).apply {
            this.text = text
            isAllCaps = false
        }

        val previousButton = makeButton("Previous")
        val nextButton = makeButton("Next")
        val rotateLeftButton = makeButton("↶ Rotate left")
        val rotateRightButton = makeButton("↷ Rotate right")
        val applyEnhancementButton = makeButton("Apply enhancement")
        val upButton = makeButton("Move up")
        val downButton = makeButton("Move down")
        val removeButton = makeButton("Remove page")

        fun horizontalRow(vararg views: View): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                views.forEach { view ->
                    addView(
                        view,
                        LinearLayout.LayoutParams(0, -2, 1f).apply {
                            marginEnd = 4
                        }
                    )
                }
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            addView(pageLabel)
            addView(
                previewContainer,
                LinearLayout.LayoutParams(-1, 300)
            )
            addView(horizontalRow(previousButton, nextButton))
            addView(horizontalRow(rotateLeftButton, rotateRightButton))
            addView(label("Image enhancement"))
            addView(enhancementSpinner)
            addView(applyEnhancementButton)
            addView(horizontalRow(upButton, downButton))
            addView(removeButton)
            addView(label("PDF page size"))
            addView(pageSizeSpinner)
            addView(label("Image compression"))
            addView(qualitySpinner)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Review and edit pages")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Save PDF", null)
            .create()

        var current = 0
        var displayedPreview: Bitmap? = null

        fun refreshPreview() {
            if (pages.isEmpty()) return

            current = current.coerceIn(0, pages.lastIndex)
            val page = pages[current]

            pageLabel.text =
                "Page ${current + 1} of ${pages.size}\nRotation: ${page.rotation}°"

            enhancementSpinner.setSelection(page.enhancement)
            previousButton.isEnabled = current > 0
            nextButton.isEnabled = current < pages.lastIndex
            upButton.isEnabled = current > 0
            downButton.isEnabled = current < pages.lastIndex
            removeButton.isEnabled = pages.size > 1

            var source: Bitmap? = null
            var preview: Bitmap? = null

            try {
                source = contentResolver.openInputStream(page.uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: throw Exception("Unable to read image.")

                preview = editBitmap(source, page)

                // Detach the old bitmap from the ImageView before recycling it.
                imagePreview.setImageDrawable(null)
                displayedPreview?.let { old ->
                    if (!old.isRecycled && old !== source && old !== preview) {
                        old.recycle()
                    }
                }

                displayedPreview = preview
                imagePreview.setImageBitmap(preview)
                preview = null // ImageView now owns the preview bitmap.
            } catch (e: Exception) {
                imagePreview.setImageDrawable(null)
                displayedPreview?.let {
                    if (!it.isRecycled) it.recycle()
                }
                displayedPreview = null
                message("Could not preview page: ${e.message}")
            } finally {
                preview?.let {
                    if (!it.isRecycled && it !== source) it.recycle()
                }
                source?.let {
                    if (!it.isRecycled) it.recycle()
                }
            }
        }

        previousButton.setOnClickListener {
            if (current > 0) {
                current--
                refreshPreview()
            }
        }

        nextButton.setOnClickListener {
            if (current < pages.lastIndex) {
                current++
                refreshPreview()
            }
        }

        rotateLeftButton.setOnClickListener {
            pages[current].rotation =
                (pages[current].rotation - 90 + 360) % 360
            refreshPreview()
        }

        rotateRightButton.setOnClickListener {
            pages[current].rotation =
                (pages[current].rotation + 90) % 360
            refreshPreview()
        }

        applyEnhancementButton.setOnClickListener {
            pages[current].enhancement =
                enhancementSpinner.selectedItemPosition
            refreshPreview()
            message("Enhancement applied to page ${current + 1}.")
        }

        upButton.setOnClickListener {
            if (current > 0) {
                val temp = pages[current]
                pages[current] = pages[current - 1]
                pages[current - 1] = temp
                current--
                refreshPreview()
            }
        }

        downButton.setOnClickListener {
            if (current < pages.lastIndex) {
                val temp = pages[current]
                pages[current] = pages[current + 1]
                pages[current + 1] = temp
                current++
                refreshPreview()
            }
        }

        removeButton.setOnClickListener {
            if (pages.size <= 1) {
                message("At least one page is required.")
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Remove page?")
                .setMessage("Remove page ${current + 1} from this PDF?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    pages.removeAt(current)
                    current = current.coerceAtMost(pages.lastIndex)
                    refreshPreview()
                }
                .show()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (pages.isEmpty()) {
                    message("Add at least one page.")
                    return@setOnClickListener
                }

                val pageSize =
                    if (pageSizeSpinner.selectedItemPosition == 0) {
                        PageSize.A4
                    } else {
                        PageSize.LETTER
                    }

                val quality = when (qualitySpinner.selectedItemPosition) {
                    0 -> 90
                    1 -> 65
                    else -> 40
                }

                val finalPages = pages.map { it.copy() }

                // Release preview memory before PDF processing starts.
                imagePreview.setImageDrawable(null)
                displayedPreview?.let {
                    if (!it.isRecycled) it.recycle()
                }
                displayedPreview = null

                dialog.dismiss()

                askPdfName(defaultName) { name ->
                    createPdfFromImages(finalPages, name, pageSize, quality)
                }
            }
        }

        dialog.setOnDismissListener {
            imagePreview.setImageDrawable(null)
            displayedPreview?.let {
                if (!it.isRecycled) it.recycle()
            }
            displayedPreview = null
        }

        refreshPreview()
        dialog.show()
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 8, 0, 4)
        }
    }

    // ---------------------------------------------------------
    // ROTATION AND IMAGE ENHANCEMENT
    // ---------------------------------------------------------

    private fun editBitmap(source: Bitmap, page: EditablePage): Bitmap {
        val rotation = ((page.rotation % 360) + 360) % 360

        // IMPORTANT: Always return a separate bitmap.
        // The caller can safely recycle source after this function returns.
        val rotated = if (rotation == 0) {
            source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw Exception("Could not copy image.")
        } else {
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
            }

            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                matrix,
                true
            )
        }

        if (page.enhancement == 0) {
            return rotated
        }

        var output: Bitmap? = null

        try {
            output = Bitmap.createBitmap(
                rotated.width,
                rotated.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(output)
            val paint = Paint(
                Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
            )

            when (page.enhancement) {
                1 -> {
                    val grayMatrix = ColorMatrix().apply {
                        setSaturation(0f)
                    }
                    paint.colorFilter = ColorMatrixColorFilter(grayMatrix)
                    canvas.drawBitmap(rotated, 0f, 0f, paint)
                }

                2 -> {
                    val grayMatrix = ColorMatrix().apply {
                        setSaturation(0f)
                    }
                    paint.colorFilter = ColorMatrixColorFilter(grayMatrix)
                    canvas.drawBitmap(rotated, 0f, 0f, paint)

                    val pixels = IntArray(output.width * output.height)
                    output.getPixels(
                        pixels, 0, output.width,
                        0, 0, output.width, output.height
                    )

                    for (i in pixels.indices) {
                        val pixel = pixels[i]
                        val gray = (
                                Color.red(pixel) * 0.299 +
                                        Color.green(pixel) * 0.587 +
                                        Color.blue(pixel) * 0.114
                                ).toInt()

                        pixels[i] = if (gray >= 160) {
                            Color.WHITE
                        } else {
                            Color.BLACK
                        }
                    }

                    output.setPixels(
                        pixels, 0, output.width,
                        0, 0, output.width, output.height
                    )
                }

                3 -> {
                    val contrast = 1.35f
                    val translate = 128f * (1f - contrast)

                    val colorMatrix = ColorMatrix(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )

                    paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                    canvas.drawBitmap(rotated, 0f, 0f, paint)
                }
            }

            return output.also { output = null }
        } finally {
            output?.let {
                if (!it.isRecycled) it.recycle()
            }
            if (!rotated.isRecycled) rotated.recycle()
        }
    }

    // ---------------------------------------------------------
    // PDF NAMING AND CREATION
    // ---------------------------------------------------------

    private fun askPdfName(defaultName: String, callback: (String) -> Unit) {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmm",
            Locale.getDefault()
        ).format(Date())

        val input = EditText(this).apply {
            setText("${defaultName}_$timestamp")
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Name your PDF")
            .setMessage("Choose a filename.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                var name = input.text.toString().trim()
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")

                if (name.isBlank()) {
                    name = "DocuScan_${System.currentTimeMillis()}"
                }
                if (!name.endsWith(".pdf", true)) name += ".pdf"

                callback(name)
            }
            .show()
    }

    private fun createPdfFromImages(
        pages: List<EditablePage>,
        name: String,
        pageSize: PageSize,
        jpegQuality: Int
    ) {
        var document: PdfDocument? = null

        try {
            document = PdfDocument()

            pages.forEachIndexed { index, pageData ->
                val source = contentResolver.openInputStream(pageData.uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: throw Exception("Could not read page ${index + 1}.")

                var edited: Bitmap? = null
                var compressedBitmap: Bitmap? = null
                var pdfPage: PdfDocument.Page? = null
                var pageFinished = false

                try {
                    edited = editBitmap(source, pageData)

                    val compressed = ByteArrayOutputStream()
                    edited.compress(
                        Bitmap.CompressFormat.JPEG,
                        jpegQuality,
                        compressed
                    )

                    val compressedBytes = compressed.toByteArray()
                    compressedBitmap = BitmapFactory.decodeByteArray(
                        compressedBytes,
                        0,
                        compressedBytes.size
                    ) ?: throw Exception(
                        "Could not process page ${index + 1}."
                    )

                    val bitmap = compressedBitmap
                        ?: throw Exception("Could not process page ${index + 1}.")

                    val pageInfo = PdfDocument.PageInfo.Builder(
                        pageSize.width,
                        pageSize.height,
                        index + 1
                    ).create()

                    pdfPage = document.startPage(pageInfo)

                    val scale = min(
                        pageSize.width.toFloat() / bitmap.width,
                        pageSize.height.toFloat() / bitmap.height
                    )

                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    val left = (pageSize.width - width) / 2f
                    val top = (pageSize.height - height) / 2f

                    pdfPage.canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(left, top, left + width, top + height),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )

                    document.finishPage(pdfPage)
                    pageFinished = true
                } finally {
                    if (pdfPage != null && !pageFinished) {
                        try {
                            document?.finishPage(pdfPage)
                        } catch (_: Exception) {
                            // Preserve the original PDF error.
                        }
                    }

                    compressedBitmap?.let {
                        if (!it.isRecycled) it.recycle()
                    }
                    edited?.let {
                        if (it !== source && !it.isRecycled) it.recycle()
                    }
                    if (!source.isRecycled) source.recycle()
                }
            }

            val output = ByteArrayOutputStream()
            document.writeTo(output)
            document.close()
            document = null

            savePdfBytes(output.toByteArray(), name)
        } catch (e: Exception) {
            message("PDF creation failed: ${e.message ?: "Unknown error"}")
        } finally {
            document?.close()
        }
    }

    private fun saveScannedPdf(source: Uri, name: String) {
        try {
            val bytes = contentResolver.openInputStream(source)?.use {
                it.readBytes()
            } ?: throw Exception("Could not read scanner PDF.")

            savePdfBytes(bytes, name)
        } catch (e: Exception) {
            message("Could not save PDF: ${e.message}")
        }
    }

    private fun savePdfBytes(bytes: ByteArray, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            message("Saving to Downloads requires Android 10 or newer.")
            return
        }

        var destination: Uri? = null

        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/DocuScan"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            destination = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw Exception("Could not create PDF.")

            contentResolver.openOutputStream(destination)?.use {
                it.write(bytes)
            } ?: throw Exception("Could not write PDF.")

            val completed = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            contentResolver.update(destination, completed, null, null)

            message("Saved: $name\nDownloads/DocuScan")
        } catch (e: Exception) {
            destination?.let {
                contentResolver.delete(it, null, null)
            }
            message("PDF save failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------
    // PDF MANAGER: SEARCH, SORT, AND METADATA
    // ---------------------------------------------------------

    private fun loadDocuments(): MutableList<PdfFile> {
        val documents = mutableListOf<PdfFile>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return documents

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.MediaColumns.MIME_TYPE} = ?"

        val args = arrayOf(
            "${Environment.DIRECTORY_DOWNLOADS}/DocuScan/",
            "application/pdf"
        )

        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                documents.add(
                    PdfFile(
                        cursor.getString(nameCol),
                        ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id
                        ),
                        cursor.getLong(sizeCol),
                        cursor.getLong(dateCol)
                    )
                )
            }
        }

        return documents
    }

    private fun showDocuments() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            message("Document listing requires Android 10 or newer.")
            return
        }

        val allDocuments = loadDocuments()

        val search = EditText(this).apply {
            hint = "Search by PDF name"
            setSingleLine(true)
        }

        val sort = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Newest first",
                    "Oldest first",
                    "Name A-Z",
                    "Name Z-A",
                    "Largest first",
                    "Smallest first"
                )
            )
        }

        val listView = ListView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
            addView(search)
            addView(sort)
            addView(listView, LinearLayout.LayoutParams(-1, 440))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("My Documents (${allDocuments.size})")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()

        val shown = mutableListOf<PdfFile>()

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                val item = shown[position]

                val layout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 12, 12, 12)
                }

                val title = TextView(this@MainActivity).apply {
                    text = item.name
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val details = TextView(this@MainActivity).apply {
                    val date = Date(item.date * 1000L)
                    text = "${formatSize(item.size)} • " +
                            DateFormat.getDateTimeInstance().format(date)
                    textSize = 12f
                }

                layout.addView(title)
                layout.addView(details)
                return layout
            }
        }

        fun refreshList() {
            val query = search.text.toString().trim()
                .lowercase(Locale.getDefault())

            val filtered = allDocuments.filter {
                it.name.lowercase(Locale.getDefault()).contains(query)
            }.toMutableList()

            when (sort.selectedItemPosition) {
                0 -> filtered.sortByDescending { it.date }
                1 -> filtered.sortBy { it.date }
                2 -> filtered.sortBy { it.name.lowercase(Locale.getDefault()) }
                3 -> filtered.sortByDescending { it.name.lowercase(Locale.getDefault()) }
                4 -> filtered.sortByDescending { it.size }
                5 -> filtered.sortBy { it.size }
            }

            shown.clear()
            shown.addAll(filtered)
            adapter.notifyDataSetChanged()
            dialog.setTitle("My Documents (${shown.size})")
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        sort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                refreshList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            showDocumentActions(shown[position])
        }

        refreshList()
        dialog.show()
    }

    private fun formatSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format(
                Locale.getDefault(), "%.1f KB", size / 1024.0
            )
            else -> String.format(
                Locale.getDefault(), "%.2f MB", size / (1024.0 * 1024.0)
            )
        }
    }

    private fun showDocumentActions(file: PdfFile) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(
                arrayOf("Open PDF", "Share PDF", "Rename PDF", "Delete PDF")
            ) { _, which ->
                when (which) {
                    0 -> openPdf(file.uri)
                    1 -> sharePdf(file.uri)
                    2 -> renamePdf(file)
                    3 -> deletePdf(file)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openPdf(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            message("No PDF viewer found on your phone.")
        }
    }

    private fun sharePdf(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    private fun renamePdf(file: PdfFile) {
        val input = EditText(this).apply {
            setText(file.name.removeSuffix(".pdf"))
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename PDF")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ ->
                var name = input.text.toString().trim()
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")

                if (name.isBlank()) {
                    message("Enter a filename.")
                    return@setPositiveButton
                }
                if (!name.endsWith(".pdf", true)) name += ".pdf"

                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    }

                    val count = contentResolver.update(file.uri, values, null, null)
                    message(
                        if (count > 0) "Renamed to $name"
                        else "Could not rename PDF."
                    )
                } catch (e: Exception) {
                    message("Rename failed: ${e.message}")
                }
            }
            .show()
    }

    private fun deletePdf(file: PdfFile) {
        AlertDialog.Builder(this)
            .setTitle("Delete PDF?")
            .setMessage("Delete \"${file.name}\"? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val deleted = contentResolver.delete(file.uri, null, null)
                    message(
                        if (deleted > 0) "PDF deleted."
                        else "Could not delete PDF."
                    )
                } catch (e: Exception) {
                    message("Delete failed: ${e.message}")
                }
            }
            .show()
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
