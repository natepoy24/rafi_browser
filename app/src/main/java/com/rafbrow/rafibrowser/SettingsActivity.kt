package com.rafbrow.rafibrowser

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<LinearLayout>(R.id.optChangePin).setOnClickListener {
            showChangePinDialog()
        }

        findViewById<LinearLayout>(R.id.optClearData).setOnClickListener {
            clearBrowserData()
        }
    }

    private fun showChangePinDialog() {
        val input = EditText(this).apply {
            hint = "New PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newPin = input.text.toString()
                if (newPin.isNotEmpty()) {
                    getSharedPreferences("RafiBrowserPrefs", Context.MODE_PRIVATE)
                        .edit().putString("app_pin", newPin).apply()
                    Toast.makeText(this, "PIN updated", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearBrowserData() {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        Toast.makeText(this, "Data Cleared", Toast.LENGTH_SHORT).show()
    }
}
