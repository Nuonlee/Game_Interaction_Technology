package com.example.virtualtouchpad

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.EditText

class CalibHandActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContentView(R.layout.activity_calib_hand)

        val buttonBackward = findViewById<ImageButton>(R.id.button_back_calibHand)

        buttonBackward.setOnClickListener {
            finish()
        }
    }
}