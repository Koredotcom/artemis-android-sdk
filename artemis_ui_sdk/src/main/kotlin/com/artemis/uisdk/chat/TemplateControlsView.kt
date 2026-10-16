package com.artemis.uisdk.chat

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.artemis.uisdk.ArtemisStrings
import com.artemis.uisdk.ArtemisThemeConfig
import com.artemis.uisdk.R
import com.artemis.uisdk.artemisColor
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.Calendar
import java.util.Locale

internal object NativeTemplateDraftOwner

/** Shared field semantics for rich forms and action sets; draft changes never submit actions. */
@SuppressLint("ViewConstructor")
internal class TemplateControlsView(
    context: Context,
    private val message: ChatMessage,
    private val payload: JsonObject,
    private val form: Boolean,
    private val theme: ArtemisThemeConfig,
    private val strings: ArtemisStrings,
    private val callbacks: ChatMessageAdapter.Callbacks,
    private val resolveLink: (String?) -> String? = { TemplateContracts.actionUrl(it) },
    private val openLink: (String) -> Unit,
) : LinearLayout(context) {
    private val renderId = payload.string("actionRenderId", "renderId") ?: message.actions?.string("renderId")
    private val scope = if (form) "form" else "actions"
    private val signature = payload.deepCopy().apply { remove("submitted") }
    private val values = callbacks.templateDraft(message, renderId, scope, signature).toMutableMap()
    private val inputs = linkedMapOf<String, Pair<JsonObject, EditText>>()
    private val selections = linkedMapOf<String, Pair<JsonObject, Spinner>>()
    private val controls = mutableListOf<Pair<String, View>>()
    private val error = TextView(context).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val submitId = if (form) "form-submit" else payload.string("submit_id")?.takeIf(String::isNotBlank)
    private val fields = payload.array(if (form) "fields" else "elements")?.mapNotNull { it.takeIf { v -> v.isJsonObject }?.asJsonObject }.orEmpty()
    private fun text(id: Int, vararg args: Any) = strings.text(context, id, *args)
    private fun locked(id: String) = callbacks.isTemplateLocked(message, id, renderId) || (submitId != null && callbacks.isTemplateLocked(message, submitId, renderId))
    private fun draftChanged() = callbacks.onTemplateDraft(message, renderId, scope, signature, values.toMap())
    private fun label(value: String) = TextView(context).apply {
        text = value; textSize = 14f; setTextColor(context.artemisColor(theme, R.color.artemis_text)); setPadding(0, dp(6), 0, dp(3))
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun kind(field: JsonObject) = field.string("type") ?: if (form) "input" else "button"
    private fun options(field: JsonObject) = field.array("options")?.mapNotNull { it.takeIf { v -> v.isJsonObject }?.asJsonObject }.orEmpty()

    init {
        orientation = VERTICAL
        val ids = fields.map { it.string("id").orEmpty() }
        val allowed = setOf("input", "select", "button", "text", "email", "number", "tel", "phone", "date", "time")
        val valid = fields.isNotEmpty() && payload.array(if (form) "fields" else "elements")?.size() == fields.size && fields.size <= 100 && ids.all(String::isNotBlank) && ids.distinct().size == ids.size && fields.all { field ->
            kind(field) in allowed && (field.string("input_type") == null || field.string("input_type") in setOf("text", "number", "email", "date", "time", "tel", "phone")) && (kind(field) != "select" || (field.array("options")?.size() == options(field).size && options(field).size <= 200 && options(field).all { !it.string("id").isNullOrBlank() } && options(field).map { it.string("id") }.distinct().size == options(field).size))
        }
        if (!valid) { if (form || fields.isNotEmpty()) addView(label(text(R.string.artemis_template_fields_unavailable))) } else {
            payload.string("title")?.let { addView(label(it)) }
            fields.forEach { field ->
                val id = field.string("id")!!
                val fieldLabel = field.string("label") ?: id
                if (kind(field) == "button") button(field, id, fieldLabel) else {
                    val title = label(if (field.flag("required")) text(R.string.artemis_template_required_label, fieldLabel) else fieldLabel)
                    addView(title)
                    val control = if (kind(field) == "select") select(field, id, fieldLabel) else input(field, id, fieldLabel)
                    control.id = View.generateViewId(); title.labelFor = control.id
                    control.contentDescription = fieldLabel
                    controls.add(id to control)
                    addView(control, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    field.string("description")?.takeIf(String::isNotBlank)?.let { addView(label(it).apply { textSize = 12f }) }
                }
            }
            addView(error)
            if (submitId != null) {
                val submit = Button(context).apply {
                    text = payload.string("submit_label") ?: text(R.string.artemis_submit)
                    isAllCaps = false; setTextColor(context.artemisColor(theme, R.color.artemis_primary))
                    setOnClickListener {
                        if (locked(submitId) || !validate()) return@setOnClickListener
                        val data = values.filterKeys { key -> inputs.containsKey(key) || selections.containsKey(key) }
                        callbacks.onAction(message, submitId, Gson().toJson(data), data, renderId)
                        applyLocks()
                    }
                }
                controls.add(submitId to submit); addView(submit)
            }
            applyLocks()
        }
    }

    private fun button(field: JsonObject, id: String, title: String) {
        val url = resolveLink(field.string("url"))
        val button = Button(context).apply {
            text = title; isAllCaps = false; setTextColor(context.artemisColor(theme, R.color.artemis_primary))
            setOnClickListener {
                if (url != null) { openLink(url); return@setOnClickListener }
                if (locked(id)) return@setOnClickListener
                callbacks.onAction(message, id, TemplateContracts.actionValue(field), null, renderId)
                applyLocks()
            }
        }
        if (url == null) controls.add(id to button)
        addView(button)
        field.string("description")?.takeIf(String::isNotBlank)?.let { addView(label(it)) }
    }

    private fun input(field: JsonObject, id: String, title: String): EditText {
        val type = field.string("input_type") ?: kind(field)
        val initial = values[id] ?: field.string("value").orEmpty()
        values[id] = initial
        return EditText(context).apply {
            setSingleLine(); textSize = 14f
            imeOptions = if (!form && submitId == null) android.view.inputmethod.EditorInfo.IME_ACTION_SEND else android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            hint = field.string("placeholder") ?: title
            filters = arrayOf(InputFilter.LengthFilter(10_000))
            inputType = when (type) {
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                "tel", "phone" -> InputType.TYPE_CLASS_PHONE
                else -> InputType.TYPE_CLASS_TEXT
            }
            setTextColor(context.artemisColor(theme, R.color.artemis_text))
            setHintTextColor(context.artemisColor(theme, R.color.artemis_muted))
            setText(initial)
            values[id] = this.text.toString()
            inputs[id] = field to this
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { values[id] = s?.toString().orEmpty(); draftChanged() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            if (type in setOf("date", "time")) {
                isFocusable = false; isClickable = true
                setOnClickListener {
                    if (!isEnabled) return@setOnClickListener
                    val now = Calendar.getInstance()
                    if (type == "date") {
                        TemplateContracts.date(values[id])?.let { now.time = it }
                        DatePickerDialog(context, { _, year, month, day ->
                            setText(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day)); submitPickedValue(field, id, this)
                        },
                            now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
                    } else {
                        values[id]?.split(':')?.let { parts ->
                            parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 }?.let { now.set(Calendar.HOUR_OF_DAY, it) }
                            parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 }?.let { now.set(Calendar.MINUTE, it) }
                        }
                        TimePickerDialog(context, { _, hour, minute ->
                            setText(String.format(Locale.ROOT, "%02d:%02d", hour, minute)); submitPickedValue(field, id, this)
                        },
                            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
                    }
                }
            } else if (!form && submitId == null) setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || enter) {
                    if (!locked(id) && validInput(field, this)) { callbacks.onAction(message, id, this.text.toString(), null, renderId); applyLocks() }
                    true
                } else false
            }
        }
    }

    private fun select(field: JsonObject, id: String, title: String): Spinner {
        val choices = options(field)
        val placeholder = field.string("placeholder") ?: text(R.string.artemis_template_select, title)
        val labels = listOf(placeholder) + choices.map { it.string("label") ?: it.string("id").orEmpty() }
        val initial = values[id] ?: field.string("value").orEmpty()
        val index = choices.indexOfFirst { it.string("id") == initial } + 1
        values[id] = choices.getOrNull(index - 1)?.string("id").orEmpty()
        return Spinner(context).apply {
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
                override fun isEnabled(position: Int) = position != 0
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        val description = choices.getOrNull(position - 1)?.string("description")
                        text = listOfNotNull(labels[position], description?.takeIf(String::isNotBlank)).joinToString("\n")
                        alpha = if (position == 0) .5f else 1f
                    }
            }
            setSelection(index)
            selections[id] = field to this
            var previous = values[id].orEmpty()
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, itemId: Long) {
                    val value = choices.getOrNull(position - 1)?.string("id").orEmpty()
                    if (value == previous) return
                    previous = value; values[id] = value; draftChanged()
                    if (!form && submitId == null && value.isNotEmpty() && !locked(id)) {
                        callbacks.onAction(message, id, value, null, renderId); applyLocks()
                    }
                }
            }
        }
    }

    private fun submitPickedValue(field: JsonObject, id: String, input: EditText) {
        if (!form && submitId == null && !locked(id) && validInput(field, input)) {
            callbacks.onAction(message, id, input.text.toString(), null, renderId); applyLocks()
        }
    }
    private fun validInput(field: JsonObject, input: EditText): Boolean {
        val value = input.text.toString()
        val issue = TemplateContracts.inputError(field, value)
        input.error = when (issue) { "required" -> text(R.string.artemis_required); "invalid" -> text(R.string.artemis_template_invalid_value); else -> null }
        if (issue != null) { input.requestFocus(); return false }
        return true
    }
    private fun validate(): Boolean {
        error.text = ""
        inputs.values.forEach { (field, view) -> if (!validInput(field, view)) return false }
        selections.forEach { (id, item) ->
            if (item.first.flag("required") && values[id].isNullOrBlank()) {
                error.text = text(R.string.artemis_template_selection_required, item.first.string("label") ?: id)
                item.second.requestFocus(); return false
            }
        }
        return true
    }
    private fun applyLocks() { controls.forEach { (id, view) -> view.isEnabled = !locked(id) } }
}
