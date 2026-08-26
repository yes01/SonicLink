package org.cloud.sonic.android.ui.logs

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.gyf.immersionbar.ktx.immersionBar
import kotlinx.coroutines.launch
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.ActivityAppLogUploadBinding
import org.cloud.sonic.android.logs.AppLogPackager
import org.cloud.sonic.android.logs.LogCandidate
import org.cloud.sonic.android.logs.LogScenario
import org.cloud.sonic.android.logs.LogSourceKind
import org.cloud.sonic.android.logs.OxygenLogCollector
import org.cloud.sonic.android.repository.PlatformSyncRepository
import org.cloud.sonic.android.ui.common.PlatformAccountPicker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AppLogUploadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppLogUploadBinding
    private lateinit var collector: OxygenLogCollector
    private lateinit var packager: AppLogPackager
    private lateinit var platformRepository: PlatformSyncRepository

    private val eventCalendar = Calendar.getInstance()
    private val candidates = linkedMapOf<String, LogCandidate>()
    private val selectedIds = linkedSetOf<String>()
    private var scenario = LogScenario.GENERAL
    private var busy = false

    private val manualFilePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        var accepted = 0
        var rejected = 0
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val metadata = queryDocument(uri)
            if (!isSupportedManualFile(metadata.first)) {
                rejected++
                return@forEach
            }
            val candidate = LogCandidate(
                id = "DOCUMENT:$uri",
                displayName = metadata.first,
                relativePath = "manual/${metadata.first}",
                size = metadata.second,
                modifiedAt = 0L,
                reason = "用户手动导入",
                sourceKind = LogSourceKind.DOCUMENT,
                contentUri = uri
            )
            candidates[candidate.id] = candidate
            selectedIds += candidate.id
            accepted++
        }
        renderCandidates()
        val message = buildString {
            append("已导入 $accepted 个文件")
            if (rejected > 0) append("，忽略 $rejected 个不支持的文件")
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppLogUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        collector = OxygenLogCollector(this)
        packager = AppLogPackager(this)
        platformRepository = PlatformSyncRepository(this)

        val isDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        immersionBar {
            statusBarColor(R.color.bg_card)
            navigationBarColor(R.color.bg_page)
            statusBarDarkFont(!isDark)
            navigationBarDarkIcon(!isDark)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnOxygen.setOnClickListener {
            Snackbar.make(binding.root, "已选择氧气应用", Snackbar.LENGTH_SHORT).show()
        }
        binding.btnYesApp.setOnClickListener {
            Snackbar.make(binding.root, "Yes 应用日志采集功能敬请期待", Snackbar.LENGTH_LONG).show()
        }
        binding.btnEventTime.setOnClickListener { selectEventDate() }
        binding.scenarioGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            scenario = when (checkedIds.firstOrNull()) {
                R.id.chipCrash -> LogScenario.CRASH
                R.id.chipMessage -> LogScenario.MESSAGE
                R.id.chipRtc -> LogScenario.RTC
                R.id.chipDownload -> LogScenario.DOWNLOAD
                else -> LogScenario.GENERAL
            }
            invalidateAutomaticResults()
        }
        binding.btnScanLogs.setOnClickListener { scanLogs() }
        binding.btnManualImport.setOnClickListener { explainManualImport() }
        binding.btnUploadLogs.setOnClickListener { confirmUpload() }

        renderEventTime()
        if (!collector.isOxygenInstalled()) {
            binding.tvScanStatus.setText(R.string.app_log_oxygen_missing)
        }
    }

    private fun selectEventDate() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                eventCalendar.set(Calendar.YEAR, year)
                eventCalendar.set(Calendar.MONTH, month)
                eventCalendar.set(Calendar.DAY_OF_MONTH, day)
                selectEventTime()
            },
            eventCalendar.get(Calendar.YEAR),
            eventCalendar.get(Calendar.MONTH),
            eventCalendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun selectEventTime() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                eventCalendar.set(Calendar.HOUR_OF_DAY, hour)
                eventCalendar.set(Calendar.MINUTE, minute)
                eventCalendar.set(Calendar.SECOND, 0)
                renderEventTime()
                invalidateAutomaticResults()
            },
            eventCalendar.get(Calendar.HOUR_OF_DAY),
            eventCalendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun renderEventTime() {
        binding.btnEventTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(eventCalendar.timeInMillis))
    }

    private fun invalidateAutomaticResults() {
        val automaticIds = candidates.values.filter { it.sourceKind != LogSourceKind.DOCUMENT }.map { it.id }
        automaticIds.forEach {
            candidates.remove(it)
            selectedIds.remove(it)
        }
        binding.tvScanStatus.text = "发生时间或问题类型已变化，请重新扫描；手动导入文件已保留"
        renderCandidates()
    }

    @SuppressLint("SetTextI18n")
    private fun scanLogs() {
        if (busy) return
        setBusy(true, "正在按规则扫描氧气日志...")
        lifecycleScope.launch {
            runCatching { collector.scan(eventCalendar.timeInMillis, scenario) }
                .onSuccess { result ->
                    val manualFiles = candidates.values.filter { it.sourceKind == LogSourceKind.DOCUMENT }
                    candidates.clear()
                    selectedIds.clear()
                    result.files.forEach {
                        candidates[it.id] = it
                        selectedIds += it.id
                    }
                    manualFiles.forEach {
                        candidates[it.id] = it
                        selectedIds += it.id
                    }
                    binding.tvScanStatus.text = buildString {
                        append(getString(R.string.app_log_scan_result, result.sourceSummary, result.files.size))
                        if (result.warnings.isNotEmpty()) append("\n${result.warnings.joinToString("\n")}")
                    }
                    renderCandidates()
                }
                .onFailure { error ->
                    binding.tvScanStatus.text = getString(R.string.app_log_scan_failed, error.message ?: "未知错误")
                    Snackbar.make(binding.root, "日志扫描失败", Snackbar.LENGTH_LONG).show()
                }
            setBusy(false)
        }
    }

    private fun explainManualImport() {
        MaterialAlertDialogBuilder(this)
            .setTitle("手动导入日志")
            .setMessage("如果系统不允许访问氧气的 Android/data 目录，请先使用厂商文件管理器或电脑 ADB 将日志复制到 Download，再在下一步选择 xlog、dmp、log、txt 或 zip 文件。")
            .setPositiveButton("选择文件") { _, _ ->
                manualFilePicker.launch(arrayOf("application/octet-stream", "application/zip", "text/plain", "*/*"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderCandidates() {
        binding.logFileList.removeAllViews()
        candidates.values.forEach { candidate ->
            val checkbox = MaterialCheckBox(this).apply {
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = resources.getDimensionPixelSize(R.dimen.spacing_8)
                }
                minimumHeight = resources.getDimensionPixelSize(R.dimen.button_min_height)
                background = AppCompatResources.getDrawable(this@AppLogUploadActivity, R.drawable.bg_log_file_item)
                isChecked = selectedIds.contains(candidate.id)
                text = buildCandidateLabel(candidate)
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                val horizontalPadding = resources.getDimensionPixelSize(R.dimen.spacing_12)
                val verticalPadding = resources.getDimensionPixelSize(R.dimen.spacing_8)
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds += candidate.id else selectedIds -= candidate.id
                    renderSelectionSummary()
                }
            }
            binding.logFileList.addView(checkbox)
        }
        renderSelectionSummary()
    }

    private fun renderSelectionSummary() {
        val selected = selectedCandidates()
        binding.tvSelectionSummary.visibility = View.VISIBLE
        binding.tvSelectionSummary.text = if (selected.isEmpty()) {
            getString(R.string.app_log_selection_empty)
        } else {
            getString(
                R.string.app_log_selection_summary,
                selected.size,
                candidates.size,
                formatSize(selected.sumOf { it.size.coerceAtLeast(0L) })
            )
        }
        binding.btnUploadLogs.isEnabled = selected.isNotEmpty() && !busy
    }

    private fun buildCandidateLabel(candidate: LogCandidate): String {
        val size = if (candidate.size >= 0) formatSize(candidate.size) else "大小未知"
        return "${candidate.displayName}\n${candidate.reason} · $size · ${candidate.sourceKind.displayName}"
    }

    private fun confirmUpload() {
        val selected = selectedCandidates()
        if (selected.isEmpty()) return
        val totalSize = selected.sumOf { it.size.coerceAtLeast(0L) }
        MaterialAlertDialogBuilder(this)
            .setTitle("确认上传应用日志")
            .setMessage("将打包 ${selected.size} 个文件（约 ${formatSize(totalSize)}）。日志可能包含账号、设备和聊天等敏感信息，请确认仅发送到对应的平台账号。")
            .setPositiveButton("选择账号并上传") { _, _ -> selectTargetAndUpload(selected) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectTargetAndUpload(selected: List<LogCandidate>) {
        PlatformAccountPicker.show(
            context = this,
            repository = platformRepository,
            title = "发送日志到平台账号",
            positiveLabel = "上传",
            onMissing = {
                Snackbar.make(binding.root, "请先在缺陷协同页绑定测试平台账号", Snackbar.LENGTH_LONG).show()
            }
        ) { target ->
            setBusy(true, "正在打包并上传日志...")
            lifecycleScope.launch {
                var packageFile: java.io.File? = null
                runCatching {
                    packageFile = packager.createPackage(selected, eventCalendar.timeInMillis, scenario)
                    platformRepository.uploadAttachment(packageFile!!, targetConfig = target).getOrThrow()
                }.onSuccess {
                    Snackbar.make(binding.root, "日志包已发送到 ${target.username.ifBlank { "用户 ${target.userId}" }}", Snackbar.LENGTH_LONG).show()
                    selectedIds.clear()
                    renderCandidates()
                }.onFailure { error ->
                    Snackbar.make(binding.root, "日志上传失败：${error.message ?: "未知错误"}", Snackbar.LENGTH_LONG).show()
                }
                packageFile?.delete()
                setBusy(false)
            }
        }
    }

    private fun setBusy(value: Boolean, status: String? = null) {
        busy = value
        binding.progressScan.visibility = if (value) View.VISIBLE else View.GONE
        binding.btnScanLogs.isEnabled = !value
        binding.btnManualImport.isEnabled = !value
        binding.btnEventTime.isEnabled = !value
        binding.scenarioGroup.isEnabled = !value
        for (index in 0 until binding.scenarioGroup.childCount) {
            binding.scenarioGroup.getChildAt(index).isEnabled = !value
        }
        if (status != null) binding.tvScanStatus.text = status
        renderSelectionSummary()
    }

    private fun selectedCandidates(): List<LogCandidate> = selectedIds.mapNotNull(candidates::get)

    private fun queryDocument(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "log_file"
        var size = -1L
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        return name to size
    }

    private fun isSupportedManualFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in setOf("xlog", "dmp", "txt", "log", "zip")
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "${value.toLong()} ${units[index]}" else String.format(Locale.getDefault(), "%.1f %s", value, units[index])
    }
}
